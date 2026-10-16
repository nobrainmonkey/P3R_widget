#!/usr/bin/env bash
# Build, test, lint and package the signed release APK using only the project-local toolchain.
#   scripts/setup_toolchain.sh   # once
#   scripts/build_release.sh     # → dist/P3R-Planner-<version>.apk
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/.toolchain/env.sh"
export P3R_QA_DIR="$ROOT/qa_out"
cd "$ROOT/android"

if [[ ! -f "$ROOT/.signing/keystore.properties" ]]; then
  echo "error: .signing/keystore.properties is required for a release; restore the private signing backup first" >&2
  exit 1
fi

./gradlew --no-daemon :app:testDebugUnitTest :app:lintRelease :app:assembleRelease

APK="app/build/outputs/apk/release/app-release.apk"
VERSION="$(sed -n "s/.*versionName '\(.*\)'.*/\1/p" app/build.gradle | head -1)"
mkdir -p "$ROOT/dist"
OUT="$ROOT/dist/P3R-Planner-$VERSION.apk"
zipalign -c -P 16 -v 4 "$APK" >/dev/null
apksigner verify --print-certs "$APK" | sed -n '1,4p'
cp "$APK" "$OUT"
(cd "$ROOT/dist" && shasum -a 256 "$(basename "$OUT")") | tee "$OUT.sha256"
aapt2 dump badging "$OUT" | grep -E "^package:|application-label:|sdkVersion|targetSdkVersion" || true
echo "Release APK: $OUT"
