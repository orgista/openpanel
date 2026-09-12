/*
 * Copyright (c) 2026 Orgista.
 * SPDX-License-Identifier: MIT
 */

package com.orgista.openpanel.quest

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap

data class LaunchableApp(
    val label: String,
    val packageName: String,
    val activityName: String,
    val isVr: Boolean,
    val isSystemApp: Boolean,
    val icon: Bitmap? = null,
    val banner: Bitmap? = null,
    val managedByArborXr: Boolean = false,
    val assignmentClient: String? = null,
)

data class AppCandidate(
    val label: String,
    val packageName: String,
    val activityName: String,
    val enabled: Boolean,
    val exported: Boolean,
    val isVr: Boolean,
    val isSystemApp: Boolean,
    val installer: String? = null,
    val applicationLabel: String = "",
)

/**
 * Managed configuration pushed through ArborXR App Configuration
 * (Android managed app restrictions; schema in res/xml/app_restrictions.xml).
 */
data class LauncherPolicy(
    val visiblePackages: Set<String> = emptySet(),
    val hiddenPackages: Set<String> = emptySet(),
    val showAllApps: Boolean = false,
)

/** Package that installs everything deployed through the ArborXR portal. */
const val ARBORXR_CLIENT_PACKAGE = "app.xrdm.client"

/**
 * Unity's auto-generated store banners are near-black slabs. Rendering one
 * full-bleed makes the hero read as an empty panel, so flat-dark art is
 * treated as absent and the hero falls back to its icon composition.
 */
object FlatArtDetector {
  fun isFlatDark(pixels: IntArray): Boolean {
    if (pixels.isEmpty()) return true
    var sum = 0.0
    var lit = 0
    for (pixel in pixels) {
      val r = (pixel ushr 16 and 0xFF) / 255.0
      val g = (pixel ushr 8 and 0xFF) / 255.0
      val b = (pixel and 0xFF) / 255.0
      val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
      sum += luma
      if (luma > 0.25) lit++
    }
    val mean = sum / pixels.size
    val litFraction = lit.toDouble() / pixels.size
    return mean < 0.10 && litFraction < 0.02
  }
}

/** Compact launcher copy that avoids a persistent management workmark. */
fun allowedAppsLabel(appCount: Int, assignmentClient: String?): String =
    if (assignmentClient != null) {
      "$appCount allowed"
    } else {
      "$appCount ${if (appCount == 1) "app" else "apps"}"
    }

/** ArborXR/MDM infrastructure that must never show up as launchable content. */
private val ARBORXR_INFRA_PACKAGES =
    setOf(
        "app.xrdm.client",
        "app.xrdm.launcher",
        "com.oculus.oemconfig",
    )

private val USER_FACING_SYSTEM_PACKAGES =
    setOf(
        "com.facebook.horizon",
        "com.google.android.apps.youtube.vr.oculus",
        "com.oculus.browser",
        "com.oculus.horizon",
        "com.oculus.horizonmediaplayer",
        "com.oculus.hzosgallery",
        "com.oculus.tv",
    )

/** A "label" such as com.oculus.os.voidactivity.VoidActivity is not a name. */
private fun looksLikeClassName(label: String): Boolean =
    label.none { it.isWhitespace() } && label.count { it == '.' } >= 2

private fun prettyLabel(candidate: AppCandidate): String {
  if (candidate.label.isNotBlank() && !looksLikeClassName(candidate.label)) {
    return candidate.label.trim()
  }
  if (candidate.applicationLabel.isNotBlank() && !looksLikeClassName(candidate.applicationLabel)) {
    return candidate.applicationLabel.trim()
  }
  val tail = candidate.packageName.substringAfterLast('.').ifBlank { candidate.packageName }
  return tail.replaceFirstChar { it.uppercase() }
}

fun selectLaunchableApps(
    candidates: List<AppCandidate>,
    ownPackageName: String,
    policy: LauncherPolicy = LauncherPolicy(),
    recentLaunches: Map<String, Long> = emptyMap(),
): List<LaunchableApp> {
  val usable =
      candidates
          .asSequence()
          .filter { it.enabled && it.exported }
          .filter { it.packageName != ownPackageName }
          .filter { it.packageName !in ARBORXR_INFRA_PACKAGES }
          .filter { it.label.isNotBlank() || it.applicationLabel.isNotBlank() }
          .toList()

  // The launcher is ArborXR-first: when the ArborXR agent has installed any
  // content, only that whitelisted content is offered. Unmanaged/dev devices
  // fall back to the curated view so the launcher is still useful there.
  val arborManaged = usable.filter { it.installer == ARBORXR_CLIENT_PACKAGE }
  val pool =
      when {
        policy.showAllApps -> usable
        arborManaged.isNotEmpty() -> arborManaged
        else -> usable.filter { !it.isSystemApp || it.packageName in USER_FACING_SYSTEM_PACKAGES }
      }

  return pool
      .groupBy { it.packageName }
      .mapNotNull { (_, entries) ->
        entries.maxWithOrNull(
            compareBy<AppCandidate> { if (it.isVr) 1 else 0 }
                .thenBy { if (it.label.contains('.') || it.label.contains('/')) 0 else 1 }
                .thenByDescending { it.label.length },
        )
      }
      .map {
        LaunchableApp(
            label = prettyLabel(it),
            packageName = it.packageName,
            activityName = it.activityName,
            isVr = it.isVr,
            isSystemApp = it.isSystemApp,
            managedByArborXr = it.installer == ARBORXR_CLIENT_PACKAGE,
            assignmentClient = it.installer,
        )
      }
      .filter { policy.visiblePackages.isEmpty() || it.packageName in policy.visiblePackages }
      .filterNot { it.packageName in policy.hiddenPackages }
      .sortedWith(
          // Horizon Navigator surfaces recently used apps first; apps never
          // launched from this headset keep the stable VR-first, A-Z order.
          compareByDescending<LaunchableApp> { recentLaunches[it.packageName] ?: 0L }
              .thenByDescending { it.isVr }
              .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
              .thenBy { it.packageName },
      )
}

/**
 * Compact launch-recency journal persisted as a single preference string
 * (`pkg|epochMillis;…`, most recent first, capped). Pure functions so the
 * ordering behaviour stays unit-testable without Android.
 */
object RecentLaunchStore {
  const val MAX_ENTRIES = 12

  fun parse(serialized: String?): Map<String, Long> =
      serialized
          .orEmpty()
          .split(';')
          .mapNotNull { entry ->
            val parts = entry.split('|')
            val timestamp = parts.getOrNull(1)?.toLongOrNull()
            if (parts.size == 2 && parts[0].isNotBlank() && timestamp != null) {
              parts[0] to timestamp
            } else {
              null
            }
          }
          .toMap()

  fun serialize(recents: Map<String, Long>): String =
      recents.entries
          .sortedByDescending { it.value }
          .take(MAX_ENTRIES)
          .joinToString(";") { "${it.key}|${it.value}" }

  fun withLaunch(recents: Map<String, Long>, packageName: String, timestamp: Long): Map<String, Long> =
      (recents + (packageName to timestamp)).entries
          .sortedByDescending { it.value }
          .take(MAX_ENTRIES)
          .associate { it.key to it.value }
}

class AppDiscovery(private val packageManager: PackageManager, private val ownPackageName: String) {
  private val installerCache = mutableMapOf<String, String?>()

  fun discover(
      policy: LauncherPolicy = LauncherPolicy(),
      recentLaunches: Map<String, Long> = emptyMap(),
  ): List<LaunchableApp> {
    val queries =
        listOf(
            Intent(Intent.ACTION_MAIN).addCategory(VR_CATEGORY) to true,
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER) to false,
        )

    val candidates =
        queries.flatMap { (intent, isVr) ->
          @Suppress("DEPRECATION")
          packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL).map { resolved ->
            val activity = resolved.activityInfo
            val application = activity.applicationInfo
            AppCandidate(
                label = resolved.loadLabel(packageManager)?.toString().orEmpty(),
                packageName = activity.packageName,
                activityName = activity.name,
                enabled = activity.enabled && application.enabled,
                exported = activity.exported,
                isVr = isVr,
                isSystemApp = application.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                installer = installerOf(activity.packageName),
                applicationLabel = application.loadLabel(packageManager)?.toString().orEmpty(),
            )
          }
        }

    // Art is decoded only for apps that will actually render, and cached
    // across refreshes: decoding icons+banners for every resolved activity on
    // each onResume was the main source of launcher heat.
    return selectLaunchableApps(candidates, ownPackageName, policy, recentLaunches).map { app ->
      app.copy(
          icon = iconFor(app.packageName),
          banner = bannerFor(app.packageName, app.activityName),
      )
    }
  }

  private fun installerOf(packageName: String): String? =
      installerCache.getOrPut(packageName) {
        runCatching { packageManager.getInstallSourceInfo(packageName).installingPackageName }
            .getOrNull()
      }

  private fun iconFor(packageName: String): Bitmap? {
    iconCache[packageName]?.let { return it }
    if (packageName in iconMisses) return null
    val bitmap =
        runCatching { packageManager.getApplicationIcon(packageName).toBitmap(ICON_PX, ICON_PX) }
            .getOrNull()
    if (bitmap != null) iconCache[packageName] = bitmap else iconMisses.add(packageName)
    return bitmap
  }

  private fun bannerFor(packageName: String, activityName: String): Bitmap? {
    bannerCache[packageName]?.let { return it }
    if (packageName in bannerMisses) return null
    val bitmap =
        runCatching {
              packageManager
                  .getActivityInfo(ComponentName(packageName, activityName), 0)
                  .loadBanner(packageManager)
                  ?.toBitmap(BANNER_W, BANNER_H)
            }
            .getOrNull()
            ?.takeUnless(::isFlatDarkBitmap)
    if (bitmap != null) bannerCache[packageName] = bitmap else bannerMisses.add(packageName)
    return bitmap
  }

  private fun isFlatDarkBitmap(banner: Bitmap): Boolean {
    val sampleWidth = 32
    val sampleHeight = 18
    val sample = Bitmap.createScaledBitmap(banner, sampleWidth, sampleHeight, true)
    val pixels = IntArray(sampleWidth * sampleHeight)
    sample.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
    if (sample !== banner) sample.recycle()
    return FlatArtDetector.isFlatDark(pixels)
  }

  companion object {
    const val VR_CATEGORY = "com.oculus.intent.category.VR"
    private const val ICON_PX = 256
    private const val BANNER_W = 960
    private const val BANNER_H = 540

    private val iconCache = ConcurrentHashMap<String, Bitmap>()
    private val bannerCache = ConcurrentHashMap<String, Bitmap>()
    private val iconMisses = ConcurrentHashMap.newKeySet<String>()
    private val bannerMisses = ConcurrentHashMap.newKeySet<String>()
  }
}
