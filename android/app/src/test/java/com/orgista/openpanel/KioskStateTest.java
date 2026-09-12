package com.orgista.openpanel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KioskStateTest {
    @Test
    public void autoPinRequiresStandaloneModeAndExplicitEnablement() {
        assertTrue(KioskState.shouldAutoPin("standalone", true));
        assertFalse(KioskState.shouldAutoPin("standalone", false));
        assertFalse(KioskState.shouldAutoPin("companion", true));
    }

    @Test
    public void kioskStartOnlySucceedsWhenAndroidActuallyEntersLockTask() {
        assertFalse(KioskState.isLockTaskActive(0));
        assertTrue(KioskState.isLockTaskActive(1));
        assertTrue(KioskState.isLockTaskActive(2));
    }

    @Test
    public void fireLockTaskRequiresDeviceOwnerToAvoidDelayedPinningRaces() {
        assertFalse(KioskState.canReliablyStartLockTask(false, true));
        assertTrue(KioskState.canReliablyStartLockTask(true, true));
        assertTrue(KioskState.canReliablyStartLockTask(false, false));
    }

    @Test
    public void fireAlwaysNormalizesToStandaloneManagement() {
        assertEquals(
            KioskState.MODE_STANDALONE,
            KioskState.normalizeModeForDevice(KioskState.MODE_COMPANION, true)
        );
        assertEquals(
            KioskState.MODE_COMPANION,
            KioskState.normalizeModeForDevice(KioskState.MODE_COMPANION, false)
        );
    }

    @Test
    public void ordinaryFireKioskUsesHomeRedirectWithoutStartingScreenPinning() {
        assertFalse(KioskState.shouldAutoPin("standalone", true, false, true));
        assertTrue(KioskState.shouldAutoPin("standalone", true, true, true));
        assertTrue(KioskState.shouldUseFireRedirectKiosk(false, true));
        assertFalse(KioskState.shouldUseFireRedirectKiosk(true, true));
        assertFalse(KioskState.shouldUseFireRedirectKiosk(false, false));
    }
}
