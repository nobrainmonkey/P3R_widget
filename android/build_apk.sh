#!/usr/bin/env bash
# Build the debug APK on macOS/Linux with Android SDK installed (requires network on first run).
set -euo pipefail
cd "$(dirname "$0")"
if [[ -z "${ANDROID_HOME:-}" ]]; then
  if [[ -d "$HOME/Library/Android/sdk" ]]; then export ANDROID_HOME="$HOME/Library/Android/sdk"
  elif [[ -d "$HOME/Android/Sdk" ]]; then export ANDROID_HOME="$HOME/Android/Sdk"
  else
    echo 'Android SDK not found. Install Android Studio, then set ANDROID_HOME.' >&2
    exit 1
  fi
fi
if [[ ! -e "$ANDROID_HOME/platforms/android-35/android.jar" ]]; then
  echo 'Android SDK Platform 35 missing: install it from Android Studio > SDK Manager.' >&2
  exit 1
fi
if command -v gradle >/dev/null 2>&1; then
  GRADLE=(gradle)
else
  GRADLE_VERSION=8.9
  DIR="$HOME/.gradle/local-distributions"
  BIN="$DIR/gradle-$GRADLE_VERSION/bin/gradle"
  if [[ ! -x "$BIN" ]]; then
    mkdir -p "$DIR"
    ZIP="$DIR/gradle-$GRADLE_VERSION-bin.zip"
    echo "Downloading Gradle $GRADLE_VERSION from the official distribution site..."
    curl --fail --location --retry 3 -o "$ZIP" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    unzip -q -o "$ZIP" -d "$DIR"
    rm -f "$ZIP"
  fi
  GRADLE=("$BIN")
fi
# Generate the standard wrapper locally, so Android Studio can subsequently sync via Gradle Wrapper.
if [[ ! -f gradle/wrapper/gradle-wrapper.jar ]]; then
  "${GRADLE[@]}" wrapper --gradle-version 8.9 --distribution-type bin
fi
"${GRADLE[@]}" --no-daemon :app:assembleDebug
printf '\nAPK: %s\n' "$(pwd)/app/build/outputs/apk/debug/app-debug.apk"
