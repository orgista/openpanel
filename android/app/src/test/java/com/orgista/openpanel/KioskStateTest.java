package com.orgista.openpanel;

import static org.junit.Assert.assertFalse;
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
}
