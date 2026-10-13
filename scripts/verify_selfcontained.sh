#!/usr/bin/env bash
# Proves the build needs nothing outside this directory:
#  - empty environment (env -i) with only the base system PATH,
#  - HOME pointed at a fresh empty folder inside .toolchain,
#  - Gradle in --offline mode (all dependencies must already be in .toolchain/gradle-home),
#  - then reports any file that tools wrote to that HOME, and any change to the real ~/.gradle, ~/.m2, ~/.android.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FAKE_HOME="$ROOT/.toolchain/verify-home"
rm -rf "$FAKE_HOME" && mkdir -p "$FAKE_HOME" "$ROOT/.toolchain/tmp"
snap() { for d in "$HOME/.gradle" "$HOME/.m2" "$HOME/.android"; do [ -e "$d" ] && find "$d" -type f -exec stat -f '%m %N' {} + 2>/dev/null; done | sort; }
before="$(snap)"
env -i HOME="$FAKE_HOME" TMPDIR="$ROOT/.toolchain/tmp" PATH="/usr/bin:/bin:/usr/sbin:/sbin" LANG=en_US.UTF-8 \
  bash -c "source '$ROOT/.toolchain/env.sh' && export P3R_QA_DIR='$ROOT/.toolchain/tmp/qa' && \
           cd '$ROOT/android' && ./gradlew --offline --no-daemon -q :app:testDebugUnitTest :app:assembleRelease"
after="$(snap)"
echo "== files written to the empty HOME during the build:"
find "$FAKE_HOME" -type f | sed "s|$FAKE_HOME|~|" || true
echo "== changes under the real ~/.gradle ~/.m2 ~/.android:"
diff <(echo "$before") <(echo "$after") && echo "(none)"
echo "== toolchain locations:"
env -i HOME="$FAKE_HOME" PATH="/usr/bin:/bin" bash -c "source '$ROOT/.toolchain/env.sh'; for v in JAVA_HOME ANDROID_HOME GRADLE_USER_HOME ANDROID_USER_HOME ANDROID_AVD_HOME; do echo \"\$v=\${!v}\"; done; which java sdkmanager"
echo "OK: offline build + tests succeeded with an empty environment and HOME"
