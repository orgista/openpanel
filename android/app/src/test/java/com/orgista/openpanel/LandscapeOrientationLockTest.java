package com.orgista.openpanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LandscapeOrientationLockTest {
    @Test
    public void recognizesAmazonManufacturerOrBrand() {
        assertTrue(LandscapeOrientationLock.isFireDevice("Amazon", "Amazon"));
        assertTrue(LandscapeOrientationLock.isFireDevice("unknown", "amazon"));
    }

    @Test
    public void ignoresNonFireAndroidDevices() {
        assertFalse(LandscapeOrientationLock.isFireDevice("Lenovo", "Lenovo"));
        assertFalse(LandscapeOrientationLock.isFireDevice(null, null));
    }

    @Test
    public void usesReverseLandscapeForTheFireTabletMount() {
        assertEquals(3, LandscapeOrientationLock.targetRotation());
    }
}
