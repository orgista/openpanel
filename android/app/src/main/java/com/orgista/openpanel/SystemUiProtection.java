package com.orgista.openpanel;

import java.util.Locale;

/** Pure policy for the Fire-safe, accessibility-backed notification-shade guard. */
final class SystemUiProtection {
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";

    private SystemUiProtection() {}

    static boolean isEnabled(String managementMode, boolean homeEnabled) {
        return "standalone".equals(managementMode) && homeEnabled;
    }

    static boolean shouldCollapse(
            String windowPackage,
            String windowClass,
            String windowTitle,
            String managementMode,
            boolean homeEnabled) {
        if (!isEnabled(managementMode, homeEnabled)
                || !SYSTEM_UI_PACKAGE.equals(windowPackage)) {
            return false;
        }
        return identifiesShade(windowClass) || identifiesShade(windowTitle);
    }

    private static boolean identifiesShade(String value) {
        if (value == null || value.isEmpty()) return false;
        String normalized = value.toLowerCase(Locale.US).replace(" ", "");
        return normalized.contains("notificationshade")
            || normalized.contains("notificationpanel")
            || normalized.contains("quicksettings")
            || normalized.contains("quickstatusbar");
    }
}
