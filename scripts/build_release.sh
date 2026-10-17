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
EXPECTED_CERT_SHA256="7123294095e29e1458eb01a3f3331a025e7dd1a5d7877b25c56fe0d8291beb2f"
VERIFICATION="$(apksigner verify --print-certs "$APK")"
CERT_SHA256="$(printf '%s\n' "$VERIFICATION" | sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p')"
if [[ "$CERT_SHA256" != "$EXPECTED_CERT_SHA256" ]]; then
  echo "error: release must retain the 3.2.0 signing certificate ($EXPECTED_CERT_SHA256)" >&2
  exit 1
fi
printf '%s\n' "$VERIFICATION" | sed -n '1,4p'
cp "$APK" "$OUT"
(cd "$ROOT/dist" && shasum -a 256 "$(basename "$OUT")") | tee "$OUT.sha256"
aapt2 dump badging "$OUT" | grep -E "^package:|application-label:|sdkVersion|targetSdkVersion" || true
echo "Release APK: $OUT"
