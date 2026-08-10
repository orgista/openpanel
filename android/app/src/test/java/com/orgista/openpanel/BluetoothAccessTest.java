package com.orgista.openpanel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BluetoothAccessTest {
    @Test
    public void adapterStateRequiresConnectPermissionStartingOnAndroid12() {
        assertTrue(BluetoothAccess.canReadAdapterState(30, false));
        assertFalse(BluetoothAccess.canReadAdapterState(31, false));
        assertTrue(BluetoothAccess.canReadAdapterState(31, true));
    }
}
