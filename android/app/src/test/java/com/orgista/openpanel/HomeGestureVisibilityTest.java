package com.orgista.openpanel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HomeGestureVisibilityTest {
    @Test
    public void hidesWhenProductionOpenPanelTakesFocus() {
        assertFalse(HomeGestureVisibility.forWindowPackage("com.orgista.openpanel"));
    }

    @Test
    public void ignoresTheOverlayAndArborXrWindowEvents() {
        assertNull(HomeGestureVisibility.forWindowPackage("com.orgista.openpanel.debug"));
        assertNull(HomeGestureVisibility.forWindowPackage("app.xrdm.client"));
    }

    @Test
    public void showsOverExternalApps() {
        assertTrue(HomeGestureVisibility.forWindowPackage("com.sidheinteractive.sif.DR"));
        assertTrue(HomeGestureVisibility.forWindowPackage("com.android.settings"));
    }

    @Test
    public void hidesUntilTheForegroundPackageIsKnown() {
        assertNull(HomeGestureVisibility.forWindowPackage(null));
        assertNull(HomeGestureVisibility.forWindowPackage(""));
    }
}
