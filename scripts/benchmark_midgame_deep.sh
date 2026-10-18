#!/usr/bin/env bash
# Optional mid-save comparison; does not run as part of the default test/CI tasks.
#   scripts/benchmark_midgame_deep.sh          # September/October: all female links + Sun MUST
#   scripts/benchmark_midgame_deep.sh --legacy # reproduce the original 16-plan comparison
#   scripts/benchmark_midgame_deep.sh --check  # inspect cached dependencies only
set -euo pipefail
benchmark_root="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$benchmark_root/.toolchain/env.sh"
cd "$benchmark_root"

# Use the dependency versions declared by this checkout, including JUnit's actual Hamcrest
# dependency, rather than a machine-specific classpath or whichever cached version sorts last.
benchmark_cp=$(python3 - "$benchmark_root/android/app/build.gradle" "$GRADLE_USER_HOME" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

build = Path(sys.argv[1]).read_text()
cache = Path(sys.argv[2]) / "caches/modules-2/files-2.1"

def declared(group, artifact):
    values = re.findall(r"testImplementation\s+['\"]" + re.escape(group + ":" + artifact)
                        + r":([^'\"]+)['\"]", build)
    if len(values) != 1:
        raise SystemExit("Expected one testImplementation declaration for " + group + ":" + artifact)
    return values[0]

def cached(group, artifact, version, extension):
    matches = list((cache / group / artifact / version).glob("*/" + artifact + "-" + version + "." + extension))
    if len(matches) != 1:
        raise SystemExit("Missing or ambiguous cached " + group + ":" + artifact + ":" + version
                         + " (" + extension + "); run the Android unit tests once to populate this checkout's Gradle cache")
    return matches[0]

json_version = declared("org.json", "json")
junit_version = declared("junit", "junit")
junit_pom = ET.parse(cached("junit", "junit", junit_version, "pom")).getroot()
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
properties = junit_pom.find("m:properties", ns)
properties = {} if properties is None else {p.tag.rsplit("}", 1)[-1]: p.text for p in properties}
hamcrest_version = None
for dependency in junit_pom.findall("m:dependencies/m:dependency", ns):
    if (dependency.findtext("m:groupId", namespaces=ns) == "org.hamcrest"
            and dependency.findtext("m:artifactId", namespaces=ns) == "hamcrest-core"):
        hamcrest_version = dependency.findtext("m:version", namespaces=ns)
        if hamcrest_version and hamcrest_version.startswith("${") and hamcrest_version.endswith("}"):
            hamcrest_version = properties.get(hamcrest_version[2:-1])
        break
if not hamcrest_version:
    raise SystemExit("Cannot resolve JUnit's hamcrest-core version from its cached POM")
print(":".join(str(p) for p in (
    cached("org.json", "json", json_version, "jar"),
    cached("junit", "junit", junit_version, "jar"),
    cached("org.hamcrest", "hamcrest-core", hamcrest_version, "jar"))))
PY
)

if [[ "${1:-}" == "--check" && $# -eq 1 ]]; then
    printf 'Cached benchmark dependencies: %s\n' "$benchmark_cp"
    exit 0
fi
if [[ $# -gt 1 ]]; then
    printf 'Usage: %s [--autumn|--legacy|--check]\n' "$0" >&2
    exit 2
fi
case "${1:-}" in
    ''|--autumn)
        benchmark_main=app.p3r.dayplanner.v3.engine.AutumnGoalBenchmark
        benchmark_dir="$benchmark_root/qa_out/must_goals/autumn-deep"
        ;;
    --legacy)
        benchmark_main=app.p3r.dayplanner.v3.engine.MidgameDeepBenchmark
        benchmark_dir="$benchmark_root/qa_out/must_goals/midgame-deep"
        ;;
    *)
        printf 'Usage: %s [--autumn|--legacy|--check]\n' "$0" >&2
        exit 2
        ;;
esac
mkdir -p "$benchmark_dir/classes"
if command -v rg >/dev/null 2>&1; then
    rg --files -g '*.java' -g '!RulesAssets.java' \
        android/app/src/main/java/app/p3r/dayplanner/v3/engine \
        android/app/src/main/java/app/p3r/dayplanner/v3/model > "$benchmark_dir/sources.txt"
else
    find android/app/src/main/java/app/p3r/dayplanner/v3/engine \
        android/app/src/main/java/app/p3r/dayplanner/v3/model \
        -name '*.java' ! -name RulesAssets.java -print > "$benchmark_dir/sources.txt"
fi
cat >> "$benchmark_dir/sources.txt" <<'SOURCES'
android/app/src/test/java/app/p3r/dayplanner/v3/engine/RulesFixtures.java
android/app/src/test/java/app/p3r/dayplanner/v3/engine/PlanMetrics.java
android/app/src/test/java/app/p3r/dayplanner/v3/engine/PlanReport.java
android/app/src/test/java/app/p3r/dayplanner/v3/engine/RescueMargin.java
android/app/src/test/java/app/p3r/dayplanner/v3/engine/PlanBreadthRealPackTest.java
scripts/benchmarks/MidgameDeepBenchmark.java
scripts/benchmarks/AutumnGoalBenchmark.java
SOURCES
javac -cp "$benchmark_cp" -d "$benchmark_dir/classes" @"$benchmark_dir/sources.txt"
java -Xmx768m -Dp3r.repo="$benchmark_root" -cp "$benchmark_dir/classes:$benchmark_cp" \
    "$benchmark_main"
