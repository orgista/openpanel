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
}
