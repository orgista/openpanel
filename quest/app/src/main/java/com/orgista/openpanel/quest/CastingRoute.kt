/*
 * Copyright (c) 2026 Orgista.
 * SPDX-License-Identifier: MIT
 */

package com.orgista.openpanel.quest

data class ExternalActivityRoute(
    val packageName: String,
    val activityName: String,
    val action: String,
    val categories: Set<String> = emptySet(),
)

/**
 * Stable entry point into Horizon OS' native sharing UI, where Cast is a
 * system-owned action ("Cast from this headset").
 *
 * Verified on Quest 3S firmware 2026-08-22: the direct
 * `SharingDialogActivityAlias` + START_CASTING route CRASHES Meta's own
 * SharingDialogActivity (ACRA in onDestroy, "Unexpected null action"), the
 * `systemux://sharing|casting` vrshell broadcasts are accepted but show no UI
 * to third parties, and SharingPanelActivity ignores a START_CASTING action
 * hint. The exported sharing panel below is the one route that reliably
 * opens, so it is the only route offered.
 */
object HorizonCasting {
  val nativeSharingPanel =
      ExternalActivityRoute(
          packageName = "com.oculus.metacam",
          activityName = "com.oculus.panelapp.sharing.SharingPanelActivity",
          action = "android.intent.action.MAIN",
          categories = setOf("android.intent.category.LAUNCHER"),
      )

  fun preferredRoutes(routeInstalled: (ExternalActivityRoute) -> Boolean): List<ExternalActivityRoute> =
      listOf(nativeSharingPanel).filter(routeInstalled)
}
