package com.orgista.openpanel;

/** Pure launcher-state decision shared with unit tests. */
final class LauncherState {
    static final String MODE_NATIVE = "native";
    static final String MODE_FIRE_REDIRECT = "fire-redirect";
    static final String MODE_FIRE_NEEDS_ACCESSIBILITY = "fire-needs-accessibility";
    static final String MODE_OTHER = "other";

    private LauncherState() {}

    static boolean isDefaultLauncher(
        String openPanelPackage,
        String resolvedHomePackage
    ) {
        return openPanelPackage != null
            && openPanelPackage.equals(resolvedHomePackage);
    }

    static String homeControlMode(
        String openPanelPackage,
        String resolvedHomePackage,
        String managementMode,
        boolean openPanelHomeEnabled,
        boolean accessibilityEnabled
    ) {
        if (isDefaultLauncher(openPanelPackage, resolvedHomePackage)) {
            return MODE_NATIVE;
        }
        if (FireLauncherRedirect.FIRE_LAUNCHER_PACKAGE.equals(resolvedHomePackage)
                && KioskState.MODE_STANDALONE.equals(managementMode)
                && openPanelHomeEnabled) {
            return accessibilityEnabled
                ? MODE_FIRE_REDIRECT
                : MODE_FIRE_NEEDS_ACCESSIBILITY;
        }
        return MODE_OTHER;
    }
}
