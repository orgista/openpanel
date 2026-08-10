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
        assertEquals("generic", DebloatCatalog.profileFor("Google", "google"));
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
    public void policyHasNoDuplicatePackageNamesPerDevice() {
        List<DebloatCatalog.Rule> rules = DebloatCatalog.rulesFor("Samsung", "Samsung");
        Set<String> packages = new HashSet<>();
        for (DebloatCatalog.Rule rule : rules) {
            assertTrue("duplicate package " + rule.packageName, packages.add(rule.packageName));
            assertNotNull(rule.reason);
        }
    }
}
