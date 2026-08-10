package com.orgista.openpanel;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Finds launchable apps across both touch-device and Android TV launchers. */
final class LaunchableAppCatalog {
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
}
