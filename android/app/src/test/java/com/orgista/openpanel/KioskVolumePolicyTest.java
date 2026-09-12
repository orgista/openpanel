package com.orgista.openpanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KioskVolumePolicyTest {
    @Test
    public void locksMediaVolumeOnlyForEnabledStandaloneFireKiosks() {
        assertTrue(KioskVolumePolicy.shouldLock("standalone", true, true));
        assertFalse(KioskVolumePolicy.shouldLock("standalone", false, true));
        assertFalse(KioskVolumePolicy.shouldLock("companion", true, true));
        assertFalse(KioskVolumePolicy.shouldLock("standalone", true, false));
    }

    @Test
    public void consumesEveryHardwareMediaVolumeMutationWhileLocked() {
        assertTrue(KioskVolumePolicy.isVolumeMutationKey(24));
        assertTrue(KioskVolumePolicy.isVolumeMutationKey(25));
        assertTrue(KioskVolumePolicy.isVolumeMutationKey(164));
        assertFalse(KioskVolumePolicy.isVolumeMutationKey(4));
    }

    @Test
    public void usesAQuietChildSafeTargetInsteadOfMaximumVolume() {
        assertEquals(7, KioskVolumePolicy.targetVolumeForMaximum(25));
        assertEquals(4, KioskVolumePolicy.targetVolumeForMaximum(15));
        assertEquals(1, KioskVolumePolicy.targetVolumeForMaximum(3));
        assertEquals(0, KioskVolumePolicy.targetVolumeForMaximum(0));
    }
}
