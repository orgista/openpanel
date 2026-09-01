/*
 * Copyright (c) 2026 Orgista.
 * SPDX-License-Identifier: MIT
 */

package com.orgista.openpanel.quest

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemSettingsRouteTest {
  @Test
  fun `wifi uses the Android action relayed by Horizon OS`() {
    assertEquals("android.settings.WIFI_SETTINGS", HorizonSystemSettings.wifi.action)
    assertEquals("android.settings.SETTINGS", HorizonSystemSettings.wifi.fallbackAction)
  }

  @Test
  fun `bluetooth uses the Android action relayed by Horizon OS`() {
    assertEquals("android.settings.BLUETOOTH_SETTINGS", HorizonSystemSettings.bluetooth.action)
    assertEquals("android.settings.SETTINGS", HorizonSystemSettings.bluetooth.fallbackAction)
  }

  @Test
  fun `date settings retains a settings-root fallback`() {
    assertEquals("android.settings.DATE_SETTINGS", HorizonSystemSettings.dateTime.action)
    assertEquals("android.settings.SETTINGS", HorizonSystemSettings.dateTime.fallbackAction)
  }

  @Test
  fun `system panels lead with firmware-verified SystemUX overlay commands`() {
    assertEquals("systemux://quick_settings", HorizonSystemSettings.wifi.overlayCommand)
    assertEquals("systemux://quick_settings", HorizonSystemSettings.bluetooth.overlayCommand)
    assertEquals("systemux://settings", HorizonSystemSettings.root.overlayCommand)
    // No datetime systemux route exists on this firmware.
    assertEquals(null, HorizonSystemSettings.dateTime.overlayCommand)
    assertEquals("systemux://quick_settings", SystemUx.QUICK_SETTINGS)
    assertEquals("com.oculus.vrshell", SystemUx.SHELL_PACKAGE)
  }
}
