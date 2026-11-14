#!/usr/bin/env bash
# Source from bash. Keep all caches and emulator state inside this checkout.
MOATRIP_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export MOATRIP_ROOT
export ANDROID_HOME="$MOATRIP_ROOT/work/linux/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$MOATRIP_ROOT/work/linux/android-user"
export ANDROID_EMULATOR_HOME="$MOATRIP_ROOT/work/linux/emulator-home"
export ANDROID_AVD_HOME="$ANDROID_EMULATOR_HOME/avd"
export GRADLE_USER_HOME="$MOATRIP_ROOT/work/linux/gradle"
export PIP_CACHE_DIR="$MOATRIP_ROOT/work/linux/pip-cache"
export TMPDIR="$MOATRIP_ROOT/work/linux/tmp"
export TEMP="$TMPDIR" TMP="$TMPDIR"
export MOATRIP_ADB="$ANDROID_HOME/platform-tools/adb"
export ADB_VENDOR_KEYS="$ANDROID_EMULATOR_HOME/adbkey"
export MOATRIP_DATABASE="$MOATRIP_ROOT/work/linux/server/moatrip.db"
export PYTHONDONTWRITEBYTECODE=1 PYTHONIOENCODING=utf-8
for trip_jdk in "$MOATRIP_ROOT"/work/linux/jdk/jdk-*; do
    if [[ -x "$trip_jdk/bin/java" ]]; then export JAVA_HOME="$trip_jdk"; break; fi
done
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
mkdir -p "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME" "$GRADLE_USER_HOME" "$PIP_CACHE_DIR" "$TMPDIR" "$MOATRIP_ROOT/work/linux/server"
