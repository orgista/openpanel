package com.orgista.openpanel;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Finds launchable apps across both touch-device and Android TV launchers. */
final class LaunchableAppCatalog {
    /** Installed admin/recovery packages that must never become child-facing tiles. */
    private static final Set<String> HIDDEN_PACKAGES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList(
            "com.teslacoilsw.launcher",
            "com.aurora.store",
            "org.fdroid.fdroid",
            "com.android.vending",
            "com.amazon.venezia",
            "com.sec.android.app.samsungapps",
            "com.xiaomi.mipicks",
            "com.huawei.appmarket",
            "com.heytap.market",
            "com.oppo.market",
            "com.bbk.appstore",
            "com.tcl.appmarket2",
            "dnsfilter.android",
            "moe.shizuku.privileged.api",
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "org.mozilla.firefox",
            "org.mozilla.fenix",
            "org.mozilla.focus",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.duckduckgo.mobile.android",
            "com.kiwibrowser.browser",
            "com.vivaldi.browser"
        ))
    );

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
            && !HIDDEN_PACKAGES.contains(packageName)
            && !packageName.startsWith("amazon.")
            && !packageName.startsWith("com.amazon.")
            && !packageName.startsWith("com.fireos.");
    }
}
