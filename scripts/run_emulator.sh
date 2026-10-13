#!/usr/bin/env bash
# Boots the project-local test emulator headless. Everything it writes (AVD, console token, prefs)
# stays under .toolchain: HOME is redirected because the emulator writes its auth token to $HOME.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/.toolchain/env.sh"
mkdir -p "$ROOT/.toolchain/home"
if ! avdmanager list avd 2>/dev/null | grep -q "Name: p3r35"; then
  ANDROID_PREFS_ROOT="$ANDROID_USER_HOME" sdkmanager --sdk_root="$ANDROID_HOME" "emulator" "system-images;android-35;google_apis;arm64-v8a" >/dev/null
  echo no | avdmanager create avd -n p3r35 -k "system-images;android-35;google_apis;arm64-v8a" -d pixel_7 --force >/dev/null
fi
HOME="$ROOT/.toolchain/home" nohup emulator -avd p3r35 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-save >"$ROOT/.toolchain/emulator.log" 2>&1 &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
echo "emulator ready: $(adb devices | sed -n 2p)"
