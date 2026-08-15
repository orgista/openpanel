package com.orgista.openpanel;

final class DeviceProfileClassifier {
    private DeviceProfileClassifier() {}

    static String deviceType(
        boolean television,
        boolean hasLeanback,
        boolean hasTouchscreen,
        int smallestScreenWidthDp
    ) {
        if (television || hasLeanback) return "television";
        if (smallestScreenWidthDp >= 600) return "tablet";
        return "handheld";
    }

    static boolean usesRemoteUi(boolean television, boolean hasDpad, boolean hasTouchscreen) {
        return television || (hasDpad && !hasTouchscreen);
    }

    static boolean usesTouchUi(String deviceType, boolean hasTouchscreen) {
        return hasTouchscreen && !"television".equals(deviceType);
    }

    static boolean showsBattery(String deviceType, boolean hasBattery) {
        return hasBattery && !"television".equals(deviceType);
    }

    static String displayControllerName(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        String normalized = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (trimmed.isEmpty() || normalized.startsWith("sim-") || normalized.startsWith("virtual-")) {
            return null;
        }
        return trimmed;
    }

    static String controllerNameForDevice(boolean television, String detectedName) {
        return television ? null : displayControllerName(detectedName);
    }
}
