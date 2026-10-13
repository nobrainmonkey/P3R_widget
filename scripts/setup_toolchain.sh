#!/usr/bin/env bash
# Fetch a self-contained build toolchain into ./.toolchain (nothing is installed system-wide):
#   - Eclipse Temurin JDK 17
#   - Android command-line tools, SDK Platform 35, Build-Tools 35.0.0
#   - Gradle caches (GRADLE_USER_HOME) and Robolectric jars (test user.home) also live under .toolchain
# Running this script accepts the Android SDK license terms on your behalf (sdkmanager --licenses).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TC="$ROOT/.toolchain"
mkdir -p "$TC" "$TC/test-home/.m2"
cd "$TC"

os="$(uname -s)"; arch="$(uname -m)"
case "$os" in Darwin) jos=mac; sdkos=mac;; Linux) jos=linux; sdkos=linux;; *) echo "unsupported OS $os" >&2; exit 1;; esac
case "$arch" in arm64|aarch64) jarch=aarch64;; x86_64|amd64) jarch=x64;; *) echo "unsupported arch $arch" >&2; exit 1;; esac

if ! ls -d jdk-17* >/dev/null 2>&1; then
  echo "Downloading Temurin JDK 17 ($jos/$jarch)..."
  curl -fsSL -o jdk17.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/$jos/$jarch/jdk/hotspot/normal/eclipse"
  tar xzf jdk17.tar.gz && rm jdk17.tar.gz
fi
JDK_DIR="$(ls -d "$TC"/jdk-17* | head -1)"
if [[ -d "$JDK_DIR/Contents/Home" ]]; then JAVA_HOME="$JDK_DIR/Contents/Home"; else JAVA_HOME="$JDK_DIR"; fi

if [[ ! -x android-sdk/cmdline-tools/latest/bin/sdkmanager ]]; then
  echo "Downloading Android command-line tools..."
  curl -fsSL -o cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-${sdkos}-11076708_latest.zip"
  mkdir -p android-sdk/cmdline-tools && unzip -q -o cmdtools.zip -d android-sdk/cmdline-tools
  rm -rf android-sdk/cmdline-tools/latest && mv android-sdk/cmdline-tools/cmdline-tools android-sdk/cmdline-tools/latest && rm cmdtools.zip
fi

cat > "$TC/env.sh" <<ENV
# Source this file to use the project-local toolchain.
export JAVA_HOME="$JAVA_HOME"
export ANDROID_HOME="$TC/android-sdk"
export ANDROID_SDK_ROOT="\$ANDROID_HOME"
export ANDROID_USER_HOME="$TC/android-user-home"
export ANDROID_EMULATOR_HOME="$TC/android-user-home"
export ANDROID_AVD_HOME="$TC/avd"
export JAVA_TOOL_OPTIONS="-Duser.home=$TC/home"
export GRADLE_USER_HOME="$TC/gradle-home"
export PATH="\$JAVA_HOME/bin:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/build-tools/35.0.0:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/emulator:\$PATH"
export P3R_TOOLCHAIN="$TC"
export P3R_ROOT="$ROOT"
ENV
# shellcheck disable=SC1091
source "$TC/env.sh"
mkdir -p "$ANDROID_USER_HOME" "$TC/home"
# sdkmanager keeps its settings under ANDROID_PREFS_ROOT (only for these calls; AGP rejects it next to ANDROID_USER_HOME).
yes | ANDROID_PREFS_ROOT="$ANDROID_USER_HOME" sdkmanager --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
ANDROID_PREFS_ROOT="$ANDROID_USER_HOME" sdkmanager --sdk_root="$ANDROID_HOME" "platforms;android-35" "build-tools;35.0.0" >/dev/null
echo "Toolchain ready. Run: source .toolchain/env.sh"
