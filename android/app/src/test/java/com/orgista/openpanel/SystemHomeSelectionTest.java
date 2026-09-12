package com.orgista.openpanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for SystemBridgePlugin.selectSystemHomeCandidate() /
 * looksLikeFallbackHomeClassName() — the pure candidate-selection logic
 * behind findSystemHomeComponent(), split out so it's testable without a
 * live PackageManager or Robolectric (this module has neither — see the
 * other *Test.java files in this package; android.content.ComponentName's
 * getters throw "not mocked" under the plain JVM unit-test jar, which is why
 * the tested helpers work in terms of plain package/class name Strings and
 * ResolveInfo/ActivityInfo/ApplicationInfo field access only).
 *
 * Covers stage2-opus.md's V1 acceptance: a candidate list where every
 * candidate is a fallback must refuse (return null), and a list with a real
 * non-system launcher alongside a system fallback must NOT refuse (must
 * return the real launcher).
 */
public class SystemHomeSelectionTest {

    private static final String SELF_PACKAGE = "com.orgista.openpanel.debug";

    private static ResolveInfo candidate(String packageName, String className, boolean system) {
        ResolveInfo info = new ResolveInfo();
        info.activityInfo = new ActivityInfo();
        info.activityInfo.packageName = packageName;
        info.activityInfo.name = className;
        info.activityInfo.applicationInfo = new ApplicationInfo();
        info.activityInfo.applicationInfo.flags = system ? ApplicationInfo.FLAG_SYSTEM : 0;
        return info;
    }

    @Test
    public void refusesWhenEveryCandidateIsAFallback() {
        List<ResolveInfo> candidates = new ArrayList<>();
        candidates.add(candidate("com.android.tv.settings", ".system.FallbackHome", true));
        candidates.add(candidate("com.tcl.keycustomfunctionservice", ".FallbackHome", true));

        SystemBridgePlugin.HomeCandidate result =
            SystemBridgePlugin.selectSystemHomeCandidate(candidates, SELF_PACKAGE);

        assertNull("every candidate is a fallback, selector must refuse (return null)", result);
    }

    @Test
    public void doesNotRefuseWhenANonSystemRealLauncherExistsAlongsideASystemFallback() {
        List<ResolveInfo> candidates = new ArrayList<>();
        // System fallback appears first in priority order, as it does on the
        // real TV (this is exactly the ordering that made the old
        // first-FLAG_SYSTEM-wins loop pick the fallback).
        candidates.add(candidate("com.tcl.keycustomfunctionservice", ".FallbackHome", true));
        candidates.add(candidate("com.teamscale.projectivylauncher", ".MainActivity", false));

        SystemBridgePlugin.HomeCandidate result =
            SystemBridgePlugin.selectSystemHomeCandidate(candidates, SELF_PACKAGE);

        assertNotNull("a real non-fallback launcher exists, selector must not refuse", result);
        assertEquals("com.teamscale.projectivylauncher", result.packageName);
    }

    @Test
    public void prefersSystemLauncherOverNonSystemWhenBothAreReal() {
        List<ResolveInfo> candidates = new ArrayList<>();
        candidates.add(candidate("com.google.android.tvlauncher", ".MainActivity", true));
        candidates.add(candidate("com.teamscale.projectivylauncher", ".MainActivity", false));

        SystemBridgePlugin.HomeCandidate result =
            SystemBridgePlugin.selectSystemHomeCandidate(candidates, SELF_PACKAGE);

        assertEquals("com.google.android.tvlauncher", result.packageName);
    }

    @Test
    public void skipsOpenPanelItselfAndSettings() {
        List<ResolveInfo> candidates = new ArrayList<>();
        candidates.add(candidate(SELF_PACKAGE, ".OpenPanelHomeActivity", false));
        candidates.add(candidate("com.android.settings", ".FallbackHome", true));
        candidates.add(candidate("com.google.android.tvlauncher", ".MainActivity", true));

        SystemBridgePlugin.HomeCandidate result =
            SystemBridgePlugin.selectSystemHomeCandidate(candidates, SELF_PACKAGE);

        assertEquals("com.google.android.tvlauncher", result.packageName);
    }

    @Test
    public void fallbackHomePrefixIsMatchedNotJustExactName() {
        assertTrue(SystemBridgePlugin.looksLikeFallbackHomeClassName(".FallbackHomeActivity"));
        assertTrue(SystemBridgePlugin.looksLikeFallbackHomeClassName(".FallbackHome"));
        assertTrue(SystemBridgePlugin.looksLikeFallbackHomeClassName(
            "com.tcl.keycustomfunctionservice.FallbackHome"));
        assertFalse(SystemBridgePlugin.looksLikeFallbackHomeClassName(".MainActivity"));
        assertTrue(SystemBridgePlugin.looksLikeFallbackHomeClassName(null));
    }
}
