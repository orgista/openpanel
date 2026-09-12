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
    public void hidesRecoveryLaunchersStoresBrowsersAndAdminUtilities() {
        assertFalse(LaunchableAppCatalog.isVisiblePackage("com.teslacoilsw.launcher"));
        assertFalse(LaunchableAppCatalog.isVisiblePackage("com.aurora.store"));
        assertFalse(LaunchableAppCatalog.isVisiblePackage("org.fdroid.fdroid"));
        assertFalse(LaunchableAppCatalog.isVisiblePackage("com.android.vending"));
        assertFalse(LaunchableAppCatalog.isVisiblePackage("com.android.chrome"));
        assertFalse(LaunchableAppCatalog.isVisiblePackage("org.mozilla.firefox"));
        assertFalse(LaunchableAppCatalog.isVisiblePackage("dnsfilter.android"));
        assertFalse(LaunchableAppCatalog.isVisiblePackage("moe.shizuku.privileged.api"));
        assertTrue(LaunchableAppCatalog.isVisiblePackage("com.example.kids.game"));
    }

    @Test
    public void hidesProtectedAmazonAppsThatFireOsWillNotDisable() {
        assertFalse(LaunchableAppCatalog.isVisiblePackage("com.amazon.csapp"));
        assertFalse(LaunchableAppCatalog.isVisiblePackage("com.fireos.usagestats.proxy"));
    }
}
