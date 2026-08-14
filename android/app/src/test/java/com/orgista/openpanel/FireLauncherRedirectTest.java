package com.orgista.openpanel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FireLauncherRedirectTest {
    @Test
    public void redirectsFireLauncherForEnabledStandaloneHome() {
        assertTrue(FireLauncherRedirect.shouldRedirect(
            "com.amazon.firelauncher",
            KioskState.MODE_STANDALONE,
            true
        ));
    }

    @Test
    public void allowsFireLauncherAfterOpenPanelHomeIsDisabled() {
        assertFalse(FireLauncherRedirect.shouldRedirect(
            "com.amazon.firelauncher",
            KioskState.MODE_STANDALONE,
            false
        ));
    }

    @Test
    public void companionModeAndOtherPackagesNeverRedirect() {
        assertFalse(FireLauncherRedirect.shouldRedirect(
            "com.amazon.firelauncher",
            KioskState.MODE_COMPANION,
            true
        ));
        assertFalse(FireLauncherRedirect.shouldRedirect(
            "com.android.settings",
            KioskState.MODE_STANDALONE,
            true
        ));
    }
}
