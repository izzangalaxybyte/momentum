#!/usr/bin/env bash
# Build a signed release APK and publish it to Appster.
#   scripts/release.sh "What changed"
# versionCode = number of commits on main, so every release from a newer commit is higher.
# Signing key: ~/.momentum/keystore.properties (never in the repo). Keep it forever.
set -euo pipefail
cd "$(dirname "$0")/.."
CHANGELOG="${1:?usage: scripts/release.sh \"What changed\"}"

[ -f "$HOME/.momentum/keystore.properties" ] || { echo "missing ~/.momentum/keystore.properties"; exit 1; }
[ -f .env ] || ./scripts/write-env.sh

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
VERSION_CODE=$(git rev-list --count HEAD)
./android/gradlew -p android testDebugUnitTest assembleRelease -PversionCode="$VERSION_CODE" --console=plain -q

APK=android/app/build/outputs/apk/release/app-release.apk
uv run --project ~/Documents/GitHub/appster ~/Documents/GitHub/appster/scripts/publish.py \
  "$APK" --changelog "$CHANGELOG" --icon android/icon.png --name "Momentum"
echo "published versionCode $VERSION_CODE"
