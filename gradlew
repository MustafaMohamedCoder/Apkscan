#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
exec /home/ubuntu/.local/gradle/gradle-8.5/bin/gradle -p "$SCRIPT_DIR" "$@"
