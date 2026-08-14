package com.orgista.openpanel;

/** Decision boundary for the Fire OS HOME-launcher accessibility fallback. */
final class FireLauncherRedirect {
    static final String FIRE_LAUNCHER_PACKAGE = "com.amazon.firelauncher";

    private FireLauncherRedirect() {}

    static boolean shouldRedirect(
        String windowPackage,
        String managementMode,
        boolean openPanelHomeEnabled
    ) {
        return FIRE_LAUNCHER_PACKAGE.equals(windowPackage)
            && KioskState.MODE_STANDALONE.equals(managementMode)
            && openPanelHomeEnabled;
    }
}
