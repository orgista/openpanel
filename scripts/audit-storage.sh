#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NAS_PREFIX="${OPENPANEL_NAS_PREFIX:-/Volumes/Files/}"
TMP_ROOT="${TMPDIR:-/tmp}"
declare -a EXTERNAL_PATHS=()

record_if_external() {
  local path="$1"
  local resolved
  resolved="$(realpath "$path" 2>/dev/null || printf '%s' "$path")"
  [[ "$resolved" == "$ROOT" || "$resolved" == "$ROOT/"* ]] && return
  EXTERNAL_PATHS+=("$path")
}

require_link_into_root() {
  local path="$1"
  local resolved
  [[ -L "$path" ]] || { echo "Expected compatibility symlink: $path" >&2; exit 1; }
  resolved="$(realpath "$path" 2>/dev/null || true)"
  [[ "$resolved" == "$ROOT/"* ]] || {
    echo "Compatibility symlink does not resolve into the canonical root: $path" >&2
    exit 1
  }
}

require_owner_only() {
  local path="$1"
  local mode
  [[ -f "$path" ]] || { echo "Missing required private file: $path" >&2; exit 1; }
  mode="$(stat -f '%Lp' "$path")"
  (( (8#$mode & 8#77) == 0 )) || {
    echo "Private file grants group or other access: $path" >&2
    exit 1
  }
}

scan_named_children() {
  local parent="$1"
  [[ -d "$parent" ]] || return
  while IFS= read -r path; do
    record_if_external "$path"
  done < <(find "$parent" -mindepth 1 -maxdepth 1 -iname '*openpanel*' -print)
}

if [[ "$ROOT/" != "$NAS_PREFIX"* ]]; then
  echo "OpenPanel is not under the expected NAS prefix: $NAS_PREFIX" >&2
  echo "Resolved project root: $ROOT" >&2
  exit 1
fi

[[ -d "$ROOT/.git" ]] || { echo "Missing engine repository: $ROOT/.git" >&2; exit 1; }
[[ -d "$ROOT/src/.git" ]] || { echo "Missing private UI repository: $ROOT/src/.git" >&2; exit 1; }

scan_named_children /private/tmp
if [[ "$(realpath "$TMP_ROOT" 2>/dev/null || printf '%s' "$TMP_ROOT")" != "/private/tmp" ]]; then
  scan_named_children "$TMP_ROOT"
fi
scan_named_children "$HOME"

for parent in "$HOME/Desktop" "$HOME/Documents" "$HOME/Downloads"; do
  [[ -d "$parent" ]] || continue
  while IFS= read -r path; do
    record_if_external "$path"
  done < <(find "$parent" -maxdepth 8 -iname '*openpanel*' -print 2>/dev/null)
done

# Known NAS services that have historically stored OpenPanel-specific records.
# These checks are intentionally shallow and fast; they do not traverse the
# entire NAS on every audit.
for parent in \
  "/Volumes/Files/Projects/Code, Apps & Websites/Raster/Rast3r.com/public/review/council" \
  "/Volumes/Files/Projects/Infrastructure & Server/ServerPlus/Files/agent-state/claude/projects" \
  "/Volumes/Files/Projects/Infrastructure & Server/ServerPlus/Files/agent-state/claude-archive-20260718/projects" \
  "/Volumes/Files/Projects/Infrastructure & Server/Unraid/Results/Android Apps/release-guard-recheck-20260702-1350" \
  "/Volumes/Files/Projects/Infrastructure & Server/Unraid/ai-ops/jobs.d"; do
  scan_named_children "$parent"
done

require_owner_only "$ROOT/private/signing/openpanel-upload.keystore"
require_owner_only "$ROOT/private/signing/openpanel-production.keystore"
require_link_into_root "$HOME/openpanel-upload.keystore"
for path in \
  "/Volumes/Files/Projects/Infrastructure & Server/ServerPlus/Files/agent-state/claude/projects/-Volumes-Files-Projects-Code--Apps---Websites-Apps-OpenPanel" \
  "/Volumes/Files/Projects/Infrastructure & Server/ServerPlus/Files/agent-state/claude-archive-20260718/projects/-Users-macstudio-Documents-Files-Projects-Apps-OpenPanel" \
  "/Volumes/Files/Projects/Infrastructure & Server/ServerPlus/Files/agent-state/claude-archive-20260718/projects/-Volumes-Files-Projects-Code--Apps---Websites-Apps-OpenPanel"; do
  require_link_into_root "$path"
done

echo "Canonical NAS root: $ROOT"
echo "Engine repository: $ROOT/.git"
echo "Private UI repository: $ROOT/src/.git"

if (( ${#EXTERNAL_PATHS[@]} > 0 )); then
  echo "OpenPanel-named data still exists outside the canonical NAS root:" >&2
  printf '  %s\n' "${EXTERNAL_PATHS[@]}" >&2
  exit 1
fi

echo "Storage audit passed: no known OpenPanel-named data was found outside the canonical NAS root."
