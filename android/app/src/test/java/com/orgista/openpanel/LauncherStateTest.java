package com.orgista.openpanel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LauncherStateTest {
    @Test
    public void resolvedHomePackageMarksLauncherAsDefault() {
        assertTrue(LauncherState.isDefaultLauncher(
            "com.orgista.openpanel.debug",
            "com.orgista.openpanel.debug"
        ));
    }

    @Test
    public void fireOsPriorityOverrideIsNotReportedAsDefaultHome() {
        assertFalse(LauncherState.isDefaultLauncher(
            "com.orgista.openpanel.debug",
            "com.amazon.firelauncher"
        ));
    }

    @Test
    public void unrelatedResolvedHomeIsNotDefault() {
        assertFalse(LauncherState.isDefaultLauncher(
            "com.orgista.openpanel.debug",
            "com.teslacoilsw.launcher"
        ));
    }

    @Test
    public void reportsActiveFireRedirectSeparatelyFromDefaultHome() {
        assertTrue(LauncherState.MODE_FIRE_REDIRECT.equals(
            LauncherState.homeControlMode(
                "com.orgista.openpanel.debug",
                "com.amazon.firelauncher",
                KioskState.MODE_STANDALONE,
                true,
                true
            )
        ));
    }

    @Test
    public void reportsWhenFireRedirectStillNeedsAccessibility() {
        assertTrue(LauncherState.MODE_FIRE_NEEDS_ACCESSIBILITY.equals(
            LauncherState.homeControlMode(
                "com.orgista.openpanel.debug",
                "com.amazon.firelauncher",
                KioskState.MODE_STANDALONE,
                true,
                false
            )
        ));
    }
}
