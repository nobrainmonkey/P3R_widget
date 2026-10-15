#!/usr/bin/env bash
# Pre-fetch the Robolectric Android SDK jars the unit tests run on (API 26, 31, 33, 34, 35) into
# the test user.home's Maven repository, with retries and SHA-512 checks. Robolectric would otherwise
# download them in the middle of the test run, without retries.
#   scripts/fetch_robolectric_jars.sh [TOOLCHAIN_DIR]   (default: ./.toolchain, or $P3R_TOOLCHAIN)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TC="${1:-${P3R_TOOLCHAIN:-$ROOT/.toolchain}}"
REPO="$TC/test-home/.m2/repository/org/robolectric/android-all-instrumented"
BASE="https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented"
# Robolectric 4.14.1 (instrumentation i7): API 26, 31, 33, 34, 35 (no test runs at API 28 any more).
VERSIONS="8.0.0_r4-robolectric-r1-i7 12-robolectric-7732740-i7
13-robolectric-9030017-i7 14-robolectric-10818077-i7 15-robolectric-12650502-i7"

sha512() { if command -v sha512sum >/dev/null; then sha512sum "$1" | cut -d' ' -f1; else shasum -a 512 "$1" | cut -d' ' -f1; fi; }

for v in $VERSIONS; do
  dir="$REPO/$v"; name="android-all-instrumented-$v"
  mkdir -p "$dir"
  if [[ -s "$dir/$name.jar" && -s "$dir/$name.jar.sha512" ]] \
     && [[ "$(sha512 "$dir/$name.jar")" == "$(tr -d ' \n' < "$dir/$name.jar.sha512" | cut -c1-128)" ]]; then
    echo "ok   $v"; continue
  fi
  echo "get  $v"
  for f in "$name.pom" "$name.pom.sha512" "$name.jar.sha512" "$name.jar"; do
    curl -fsSL --retry 5 --retry-all-errors --retry-delay 5 -o "$dir/$f.part" "$BASE/$v/$f"
    mv "$dir/$f.part" "$dir/$f"
  done
  if [[ "$(sha512 "$dir/$name.jar")" != "$(tr -d ' \n' < "$dir/$name.jar.sha512" | cut -c1-128)" ]]; then
    rm -f "$dir/$name.jar"; echo "SHA-512 mismatch for $v" >&2; exit 1
  fi
done
echo "Robolectric jars ready in $REPO"
