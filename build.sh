#!/usr/bin/env bash
# Self-restoring ZI Gate release build.
# The Android SDK / JDK live under /home/user/tools, but the workspace snapshot
# drops large binaries between sessions, so we re-download anything missing and
# then build. Idempotent: safe to run repeatedly.
set -euo pipefail

ROOT=/home/user
TOOLS="$ROOT/tools"
SDK="$TOOLS/sdk"
JDK="$TOOLS/jdk17"
GRADLE_HOME="$ROOT/gradle-home"
PROJ="$ROOT/ZIGate"

export ANDROID_HOME="$SDK"
export GRADLE_USER_HOME="$GRADLE_HOME"

# ---- 1. JDK 17 (Temurin) ----
if [ ! -x "$JDK/bin/java" ]; then
  echo ">>> Restoring JDK 17 ..."
  rm -rf "$JDK"
  cd "$TOOLS"
  curl -sL -o jdk17.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  tar xzf jdk17.tar.gz
  mv jdk-17* jdk17
  rm -f jdk17.tar.gz
fi
chmod -R +x "$JDK/bin"
export JAVA_HOME="$JDK"

# ---- 2. Android SDK: cmdline-tools + platform 34 + build-tools 34 ----
mkdir -p "$SDK/cmdline-tools"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo ">>> Restoring cmdline-tools ..."
  cd "$TOOLS"
  rm -rf "$SDK/cmdline-tools"
  curl -sL -o cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  mkdir -p "$SDK/cmdline-tools"
  unzip -q cmdtools.zip -d "$SDK/cmdline-tools"
  mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -f cmdtools.zip
fi
chmod -R +x "$SDK/cmdline-tools/latest/bin"
SDKMAN="$SDK/cmdline-tools/latest/bin/sdkmanager"

if [ ! -f "$SDK/build-tools/34.0.0/aapt2" ] || [ ! -f "$SDK/platforms/android-34/android.jar" ]; then
  echo ">>> Installing SDK platform 34 + build-tools 34.0.0 ..."
  "$SDKMAN" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
  "$SDKMAN" --sdk_root="$SDK" "platforms;android-34" "build-tools;34.0.0" "platform-tools" >/dev/null 2>&1 || true
fi
# Normalize SDK dirs: on interrupted installs sdkmanager creates "<pkg>-2"
# folders. Move the complete ones to the expected paths and drop stale partials.
normalize() {
  local base="$1" marker="$2"
  if [ -e "$SDK/$base" ] && [ -e "$SDK/$base-2" ]; then
    # keep whichever has the marker file
    if [ -e "$SDK/$base/$marker" ]; then rm -rf "$SDK/$base-2";
    elif [ -e "$SDK/$base-2/$marker" ]; then rm -rf "$SDK/$base"; mv "$SDK/$base-2" "$SDK/$base";
    fi
  elif [ ! -e "$SDK/$base" ] && [ -e "$SDK/$base-2" ]; then
    mv "$SDK/$base-2" "$SDK/$base"
  fi
}
normalize "build-tools/34.0.0" "aapt2"
normalize "platforms/android-34" "android.jar"
chmod -R +x "$SDK/build-tools/34.0.0" 2>/dev/null || true
chmod -R +x "$SDK/platform-tools" 2>/dev/null || true

# ---- 3. Build (with retry: the dex step often crashes the daemon on small RAM) ----
cd "$PROJ"
chmod +x ./gradlew
echo ">>> Building $(date +%T)"
JVMARGS="-Xmx600m -XX:MaxMetaspaceSize=384m"
build_attempt() {
  ./gradlew :app:assembleRelease --no-daemon -Dorg.gradle.jvmargs="$JVMARGS" 2>&1 | tee /tmp/zigate_build.log
}
build_attempt || { echo ">>> Retrying once (daemon likely OOM'd during dex)..."; sleep 3; build_attempt; }
grep -q "BUILD SUCCESSFUL" /tmp/zigate_build.log && echo ">>> BUILD OK" || { echo ">>> BUILD FAILED"; exit 1; }

BT="$SDK/build-tools/34.0.0"
APK="$PROJ/app/build/outputs/apk/release/app-release.apk"
VNAME=$(grep 'versionName' "$PROJ/app/build.gradle" | head -1 | cut -d\" -f2)
OUT="$PROJ/output/ZI-Gate-v${VNAME}-release.apk"
mkdir -p "$PROJ/output"
# keep only the newest release in output/
find "$PROJ/output" -name 'ZI-Gate-*-release.apk' ! -newer "$APK" -delete 2>/dev/null || true
cp "$APK" "$OUT"
echo ">>> Out: $OUT"
echo ">>> Package:"
"$BT"/aapt2 dump badging "$APK" 2>/dev/null | grep -E "^package:|^application-label:"
echo ">>> Signer (first):"
"$BT"/apksigner verify --print-certs "$APK" 2>/dev/null | head -2
echo ">>> SHA-256:"
sha256sum "$OUT"
