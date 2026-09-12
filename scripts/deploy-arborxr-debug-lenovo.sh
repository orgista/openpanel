#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' \
  'This workflow is disabled: it creates the obsolete com.orgista.openpanel.debug app entry.' \
  'Use the existing com.orgista.openpanel app and its Debug release channel instead.' >&2
exit 64

OPENPANEL_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OPENPANEL_APK="$OPENPANEL_ROOT/private/releases/OpenPanel-1.1.22-vc31-debug.apk"
OPENPANEL_STATE_DIR="$OPENPANEL_ROOT/private/deployment"
OPENPANEL_STATE_FILE="$OPENPANEL_STATE_DIR/arborxr-debug-lenovo.json"

ARBORXR_API_BASE="https://api.xrdm.app/api/v3"
ARBORXR_KEYCHAIN_ACCOUNT="openpanel-deploy"
ARBORXR_KEYCHAIN_SERVICE="OpenPanel ArborXR API"

TARGET_DEVICE_ID="40ae8e88-8929-493b-afb4-fd5afa4116b1"
TARGET_DEVICE_NAME="Lenovo"
TARGET_DEVICE_SERIAL="HA1TMR6V"
TARGET_DEVICE_MODEL_ID="237010fd-995e-444d-82fb-3e3f0db1c6c1"

EXPECTED_PACKAGE="com.orgista.openpanel.debug"
EXPECTED_VERSION="1.1.22-debug"
EXPECTED_VERSION_CODE="31"
EXPECTED_APK_SHA256="4deb1160f142cabfd138875bb36f8b64ec2f4b60cccdd1d016c3844648526b01"
EXPECTED_CERT_SHA256="11b408c4319ed4355ef3be99e770566b631bb4ff67eac67e421646a7cf2836f8"
APP_NAME="OpenPanel Debug"
CHANNEL_NAME="Lenovo Debug"

usage() {
  cat <<'EOF'
Usage: scripts/deploy-arborxr-debug-lenovo.sh COMMAND

Commands:
  verify  Verify the local debug APK without contacting ArborXR.
  status  Show saved state and current Lenovo assignment without changing it.
  deploy  Upload the verified debug APK, assign only the ungrouped Lenovo,
          preserve its production app assignment, and launch OpenPanel Debug.
EOF
}

require_command() {
  command -v "$1" >/dev/null || {
    printf 'Required command not found: %s\n' "$1" >&2
    exit 1
  }
}

verify_apk() {
  [[ -f "$OPENPANEL_APK" ]] || {
    printf 'Debug APK not found: %s\n' "$OPENPANEL_APK" >&2
    exit 1
  }

  local actual_sha android_sdk build_tools apksigner aapt badging signature
  actual_sha="$(shasum -a 256 "$OPENPANEL_APK" | awk '{print $1}')"
  [[ "$actual_sha" == "$EXPECTED_APK_SHA256" ]] || {
    printf 'APK checksum mismatch. Expected %s, received %s.\n' \
      "$EXPECTED_APK_SHA256" "$actual_sha" >&2
    exit 1
  }

  android_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  build_tools="$android_sdk/build-tools"
  apksigner="$(find "$build_tools" -name apksigner -type f | sort -V | tail -1)"
  aapt="$(find "$build_tools" -name aapt -type f | sort -V | tail -1)"
  [[ -x "$apksigner" && -x "$aapt" ]] || {
    printf '%s\n' 'Android APK verification tools were not found.' >&2
    exit 1
  }

  badging="$("$aapt" dump badging "$OPENPANEL_APK")"
  signature="$("$apksigner" verify --verbose --print-certs "$OPENPANEL_APK")"
  grep -Fq \
    "package: name='$EXPECTED_PACKAGE' versionCode='$EXPECTED_VERSION_CODE' versionName='$EXPECTED_VERSION'" \
    <<<"$badging"
  grep -Fq 'Number of signers: 1' <<<"$signature"
  tr '[:upper:]' '[:lower:]' <<<"$signature" | \
    grep -Fq "signer #1 certificate sha-256 digest: $EXPECTED_CERT_SHA256"

  printf 'Verified %s v%s (%s), SHA-256 %s.\n' \
    "$EXPECTED_PACKAGE" "$EXPECTED_VERSION" "$EXPECTED_VERSION_CODE" "$actual_sha"
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

init_state() {
  mkdir -p "$OPENPANEL_STATE_DIR"
  chmod 700 "$OPENPANEL_STATE_DIR"
  if [[ ! -f "$OPENPANEL_STATE_FILE" ]]; then
    jq -n \
      --arg packageName "$EXPECTED_PACKAGE" \
      --arg version "$EXPECTED_VERSION" \
      --argjson versionCode "$EXPECTED_VERSION_CODE" \
      --arg apkSha256 "$EXPECTED_APK_SHA256" \
      --arg deviceId "$TARGET_DEVICE_ID" \
      --arg deviceSerial "$TARGET_DEVICE_SERIAL" \
      '{
        packageName: $packageName,
        version: $version,
        versionCode: $versionCode,
        apkSha256: $apkSha256,
        deviceId: $deviceId,
        deviceSerial: $deviceSerial
      }' >"$OPENPANEL_STATE_FILE"
    chmod 600 "$OPENPANEL_STATE_FILE"
  fi
  local temp_file
  temp_file="$(mktemp "$OPENPANEL_STATE_DIR/debug-state.XXXXXX")"
  jq \
    --arg packageName "$EXPECTED_PACKAGE" \
    --arg version "$EXPECTED_VERSION" \
    --argjson versionCode "$EXPECTED_VERSION_CODE" \
    --arg apkSha256 "$EXPECTED_APK_SHA256" \
    --arg deviceId "$TARGET_DEVICE_ID" \
    --arg deviceSerial "$TARGET_DEVICE_SERIAL" \
    '.packageName = $packageName |
      .version = $version |
      .versionCode = $versionCode |
      .apkSha256 = $apkSha256 |
      .deviceId = $deviceId |
      .deviceSerial = $deviceSerial |
      .deploymentVerified = false' \
    "$OPENPANEL_STATE_FILE" >"$temp_file"
  chmod 600 "$temp_file"
  mv "$temp_file" "$OPENPANEL_STATE_FILE"
}

state_get() {
  jq -r "$1 // empty" "$OPENPANEL_STATE_FILE"
}

state_set_string() {
  local field="$1"
  local value="$2"
  local temp_file
  temp_file="$(mktemp "$OPENPANEL_STATE_DIR/debug-state.XXXXXX")"
  jq --arg value "$value" ".$field = \$value" "$OPENPANEL_STATE_FILE" >"$temp_file"
  chmod 600 "$temp_file"
  mv "$temp_file" "$OPENPANEL_STATE_FILE"
}

state_set_json() {
  local field="$1"
  local value="$2"
  local temp_file
  temp_file="$(mktemp "$OPENPANEL_STATE_DIR/debug-state.XXXXXX")"
  jq --argjson value "$value" ".$field = \$value" "$OPENPANEL_STATE_FILE" >"$temp_file"
  chmod 600 "$temp_file"
  mv "$temp_file" "$OPENPANEL_STATE_FILE"
}

validate_token() {
  api_request GET '/current-user' | \
    jq -e '(.id | type == "string") and (.email | type == "string")' >/dev/null
}

verify_target_device() {
  local device_json
  device_json="$(api_request GET "/devices/$TARGET_DEVICE_ID")"
  jq -e --arg id "$TARGET_DEVICE_ID" --arg serial "$TARGET_DEVICE_SERIAL" \
    '.id == $id and (.serialNumber // .serial) == $serial and
      .group == null and .isOnline == true' \
    <<<"$device_json" >/dev/null || {
    printf '%s must be online, ungrouped, and match serial %s.\n' \
      "$TARGET_DEVICE_NAME" "$TARGET_DEVICE_SERIAL" >&2
    exit 1
  }
}

ensure_debug_app() {
  local app_id app_json create_body versions build_id upload_result channel_id channels
  app_id="$(state_get '.appId')"
  if [[ -n "$app_id" ]]; then
    app_json="$(api_request GET "/apps/$app_id")"
    jq -e --arg id "$app_id" --arg name "$APP_NAME" \
      '.id == $id and .name == $name' <<<"$app_json" >/dev/null
  else
    create_body="$(jq -n \
      --arg name "$APP_NAME" \
      --arg modelId "$TARGET_DEVICE_MODEL_ID" \
      '{name: $name, deviceModelIds: [$modelId]}')"
    app_json="$(api_request POST '/apps' "$create_body")"
    app_id="$(jq -r '.id' <<<"$app_json")"
    [[ -n "$app_id" && "$app_id" != 'null' ]] || {
      printf '%s\n' 'ArborXR did not return a debug app ID.' >&2
      exit 1
    }
    state_set_string appId "$app_id"
  fi

  versions="$(arborxr_cli apps versions "$app_id")"
  build_id="$(jq -r \
    --arg version "$EXPECTED_VERSION" \
    --argjson code "$EXPECTED_VERSION_CODE" \
    '.[] | select(.version == $version and .code == $code and .status == "available") | .id' \
    <<<"$versions" | head -1)"

  if [[ -z "$build_id" ]]; then
    channel_id="$(state_get '.releaseChannelId')"
    if [[ -n "$channel_id" ]]; then
      upload_result="$(arborxr_cli apps upload \
        "$app_id" "$OPENPANEL_APK" \
        --version_number "$EXPECTED_VERSION" \
        --notes 'Lenovo-only debug build for YouTube channel browsing and automatic channel-title repair validation.' \
        --wait --wait_time 600 \
        --release-channel-id "$channel_id")"
    else
      upload_result="$(arborxr_cli apps upload \
        "$app_id" "$OPENPANEL_APK" \
        --version_number "$EXPECTED_VERSION" \
        --notes 'Lenovo-only debug build for YouTube channel browsing and automatic channel-title repair validation.' \
        --wait --wait_time 600 \
        --new-release-channel-title "$CHANNEL_NAME")"
    fi
    jq -e '.status == "available" or .id != null' <<<"$upload_result" >/dev/null
    versions="$(arborxr_cli apps versions "$app_id")"
    build_id="$(jq -r \
      --arg version "$EXPECTED_VERSION" \
      --argjson code "$EXPECTED_VERSION_CODE" \
      '.[] | select(.version == $version and .code == $code and .status == "available") | .id' \
      <<<"$versions" | head -1)"
  fi

  [[ -n "$build_id" ]] || {
    printf '%s\n' 'The debug build did not become available in ArborXR.' >&2
    exit 1
  }
  state_set_string buildId "$build_id"

  channels="$(arborxr_cli apps release_channels "$app_id")"
  channel_id="$(jq -r \
    --arg name "$CHANNEL_NAME" \
    --arg buildId "$build_id" \
    '.[] | select((.name // .title) == $name and ((.version.id // .targetBuild.id) == $buildId)) | .id' \
    <<<"$channels" | head -1)"
  [[ -n "$channel_id" ]] || {
    printf '%s\n' 'The Lenovo Debug release channel was not created.' >&2
    exit 1
  }
  state_set_string releaseChannelId "$channel_id"

  app_json="$(api_request GET "/apps/$app_id")"
  jq -e --arg packageName "$EXPECTED_PACKAGE" \
    '.packageName == $packageName' <<<"$app_json" >/dev/null
}

wait_for_install() {
  local app_id="$1"
  local attempt status_json status installed_version error_description
  for attempt in $(seq 1 120); do
    status_json="$(api_request GET \
      "/statuses?per_page=100&deviceIds[]=$TARGET_DEVICE_ID&contentIds[]=$app_id&contentTypes[]=app")"
    status="$(jq -r '.data | sort_by(.statusTimestamp // "") | last | .status // empty' <<<"$status_json")"
    installed_version="$(jq -r '.data | sort_by(.statusTimestamp // "") | last | .content.installedVersion // empty' <<<"$status_json")"
    error_description="$(jq -r '.data | sort_by(.statusTimestamp // "") | last | .errorDescription // empty' <<<"$status_json")"
    case "$status" in
      succeeded-install|succeeded-update|succeeded-higher-version-installed)
        [[ "$installed_version" == "$EXPECTED_VERSION" ]] || {
          printf 'ArborXR installed %s instead of %s.\n' \
            "${installed_version:-unknown}" "$EXPECTED_VERSION" >&2
          return 1
        }
        return 0
        ;;
      failed-install|failed-update)
        printf 'ArborXR debug deployment failed: %s%s\n' \
          "$status" "${error_description:+ — $error_description}" >&2
        return 1
        ;;
    esac
    if (( attempt % 6 == 0 )); then
      printf 'Waiting for ArborXR debug install (current: %s).\n' "${status:-not reported}"
    fi
    sleep 5
  done
  printf '%s\n' 'Timed out waiting for the Lenovo debug install.' >&2
  return 1
}

deploy_debug() {
  local app_id channel_id assigned_channels current_assigned
  local experience visible_contents launcher_body final_experience
  app_id="$(state_get '.appId')"
  channel_id="$(state_get '.releaseChannelId')"
  [[ -n "$app_id" && -n "$channel_id" ]] || {
    printf '%s\n' 'Debug app state is incomplete.' >&2
    exit 1
  }

  verify_target_device
  experience="$(api_request GET "/devices/$TARGET_DEVICE_ID/device-experience")"
  jq -e '.mode == "custom-launcher" and .launcherContent.type == "app"' \
    <<<"$experience" >/dev/null || {
    printf '%s\n' 'The Lenovo is no longer using the guarded custom launcher.' >&2
    exit 1
  }
  if [[ -z "$(state_get '.deviceExperienceBackup.mode')" ]]; then
    state_set_json deviceExperienceBackup "$experience"
  fi

  assigned_channels="$(api_request GET "/devices/$TARGET_DEVICE_ID/release-channels?per_page=100")"
  current_assigned="$(jq -r --arg id "$channel_id" \
    '[.data[] | select(.id == $id)] | length' <<<"$assigned_channels")"
  if [[ "$current_assigned" == '0' ]]; then
    api_request POST "/devices/$TARGET_DEVICE_ID/release-channels" \
      "$(jq -n --arg id "$channel_id" '{releaseChannelId: $id}')" >/dev/null
  fi

  wait_for_install "$app_id"
  visible_contents="$(jq -c --arg appId "$app_id" '
    ([.visibleContents[] | select(.id != $appId)] + [{id: $appId, type: "app"}])
  ' <<<"$experience")"
  launcher_body="$(jq -n \
    --arg appId "$app_id" \
    --argjson visibleContents "$visible_contents" \
    '{launcherContent: {id: $appId, type: "app"}, visibleContents: $visibleContents}')"

  api_request POST "/devices/$TARGET_DEVICE_ID/device-experience/in-house-launcher" \
    "$launcher_body" >/dev/null
  api_request POST "/devices/$TARGET_DEVICE_ID/launch/apps/$app_id" \
    '{"shouldQuitCurrentApp":true}' >/dev/null

  final_experience="$(api_request GET "/devices/$TARGET_DEVICE_ID/device-experience")"
  jq -e --arg appId "$app_id" --argjson before "$experience" '
    .mode == "custom-launcher" and
    .launcherContent == {id: $appId, type: "app"} and
    ([.visibleContents[] | select(.id != $appId)] ==
      [$before.visibleContents[] | select(.id != $appId)]) and
    ([.visibleContents[] | select(.id == $appId)] | length) == 1
  ' <<<"$final_experience" >/dev/null || {
    printf '%s\n' 'ArborXR changed more than the guarded debug launcher entry.' >&2
    exit 1
  }

  state_set_json deploymentVerified true
  state_set_string deployedAt "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  printf 'ArborXR debug deployment verified on %s only.\n' "$TARGET_DEVICE_NAME"
}

show_status() {
  init_state
  jq '{packageName, version, versionCode, deviceId, deviceSerial, appId, buildId, releaseChannelId, deploymentVerified, deployedAt}' \
    "$OPENPANEL_STATE_FILE"
  load_token
  validate_token
  api_request GET "/devices/$TARGET_DEVICE_ID" | \
    jq '{id, name, serialNumber, isOnline, group, runningApp}'
}

main() {
  require_command jq
  require_command shasum
  case "${1-}" in
    verify)
      verify_apk
      ;;
    status)
      require_command curl
      require_command security
      show_status
      ;;
    deploy)
      require_command curl
      require_command security
      require_command abxr-cli
      verify_apk
      init_state
      load_token
      validate_token
      ensure_debug_app
      deploy_debug
      ;;
    *)
      usage
      exit 1
      ;;
  esac
}

main "$@"
