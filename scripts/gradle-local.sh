#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_LOCAL_CACHE_ROOT="${TMPDIR:-/tmp}/openpanel-gradle-cache"

clean_legacy_local_data() {
  # These are exact, disposable cache locations used by older versions of this
  # helper. Never broaden this list or accept a caller-provided deletion path.
  rm -rf -- "$DEFAULT_LOCAL_CACHE_ROOT"
  rm -rf -- /private/tmp/openpanel-gradle-home
  rm -rf -- /private/tmp/openpanel-project-cache
  rm -rf -- /private/tmp/openpanel-project-cache-clean
  rm -rf -- "${TMPDIR:-/tmp}/openpanel-gradle-home"
  rm -rf -- "${TMPDIR:-/tmp}/openpanel-gradle-project-cache"
}

if [[ "${1:-}" == "--clean-local-data" ]]; then
  clean_legacy_local_data
  echo "Removed disposable OpenPanel Gradle data outside $ROOT."
  exit 0
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -x /usr/libexec/java_home ]]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  else
    echo "OpenPanel requires JDK 21. Set JAVA_HOME to a JDK 21 installation." >&2
    exit 1
  fi
fi

JAVA_VERSION_OUTPUT="$("$JAVA_HOME/bin/java" -version 2>&1)"
if [[ "$JAVA_VERSION_OUTPUT" != *'version "21.'* ]]; then
  echo "OpenPanel requires JDK 21; JAVA_HOME points to a different version." >&2
  exit 1
fi

MANAGED_CACHE=true
if [[ -n "${GRADLE_USER_HOME:-}" || -n "${OPENPANEL_GRADLE_PROJECT_CACHE:-}" || -n "${OPENPANEL_LOCAL_CACHE_ROOT:-}" ]]; then
  MANAGED_CACHE=false
fi

LOCAL_CACHE_ROOT="${OPENPANEL_LOCAL_CACHE_ROOT:-$DEFAULT_LOCAL_CACHE_ROOT}"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$LOCAL_CACHE_ROOT/user-home}"
PROJECT_CACHE="${OPENPANEL_GRADLE_PROJECT_CACHE:-$LOCAL_CACHE_ROOT/project-cache}"

mkdir -p "$GRADLE_USER_HOME" "$PROJECT_CACHE"

cleanup_managed_cache() {
  if [[ "$MANAGED_CACHE" == true && "${OPENPANEL_KEEP_LOCAL_CACHE:-0}" != "1" ]]; then
    rm -rf -- "$DEFAULT_LOCAL_CACHE_ROOT"
  fi
}

trap cleanup_managed_cache EXIT INT TERM

export JAVA_HOME
export GRADLE_USER_HOME

if [[ -n "${GRADLE_HOME:-}" ]]; then
  GRADLE_BIN="$GRADLE_HOME/bin/gradle"
elif [[ -x "$ROOT/.toolchains/gradle-8.14.5/bin/gradle" ]]; then
  GRADLE_BIN="$ROOT/.toolchains/gradle-8.14.5/bin/gradle"
elif [[ -x "$ROOT/.toolchains/gradle-8.13/bin/gradle" ]]; then
  GRADLE_BIN="$ROOT/.toolchains/gradle-8.13/bin/gradle"
else
  GRADLE_BIN="$ROOT/android/gradlew"
fi

"$GRADLE_BIN" -p "$ROOT/android" --project-cache-dir "$PROJECT_CACHE" "$@"
