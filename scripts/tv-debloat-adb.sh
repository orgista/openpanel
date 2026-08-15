#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <device-serial> [preview|apply|restore]"
}

device_serial="${1:-}"
action="${2:-preview}"

if [[ -z "$device_serial" ]] || [[ "$action" != "preview" && "$action" != "apply" && "$action" != "restore" ]]; then
  usage
  exit 2
fi

if ! adb -s "$device_serial" get-state 2>/dev/null | rg -q '^device$'; then
  echo "ADB device is not available: $device_serial"
  exit 1
fi

if ! adb -s "$device_serial" shell pm list features | tr -d '\r' | rg -q 'android\.software\.leanback'; then
  echo "Refusing to apply the TV policy to a device without Android TV Leanback."
  exit 1
fi

# Exact packages only. This mirrors OpenPanel's reviewed TCL/generic TV policy.
# It deliberately excludes Settings, WebView, Play services, launchers, TV
# input, HDMI, Wi-Fi, Bluetooth, OTA, package installation, and ADB services.
packages=(
  com.tcl.appmarket2
  com.tcl.bi
  com.tcl.bootadservice
  com.tcl.esticker
  com.tcl.showmode
  com.tcl.usercenter
  com.tcl.waterfall.overseas
  com.google.android.tv.bugreportsender
  com.google.android.tvrecommendations
  com.google.android.youtube.tv
  com.google.android.youtube.tvmusic
  com.google.android.videos
)

for package_name in "${packages[@]}"; do
  if [[ "$action" == "restore" ]]; then
    if adb -s "$device_serial" shell cmd package install-existing --user 0 "$package_name" 2>/dev/null | tr -d '\r' | rg -q 'installed|Package'; then
      echo "restored $package_name"
    else
      echo "not-restored $package_name"
    fi
    continue
  fi

  if ! adb -s "$device_serial" shell pm path "$package_name" 2>/dev/null | tr -d '\r' | rg -q '^package:'; then
    echo "not-installed $package_name"
    continue
  fi

  if [[ "$action" == "preview" ]]; then
    echo "eligible $package_name"
    continue
  fi

  result="$(adb -s "$device_serial" shell pm uninstall --user 0 "$package_name" 2>&1 | tr -d '\r')"
  if [[ "$result" == *Success* ]]; then
    echo "removed-for-user-0 $package_name"
  else
    echo "unchanged $package_name: $result"
  fi
done
