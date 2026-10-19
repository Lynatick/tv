#!/bin/zsh
set -euo pipefail
cd "${0:A:h}"
export JAVA_HOME="${HORIZON_JAVA_HOME:-/Users/dmitry_baranov/Library/Java/JavaVirtualMachines/ms-25.0.4.1/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/Users/dmitry_baranov/Library/Android/sdk}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-${PWD:h}/flutter-migration-gradle-cache}"
export PATH="$JAVA_HOME/bin:$PATH"
gradle_bin=/Users/dmitry_baranov/.gradle/wrapper/dists/gradle-9.1.0-bin/9agqghryom9wkf8r80qlhnts3/gradle-9.1.0/bin/gradle
if [[ -x "$gradle_bin" ]]; then exec "$gradle_bin" "$@"; else exec ./gradlew "$@"; fi
