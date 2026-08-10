#!/usr/bin/env bash
set -euo pipefail

OPENPANEL_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OPENPANEL_APK="${OPENPANEL_APK:-$OPENPANEL_ROOT/android/app/build/outputs/apk/release/app-release.apk}"
OPENPANEL_STATE_DIR="$OPENPANEL_ROOT/private/deployment"

ARBORXR_API_BASE="https://api.xrdm.app/api/v3"
ARBORXR_KEYCHAIN_ACCOUNT="openpanel-deploy"
ARBORXR_KEYCHAIN_SERVICE="OpenPanel ArborXR API"

APP_ID="a05cc83e-871e-4ab9-86fe-773a37d946a7"
APP_NAME="OpenPanel"
EXPECTED_PACKAGE="com.orgista.openpanel"
EXPECTED_CERT_SHA256="94323ad02be157c6812605ef970f413f2f1571eb7b23cdc89241f8116e99bb0c"

DEBUG_CHANNEL_ID="4339733b-6046-4124-b1fd-dcff6a64e3ad"
DEBUG_CHANNEL_NAME="Debug"
PRODUCTION_CHANNEL_ID="69e29a9b-d1aa-4291-9f7f-b9bf272bd58a"
PRODUCTION_CHANNEL_NAME="Production"
LATEST_CHANNEL_ID="7d27d784-31e3-4dff-b914-769cafbedb5b"
LATEST_CHANNEL_NAME="Latest"

TEST_DEVICE_ID="c5e38e0a-f5d3-45cf-8395-ae2c6fb21d60"
TEST_DEVICE_NAME="Pink TCL"
TEST_DEVICE_SERIAL="HELZR8YHZLJZDEXW"

PRODUCTION_GROUP_IDS=(
  "72223c04-4241-4fd5-a640-b6cfa9f80704"
  "9faa3fc4-92c6-4ba8-a9a4-db918471917c"
  "f621725e-0826-4901-902e-76446084291e"
)
PRODUCTION_GROUP_NAMES=("Kids" "Boys" "Music")

EXPECTED_VERSION="$(node -p "require('$OPENPANEL_ROOT/package.json').version")"
EXPECTED_VERSION_CODE="$(awk '$1 == "versionCode" {print $2; exit}' "$OPENPANEL_ROOT/android/app/build.gradle")"
OPENPANEL_STATE_FILE="$OPENPANEL_STATE_DIR/arborxr-release-$EXPECTED_VERSION.json"

usage() {
  cat <<EOF
Usage: scripts/deploy-arborxr-beta.sh COMMAND

Commands:
  verify             Verify the signed release APK locally.
  status             Show the guarded OpenPanel channel, assignment, and device state.
  prepare            Upload the verified APK and pin Debug to the candidate build.
  test-candidate     Prepare, deploy Debug only to Pink TCL, launch it, and verify install.
  deploy-production  Require the tested candidate, then promote Production for Kids/Boys/Music.
  deploy             Run prepare, test-candidate, and deploy-production in order.

The script never creates an app or release channel and never assigns Debug or
Latest to a production group. Production promotion changes only the existing
Production channel's build; group assignments and launcher app lists are kept.
EOF
}

require_command() {
  command -v "$1" >/dev/null || {
    printf 'Required command not found: %s\n' "$1" >&2
    exit 1
  }
}

load_token() {
  ABXR_API_TOKEN="$(
    security find-generic-password \
      -a "$ARBORXR_KEYCHAIN_ACCOUNT" \
      -s "$ARBORXR_KEYCHAIN_SERVICE" \
      -w
  )" || {
    printf '%s\n' 'ArborXR API token was not found in macOS Keychain.' >&2
    exit 1
  }
  [[ "$ABXR_API_TOKEN" != *$'\n'* && "$ABXR_API_TOKEN" != *$'\r'* ]] || {
    printf '%s\n' 'The saved ArborXR token contains invalid newline characters.' >&2
    exit 1
  }
  export ABXR_API_TOKEN
}

cleanup() {
  unset ABXR_API_TOKEN
}
trap cleanup EXIT

api_request() {
  local method="$1"
  local path="$2"
  local body="${3-}"
  local -a args=(
    --silent
    --show-error
    --fail-with-body
    --globoff
    --request "$method"
    --header @<(printf 'Authorization: Bearer %s\n' "$ABXR_API_TOKEN")
    --header 'Accept: application/json'
  )
  if [[ -n "$body" ]]; then
    args+=(--header 'Content-Type: application/json' --data "$body")
  fi
  curl "${args[@]}" "$ARBORXR_API_BASE$path"
}

arborxr_cli() {
  abxr-cli --silent --format json "$@"
}

verify_token() {
  api_request GET '/current-user' | jq -e \
    '(.id | type == "string") and (.email | type == "string")' >/dev/null
}

apk_tools() {
  local android_sdk build_tools
  android_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  build_tools="$android_sdk/build-tools"
  APKSIGNER="$(find "$build_tools" -name apksigner -type f | sort -V | tail -1)"
  AAPT="$(find "$build_tools" -name aapt -type f | sort -V | tail -1)"
  [[ -x "$APKSIGNER" && -x "$AAPT" ]] || {
    printf '%s\n' 'Android APK verification tools were not found.' >&2
    exit 1
  }
}

verify_apk() {
  [[ -f "$OPENPANEL_APK" ]] || {
    printf 'Production APK not found: %s\n' "$OPENPANEL_APK" >&2
    exit 1
  }
  apk_tools

  local sha badging signature actual_cert checksum_file checksum_value
  sha="$(shasum -a 256 "$OPENPANEL_APK" | awk '{print $1}')"
  checksum_file="$OPENPANEL_APK.sha256"
  if [[ -f "$checksum_file" ]]; then
    checksum_value="$(awk 'NR == 1 {print $1}' "$checksum_file")"
    [[ "$checksum_value" == "$sha" ]] || {
      printf '%s\n' 'The APK does not match its SHA-256 checksum file.' >&2
      exit 1
    }
  fi

  badging="$("$AAPT" dump badging "$OPENPANEL_APK")"
  signature="$("$APKSIGNER" verify --verbose --print-certs "$OPENPANEL_APK")"
  grep -Fq "package: name='$EXPECTED_PACKAGE' versionCode='$EXPECTED_VERSION_CODE' versionName='$EXPECTED_VERSION'" <<<"$badging"
  grep -Fq "sdkVersion:'24'" <<<"$badging"
  grep -Fq "targetSdkVersion:'36'" <<<"$badging"
  grep -Fq 'leanback-launchable-activity:' <<<"$badging"
  grep -Fq 'Number of signers: 1' <<<"$signature"
  actual_cert="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$signature" | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]')"
  [[ "$actual_cert" == "$EXPECTED_CERT_SHA256" ]] || {
    printf '%s\n' 'The release APK is not signed by the expected production certificate.' >&2
    exit 1
  }

  printf 'Verified %s %s (versionCode %s), SHA-256 %s.\n' \
    "$EXPECTED_PACKAGE" "$EXPECTED_VERSION" "$EXPECTED_VERSION_CODE" "$sha"
}

group_is_production() {
  local candidate="$1"
  local expected
  for expected in "${PRODUCTION_GROUP_IDS[@]}"; do
    [[ "$candidate" == "$expected" ]] && return 0
  done
  return 1
}

verify_app_and_channels() {
  local app channels debug_count production_count latest_count
  app="$(api_request GET "/apps/$APP_ID")"
  jq -e --arg id "$APP_ID" --arg name "$APP_NAME" --arg package "$EXPECTED_PACKAGE" \
    '.id == $id and .name == $name and .packageName == $package' <<<"$app" >/dev/null

  channels="$(arborxr_cli apps release_channels "$APP_ID")"
  debug_count="$(jq --arg id "$DEBUG_CHANNEL_ID" --arg name "$DEBUG_CHANNEL_NAME" \
    '[.[] | select(.id == $id and .name == $name)] | length' <<<"$channels")"
  production_count="$(jq --arg id "$PRODUCTION_CHANNEL_ID" --arg name "$PRODUCTION_CHANNEL_NAME" \
    '[.[] | select(.id == $id and .name == $name)] | length' <<<"$channels")"
  latest_count="$(jq --arg id "$LATEST_CHANNEL_ID" --arg name "$LATEST_CHANNEL_NAME" \
    '[.[] | select(.id == $id and .name == $name)] | length' <<<"$channels")"
  [[ "$debug_count" == 1 && "$production_count" == 1 && "$latest_count" == 1 ]] || {
    printf '%s\n' 'The guarded OpenPanel release channels no longer match configuration.' >&2
    exit 1
  }
}

audit_assignments() {
  local groups group_id group_name channels openpanel_ids expected_name index
  local devices device_id device_name device_serial device_group debug_count latest_count

  groups="$(arborxr_cli groups list)"
  while IFS=$'\t' read -r group_id group_name; do
    [[ -n "$group_id" ]] || continue
    channels="$(api_request GET "/groups/$group_id/release-channels?per_page=100")"
    openpanel_ids="$(jq -c --arg appId "$APP_ID" '[.data[] | select(.app.id == $appId) | .id]' <<<"$channels")"
    if group_is_production "$group_id"; then
      [[ "$openpanel_ids" == "[\"$PRODUCTION_CHANNEL_ID\"]" ]] || {
        printf 'Production group %s has an unexpected OpenPanel assignment: %s\n' \
          "$group_name" "$openpanel_ids" >&2
        exit 1
      }
    elif [[ "$openpanel_ids" != '[]' ]]; then
      printf 'Non-production group %s has an OpenPanel assignment: %s\n' \
        "$group_name" "$openpanel_ids" >&2
      exit 1
    fi
  done < <(jq -r '.[] | select(.isConfigured == true) | [.id, .name] | @tsv' <<<"$groups")

  for index in "${!PRODUCTION_GROUP_IDS[@]}"; do
    group_id="${PRODUCTION_GROUP_IDS[$index]}"
    expected_name="${PRODUCTION_GROUP_NAMES[$index]}"
    jq -e --arg id "$group_id" --arg name "$expected_name" \
      '.[] | select(.id == $id and .name == $name and .isConfigured == true)' \
      <<<"$groups" >/dev/null || {
        printf 'Production group %s (%s) no longer matches configuration.\n' \
          "$expected_name" "$group_id" >&2
        exit 1
      }
  done

  devices="$(arborxr_cli devices list)"
  while IFS=$'\t' read -r device_id device_name device_serial device_group; do
    [[ -n "$device_id" ]] || continue
    channels="$(api_request GET "/devices/$device_id/release-channels?per_page=100")"
    debug_count="$(jq --arg id "$DEBUG_CHANNEL_ID" '[.data[] | select(.id == $id)] | length' <<<"$channels")"
    latest_count="$(jq --arg id "$LATEST_CHANNEL_ID" '[.data[] | select(.id == $id)] | length' <<<"$channels")"
    [[ "$latest_count" == 0 ]] || {
      printf 'OpenPanel Latest is assigned to device %s; promotion stopped.\n' "$device_name" >&2
      exit 1
    }
    if [[ "$device_id" == "$TEST_DEVICE_ID" ]]; then
      [[ "$device_name" == "$TEST_DEVICE_NAME" && "$device_serial" == "$TEST_DEVICE_SERIAL" && "$device_group" == 'null' && "$debug_count" == 1 ]] || {
        printf '%s\n' 'The guarded Pink TCL test-device assignment no longer matches.' >&2
        exit 1
      }
    elif [[ "$debug_count" != 0 ]]; then
      printf 'OpenPanel Debug is assigned outside Pink TCL: %s.\n' "$device_name" >&2
      exit 1
    fi
  done < <(jq -r '.[] | [.id, .name, .serialNumber, (if .group == null then "null" else .group.id end)] | @tsv' <<<"$devices")

  jq -e --arg id "$TEST_DEVICE_ID" --arg name "$TEST_DEVICE_NAME" --arg serial "$TEST_DEVICE_SERIAL" \
    '.[] | select(.id == $id and .name == $name and .serialNumber == $serial and .group == null and .isOnline == true)' \
    <<<"$devices" >/dev/null || {
      printf '%s\n' 'Pink TCL must be online, ungrouped, and match the guarded serial.' >&2
      exit 1
    }

  printf '%s\n' 'Assignment audit passed: Debug only on Pink TCL; Production only on Kids, Boys, and Music; OpenPanel Latest unassigned.'
}

candidate_build_id() {
  local sha versions
  sha="$(shasum -a 256 "$OPENPANEL_APK" | awk '{print $1}')"
  versions="$(arborxr_cli apps versions "$APP_ID")"
  jq -r --arg version "$EXPECTED_VERSION" --argjson code "$EXPECTED_VERSION_CODE" --arg sha "$sha" '
    .[] |
    select(.version == $version and .code == $code and .status == "available") |
    select((.checksum.value // "" | ascii_downcase) == ($sha | ascii_downcase)) |
    .id
  ' <<<"$versions" | head -1
}

write_state() {
  local build_id="$1"
  local sha
  sha="$(shasum -a 256 "$OPENPANEL_APK" | awk '{print $1}')"
  mkdir -p "$OPENPANEL_STATE_DIR"
  chmod 700 "$OPENPANEL_STATE_DIR"
  umask 077
  jq -n \
    --arg packageName "$EXPECTED_PACKAGE" \
    --arg version "$EXPECTED_VERSION" \
    --argjson versionCode "$EXPECTED_VERSION_CODE" \
    --arg apkSha256 "$sha" \
    --arg appId "$APP_ID" \
    --arg buildId "$build_id" \
    --arg debugReleaseChannelId "$DEBUG_CHANNEL_ID" \
    --arg productionReleaseChannelId "$PRODUCTION_CHANNEL_ID" \
    --arg testDeviceId "$TEST_DEVICE_ID" \
    --arg preparedAt "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
    '{
      packageName: $packageName,
      version: $version,
      versionCode: $versionCode,
      apkSha256: $apkSha256,
      appId: $appId,
      buildId: $buildId,
      debugReleaseChannelId: $debugReleaseChannelId,
      productionReleaseChannelId: $productionReleaseChannelId,
      testDeviceId: $testDeviceId,
      preparedAt: $preparedAt
    }' \
    >"$OPENPANEL_STATE_FILE"
}

state_set() {
  local field="$1"
  local value="$2"
  local temp_file
  temp_file="$(mktemp "$OPENPANEL_STATE_DIR/state.XXXXXX")"
  jq --arg value "$value" ".$field = \$value" "$OPENPANEL_STATE_FILE" >"$temp_file"
  chmod 600 "$temp_file"
  mv "$temp_file" "$OPENPANEL_STATE_FILE"
}

prepare_candidate() {
  local build_id upload_result channels
  verify_app_and_channels
  audit_assignments

  build_id="$(candidate_build_id)"
  if [[ -z "$build_id" ]]; then
    upload_result="$(arborxr_cli apps upload \
      "$APP_ID" "$OPENPANEL_APK" \
      --version_number "$EXPECTED_VERSION" \
      --notes 'Adds reversible Device Health debloating, default notification management, wallpaper protections, reliable kiosk exit, and validated PBS/YouTube, touch, keyboard, D-pad, and admin flows.' \
      --wait --wait_time 600 \
      --release-channel-id "$DEBUG_CHANNEL_ID")"
    jq -e '.status == "available" or .id != null' <<<"$upload_result" >/dev/null
    build_id="$(candidate_build_id)"
  fi
  [[ -n "$build_id" ]] || {
    printf 'OpenPanel %s did not become available in ArborXR.\n' "$EXPECTED_VERSION" >&2
    exit 1
  }

  arborxr_cli apps release_channel_set_version "$APP_ID" \
    --release_channel_id "$DEBUG_CHANNEL_ID" \
    --version_id "$build_id" >/dev/null
  channels="$(arborxr_cli apps release_channels "$APP_ID")"
  jq -e --arg channelId "$DEBUG_CHANNEL_ID" --arg buildId "$build_id" '
    .[] | select(.id == $channelId and .name == "Debug" and .version.id == $buildId)
  ' <<<"$channels" >/dev/null
  write_state "$build_id"
  printf 'ArborXR Debug now points to verified OpenPanel %s (build %s).\n' \
    "$EXPECTED_VERSION" "$build_id"
}

wait_for_version() {
  local device_id="$1"
  local device_name="$2"
  local attempt statuses status installed error_description
  for attempt in $(seq 1 120); do
    statuses="$(api_request GET "/statuses?per_page=100&deviceIds[]=$device_id&contentIds[]=$APP_ID&contentTypes[]=app")"
    status="$(jq -r '.data | sort_by(.statusTimestamp // "") | last | .status // empty' <<<"$statuses")"
    installed="$(jq -r '.data | sort_by(.statusTimestamp // "") | last | .content.installedVersion // empty' <<<"$statuses")"
    error_description="$(jq -r '.data | sort_by(.statusTimestamp // "") | last | .errorDescription // empty' <<<"$statuses")"
    case "$status" in
      succeeded-install|succeeded-update|succeeded-higher-version-installed)
        if [[ "$installed" == "$EXPECTED_VERSION" ]]; then
          printf 'ArborXR verified OpenPanel %s on %s.\n' "$installed" "$device_name"
          return 0
        fi
        ;;
      failed-install|failed-update)
        printf 'ArborXR failed on %s: %s%s\n' \
          "$device_name" "$status" "${error_description:+ — $error_description}" >&2
        return 1
        ;;
    esac
    if (( attempt % 6 == 0 )); then
      printf 'Waiting for OpenPanel %s on %s (current: %s, installed: %s).\n' \
        "$EXPECTED_VERSION" "$device_name" "${status:-not reported}" "${installed:-unknown}"
    fi
    sleep 5
  done
  printf 'Timed out waiting for OpenPanel %s on %s.\n' "$EXPECTED_VERSION" "$device_name" >&2
  return 1
}

test_candidate() {
  local device
  prepare_candidate
  device="$(api_request GET "/devices/$TEST_DEVICE_ID")"
  jq -e --arg id "$TEST_DEVICE_ID" --arg name "$TEST_DEVICE_NAME" --arg serial "$TEST_DEVICE_SERIAL" '
    .id == $id and .name == $name and (.serialNumber // .serial) == $serial and
    .group == null and .isOnline == true
  ' <<<"$device" >/dev/null
  wait_for_version "$TEST_DEVICE_ID" "$TEST_DEVICE_NAME"
  api_request POST "/devices/$TEST_DEVICE_ID/launch/apps/$APP_ID" \
    '{"shouldQuitCurrentApp":true}' >/dev/null
  state_set candidateTestedAt "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  printf 'Candidate launched and install status verified on %s only.\n' "$TEST_DEVICE_NAME"
}

require_tested_candidate() {
  local build_id state_build tested_at live_build
  build_id="$(candidate_build_id)"
  [[ -n "$build_id" && -f "$OPENPANEL_STATE_FILE" ]] || {
    printf '%s\n' 'Run test-candidate successfully before production promotion.' >&2
    exit 1
  }
  state_build="$(jq -r '.buildId // empty' "$OPENPANEL_STATE_FILE")"
  tested_at="$(jq -r '.candidateTestedAt // empty' "$OPENPANEL_STATE_FILE")"
  [[ "$state_build" == "$build_id" && -n "$tested_at" ]] || {
    printf '%s\n' 'The current candidate has not completed the guarded test-device gate.' >&2
    exit 1
  }
  live_build="$(arborxr_cli apps release_channels "$APP_ID" | jq -r \
    --arg id "$DEBUG_CHANNEL_ID" '.[] | select(.id == $id) | .version.id')"
  [[ "$live_build" == "$build_id" ]] || {
    printf '%s\n' 'Debug no longer points to the tested candidate.' >&2
    exit 1
  }
}

promote_production() {
  local build_id channels devices device_row device_id device_name online_count offline_count
  verify_app_and_channels
  audit_assignments
  require_tested_candidate
  wait_for_version "$TEST_DEVICE_ID" "$TEST_DEVICE_NAME"
  build_id="$(candidate_build_id)"

  arborxr_cli apps release_channel_set_version "$APP_ID" \
    --release_channel_id "$PRODUCTION_CHANNEL_ID" \
    --version_id "$build_id" >/dev/null
  channels="$(arborxr_cli apps release_channels "$APP_ID")"
  jq -e --arg channelId "$PRODUCTION_CHANNEL_ID" --arg buildId "$build_id" '
    .[] | select(.id == $channelId and .name == "Production" and .version.id == $buildId)
  ' <<<"$channels" >/dev/null

  devices="$(arborxr_cli devices list)"
  online_count=0
  offline_count=0
  while IFS= read -r device_row; do
    device_id="$(jq -r '.id' <<<"$device_row")"
    device_name="$(jq -r '.name' <<<"$device_row")"
    if [[ "$(jq -r '.isOnline' <<<"$device_row")" == true ]]; then
      wait_for_version "$device_id" "$device_name"
      ((online_count += 1))
    else
      ((offline_count += 1))
    fi
  done < <(jq -c --argjson ids "$(printf '%s\n' "${PRODUCTION_GROUP_IDS[@]}" | jq -R . | jq -s .)" '
    .[] | select(.group != null and (.group.id as $id | $ids | index($id)))
  ' <<<"$devices")

  state_set productionPromotedAt "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  state_set productionOnlineVerified "$online_count"
  state_set productionOfflineQueued "$offline_count"
  printf 'Production now points to OpenPanel %s; %s online device(s) verified and %s offline device(s) queued.\n' \
    "$EXPECTED_VERSION" "$online_count" "$offline_count"
}

show_status() {
  local channels devices groups
  verify_app_and_channels
  audit_assignments
  channels="$(arborxr_cli apps release_channels "$APP_ID")"
  devices="$(arborxr_cli devices list)"
  groups="$(arborxr_cli groups list)"
  jq -n \
    --arg packageName "$EXPECTED_PACKAGE" \
    --arg localVersion "$EXPECTED_VERSION" \
    --argjson localVersionCode "$EXPECTED_VERSION_CODE" \
    --argjson channels "$(jq '[.[] | select(.id == $debug or .id == $production or .id == $latest) | {id,name,version:(.version|{id,version,code,status})}]' --arg debug "$DEBUG_CHANNEL_ID" --arg production "$PRODUCTION_CHANNEL_ID" --arg latest "$LATEST_CHANNEL_ID" <<<"$channels")" \
    --argjson testDevice "$(jq --arg id "$TEST_DEVICE_ID" '.[] | select(.id == $id) | {id,name,serialNumber,isOnline,group,runningApp}' <<<"$devices")" \
    --argjson productionGroups "$(jq --argjson ids "$(printf '%s\n' "${PRODUCTION_GROUP_IDS[@]}" | jq -R . | jq -s .)" '[.[] | select(.id as $id | $ids | index($id)) | {id,name,isConfigured}]' <<<"$groups")" \
    '{
      packageName: $packageName,
      localVersion: $localVersion,
      localVersionCode: $localVersionCode,
      channels: $channels,
      testDevice: $testDevice,
      productionGroups: $productionGroups
    }'
}

main() {
  require_command node
  require_command jq
  require_command curl
  require_command security
  require_command abxr-cli
  require_command shasum
  case "${1-}" in
    verify)
      verify_apk
      ;;
    status)
      load_token
      verify_token
      show_status
      ;;
    prepare)
      verify_apk
      load_token
      verify_token
      prepare_candidate
      ;;
    test-candidate)
      verify_apk
      load_token
      verify_token
      test_candidate
      ;;
    deploy-production)
      verify_apk
      load_token
      verify_token
      promote_production
      ;;
    deploy)
      verify_apk
      load_token
      verify_token
      test_candidate
      promote_production
      ;;
    -h|--help|help|'')
      usage
      ;;
    *)
      usage >&2
      exit 2
      ;;
  esac
}

main "$@"
