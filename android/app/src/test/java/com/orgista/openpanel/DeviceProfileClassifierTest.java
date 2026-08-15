package com.orgista.openpanel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DeviceProfileClassifierTest {
    @Test
    public void classifiesTelevisionsBeforeScreenSizeOrTouchInput() {
        assertEquals(
            "television",
            DeviceProfileClassifier.deviceType(true, false, true, 800)
        );
        assertEquals(
            "television",
            DeviceProfileClassifier.deviceType(false, true, false, 540)
        );
    }

    @Test
    public void classifiesLargeAndroidDevicesAsTabletsAndSmallerOnesAsHandhelds() {
        assertEquals(
            "tablet",
            DeviceProfileClassifier.deviceType(false, false, true, 600)
        );
        assertEquals(
            "tablet",
            DeviceProfileClassifier.deviceType(false, false, false, 720)
        );
        assertEquals(
            "handheld",
            DeviceProfileClassifier.deviceType(false, false, true, 599)
        );
    }

    @Test
    public void remoteUiIsUsedForTelevisionsOrDpadOnlyDevices() {
        assertTrue(DeviceProfileClassifier.usesRemoteUi(true, false, false));
        assertTrue(DeviceProfileClassifier.usesRemoteUi(false, true, false));
        assertFalse(DeviceProfileClassifier.usesRemoteUi(false, true, true));
        assertFalse(DeviceProfileClassifier.usesRemoteUi(false, false, true));
    }

    @Test
    public void touchUiAndBatteryAreSuppressedOnTelevisions() {
        assertFalse(DeviceProfileClassifier.usesTouchUi("television", true));
        assertFalse(DeviceProfileClassifier.showsBattery("television", true));
        assertTrue(DeviceProfileClassifier.usesTouchUi("tablet", true));
        assertTrue(DeviceProfileClassifier.showsBattery("tablet", true));
        assertFalse(DeviceProfileClassifier.showsBattery("tablet", false));
    }

    @Test
    public void syntheticControllerNamesAreNotShownToPeople() {
        assertEquals(null, DeviceProfileClassifier.displayControllerName(null));
        assertEquals(null, DeviceProfileClassifier.displayControllerName("  "));
        assertEquals(null, DeviceProfileClassifier.displayControllerName("sim-mouse"));
        assertEquals(null, DeviceProfileClassifier.displayControllerName("virtual-search"));
        assertEquals(
            "Chromecast Voice Remote",
            DeviceProfileClassifier.displayControllerName(" Chromecast Voice Remote ")
        );
        assertEquals(
            null,
            DeviceProfileClassifier.controllerNameForDevice(true, "mtkinp")
        );
        assertEquals(
            "Chromecast Voice Remote",
            DeviceProfileClassifier.controllerNameForDevice(false, "Chromecast Voice Remote")
        );
    }
}
