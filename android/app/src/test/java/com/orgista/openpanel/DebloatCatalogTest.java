package com.orgista.openpanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DebloatCatalogTest {

    @Test
    public void detectsMajorOemProfiles() {
        assertEquals("lenovo", DebloatCatalog.profileFor("LENOVO", "Lenovo"));
        assertEquals("samsung", DebloatCatalog.profileFor("samsung", "samsung"));
        assertEquals("xiaomi", DebloatCatalog.profileFor("Xiaomi", "Redmi"));
        assertEquals("oplus", DebloatCatalog.profileFor("OnePlus", "OnePlus"));
        assertEquals("huawei", DebloatCatalog.profileFor("HONOR", "HONOR"));
        assertEquals("amazon", DebloatCatalog.profileFor("Amazon", "Amazon"));
        assertEquals("tcl", DebloatCatalog.profileFor("TCL", "TCL"));
        assertEquals("generic", DebloatCatalog.profileFor("Google", "google"));
    }

    @Test
    public void tclTvPolicyContainsOnlyReviewedOptionalServices() {
        List<DebloatCatalog.Rule> rules = DebloatCatalog.rulesFor("TCL", "TCL");
        Set<String> packages = new HashSet<>();
        for (DebloatCatalog.Rule rule : rules) packages.add(rule.packageName);

        assertTrue(packages.contains("com.google.android.youtube.tv"));
        assertTrue(packages.contains("com.google.android.videos"));
        assertTrue(packages.contains("com.google.android.tv.bugreportsender"));
        assertTrue(packages.contains("com.tcl.bi"));
        assertTrue(packages.contains("com.tcl.bootadservice"));
        assertTrue(packages.contains("com.tcl.showmode"));
        assertFalse(packages.contains("com.google.android.tvlauncher"));
        assertFalse(packages.contains("com.tcl.tvinput"));
    }

    @Test
    public void lenovoPolicyContainsRequestedApps() {
        List<DebloatCatalog.Rule> rules = DebloatCatalog.rulesFor("LENOVO", "Lenovo");
        Set<String> packages = new HashSet<>();
        for (DebloatCatalog.Rule rule : rules) packages.add(rule.packageName);

        assertTrue(packages.contains("com.google.android.apps.books"));
        assertTrue(packages.contains("com.google.android.play.games"));
        assertTrue(packages.contains("com.google.android.youtube"));
        assertTrue(packages.contains("com.google.android.apps.youtube.kids"));
        assertTrue(packages.contains("com.google.android.apps.kids.home"));
        assertTrue(packages.contains("com.google.android.keep"));
        assertTrue(packages.contains("com.google.android.apps.walletnfcrel"));
        assertTrue(packages.contains("com.google.android.apps.nbu.paisa.user"));
        assertTrue(packages.contains("com.lenovo.hec.lenovoextend"));
        assertFalse(packages.contains("com.lenovo.hecplatform.hecagent"));
    }

    @Test
    public void wallpaperAndManagementPackagesStayProtected() {
        assertTrue(DebloatCatalog.isProtectedPackage("com.tblenovo.wallpaper"));
        assertTrue(DebloatCatalog.isProtectedPackage("com.android.wallpaper.livepicker"));
        assertTrue(DebloatCatalog.isProtectedPackage("com.zui.theme.settings.overlay.blue"));
        assertTrue(DebloatCatalog.isProtectedPackage("com.lenovo.hecplatform.hecagent"));
        assertTrue(DebloatCatalog.isProtectedPackage("app.xrdm.client"));
        assertTrue(DebloatCatalog.isProtectedPackage("com.orgista.openpanel"));
        assertFalse(DebloatCatalog.isProtectedPackage("com.lenovo.hec.lenovoextend"));
    }

    @Test
    public void amazonPolicyRecognizesReviewedFireOsApps() {
        List<DebloatCatalog.Rule> rules = DebloatCatalog.rulesFor("Amazon", "Amazon");
        Set<String> packages = new HashSet<>();
        for (DebloatCatalog.Rule rule : rules) packages.add(rule.packageName);

        assertTrue(packages.contains("com.amazon.afe.app"));
        assertTrue(packages.contains("com.amazon.avod"));
        assertTrue(packages.contains("com.amazon.dee.app"));
        assertTrue(packages.contains("com.amazon.firespotlight"));
        assertTrue(packages.contains("com.amazon.client.metrics"));
        assertTrue(packages.contains("com.amazon.device.metrics"));
        assertTrue(packages.contains("com.amazon.hybridadidservice"));
        assertTrue(packages.contains("com.amazon.tahoe"));
        assertTrue(packages.contains("com.amazon.wirelessmetrics.service"));
        assertTrue(packages.contains("com.audible.application.kindle"));
        assertTrue(packages.contains("com.kingsoft.office.amz"));
        assertTrue(packages.contains("com.amazon.cloud9"));
        assertTrue(packages.contains("com.amazon.cloud9.contentservice"));
        assertTrue(packages.contains("com.android.gallery3d"));
        assertTrue(packages.contains("com.android.camera2"));
    }

    @Test
    public void genericChildKioskPolicyIncludesStockBrowsersAndMediaApps() {
        List<DebloatCatalog.Rule> rules = DebloatCatalog.rulesFor("Google", "google");
        Set<String> packages = new HashSet<>();
        for (DebloatCatalog.Rule rule : rules) packages.add(rule.packageName);

        assertTrue(packages.contains("com.android.chrome"));
        assertTrue(packages.contains("org.mozilla.firefox"));
        assertTrue(packages.contains("com.android.gallery3d"));
        assertTrue(packages.contains("com.google.android.apps.photos"));
        assertTrue(packages.contains("com.android.camera2"));
    }

    @Test
    public void fireOsCoreLauncherPolicyAndSetupStayProtected() {
        assertTrue(DebloatCatalog.isProtectedPackage("com.amazon.firelauncher"));
        assertTrue(DebloatCatalog.isProtectedPackage("com.amazon.settings"));
        assertTrue(DebloatCatalog.isProtectedPackage("com.amazon.frameworksettings"));
        assertTrue(DebloatCatalog.isProtectedPackage("com.amazon.webview.chromium"));
        assertTrue(DebloatCatalog.isProtectedPackage("com.amazon.device.software.ota"));
        assertTrue(DebloatCatalog.isProtectedPackage("com.amazon.device.software.ota.override"));
        assertTrue(DebloatCatalog.isProtectedPackage("com.amazon.kindle.otter.oobe"));
        assertTrue(DebloatCatalog.isProtectedPackage("com.amazon.parentalcontrols"));
        assertTrue(DebloatCatalog.isProtectedPackage("com.amazon.pm"));
        assertFalse(DebloatCatalog.isProtectedPackage("com.amazon.firespotlight"));
    }

    @Test
    public void tvLaunchersInputsSettingsAndUpdatesStayProtected() {
        assertTrue(DebloatCatalog.isProtectedPackage("com.google.android.tvlauncher"));
        assertTrue(DebloatCatalog.isProtectedPackage("com.google.android.tv.remote.service"));
        assertTrue(DebloatCatalog.isProtectedPackage("com.tcl.keycustomfunctionservice"));
        assertTrue(DebloatCatalog.isProtectedPackage("com.tcl.settings"));
        assertTrue(DebloatCatalog.isProtectedPackage("com.tcl.tvinput"));
        assertTrue(DebloatCatalog.isProtectedPackage("com.tcl.versionUpdateApp"));
        assertFalse(DebloatCatalog.isProtectedPackage("com.tcl.bi"));
    }

    @Test
    public void policyHasNoDuplicatePackageNamesPerDevice() {
        List<DebloatCatalog.Rule> rules = DebloatCatalog.rulesFor("Samsung", "Samsung");
        Set<String> packages = new HashSet<>();
        for (DebloatCatalog.Rule rule : rules) {
            assertTrue("duplicate package " + rule.packageName, packages.add(rule.packageName));
            assertNotNull(rule.reason);
        }
    }
}
