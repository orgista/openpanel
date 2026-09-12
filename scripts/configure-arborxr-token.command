#!/usr/bin/env bash
set -euo pipefail

ARBORXR_KEYCHAIN_ACCOUNT="openpanel-deploy"
ARBORXR_KEYCHAIN_SERVICE="OpenPanel ArborXR API"

printf '%s\n' "Paste the ArborXR access token below. It will not be displayed."
IFS= read -r -s ARBORXR_TOKEN
printf '\n'

if [[ -z "$ARBORXR_TOKEN" ]]; then
  printf '%s\n' "No token was entered." >&2
  exit 1
fi

cleanup() {
  unset ARBORXR_TOKEN ABXR_API_TOKEN
}
trap cleanup EXIT

export ABXR_API_TOKEN="$ARBORXR_TOKEN"

if ! abxr-cli --silent --format json org info >/dev/null; then
  printf '%s\n' "ArborXR rejected this token. Nothing was saved." >&2
  exit 1
fi

security add-generic-password \
  -U \
  -a "$ARBORXR_KEYCHAIN_ACCOUNT" \
  -s "$ARBORXR_KEYCHAIN_SERVICE" \
  -w "$ARBORXR_TOKEN" \
  >/dev/null

printf '%s\n' "ArborXR API access is valid and saved in macOS Keychain."
printf '%s\n' "Press Return to close this window."
IFS= read -r _
