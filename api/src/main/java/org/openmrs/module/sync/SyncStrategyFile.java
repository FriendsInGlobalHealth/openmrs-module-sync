/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.sync;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.sync.api.SyncService;
import org.openmrs.module.sync.serialization.Item;
import org.openmrs.module.sync.serialization.Record;
import org.openmrs.module.sync.server.RemoteServer;
import org.openmrs.module.sync.server.RemoteServerType;
import org.openmrs.module.sync.server.SyncServerRecord;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * sync strategy that implements sync-ing via disconnected push/pull.
 */
public class SyncStrategyFile {
	
	//fields
	private final Log log = LogFactory.getLog(getClass());

	// Caches a map of classes representing the stopped items to their uuids.
	private final Map<String, Set<String>> dependedStoppedItemUuids = new LinkedHashMap<String, Set<String>>();
	
	//constructor
	public SyncStrategyFile() {
	}
	
	/**
	 * Using the sourceChild sync source, create a sync transmission using JournalManager
	 */
	public SyncTransmission createSyncTransmission(SyncSource source) {
		
		SyncTransmission tx = new SyncTransmission();
		List<SyncRecord> changeset = null;
		
		//retrieve value of the last sync timestamps
		SyncPoint lastSyncLocal = source.getLastSyncLocal();
		
		//establish the 'new' sync point; this will be new sync local after transmission was 'exported'
		SyncPoint lastSyncLocalNew = source.moveSyncPoint();
		
		//get changeset for sourceA
		changeset = this.getChangeset(source, lastSyncLocal, lastSyncLocalNew);
		
		String sourceUuid = source.getSyncSourceUuid();
		
		//pack it into transmission, don't write temp file
		SyncTransmission syncTx = new SyncTransmission(sourceUuid, changeset);
		syncTx.create(false);
		
		//set new SyncPoint
		source.setLastSyncLocal(lastSyncLocalNew);
		
		return syncTx;
	}
	
	/**
	 * Prepares a sync transmission containing sync records from source that are to be send to the
	 * remote server. The records to be sent are determined as follows: <br/>
	 * - select records from sync journal that are in the correct state (see
	 * SyncConstants.SYNC_TO_PARENT_STATES) <br/>
	 * - if a sync record from the journal reached state of FAILED_AND_STOPPED; do not attempt to
	 * send it and records after it again <br/>
	 * - filter out records that contain classes that are not accepted by the server
	 * 
	 * @param source server from where changes are to be retrieved (local server)
	 * @param writeFileToo flag to dump file or not
	 * @param server server to send Tx to
	 * @param maxSyncRecords The maximum number of sync records to include in the Sync Transmission
	 * @return
	 * @see org.openmrs.module.sync.SyncConstants#SYNC_TO_PARENT_STATES
	 */
	public SyncTransmission createStateBasedSyncTransmission(SyncSource source, boolean writeFileToo, RemoteServer server,
	                                                         boolean requestResponseWithTransmission, Integer maxSyncRecords) {
		
		SyncTransmission syncTx = null;

		if (server != null) {
			List<SyncRecord> changeset = null;
			List<SyncRecord> filteredChangeset = new ArrayList<SyncRecord>();
			List<SyncRecord> failedAndStopped = new ArrayList<SyncRecord>();

			
			//get changeset for sourceA
			changeset = this.getStateBasedChangeset(source, server, maxSyncRecords);
			
			// need to check each SyncRecord to see if it's eligible for sync'ing
			if (changeset != null) {
				for (SyncRecord record : changeset) {
					if (record.getState() == SyncRecordState.FAILED_AND_STOPPED) {
						failedAndStopped.add(record);
						updatedDependedStoppedItemUuids(record);
						continue;
					}

					Set<String> containedClasses = record.getContainedClassSet();
					if (server.shouldBeSentSyncRecord(record)) {
						if (record.getState() == SyncRecordState.DEPENDS_ON_FAILED_AND_STOPPED) {
							continue;
						}

						// Check if record has any item which depends on an item in the failed and stopped record
						if(!failedAndStopped.isEmpty() && dependsOnFailedAndStopped(record)) {
							if (server.getServerType().equals(RemoteServerType.PARENT)) {
								record.setState(SyncRecordState.DEPENDS_ON_FAILED_AND_STOPPED);
								Context.getService(SyncService.class).updateSyncRecord(record);
							} else {
								SyncServerRecord serverRecord = record.getServerRecord(server);
								if (serverRecord != null) {
									serverRecord.setState(SyncRecordState.DEPENDS_ON_FAILED_AND_STOPPED);
									Context.getService(SyncService.class).updateSyncRecord(record);
								}
							}
							log.warn("NOT ADDING RECORD TO TRANSMISSION, RECORD CONTAINS ITEM THAT DEPEND ON OTHER ITEMS WHICH ARE CONTAINED IN "
									+	"FAILED AND STOPPED RECORD");
							continue;
						}

						filteredChangeset.add(record);
					} else {
						if (server.getServerType().equals(RemoteServerType.PARENT)) {
							record.setState(SyncRecordState.NOT_SUPPOSED_TO_SYNC);
							Context.getService(SyncService.class).updateSyncRecord(record);
						} else {
							SyncServerRecord serverRecord = record.getServerRecord(server);
							if (serverRecord != null) {
								serverRecord.setState(SyncRecordState.NOT_SUPPOSED_TO_SYNC);
								Context.getService(SyncService.class).updateSyncRecord(record);
							}
						}
						log.warn("NOT ADDING RECORD TO TRANSMISSION, SERVER IS NOT SET TO SEND ALL OF " + containedClasses
						        + " TO SERVER " + server.getNickname());
					}
				}
			}

			if(!failedAndStopped.isEmpty()) {
				// Simply send info about the first failed and stopped record.
				SyncUtil.sendSyncErrorMessage(failedAndStopped.get(0), server, new SyncException("Reached maximum retry count"));
			}
			//pack it into transmission
			syncTx = new SyncTransmission(source.getSyncSourceUuid(), filteredChangeset, server.getUuid());
			syncTx.setIsRequestingTransmission(requestResponseWithTransmission);
			syncTx.create(writeFileToo);
			syncTx.setSyncTargetUuid(server.getUuid());
		}
		
		return syncTx;
	}
	
	/**
	 * Update status of a given sync transmission
	 */
	public void updateSyncTransmission(SyncTransmission Tx) {
		
		//TODO
		
		return;
		
	}
	
	/**
	 * TODO: Review the 'exported' transmissions and return the list of the ones that did not
	 * receive a confirmation from the server; these are in the 'pending' state.
	 */
	public List<String> getPendingTransmissions() {
		//TODO
		List<String> pending = new ArrayList<String>();
		
		return pending;
	}
	
	/**
	 * Apply given sync tx to source.
	 */
	public void applySyncTransmission(SyncSource source, SyncTransmission tx) {
		
		//TODO
		
		return;
	}
	
	private List<SyncRecord> getChangeset(SyncSource source, SyncPoint from, SyncPoint to) {
		List<SyncRecord> deleted = null;
		List<SyncRecord> changed = null;
		List<SyncRecord> changeset = null;
		
		//get all local deletes, inserts and updates
		deleted = source.getDeleted(from, to);
		changed = source.getChanged(from, to);
		
		//merge
		changeset = deleted;
		changeset.addAll(changed);
		
		return changeset;
	}
	
	private List<SyncRecord> getStateBasedChangesets(SyncSource source, Integer maxSyncRecords) {
		List<SyncRecord> deleted = null;
		List<SyncRecord> changed = null;
		List<SyncRecord> changeset = null;
		
		//get all local deletes, inserts and updates
		deleted = source.getDeleted();
		changed = source.getChanged(maxSyncRecords);
		
		//merge
		changeset = deleted;
		changeset.addAll(changed);
		
		return changeset;
	}
	
	private List<SyncRecord> getStateBasedChangeset(SyncSource source, RemoteServer server, Integer maxResults) {
		List<SyncRecord> deleted = null;
		List<SyncRecord> changed = null;
		List<SyncRecord> changeset = null;
		
		//get all local deletes, inserts and updates
		deleted = source.getDeleted();
		changed = source.getChanged(server, maxResults);
		
		//merge
		changeset = deleted;
		changeset.addAll(changed);
		
		return changeset;
	}
	
	//apply items to source
	private void applyChangeset(SyncSource source, List<SyncRecord> items) {
		
		//TODO
		return;
	}

	/**
	 * The checks all the items in a record against the dependedStoppedItemUuids item to see if any of them is dependent on any one of the stopped items
	 * @param record the record to be checked
	 * @return boolean, true if the record has at least one item that depends on one of the stopped records, false otherwise.
	 */
	private boolean dependsOnFailedAndStopped(SyncRecord record) {
		if(record.hasItems()) {
			for(SyncItem item: record.getItems()) {
				try {
					Record parsedCdata = Record.create(item.getContent());

					for(Item cDataItem: parsedCdata.getItems(parsedCdata.getRootItem())) {
						String type = cDataItem.getAttribute("type");
						if(StringUtils.isNotBlank(type) && type.startsWith("org.openmrs.")) {
							String uuidToCheck = null;
							if(parsedCdata.getName().startsWith("org.hibernate.collection.")) {
								uuidToCheck = cDataItem.getAttribute("uuid");
							} else if(parsedCdata.getName().startsWith("org.openmrs.")) {
								uuidToCheck = cDataItem.getData();
							}

							// Now check if the item is depending on any failed and stopped items.
							if(dependedStoppedItemUuids.size() > 0 && dependedStoppedItemUuids.containsKey(type)) {
								return dependedStoppedItemUuids.get(type).contains(uuidToCheck);
							}
						}
					}
				} catch (Exception e) {
					log.error("An error while parsing SyncItem content: " + item.getContent());
					e.printStackTrace();
				}
			}
		}
		return false;
	}

	/**
	 * Generate a map of SyncItem Contained Class to UUID of record to be synced. At the time of creation this method is intended to generate a map
	 * that tracks the items in a failed and stopped record by mapping the class name to list of uuid of such records.
	 * @param record a record to be added to the map.
	 */
	private void updatedDependedStoppedItemUuids(SyncRecord record) {
		if(record != null && record.hasItems()) {
			for(SyncItem item: record.getItems()) {
				if(item.getContainedType().getName().startsWith("org.hibernate.collection")) {
					continue;
				}
				Class containedType = item.getContainedType();
				if(containedType.getName().startsWith("org.openmrs.") && SyncItemState.NEW.equals(item.getState())) {
					if(!dependedStoppedItemUuids.containsKey(containedType.getName())){
						dependedStoppedItemUuids.put(containedType.getName(), new HashSet<String>());
					}

					dependedStoppedItemUuids.get(containedType.getName()).add(item.getKey().getKeyValue().toString());
				}
			}
		}
	}
	
}
