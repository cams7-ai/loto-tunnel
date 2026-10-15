#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Configure JAVA_HOME (JDK >=17) and ANDROID_HOME externally, or use local.properties.
if [ "$#" -eq 0 ]; then set -- assembleDebug; fi
exec ./gradlew "$@"
