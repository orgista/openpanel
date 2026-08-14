package com.orgista.openpanel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SystemUiProtectionTest {
    @Test
    public void enablesOnlyForStandaloneHomeMode() {
        assertTrue(SystemUiProtection.isEnabled("standalone", true));
        assertFalse(SystemUiProtection.isEnabled("companion", true));
        assertFalse(SystemUiProtection.isEnabled("standalone", false));
    }

    @Test
    public void recognizesFireNotificationAndQuickSettingsWindows() {
        assertTrue(SystemUiProtection.shouldCollapse(
            "com.android.systemui", null, "NotificationShade", "standalone", true));
        assertTrue(SystemUiProtection.shouldCollapse(
            "com.android.systemui",
            "com.android.systemui.statusbar.phone.NotificationPanelView",
            null,
            "standalone",
            true));
        assertTrue(SystemUiProtection.shouldCollapse(
            "com.android.systemui", null, "Quick Settings", "standalone", true));
    }

    @Test
    public void leavesOtherSystemUiWindowsAndAdminSettingsAlone() {
        assertFalse(SystemUiProtection.shouldCollapse(
            "com.android.systemui", "android.widget.FrameLayout", "VolumeDialog", "standalone", true));
        assertFalse(SystemUiProtection.shouldCollapse(
            "com.android.settings", null, "Settings", "standalone", true));
        assertFalse(SystemUiProtection.shouldCollapse(
            "com.android.systemui", null, "NotificationShade", "companion", true));
    }
}
