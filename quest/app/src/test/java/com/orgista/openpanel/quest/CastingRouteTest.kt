/*
 * Copyright (c) 2026 Orgista.
 * SPDX-License-Identifier: MIT
 */

package com.orgista.openpanel.quest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CastingRouteTest {
  @Test
  fun `defines Horizon's exported sharing panel`() {
    val route = HorizonCasting.nativeSharingPanel

    assertEquals("com.oculus.metacam", route.packageName)
    assertEquals("com.oculus.panelapp.sharing.SharingPanelActivity", route.activityName)
    assertEquals("android.intent.action.MAIN", route.action)
    assertEquals(setOf("android.intent.category.LAUNCHER"), route.categories)
  }

  @Test
  fun `preferred routes expose only the sharing panel and only when installed`() {
    assertEquals(listOf(HorizonCasting.nativeSharingPanel), HorizonCasting.preferredRoutes { true })
    assertTrue(HorizonCasting.preferredRoutes { false }.isEmpty())
  }
}
