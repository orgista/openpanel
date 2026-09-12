package com.orgista.openpanel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NotificationBlockPolicyTest {
    @Test
    public void preservesKioskNetworkAndEmergencyServices() {
        assertTrue(NotificationBlockPolicy.shouldPreserve(
            "com.orgista.openpanel.debug", "com.orgista.openpanel.debug", "com.amazon.firelauncher"));
        assertTrue(NotificationBlockPolicy.shouldPreserve(
            "dnsfilter.android", "com.orgista.openpanel.debug", "com.amazon.firelauncher"));
        assertTrue(NotificationBlockPolicy.shouldPreserve(
            "moe.shizuku.privileged.api", "com.orgista.openpanel.debug", "com.amazon.firelauncher"));
        assertTrue(NotificationBlockPolicy.shouldPreserve(
            "com.android.systemui", "com.orgista.openpanel.debug", "com.amazon.firelauncher"));
        assertTrue(NotificationBlockPolicy.shouldPreserve(
            "com.amazon.firelauncher", "com.orgista.openpanel.debug", "com.amazon.firelauncher"));
    }

    @Test
    public void blocksConsumerAndAmazonUpdateNotifications() {
        assertFalse(NotificationBlockPolicy.shouldPreserve(
            "com.aurora.store", "com.orgista.openpanel.debug", "com.amazon.firelauncher"));
        assertFalse(NotificationBlockPolicy.shouldPreserve(
            "com.amazon.device.software.ota", "com.orgista.openpanel.debug", "com.amazon.firelauncher"));
    }
}
