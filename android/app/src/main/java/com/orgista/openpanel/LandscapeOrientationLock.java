package com.orgista.openpanel;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;

import java.util.Locale;

/** Keeps Fire OS in landscape when the operator has granted Modify system settings. */
final class LandscapeOrientationLock {
    private static final String LOG_TAG = "OpenPanel";

    private LandscapeOrientationLock() {}

    static boolean isFireDevice(String manufacturer, String brand) {
        String normalizedManufacturer = normalize(manufacturer);
        String normalizedBrand = normalize(brand);
        return normalizedManufacturer.contains("amazon") || normalizedBrand.contains("amazon");
    }

    static boolean enforce(Context context) {
        if (!isFireDevice(Build.MANUFACTURER, Build.BRAND)) return false;
        if (!Settings.System.canWrite(context)) {
            Log.w(LOG_TAG, "Fire landscape lock needs Modify system settings permission");
            return false;
        }
        try {
            if (Settings.System.getInt(
                    context.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION,
                    0
                ) != 0) {
                Settings.System.putInt(
                    context.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION,
                    0
                );
            }
            if (Settings.System.getInt(
                    context.getContentResolver(),
                    Settings.System.USER_ROTATION,
                    Surface.ROTATION_0
                ) != targetRotation()) {
                Settings.System.putInt(
                    context.getContentResolver(),
                    Settings.System.USER_ROTATION,
                    targetRotation()
                );
            }
            return true;
        } catch (RuntimeException error) {
            Log.w(LOG_TAG, "Fire landscape lock could not update system rotation", error);
            return false;
        }
    }

    /** Fire tablet is mounted with its buttons/camera on the opposite landscape edge. */
    static int targetRotation() {
        return Surface.ROTATION_270;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
