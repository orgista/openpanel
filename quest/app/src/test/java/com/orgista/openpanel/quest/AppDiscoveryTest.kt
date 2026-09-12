/*
 * Copyright (c) 2026 Orgista.
 * SPDX-License-Identifier: MIT
 */

package com.orgista.openpanel.quest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDiscoveryTest {
  @Test
  fun `filters inaccessible entries and the current app`() {
    val result =
        selectLaunchableApps(
            listOf(
                candidate("Current", "com.orgista.openpanel.quest"),
                candidate("Disabled", "example.disabled", enabled = false),
                candidate("Private", "example.private", exported = false),
                candidate("Visible", "example.visible"),
            ),
            ownPackageName = "com.orgista.openpanel.quest",
        )

    assertEquals(listOf("example.visible"), result.map { it.packageName })
  }

  @Test
  fun `deduplicates components and preserves the VR classification`() {
    val result =
        selectLaunchableApps(
            listOf(
                candidate("Browser", "example.browser", isVr = false),
                candidate("Browser", "example.browser", isVr = true),
            ),
            ownPackageName = "self",
        )

    assertEquals(1, result.size)
    assertTrue(result.single().isVr)
  }

  @Test
  fun `collapses multiple activities into one user facing app`() {
    val result =
        selectLaunchableApps(
            listOf(
                candidate("Browser", "example.browser", activityName = "example.browser.Main"),
                candidate(
                    "Browser settings",
                    "example.browser",
                    activityName = "example.browser.Settings",
                ),
            ),
            ownPackageName = "self",
        )

    assertEquals(1, result.size)
    assertEquals("example.browser", result.single().packageName)
  }

  @Test
  fun `hides internal system packages but keeps user facing Horizon apps`() {
    val result =
        selectLaunchableApps(
            listOf(
                candidate("Guardian", "com.oculus.guardian", isSystemApp = true),
                candidate("Settings internals", "com.android.settings", isSystemApp = true),
                candidate("Browser", "com.oculus.browser", isSystemApp = true),
                candidate("YouTube VR", "com.google.android.apps.youtube.vr.oculus", isSystemApp = true),
                candidate("Game", "com.example.game"),
            ),
            ownPackageName = "self",
        )

    assertEquals(
        listOf("com.example.game", "com.google.android.apps.youtube.vr.oculus", "com.oculus.browser"),
        result.map { it.packageName }.sorted(),
    )
  }

  @Test
  fun `sorts VR apps first and labels alphabetically`() {
    val result =
        selectLaunchableApps(
            listOf(
                candidate("Zulu", "example.zulu"),
                candidate("beta", "example.beta", isVr = true),
                candidate("Alpha", "com.oculus.browser", isVr = true, isSystemApp = true),
            ),
            ownPackageName = "self",
        )

    assertEquals(listOf("Alpha", "beta", "Zulu"), result.map { it.label })
    assertTrue(result.first().isSystemApp)
    assertFalse(result.last().isVr)
  }

  @Test
  fun `shows only ArborXR managed apps when the agent installed any`() {
    val result =
        selectLaunchableApps(
            listOf(
                candidate("Managed Game", "com.example.managed", installer = ARBORXR_CLIENT_PACKAGE),
                candidate("Sideloaded", "com.example.sideloaded"),
                candidate("ArborXR Home", "app.xrdm.launcher", installer = ARBORXR_CLIENT_PACKAGE),
                candidate("Browser", "com.oculus.browser", isSystemApp = true),
            ),
            ownPackageName = "self",
        )

    assertEquals(listOf("com.example.managed"), result.map { it.packageName })
    assertTrue(result.single().managedByArborXr)
    assertEquals(ARBORXR_CLIENT_PACKAGE, result.single().assignmentClient)
  }

  @Test
  fun `labels ArborXR lists as allowed without exposing a workmark`() {
    assertEquals("3 allowed", allowedAppsLabel(3, ARBORXR_CLIENT_PACKAGE))
    assertEquals("1 allowed", allowedAppsLabel(1, ARBORXR_CLIENT_PACKAGE))
    assertEquals("3 apps", allowedAppsLabel(3, null))
    assertEquals("1 app", allowedAppsLabel(1, null))
  }

  @Test
  fun `falls back to the curated list on unmanaged devices`() {
    val result =
        selectLaunchableApps(
            listOf(
                candidate("Sideloaded", "com.example.sideloaded"),
                candidate("Browser", "com.oculus.browser", isSystemApp = true),
            ),
            ownPackageName = "self",
        )

    assertEquals(
        listOf("com.example.sideloaded", "com.oculus.browser"),
        result.map { it.packageName }.sorted(),
    )
    assertFalse(result.any { it.managedByArborXr })
  }

  @Test
  fun `honors managed configuration visibility policy`() {
    val candidates =
        listOf(
            candidate("Alpha", "com.example.alpha", installer = ARBORXR_CLIENT_PACKAGE),
            candidate("Beta", "com.example.beta", installer = ARBORXR_CLIENT_PACKAGE),
        )

    val hidden =
        selectLaunchableApps(
            candidates,
            ownPackageName = "self",
            policy = LauncherPolicy(hiddenPackages = setOf("com.example.beta")),
        )
    assertEquals(listOf("com.example.alpha"), hidden.map { it.packageName })

    val allowlisted =
        selectLaunchableApps(
            candidates,
            ownPackageName = "self",
            policy = LauncherPolicy(visiblePackages = setOf("com.example.beta")),
        )
    assertEquals(listOf("com.example.beta"), allowlisted.map { it.packageName })
  }

  @Test
  fun `sorts recently launched apps first then falls back to stable order`() {
    val result =
        selectLaunchableApps(
            listOf(
                candidate("Alpha", "example.alpha", isVr = true),
                candidate("Middle", "example.middle"),
                candidate("Zulu", "example.zulu"),
            ),
            ownPackageName = "self",
            recentLaunches = mapOf("example.zulu" to 2_000L, "example.middle" to 1_000L),
        )

    assertEquals(listOf("Zulu", "Middle", "Alpha"), result.map { it.label })
  }

  @Test
  fun `recent launch journal round trips caps and updates`() {
    assertEquals(emptyMap<String, Long>(), RecentLaunchStore.parse(null))
    assertEquals(emptyMap<String, Long>(), RecentLaunchStore.parse("garbage;no|pipe|x"))

    val one = RecentLaunchStore.withLaunch(emptyMap(), "example.a", 100L)
    val two = RecentLaunchStore.withLaunch(one, "example.b", 200L)
    val relaunched = RecentLaunchStore.withLaunch(two, "example.a", 300L)
    val serialized = RecentLaunchStore.serialize(relaunched)
    assertEquals("example.a|300;example.b|200", serialized)
    assertEquals(relaunched, RecentLaunchStore.parse(serialized))

    var crowded = emptyMap<String, Long>()
    repeat(RecentLaunchStore.MAX_ENTRIES + 4) { index ->
      crowded = RecentLaunchStore.withLaunch(crowded, "example.pkg$index", index.toLong())
    }
    assertEquals(RecentLaunchStore.MAX_ENTRIES, crowded.size)
    assertFalse("example.pkg0" in crowded)
    assertTrue("example.pkg${RecentLaunchStore.MAX_ENTRIES + 3}" in crowded)
  }

  @Test
  fun `prettifies raw class name labels`() {
    val result =
        selectLaunchableApps(
            listOf(
                candidate(
                    "com.unity3d.player.UnityPlayerActivity",
                    "com.example.training",
                    applicationLabel = "com.unity3d.player.UnityPlayerActivity",
                ),
            ),
            ownPackageName = "self",
        )

    assertEquals(listOf("Training"), result.map { it.label })
  }

  private fun candidate(
      label: String,
      packageName: String,
      activityName: String = "$packageName.MainActivity",
      enabled: Boolean = true,
      exported: Boolean = true,
      isVr: Boolean = false,
      isSystemApp: Boolean = false,
      installer: String? = null,
      applicationLabel: String = "",
  ) =
      AppCandidate(
          label = label,
          packageName = packageName,
          activityName = activityName,
          enabled = enabled,
          exported = exported,
          isVr = isVr,
          isSystemApp = isSystemApp,
          installer = installer,
          applicationLabel = applicationLabel,
      )
}
