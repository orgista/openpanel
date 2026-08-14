package com.orgista.openpanel;

import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;

import java.security.MessageDigest;

/**
 * Read-only checks for the "special app access" grants Fully-style kiosks rely
 * on (accessibility, overlay, usage access, write-settings, battery), plus the
 * signing checksum needed to build a Device-Owner provisioning QR. Kept apart
 * from SystemBridgePlugin so the plugin only wires calls to these.
 *
 * None of these can be flipped by a Device Owner through a public API — they are
 * per-app-op grants the user toggles in Settings — so the UI auto-grants what a
 * Device Owner actually can (runtime permissions, keyguard) and routes the rest
 * here for a status read plus a deep link into the right Settings screen.
 */
final class DeviceAccess {
    private DeviceAccess() {}

    static boolean isAccessibilityServiceEnabled(Context context) {
        ComponentName service = new ComponentName(context, HomeGestureAccessibilityService.class);
        return secureComponentListContains(context,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, service);
    }

    static boolean isNotificationListenerEnabled(Context context) {
        ComponentName service = new ComponentName(
            context, OpenPanelNotificationListenerService.class);
        return secureComponentListContains(context,
            "enabled_notification_listeners", service);
    }

    private static boolean secureComponentListContains(
            Context context, String setting, ComponentName component) {
        String enabled = Settings.Secure.getString(context.getContentResolver(), setting);
        if (enabled == null || enabled.isEmpty()) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        String flat = component.flattenToString();
        while (splitter.hasNext()) {
            if (flat.equalsIgnoreCase(splitter.next())) return true;
        }
        return false;
    }

    static boolean canDrawOverlays(Context context) {
        return Settings.canDrawOverlays(context);
    }

    static boolean hasUsageAccess(Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) return false;
        try {
            int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.getPackageName())
                : appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception error) {
            return false;
        }
    }

    static boolean canWriteSettings(Context context) {
        return Settings.System.canWrite(context);
    }

    static boolean isIgnoringBatteryOptimizations(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /**
     * SHA-256 of this app's signing certificate, URL-safe base64 without padding
     * or line wraps — the exact form Android's Device-Owner QR provisioning
     * expects for PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM. Computed from the
     * running build's own signature, so the QR matches whichever OpenPanel APK
     * (debug or release, same key) is hosted at the download URL. Null on failure.
     */
    @SuppressWarnings({"deprecation", "PackageManagerGetSignatures"})
    static String signingChecksum(Context context) {
        try {
            byte[] cert = null;
            PackageManager pm = context.getPackageManager();
            String pkg = context.getPackageName();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                if (info.signingInfo != null) {
                    Signature[] signers = info.signingInfo.getApkContentsSigners();
                    if (signers != null && signers.length > 0) cert = signers[0].toByteArray();
                }
            } else {
                PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
                if (info.signatures != null && info.signatures.length > 0) {
                    cert = info.signatures[0].toByteArray();
                }
            }
            if (cert == null) return null;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert);
            return Base64.encodeToString(digest,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Exception error) {
            return null;
        }
    }
}
