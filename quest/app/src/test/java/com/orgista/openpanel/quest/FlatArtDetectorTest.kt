/*
 * Copyright (c) 2026 Orgista.
 * SPDX-License-Identifier: MIT
 */

package com.orgista.openpanel.quest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlatArtDetectorTest {
  private fun pixels(count: Int, color: Int): IntArray = IntArray(count) { color }

  @Test
  fun `an all-black banner is flat-dark`() {
    assertTrue(FlatArtDetector.isFlatDark(pixels(576, 0xFF000000.toInt())))
  }

  @Test
  fun `a near-black banner with faint texture is flat-dark`() {
    val dark = pixels(576, 0xFF0A0B0C.toInt())
    for (i in 0 until 576 step 7) dark[i] = 0xFF16181A.toInt()
    assertTrue(FlatArtDetector.isFlatDark(dark))
  }

  @Test
  fun `a dark banner with real highlights keeps its art`() {
    val scene = pixels(576, 0xFF101214.toInt())
    for (i in 0 until 60) scene[i] = 0xFFC9D2DC.toInt()
    assertFalse(FlatArtDetector.isFlatDark(scene))
  }

  @Test
  fun `a mid-gray banner keeps its art`() {
    assertFalse(FlatArtDetector.isFlatDark(pixels(576, 0xFF808080.toInt())))
  }

  @Test
  fun `a colorful banner keeps its art`() {
    val art = IntArray(576) { index -> if (index % 2 == 0) 0xFF2266CC.toInt() else 0xFFCC8844.toInt() }
    assertFalse(FlatArtDetector.isFlatDark(art))
  }

  @Test
  fun `empty pixel data counts as flat-dark`() {
    assertTrue(FlatArtDetector.isFlatDark(IntArray(0)))
  }
}
