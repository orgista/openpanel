package com.orgista.openpanel;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Finds launchable apps across both touch-device and Android TV launchers. */
final class LaunchableAppCatalog {
    private static final String NOVA_LAUNCHER_PACKAGE = "com.teslacoilsw.launcher";

    private LaunchableAppCatalog() {}

    static List<String> categories() {
        return Arrays.asList(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER);
    }

    static List<ResolveInfo> query(PackageManager packageManager) {
        List<ResolveInfo> activities = new ArrayList<>();
        for (String category : categories()) {
            Intent main = new Intent(Intent.ACTION_MAIN, null);
            main.addCategory(category);
            List<ResolveInfo> matches = packageManager.queryIntentActivities(main, 0);
            if (matches != null) activities.addAll(matches);
        }
        return activities;
    }

    /**
     * Recovery launchers may remain installed on constrained Fire OS devices, but should not
     * appear as child-facing apps inside OpenPanel.
     */
    static boolean isVisiblePackage(String packageName) {
        return packageName != null
            && !NOVA_LAUNCHER_PACKAGE.equals(packageName)
            && !packageName.startsWith("amazon.")
            && !packageName.startsWith("com.amazon.")
            && !packageName.startsWith("com.fireos.");
    }
}
