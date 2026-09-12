/*
 * Copyright (c) 2026 Orgista.
 * SPDX-License-Identifier: MIT
 */

package com.orgista.openpanel.quest

data class SystemSettingsRoute(
    val action: String,
    val fallbackAction: String = ROOT_SETTINGS_ACTION,
    /**
     * SystemUX overlay command tried FIRST via Meta's documented system
     * deep-linking contract (launch intent for com.oculus.vrshell with an
     * `intent_data` extra): the panel opens as a small overlay over the
     * running app — Horizon home / ArborXR Home behavior — instead of
     * app-switching into full Settings. Null skips the overlay attempt.
     */
    val overlayCommand: String? = null,
) {
  companion object {
    const val ROOT_SETTINGS_ACTION = "android.settings.SETTINGS"
  }
}

/**
 * SystemUX deep-link constants (Meta "System Deep Linking" contract).
 *
 * Route names are FIRMWARE-VERIFIED (Quest 3S, 2026-08-23) by enumerating
 * `systemux://` strings in VrShell.apk and live-testing: this build has NO
 * wifi/bluetooth/sharing/datetime routes (each logs "Invalid route").
 * `quick_settings` opens Horizon's Quick Settings as a TRUE OVERLAY
 * (OVERLAY_LAUNCHER category — a small panel over the running app, the
 * ArborXR Home behavior) and carries Wi-Fi, Bluetooth, and Cast. `settings`
 * opens the Horizon settings panel via the shell relay. Do NOT add a `uri`
 * extra unless the sub-path is verified — an invalid uri poisons the whole
 * request ("Invalid uri=…" then "Failed to launch").
 */
object SystemUx {
  const val SHELL_PACKAGE = "com.oculus.vrshell"
  const val EXTRA_INTENT_DATA = "intent_data"
  const val EXTRA_URI = "uri"
  const val QUICK_SETTINGS = "systemux://quick_settings"
  const val SETTINGS = "systemux://settings"
}

/** Horizon system panels: overlay-first, Android settings action fallback. */
object HorizonSystemSettings {
  val root = SystemSettingsRoute(SystemSettingsRoute.ROOT_SETTINGS_ACTION, overlayCommand = SystemUx.SETTINGS)
  val wifi =
      SystemSettingsRoute("android.settings.WIFI_SETTINGS", overlayCommand = SystemUx.QUICK_SETTINGS)
  val bluetooth =
      SystemSettingsRoute(
          "android.settings.BLUETOOTH_SETTINGS",
          overlayCommand = SystemUx.QUICK_SETTINGS,
      )
  // No datetime systemux route on this firmware; the Android action chain
  // (verified 0.3.7) stays authoritative for the clock chip.
  val dateTime = SystemSettingsRoute("android.settings.DATE_SETTINGS")
}
