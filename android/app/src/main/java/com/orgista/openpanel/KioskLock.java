package com.orgista.openpanel;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared Device-Owner kiosk lockdown so MainActivity's auto-pin and the plugin's
 * enableKioskLock behave identically: a silent LOCKED lock task with the status
 * bar + notifications hidden — never the user-confirmed "App is pinned" mode.
 *
 * The nag appears when startLockTask() runs without the calling package being in
 * the Device-Owner lock-task allowlist, so this always allowlists OpenPanel
 * (merged with any existing entries) first.
 */
final class KioskLock {
    private static final String LOG_TAG = "OpenPanel";

    private KioskLock() {}

    private static DevicePolicyManager dpm(Context context) {
        return (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    /**
     * As Device Owner, allowlist OpenPanel (plus any extra packages, merged with
     * the current allowlist so previously-added apps stay launchable) and hide
     * the status bar + notifications, so a following startLockTask() enters
     * silent LOCKED mode. No-op — returns false — when not Device Owner.
     */
    static boolean applyDeviceOwnerLockdown(Context context, List<String> extraPackages) {
        DevicePolicyManager dpm = dpm(context);
        String self = context.getPackageName();
        if (dpm == null || !dpm.isDeviceOwnerApp(self)) return false;
        ComponentName admin = OpenPanelDeviceAdminReceiver.getComponentName(context);
        try {
            Set<String> allow = new LinkedHashSet<>();
            allow.add(self);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String[] current = dpm.getLockTaskPackages(admin);
                if (current != null) allow.addAll(Arrays.asList(current));
            }
            if (extraPackages != null) {
                for (String p : extraPackages) {
                    if (p != null && !p.isEmpty()) allow.add(p);
                }
            }
            dpm.setLockTaskPackages(admin, allow.toArray(new String[0]));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Keep only the power long-press menu; hide status bar,
                // notifications, Home, and Recents inside the lock task.
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS);
            }
            dpm.setStatusBarDisabled(admin, true);
            // Honor the operator's lock-screen choice on every kiosk engage; the
            // DevicePolicyManager keyguard flag resets across reboots.
            try { dpm.setKeyguardDisabled(admin, KioskState.isKeyguardDisabled(context)); }
            catch (Exception ignored) {}
            return true;
        } catch (Exception error) {
            Log.w(LOG_TAG, "applyDeviceOwnerLockdown failed", error);
            return false;
        }
    }

    /** Re-enable the status bar when leaving the kiosk (Device Owner only). */
    static void releaseStatusBar(Context context) {
        DevicePolicyManager dpm = dpm(context);
        String self = context.getPackageName();
        if (dpm == null || !dpm.isDeviceOwnerApp(self)) return;
        try {
            dpm.setStatusBarDisabled(OpenPanelDeviceAdminReceiver.getComponentName(context), false);
        } catch (Exception ignored) {}
    }
}
