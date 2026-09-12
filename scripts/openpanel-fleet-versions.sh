#!/usr/bin/env bash
# One table: what OpenPanel version every known build target is actually running,
# compared with the version in this checkout. Run it after every fix that ships
# to one device — a fix is not "done" until every row is at or above it.
#
#   scripts/openpanel-fleet-versions.sh            # adb + ArborXR
#   scripts/openpanel-fleet-versions.sh --adb      # only devices on adb (no network/API)
#   scripts/openpanel-fleet-versions.sh --arborxr  # only the ArborXR fleet
#
# Rows:
#   adb      every device `adb devices` can see, both packages
#            (com.orgista.openpanel = production key, .debug = Mac Studio debug key)
#   arborxr  ArborXR deployment status for the OpenPanel app on every enrolled device
#            (installed version vs the version its channel targets), plus the
#            three release channels, so a build that is uploaded but not promoted
#            shows up as such.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-$ROOT/.toolchains/platform-tools/adb}"
[[ -x "$ADB" ]] || ADB="$(command -v adb || true)"
CLI="$ROOT/scripts/arborxr-cli-keychain.sh"
APP_ID="a05cc83e-871e-4ab9-86fe-773a37d946a7"
API="https://api.xrdm.app/api/v3"

MODE="${1:-all}"
REPO_VERSION="$(node -p "require('$ROOT/package.json').version" 2>/dev/null || sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' "$ROOT/package.json" | head -1)"
REPO_CODE="$(awk '$1 == "versionCode" {print $2; exit}' "$ROOT/android/app/build.gradle")"

printf 'Repo: %s (versionCode %s)\n\n' "$REPO_VERSION" "$REPO_CODE"
printf '%-8s %-26s %-30s %-14s %-12s %s\n' SOURCE DEVICE MODEL PACKAGE VERSION STATE
printf '%-8s %-26s %-30s %-14s %-12s %s\n' ------ ------ ----- ------- ------- -----

# Compare "1.1.48-debug" / "1.1.48" against the repo version.
state_for() {
  local v="${1%%-*}"
  [[ -z "$v" ]] && { echo "-"; return; }
  if [[ "$v" == "$REPO_VERSION" ]]; then echo "current"
  elif [[ "$(printf '%s\n%s\n' "$v" "$REPO_VERSION" | sort -V | head -1)" == "$v" ]]; then echo "BEHIND"
  else echo "ahead?"; fi
}

if [[ "$MODE" == "all" || "$MODE" == "--adb" ]]; then
  if [[ -x "$ADB" ]]; then
    "$ADB" devices -l 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}' | while read -r serial; do
      model="$("$ADB" -s "$serial" shell getprop ro.product.model </dev/null 2>/dev/null | tr -d '\r')"
      found=0
      for pkg in com.orgista.openpanel com.orgista.openpanel.debug; do
        v="$("$ADB" -s "$serial" shell "dumpsys package $pkg 2>/dev/null | grep -m1 versionName" </dev/null | tr -d '\r' | sed 's/.*versionName=//')"
        [[ -n "$v" ]] || continue
        found=1
        printf '%-8s %-26s %-30s %-14s %-12s %s\n' adb "$serial" "$model" "${pkg#com.orgista.}" "$v" "$(state_for "$v")"
      done
      (( found )) || printf '%-8s %-26s %-30s %-14s %-12s %s\n' adb "$serial" "$model" "-" "-" "not installed"
    done
  else
    echo "adb not found; skipping adb rows" >&2
  fi
fi

if [[ "$MODE" == "all" || "$MODE" == "--arborxr" ]]; then
  if command -v abxr-cli >/dev/null && command -v jq >/dev/null; then
    TOKEN="$(security find-generic-password -a openpanel-deploy -s "OpenPanel ArborXR API" -w 2>/dev/null || true)"
    if [[ -n "$TOKEN" ]]; then
      # ArborXR's deployment status per device: the version the device reports
      # installed for the OpenPanel app entry, and the version its channel targets.
      devices_json="$("$CLI" --silent --format json devices list 2>/dev/null)"
      curl -sS -H "Authorization: Bearer $TOKEN" -H 'Accept: application/json' \
          "$API/statuses?per_page=100&contentIds[]=$APP_ID&contentTypes[]=app" 2>/dev/null \
        | jq -r --argjson devices "$devices_json" '
            ($devices | map({key: .id, value: .}) | from_entries) as $d
            | .data[]
            | ($d[.deviceId] // {}) as $dev
            | [ ($dev.name // .deviceId),
                ($dev.deviceModel.name // "-"),
                (.content.installedVersion // "-"),
                (.content.targetVersion // .content.version // "-"),
                .status,
                (if ($dev.isOnline // false) then "" else " (offline)" end) ] | @tsv' \
        | while IFS=$'\t' read -r name model installed target status offline; do
            printf '%-8s %-26s %-30s %-14s %-12s %s\n' arborxr "$name" "$model" "openpanel" "$installed" \
              "$(state_for "$installed"); channel->$target, $status$offline"
          done
      echo
      echo "ArborXR release channels (com.orgista.openpanel):"
      "$CLI" --silent --format json apps release_channels "$APP_ID" 2>/dev/null \
        | jq -r '.[] | "  \(.name)\t-> \(.version.version // "none")\t(\(.version.status // "-"))"' | column -t -s $'\t'
      latest_uploaded="$("$CLI" --silent --format json apps versions "$APP_ID" 2>/dev/null | jq -r '[.[] | select(.status=="available")] | sort_by(.code) | last | "\(.version) (code \(.code))"')"
      echo "  newest available build: $latest_uploaded"
      unset TOKEN
    else
      echo "ArborXR token not in Keychain; skipping ArborXR rows" >&2
    fi
  else
    echo "abxr-cli/jq not found; skipping ArborXR rows" >&2
  fi
fi
