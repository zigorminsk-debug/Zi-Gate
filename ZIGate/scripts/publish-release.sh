#!/usr/bin/env bash
# Build signed APK and publish a GitHub Release (tag v$versionName).
# Usage: from repo root or ZIGate/:  ./scripts/publish-release.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
cd "$HERE"

VNAME=$(grep 'versionName' app/build.gradle | head -1 | cut -d'"' -f2)
VCODE=$(grep 'versionCode' app/build.gradle | head -1 | awk '{print $2}')
TAG="v${VNAME}"

echo ">>> Building ZI Gate ${VNAME} (${VCODE})"
chmod +x ./gradlew
./gradlew :app:assembleRelease --no-daemon

APK="output/ZI-Gate-v${VNAME}-release.apk"
if [ ! -f "$APK" ]; then
  mkdir -p output
  cp app/build/outputs/apk/release/app-release.apk "$APK"
fi
ls -l "$APK"

if command -v gh >/dev/null 2>&1; then
  if gh release view "$TAG" >/dev/null 2>&1; then
    echo ">>> Updating existing release $TAG"
    gh release upload "$TAG" "$APK" --clobber
  else
    echo ">>> Creating GitHub release $TAG"
    git tag -f "$TAG"
    git push -f origin "$TAG" || true
    gh release create "$TAG" "$APK" --title "ZI Gate v${VNAME}" --generate-notes
  fi
else
  echo ">>> gh not installed; APK is at $APK"
fi
