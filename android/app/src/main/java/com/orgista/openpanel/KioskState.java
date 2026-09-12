package com.orgista.openpanel;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

/** Persistent native kiosk state shared by MainActivity and the Capacitor bridge. */
final class KioskState {
    static final String MODE_COMPANION = "companion";
    static final String MODE_STANDALONE = "standalone";

    private static final String PREFERENCES = "openpanel.kiosk";
    private static final String KEY_MODE = "managementMode";
    private static final String KEY_ENABLED = "kioskEnabled";
    private static final String KEY_KEYGUARD_DISABLED = "keyguardDisabled";

    private KioskState() {}

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    static String getMode(Context context) {
        String stored = preferences(context).getString(KEY_MODE, MODE_COMPANION);
        return normalizeModeForDevice(
            stored,
            LandscapeOrientationLock.isFireDevice(Build.MANUFACTURER, Build.BRAND)
        );
    }

    static void setMode(Context context, String mode) {
        String normalized = normalizeModeForDevice(
            mode,
            LandscapeOrientationLock.isFireDevice(Build.MANUFACTURER, Build.BRAND)
        );
        preferences(context).edit().putString(KEY_MODE, normalized).apply();
    }

    static boolean isEnabled(Context context) {
        return preferences(context).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    // Whether the operator asked to suppress the lock screen. There is no API to
    // read the live keyguard-disabled policy back, so we remember the intent and
    // re-apply it on launch (a reboot clears the DevicePolicyManager flag).
    static boolean isKeyguardDisabled(Context context) {
        return preferences(context).getBoolean(KEY_KEYGUARD_DISABLED, false);
    }

    static void setKeyguardDisabled(Context context, boolean disabled) {
        preferences(context).edit().putBoolean(KEY_KEYGUARD_DISABLED, disabled).apply();
    }

    static boolean shouldAutoPin(Context context) {
        DevicePolicyManager policy = (DevicePolicyManager) context.getSystemService(
            Context.DEVICE_POLICY_SERVICE
        );
        boolean deviceOwner = policy != null && policy.isDeviceOwnerApp(context.getPackageName());
        boolean fireDevice = LandscapeOrientationLock.isFireDevice(
            Build.MANUFACTURER,
            Build.BRAND
        );
        return shouldAutoPin(getMode(context), isEnabled(context), deviceOwner, fireDevice);
    }

    static boolean shouldAutoPin(String mode, boolean enabled) {
        return MODE_STANDALONE.equals(mode) && enabled;
    }

    static boolean shouldAutoPin(
        String mode,
        boolean enabled,
        boolean deviceOwner,
        boolean fireDevice
    ) {
        return shouldAutoPin(mode, enabled)
            && canReliablyStartLockTask(deviceOwner, fireDevice);
    }

    static boolean isLockTaskActive(int state) {
        return state != 0;
    }

    static boolean canReliablyStartLockTask(boolean deviceOwner, boolean fireDevice) {
        return deviceOwner || !fireDevice;
    }

    static boolean shouldUseFireRedirectKiosk(boolean deviceOwner, boolean fireDevice) {
        return fireDevice && !deviceOwner;
    }

    static String normalizeModeForDevice(String mode, boolean fireDevice) {
        return fireDevice ? MODE_STANDALONE : normalizeMode(mode);
    }

    static String normalizeMode(String mode) {
        return MODE_STANDALONE.equals(mode) ? MODE_STANDALONE : MODE_COMPANION;
    }
}
