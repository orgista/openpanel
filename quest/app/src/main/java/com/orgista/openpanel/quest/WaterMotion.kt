/*
 * Copyright (c) 2026 Orgista.
 * SPDX-License-Identifier: MIT
 */

package com.orgista.openpanel.quest

import kotlin.math.sin

data class WaterLayerOffset(val u: Float, val v: Float)

data class WaterFrame(val near: WaterLayerOffset, val far: WaterLayerOffset)

/**
 * Continuous UV motion for two translucent ripple layers.
 *
 * Modeled on the Blender "realtime animated water shader" technique (two
 * noise fields whose 4D W value animates): the layers drift CONTINUOUSLY in
 * opposite directions at different temporal frequencies, so their overlap
 * forms an interference pattern that undulates in place — water that visibly
 * moves — instead of a texture that slides. The previous implementation
 * clamped offsets to a ±0.14 excursion with a hard wrap, which snapped and
 * read as static (Anthony: "the water does not move").
 */
object WaterMotion {
  /**
   * 15Hz UV updates keep slow water motion visible while avoiding the high
   * sustained CPU cost seen from 30Hz material updates on Quest 3S.
   */
  const val UPDATE_INTERVAL_MS = 66L

  /** Full-period wrap: offsets stay in [0,1) so drift never snaps. */
  private fun wrap(phase: Float): Float = ((phase % 1f) + 1f) % 1f

  fun frame(elapsedMs: Long): WaterFrame {
    val seconds = elapsedMs.coerceAtLeast(0L) / 1_000f
    return WaterFrame(
        near =
            WaterLayerOffset(
                u = wrap(seconds * 0.030f + 0.030f * sin(seconds * 0.55f)),
                v = wrap(seconds * 0.011f + 0.016f * sin(seconds * 0.31f)),
            ),
        far =
            WaterLayerOffset(
                u = wrap(-seconds * 0.022f + 0.024f * sin(seconds * 0.43f + 2.1f)),
                v = wrap(seconds * 0.008f + 0.012f * sin(seconds * 0.23f + 1.2f)),
            ),
    )
  }
}
