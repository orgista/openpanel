package com.orgista.openpanel;

/** Decides when the opt-in escape gesture should accept touches. */
final class HomeGestureVisibility {
    private static final String OPENPANEL_PACKAGE = "com.orgista.openpanel";
    private static final String OPENPANEL_DEBUG_PACKAGE = "com.orgista.openpanel.debug";
    private static final String ARBORXR_PACKAGE = "app.xrdm.client";

    private HomeGestureVisibility() {}

    /** Returns null for window events that must not change the current state. */
    static Boolean forWindowPackage(String windowPackage) {
        if (windowPackage == null || windowPackage.isEmpty()) return null;
        if (OPENPANEL_DEBUG_PACKAGE.equals(windowPackage)
            || ARBORXR_PACKAGE.equals(windowPackage)) {
            return null;
        }
        return !OPENPANEL_PACKAGE.equals(windowPackage);
    }
}
