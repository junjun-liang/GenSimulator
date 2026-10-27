#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB_PATH:-adb}"
SERIAL="${ANDROID_SERIAL:-}"
BOOTSTRAP_ACTIVITY="${GENCARDS_BRIDGE_BOOTSTRAP_ACTIVITY_COMPONENT:-com.flyme.auto.gensimulator.bridge/.BridgeBootstrapActivity}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [[ -z "$SERIAL" ]]; then
  mapfile -t DEVICES < <("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
  if [[ ${#DEVICES[@]} -ne 1 ]]; then
    echo "Expected exactly one connected device or set ANDROID_SERIAL." >&2
    exit 2
  fi
  SERIAL="${DEVICES[0]}"
fi

cd "$ROOT"
./gradlew :android-bridge:assembleDebug
APK="android-bridge/build/outputs/apk/debug/android-bridge-debug.apk"
[[ -f "$APK" ]] || { echo "Bridge APK not found: $APK" >&2; exit 3; }

USER_ID="$("$ADB" -s "$SERIAL" shell am get-current-user | tr -d '\r')"
[[ "$USER_ID" =~ ^[0-9]+$ ]] || { echo "Unable to determine active Android user" >&2; exit 4; }
"$ADB" -s "$SERIAL" install -r --user "$USER_ID" "$APK"
START_OUTPUT="$("$ADB" -s "$SERIAL" shell am start --user "$USER_ID" -n "$BOOTSTRAP_ACTIVITY" 2>&1)"
[[ "$START_OUTPUT" != *"Error:"* ]] || { echo "Bridge bootstrap activity failed: $START_OUTPUT" >&2; exit 5; }
echo "BRIDGE_INSTALLED serial=$SERIAL apk=$APK"
