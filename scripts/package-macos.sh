#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "macOS packaging must be run on macOS." >&2
  exit 1
fi

if ! JDK_HOME="$(/usr/libexec/java_home -v '17+' 2>/dev/null)" || [[ -z "$JDK_HOME" ]]; then
  echo "JDK 17 or newer is required. Install one, then run this script again." >&2
  exit 1
fi

export JAVA_HOME="$JDK_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

APP_NAME="GenCardsVoiceSimulator"
APP_PATH="build/compose/binaries/main/app/${APP_NAME}.app"
DMG_PATH="build/compose/binaries/main/dmg/${APP_NAME}-1.0.0-arm64.dmg"

./gradlew clean createDistributable --no-daemon --console=plain

mkdir -p "$(dirname "$DMG_PATH")"
hdiutil create \
  -volname "$APP_NAME" \
  -srcfolder "$APP_PATH" \
  -format UDZO \
  -ov \
  "$DMG_PATH"

echo "Created: $DMG_PATH"
