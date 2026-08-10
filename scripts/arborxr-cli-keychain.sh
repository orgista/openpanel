#!/usr/bin/env bash
set -euo pipefail

ARBORXR_KEYCHAIN_ACCOUNT="openpanel-deploy"
ARBORXR_KEYCHAIN_SERVICE="OpenPanel ArborXR API"

ABXR_API_TOKEN="$(
  security find-generic-password \
    -a "$ARBORXR_KEYCHAIN_ACCOUNT" \
    -s "$ARBORXR_KEYCHAIN_SERVICE" \
    -w
)" || {
  printf '%s\n' "ArborXR API token was not found in macOS Keychain." >&2
  printf '%s\n' "Run scripts/configure-arborxr-token.command first." >&2
  exit 1
}

cleanup() {
  unset ABXR_API_TOKEN
}
trap cleanup EXIT

export ABXR_API_TOKEN
exec abxr-cli "$@"
