package com.orgista.openpanel;

import static org.junit.Assert.assertEquals;

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
}
