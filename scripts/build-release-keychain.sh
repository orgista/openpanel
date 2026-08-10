#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEYSTORE="$ROOT/private/signing/openpanel-production.keystore"
KEY_ALIAS="openpanel-production-v2"
KEYCHAIN_ACCOUNT="openpanel-production-v2"
KEYCHAIN_SERVICE="OpenPanel production signing key v2"
APK="$ROOT/android/app/build/outputs/apk/release/app-release.apk"
EXPECTED_VERSION="1.1.28"
EXPECTED_VERSION_CODE="37"

[[ -f "$KEYSTORE" ]] || {
  echo "Missing production keystore: $KEYSTORE" >&2
  exit 1
}

OPENPANEL_SIGNING_PASSWORD="$(
  security find-generic-password \
    -a "$KEYCHAIN_ACCOUNT" \
    -s "$KEYCHAIN_SERVICE" \
    -w
)" || {
  echo "The OpenPanel production password was not available from macOS Keychain." >&2
  exit 1
}

cleanup() {
  unset OPENPANEL_SIGNING_PASSWORD
  unset OPENPANEL_KEYSTORE_FILE OPENPANEL_KEYSTORE_PASS
  unset OPENPANEL_KEY_ALIAS OPENPANEL_KEY_PASS
}
trap cleanup EXIT

export OPENPANEL_KEYSTORE_FILE="$KEYSTORE"
export OPENPANEL_KEYSTORE_PASS="$OPENPANEL_SIGNING_PASSWORD"
export OPENPANEL_KEY_ALIAS="$KEY_ALIAS"
export OPENPANEL_KEY_PASS="$OPENPANEL_SIGNING_PASSWORD"

if (( $# > 0 )); then
  "$ROOT/scripts/gradle-local.sh" "$@"
else
  "$ROOT/scripts/gradle-local.sh" \
    :app:test \
    :app:lintDebug \
    :app:assembleDebugAndroidTest \
    :app:assembleRelease
fi

[[ -f "$APK" ]] || {
  echo "Release build completed without producing the expected APK." >&2
  exit 1
}

ANDROID_BUILD_TOOLS="${ANDROID_HOME:-$HOME/Library/Android/sdk}/build-tools"
APKSIGNER="$(find "$ANDROID_BUILD_TOOLS" -name apksigner -type f | sort -V | tail -1)"
AAPT="$(find "$ANDROID_BUILD_TOOLS" -name aapt -type f | sort -V | tail -1)"

[[ -x "$APKSIGNER" && -x "$AAPT" ]] || {
  echo "Android APK verification tools were not found." >&2
  exit 1
}

SIGNATURE="$($APKSIGNER verify --verbose --print-certs "$APK")"
BADGING="$($AAPT dump badging "$APK")"

grep -Fq "Number of signers: 1" <<< "$SIGNATURE"
grep -Fq "package: name='com.orgista.openpanel' versionCode='$EXPECTED_VERSION_CODE' versionName='$EXPECTED_VERSION'" <<< "$BADGING"
grep -Fq "sdkVersion:'24'" <<< "$BADGING"
grep -Fq "targetSdkVersion:'36'" <<< "$BADGING"

printf '%s\n' "$SIGNATURE"
printf '%s\n' "$BADGING" | grep -E "^(package:|sdkVersion:|targetSdkVersion:|leanback-launchable-activity:)"
shasum -a 256 "$APK" > "$APK.sha256"
echo "Verified production APK: $APK"
echo "SHA-256 file: $APK.sha256"
