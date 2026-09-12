package com.orgista.openpanel;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Decision boundary for the Fire OS HOME-launcher accessibility fallback. */
final class FireLauncherRedirect {
    static final String FIRE_LAUNCHER_PACKAGE = "com.amazon.firelauncher";

    /**
     * Exact Fire OS home/setup packages. Fire OS ships different home packages
     * across tablet generations and OTA builds, so a single constant silently
     * disabled the redirect on any build that did not use `com.amazon.firelauncher`.
     * Exact names only — this never pattern-matches an unknown package.
     */
    private static final Set<String> FIRE_HOME_PACKAGES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList(
            FIRE_LAUNCHER_PACKAGE,
            "com.amazon.hedwig",
            "com.amazon.tv.launcher",
            "com.amazon.kindle.otter.oobe.forced.ftue"
        ))
    );

    private FireLauncherRedirect() {}

    static boolean shouldRedirect(
        String windowPackage,
        String managementMode,
        boolean openPanelHomeEnabled
    ) {
        return shouldRedirect(windowPackage, managementMode, openPanelHomeEnabled, null);
    }

    /**
     * @param resolvedHomePackage the device's current default HOME package when it is
     *     *not* OpenPanel, or null. Passing it lets an unrecognised OEM launcher still
     *     be redirected while OpenPanel owns HOME, without guessing package names.
     */
    static boolean shouldRedirect(
        String windowPackage,
        String managementMode,
        boolean openPanelHomeEnabled,
        String resolvedHomePackage
    ) {
        if (windowPackage == null) return false;
        if (!KioskState.MODE_STANDALONE.equals(managementMode)) return false;
        if (!openPanelHomeEnabled) return false;
        return isKnownFireHome(windowPackage)
            || windowPackage.equals(resolvedHomePackage);
    }

    static boolean isKnownFireHome(String windowPackage) {
        return windowPackage != null && FIRE_HOME_PACKAGES.contains(windowPackage);
    }
}
