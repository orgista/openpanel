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

    @Test
    public void redirectsOtherKnownFireHomePackages() {
        for (String fireHome : new String[] {
            "com.amazon.hedwig",
            "com.amazon.tv.launcher",
            "com.amazon.kindle.otter.oobe.forced.ftue"
        }) {
            assertTrue(fireHome, FireLauncherRedirect.shouldRedirect(
                fireHome,
                KioskState.MODE_STANDALONE,
                true
            ));
        }
    }

    @Test
    public void redirectsAnUnrecognisedLauncherWhenItIsTheResolvedHome() {
        assertTrue(FireLauncherRedirect.shouldRedirect(
            "com.example.unknownlauncher",
            KioskState.MODE_STANDALONE,
            true,
            "com.example.unknownlauncher"
        ));
    }

    @Test
    public void doesNotRedirectOrdinaryAppsWhenAHomePackageIsSupplied() {
        assertFalse(FireLauncherRedirect.shouldRedirect(
            "com.android.settings",
            KioskState.MODE_STANDALONE,
            true,
            "com.example.unknownlauncher"
        ));
    }

    @Test
    public void nullWindowPackageNeverRedirects() {
        assertFalse(FireLauncherRedirect.shouldRedirect(
            null,
            KioskState.MODE_STANDALONE,
            true,
            "com.example.unknownlauncher"
        ));
    }

    @Test
    public void nullResolvedHomeDoesNotMatchANullWindowPackage() {
        assertFalse(FireLauncherRedirect.shouldRedirect(
            null,
            KioskState.MODE_STANDALONE,
            true,
            null
        ));
        assertFalse(FireLauncherRedirect.isKnownFireHome(null));
    }
}
