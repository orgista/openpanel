/*
 * Copyright (c) 2026 Orgista.
 * SPDX-License-Identifier: MIT
 */

package com.orgista.openpanel.quest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelLayoutTest {
  @Test
  fun `library keeps Horizon's 1840x1000 window aspect`() {
    assertEquals(
        1840f / 1000f,
        PanelLayout.LIBRARY_WIDTH_METERS / PanelLayout.LIBRARY_HEIGHT_METERS,
        0.001f,
    )
  }

  @Test
  fun `system bar stays a compact floating pill`() {
    assertTrue(PanelLayout.BAR_HEIGHT_METERS <= 0.13f)
    assertTrue(PanelLayout.BAR_WIDTH_METERS <= 0.75f)
    assertTrue(PanelLayout.BAR_WIDTH_METERS / PanelLayout.BAR_HEIGHT_METERS >= 4f)
  }

  @Test
  fun `library avoids a giant empty slab when the managed catalog is small`() {
    assertTrue(PanelLayout.LIBRARY_WIDTH_METERS in 1.35f..1.45f)
    assertTrue(PanelLayout.LIBRARY_HEIGHT_METERS < 1f)
  }

  @Test
  fun `system bar is tall enough for its round buttons`() {
    val barHeightInDp = PanelLayout.BAR_HEIGHT_METERS * PanelLayout.DP_PER_METER

    // 28dp buttons + a little breathing room
    assertTrue(barHeightInDp >= 36f)
  }

  @Test
  fun `panels render at Horizon-class supersampled density`() {
    assertTrue(PanelLayout.MAIN_RESOLUTION_SCALE in 1.35f..1.55f)
    assertTrue(PanelLayout.SECONDARY_RESOLUTION_SCALE in 1.2f..1.45f)
  }

  @Test
  fun `reset-size control appears only when the window meaningfully drifts`() {
    assertTrue(!PanelLayout.isResizedScale(1f))
    assertTrue(!PanelLayout.isResizedScale(1.015f))
    assertTrue(!PanelLayout.isResizedScale(0.99f))
    assertTrue(PanelLayout.isResizedScale(1.05f))
    assertTrue(PanelLayout.isResizedScale(0.9f))
    assertTrue(PanelLayout.isResizedScale(1.35f))
  }
}
