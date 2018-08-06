package org.openmrs.module.sync;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.sync.api.SyncService;
import org.openmrs.module.sync.server.RemoteServer;
import org.openmrs.test.BaseModuleContextSensitiveTest;

public class SyncStrategyFileTest extends BaseModuleContextSensitiveTest {
    private static final String STRATEGY_TEST_DATA_FILE = "org/openmrs/module/sync/include/SyncRecords_SyncStrategyFileTest.xml";
    private static final String PARENT_SERVER_FILE = "org/openmrs/module/sync/include/SyncRemoteChildServer.xml";
    private static final SyncStrategyFile STRATEGY_FILE_INSTANCE = new SyncStrategyFile();
    private static final boolean RESPONSE_WITH_TX = false;
    private static final SyncSource source = new SyncSourceJournal();
    private static final int MAX_RETRY = 5;
    private RemoteServer server;

    @Before
    public void setup() throws Exception {
        executeDataSet(STRATEGY_TEST_DATA_FILE);
        executeDataSet(PARENT_SERVER_FILE);
        server = Context.getService(SyncService.class).getRemoteServer(1);
    }

    @Test
    public void createStateBasedSyncTransmission_shouldReturnNullIfRemoteServerIsNull() {
        Assert.assertNull(STRATEGY_FILE_INSTANCE.createStateBasedSyncTransmission(source, false, null, RESPONSE_WITH_TX, MAX_RETRY));
    }

    @Test
    public void createStateBasedSyncTransmission_shouldCreateTransmissionSkippingFailedAndStopped() {
        int expectedSyncRecords = 1;
        SyncTransmission tx = STRATEGY_FILE_INSTANCE.createStateBasedSyncTransmission(source, false, server, RESPONSE_WITH_TX, MAX_RETRY);
        Assert.assertNotNull(tx);
        Assert.assertEquals(expectedSyncRecords, tx.getSyncRecords().size());
    }
}
