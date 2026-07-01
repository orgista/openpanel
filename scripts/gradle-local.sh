#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-$ROOT/.toolchains/jdk-17.0.18+8/Contents/Home}"
GRADLE_HOME="${GRADLE_HOME:-$ROOT/.toolchains/gradle-8.13}"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.gradle-home}"

export JAVA_HOME
export GRADLE_USER_HOME

exec "$GRADLE_HOME/bin/gradle" "$@"
