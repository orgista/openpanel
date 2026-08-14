package com.orgista.openpanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import java.util.Arrays;

import org.junit.Test;

public class LaunchableAppCatalogTest {
    @Test
    public void scansPhoneTabletAndTvLauncherCategories() {
        assertEquals(
            Arrays.asList(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER),
            LaunchableAppCatalog.categories()
        );
    }

    @Test
    public void hidesNovaRecoveryLauncherFromOpenPanelCatalog() {
        assertFalse(LaunchableAppCatalog.isVisiblePackage("com.teslacoilsw.launcher"));
        assertTrue(LaunchableAppCatalog.isVisiblePackage("org.fdroid.fdroid"));
    }

    @Test
    public void hidesProtectedAmazonAppsThatFireOsWillNotDisable() {
        assertFalse(LaunchableAppCatalog.isVisiblePackage("com.amazon.csapp"));
        assertFalse(LaunchableAppCatalog.isVisiblePackage("com.fireos.usagestats.proxy"));
    }
}
