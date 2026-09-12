package com.orgista.openpanel;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Shared policy used by the bridge and notification-listener process. */
final class NotificationBlockPolicy {
    static final String PREFS = "openpanel.device_health.v1";
    static final String PREF_ENABLED = "manage_notifications";
    /** Single source of truth for ArborXR's MDM/DPC package (the Device Owner on
     *  managed devices). Referenced from the bridge and gesture layer too. */
    static final String ARBORXR_DPC_PACKAGE = "app.xrdm.client";

    private static final Set<String> OPERATIONAL_PACKAGES = new HashSet<>(Arrays.asList(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.bluetooth",
        "com.android.networkstack",
        "com.android.networkstack.tethering",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.providers.downloads",
        "com.android.cellbroadcastreceiver",
        "com.google.android.cellbroadcastreceiver",
        "com.amazon.cellbroadcastreceiver",
        "com.amazon.dpcclient",
        "com.amazon.firelauncher",
        ARBORXR_DPC_PACKAGE,
        "dnsfilter.android",
        "moe.shizuku.privileged.api"
    ));

    private NotificationBlockPolicy() {}

    static boolean isEnabled(Context context) {
        return preferences(context).getBoolean(PREF_ENABLED, true);
    }

    static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(PREF_ENABLED, enabled).apply();
    }

    static boolean shouldPreserve(
            String packageName,
            String openPanelPackage,
            String resolvedHomePackage) {
        if (packageName == null || packageName.isEmpty()) return true;
        return packageName.equals(openPanelPackage)
            || packageName.equals(resolvedHomePackage)
            || OPERATIONAL_PACKAGES.contains(packageName);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
