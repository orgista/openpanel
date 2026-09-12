/*
 * Copyright (c) 2026 Orgista.
 * SPDX-License-Identifier: MIT
 */

package com.orgista.openpanel.quest

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaterMotionTest {
  @Test
  fun `water uv offsets stay wrapped to a full texture period`() {
    for (elapsedMs in 0L..60_000L step 137L) {
      val frame = WaterMotion.frame(elapsedMs)

      assertTrue(frame.near.u in 0f..1f)
      assertTrue(frame.near.v in 0f..1f)
      assertTrue(frame.far.u in 0f..1f)
      assertTrue(frame.far.v in 0f..1f)
    }
  }

  @Test
  fun `water layers counter-drift instead of sliding as one sheet`() {
    val early = WaterMotion.frame(2_000L)
    val late = WaterMotion.frame(12_000L)
    val nearDrift = late.near.u - early.near.u
    val farDrift = late.far.u - early.far.u

    // Opposite signed u-motion (modulo wrap) is what creates the in-place
    // undulating interference — the animated-water-shader look.
    assertNotEquals(early.near.u, early.far.u, 0.0001f)
    assertTrue(nearDrift > 0.1f)
    assertTrue(farDrift < -0.1f || farDrift > 0.5f) // wrapped negative drift
  }

  @Test
  fun `animation is deterministic and begins at the authored resting pose`() {
    val first = WaterMotion.frame(0L)
    val repeated = WaterMotion.frame(0L)

    assertEquals(first, repeated)
    assertEquals(0f, first.near.u, 0.0001f)
    assertEquals(0f, first.near.v, 0.0001f)
  }

  @Test
  fun `water movement is clearly visible over a five second observation`() {
    val first = WaterMotion.frame(1_000L)
    val later = WaterMotion.frame(6_000L)

    // Continuous drift: at least ~10% of the texture period in five seconds,
    // roughly double the old clamped scheme's whole excursion budget.
    assertTrue(abs(later.near.u - first.near.u) >= 0.10f)
    assertTrue(abs(later.far.u - first.far.u) >= 0.07f)
  }

  @Test
  fun `motion never snaps between consecutive updates`() {
    var previous = WaterMotion.frame(0L)
    for (elapsedMs in WaterMotion.UPDATE_INTERVAL_MS..30_000L step WaterMotion.UPDATE_INTERVAL_MS) {
      val current = WaterMotion.frame(elapsedMs)
      fun stepOf(now: Float, before: Float): Float {
        val raw = abs(now - before)
        return minOf(raw, 1f - raw) // modulo-aware distance
      }
      assertTrue(stepOf(current.near.u, previous.near.u) < 0.02f)
      assertTrue(stepOf(current.far.u, previous.far.u) < 0.02f)
      previous = current
    }
  }

  @Test
  fun `update cadence is low enough for motion and capped for Quest`() {
    assertTrue(WaterMotion.UPDATE_INTERVAL_MS <= 100L)
    assertTrue(WaterMotion.UPDATE_INTERVAL_MS >= 33L)
  }
}
