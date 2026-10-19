#!/bin/bash
# SessionStart hook for Claude Code cloud sessions: provisions the project-local Android
# toolchain in ./.toolchain (Android SDK, Robolectric jars, Gradle caches) so the Python
# data checks, unit tests, lint and debug builds work. Mirrors scripts/setup_toolchain.sh,
# but reuses the container's JDK (whose truststore and JAVA_TOOL_OPTIONS already carry the
# egress proxy settings) instead of downloading Temurin.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

ROOT="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
TC="$ROOT/.toolchain"
SDK="$TC/android-sdk"
mkdir -p "$TC/test-home/.m2" "$TC/android-user-home" "$TC/gradle-home"

if ! command -v java >/dev/null 2>&1; then
  echo "session-start: no JDK on PATH; run scripts/setup_toolchain.sh instead" >&2
  exit 1
fi
JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
export JAVA_HOME

# Android command-line tools, SDK Platform 35, Build-Tools 35.0.0 (same pins as CI).
if [[ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]]; then
  echo "Downloading Android command-line tools..."
  tmp="$(mktemp -d)"
  curl -fsSL --retry 5 --retry-all-errors -o "$tmp/cmdtools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  mkdir -p "$SDK/cmdline-tools"
  unzip -q -o "$tmp/cmdtools.zip" -d "$tmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi
if [[ ! -d "$SDK/platforms/android-35" || ! -d "$SDK/build-tools/35.0.0" ]]; then
  sdkm() { ANDROID_PREFS_ROOT="$TC/android-user-home" "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "$@"; }
  yes | sdkm --licenses >/dev/null 2>&1 || true
  sdkm "platforms;android-35" "build-tools;35.0.0" >/dev/null
fi

# Robolectric's Android SDK jars for the unit tests (checksum-verified, idempotent).
"$ROOT/scripts/fetch_robolectric_jars.sh" "$TC"

# Maven Central rate-limits (HTTP 429) shared cloud egress IPs, so put Google's Maven Central
# mirror ahead of it for this session's Gradle builds. Lives in GRADLE_USER_HOME, not the repo.
mkdir -p "$TC/gradle-home/init.d"
cat > "$TC/gradle-home/init.d/maven-central-mirror.gradle" <<'GRADLE'
def mirror = 'https://maven-central.storage-download.googleapis.com/maven2/'
beforeSettings { settings ->
    settings.pluginManagement.repositories { maven { url mirror } }
    settings.dependencyResolutionManagement.repositories { maven { url mirror } }
}
GRADLE

# Session environment (JAVA_TOOL_OPTIONS is deliberately left alone: it holds the proxy config).
if [[ -n "${CLAUDE_ENV_FILE:-}" ]]; then
  cat >> "$CLAUDE_ENV_FILE" <<ENV
export JAVA_HOME="$JAVA_HOME"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_USER_HOME="$TC/android-user-home"
export GRADLE_USER_HOME="$TC/gradle-home"
export P3R_TOOLCHAIN="$TC"
export P3R_ROOT="$ROOT"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/build-tools/35.0.0:\$PATH"
ENV
fi

# Warm the Gradle wrapper and dependency caches (cached with the container snapshot).
cd "$ROOT/android"
export ANDROID_HOME="$SDK" ANDROID_USER_HOME="$TC/android-user-home" GRADLE_USER_HOME="$TC/gradle-home"
for attempt in 1 2 3; do
  if ./gradlew --no-daemon -q :app:compileDebugUnitTestJavaWithJavac; then break; fi
  [[ $attempt == 3 ]] && exit 1
  echo "Gradle warm-up failed (attempt $attempt); retrying..." >&2; sleep $((attempt * 10))
done
echo "P3R toolchain ready in $TC"
