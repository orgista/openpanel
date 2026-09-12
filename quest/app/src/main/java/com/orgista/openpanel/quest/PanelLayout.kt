/*
 * Copyright (c) 2026 Orgista.
 * SPDX-License-Identifier: MIT
 */

package com.orgista.openpanel.quest

/** Physical panel dimensions and texture-density budgets for the Quest launcher. */
object PanelLayout {
  const val DP_PER_METER = 500f

  // Navigator parity: Horizon OS renders its App Library as one 1840×1000
  // volumetric window (200 dpi, observed on Horizon OS v81); the library
  // keeps that aspect and subtends roughly the same angle at ~1.8 m. The
  // system bar is deliberately more compact than Horizon's 850×175 window —
  // a small floating pill of round buttons, per the day-to-day navigator
  // look (Anthony: the big dock read nothing like Meta OS).
  // On-device QA with the single-app ArborXR allow-list showed that Horizon's
  // full library width left too much unused surface. Keep the native aspect,
  // but use the visually balanced compact size from the headset review pass.
  const val LIBRARY_WIDTH_METERS = 1.40f
  const val LIBRARY_HEIGHT_METERS = LIBRARY_WIDTH_METERS * (1000f / 1840f)
  const val BAR_WIDTH_METERS = 0.64f
  const val BAR_HEIGHT_METERS = 0.075f

  // The panel is physically smaller than Horizon's library window, so render it
  // at a supersampled virtual-display budget. This keeps the compact OpenPanel
  // footprint while getting close to Horizon's observed 1840×1000 texture budget
  // and avoids the visible compositor grid that shows up on low-res dark sheets.
  const val MAIN_RESOLUTION_SCALE = 1.45f
  const val SECONDARY_RESOLUTION_SCALE = 1.35f

  /**
   * True when the library window's scale has drifted meaningfully from its
   * authored size — gates the subtle reset-size control in the header.
   */
  fun isResizedScale(scale: Float): Boolean = kotlin.math.abs(scale - 1f) > 0.02f
}
