#!/usr/bin/env python3
"""Compare the Java fusion calculator with the pinned upstream reference.

Reads only upstream JSON. The reference below independently follows
aqiu384/megaten-fusion-tool's smt-nonelem-fusions.ts and per-nonelem-fusions.ts;
it does not execute upstream code. Compiles our two plain Java classes into a
temporary directory and checks every material pair and both argument orders
under all eight DLC ownership combinations. No Gradle or network is needed.

Example (after sourcing .toolchain/env.sh):
  python3 scripts/verify_fusion_reference.py \
    --tool-repo /workspace/reference-repos/megaten-fusion-tool
"""

import argparse
from collections import Counter
import itertools
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


PROJECT = Path(__file__).resolve().parents[1]
TOOL_COMMIT = "0b207612f6cdcff092e62af16f5c355271a1d892"
PACK_MASKS = {
    "Persona 5 Set 1": 1,
    "Persona 5 Set 2": 2,
    "Persona 4 Set": 4,
    "Persona 4 Set 1": 4,
}
JAVA_DUMP = r"""
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import app.p3r.dayplanner.v3.compendium.*;
public class FusionReferenceDump {
    public static void main(String[] args) throws Exception {
        Compendium data = Compendium.parse(new String(
            Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8));
        String[] packs = {"p5r_1", "p5r_2", "p4g"};
        for (int mask = 0; mask < 8; mask++) {
            Set<String> owned = new HashSet<>();
            for (int k = 0; k < 3; k++) if ((mask & (1 << k)) != 0) owned.add(packs[k]);
            FusionCalculator calculator = new FusionCalculator(data, owned);
            List<Compendium.Persona> personas = calculator.availablePersonas();
            for (int i = 0; i < personas.size(); i++) {
                for (int j = i + 1; j < personas.size(); j++) {
                    Compendium.Persona a = personas.get(i), b = personas.get(j);
                    FusionCalculator.Recipe forward = calculator.fuse(a.id, b.id);
                    FusionCalculator.Recipe backward = calculator.fuse(b.id, a.id);
                    System.out.println(mask + "\t" + a.key + "\t" + b.key + "\t"
                        + (forward == null ? "-" : forward.result.key) + "\t"
                        + (backward == null ? "-" : backward.result.key));
                }
            }
        }
    }
}
"""


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def reference_pairs(demons, specials, chart, dlc, mask):
    available = {name: row for name, row in demons.items()
                 if name not in dlc or mask & dlc[name]}
    inverse = {(row["race"], row["lvl"]): name for name, row in available.items()}
    result_levels = {race: sorted(row["lvl"] for name, row in available.items()
                                 if row["race"] == race and name not in specials)
                     for race in chart["races"]}
    ingredient_levels = {race: sorted(row["lvl"] for row in available.values()
                                     if row["race"] == race)
                         for race in chart["races"]}
    table = {}
    for i, row in enumerate(chart["table"]):
        for j, result in enumerate(row):
            table[chart["races"][i], chart["races"][j]] = result
            table[chart["races"][j], chart["races"][i]] = result

    pairs = {}
    for first, row in available.items():
        race_a, level_a = row["race"], row["lvl"]
        local = {}
        # The upstream helper enumerates possible second materials by result
        # level bins, rather than calculating a result from the material pair.
        for race_b in chart["races"]:
            race_result = table[race_a, race_b]
            if race_result == "-":
                continue
            levels = result_levels[race_result]
            bins = [2 * (level - 0.5) - level_a for level in levels]
            for level_b in ingredient_levels[race_b]:
                if not bins or (race_a == race_b and level_a == level_b):
                    continue
                index = min(sum(level_b > bound for bound in bins), len(bins) - 1)
                result = inverse[race_result, levels[index]]
                if result == first and index + 1 < len(levels):
                    result = inverse[race_result, levels[index + 1]]
                local[inverse[race_b, level_b]] = result

        # Persona's same-arcana helper excludes the first material's level
        # before reducing the result bins, then steps down past material two.
        levels = [2 * level for level in result_levels[race_a] if level != level_a]
        for level_b in ingredient_levels[race_a]:
            if level_b == level_a:
                continue
            index = sum(level_a + level_b + 2 >= level for level in levels) - 1
            if index >= 0 and levels[index] // 2 == level_b:
                index -= 1
            if index >= 0:
                local[inverse[race_a, level_b]] = inverse[race_a, levels[index] // 2]

        # Fixed two-input fusions override their ordinary pair in upstream.
        for result, ingredients in specials.items():
            if len(ingredients) == 2 and first in ingredients:
                second = ingredients[0] if ingredients[1] == first else ingredients[1]
                if second in local:
                    local[second] = result if result in available else "-"
        for second, result in local.items():
            pairs[first, second] = result
    expected_count = len(available) * (len(available) - 1) // 2
    return pairs, expected_count


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tool-repo", type=Path, required=True)
    parser.add_argument("--asset", type=Path,
                        default=PROJECT / "android/app/src/main/assets/p3r_compendium.json")
    parser.add_argument("--json-jar", type=Path,
                        help="org.json jar; otherwise use the project's cached Gradle dependency")
    args = parser.parse_args()
    revision = subprocess.check_output(
        ["git", "-C", str(args.tool_repo), "rev-parse", "HEAD"], text=True).strip()
    if revision != TOOL_COMMIT:
        raise SystemExit(f"Expected upstream {TOOL_COMMIT}; found {revision}")
    for command in ("java", "javac"):
        if shutil.which(command) is None:
            raise SystemExit(f"Missing {command}; source .toolchain/env.sh first")
    json_jar = args.json_jar
    if json_jar is None:
        jars = sorted((PROJECT / ".toolchain/gradle-home/caches/modules-2/files-2.1/org.json/json")
                      .glob("*/*/json-*.jar"))
        if not jars:
            raise SystemExit("No cached org.json jar; pass --json-jar")
        json_jar = jars[-1]

    source = args.tool_repo / "src/app/p3r/data"
    demons = read_json(source / "demon-data.json")
    specials = read_json(source / "special-recipes.json")
    chart = read_json(source / "fusion-chart.json")
    unlocks = read_json(source / "demon-unlocks.json")
    dlc = {name: PACK_MASKS[pack] for group in unlocks
           if group["category"] == "Downloadable Content"
           for name, pack in group["conditions"].items()}
    references = {mask: reference_pairs(demons, specials, chart, dlc, mask)
                  for mask in range(8)}
    java_source = PROJECT / "android/app/src/main/java/app/p3r/dayplanner/v3/compendium"
    with tempfile.TemporaryDirectory(prefix="p3r-fusion-reference-") as directory:
        temporary = Path(directory)
        dump = temporary / "FusionReferenceDump.java"
        dump.write_text(JAVA_DUMP, encoding="utf-8")
        subprocess.run(["javac", "-cp", str(json_jar), "-d", directory, str(dump),
                        str(java_source / "Compendium.java"),
                        str(java_source / "FusionCalculator.java")], check=True)
        actual = subprocess.check_output(["java", "-cp", os.pathsep.join((directory, str(json_jar))),
                                          "FusionReferenceDump", str(args.asset)], text=True)
    counts = Counter()
    seen = set()
    mismatches = []
    for line in actual.splitlines():
        mask_text, first, second, forward, backward = line.split("\t")
        mask = int(mask_text)
        if mask not in references or any(name not in demons or (name in dlc and not mask & dlc[name])
                                         for name in (first, second)) or first == second:
            raise SystemExit(f"Unexpected Java material pair: {line}")
        pair = (mask, frozenset((first, second)))
        if pair in seen:
            raise SystemExit(f"Duplicate Java material pair: {line}")
        seen.add(pair)
        counts[mask] += 1
        reference = references[mask][0]
        expected = reference.get((first, second), "-")
        expected_backward = reference.get((second, first), "-")
        if forward != expected or backward != expected_backward or expected != expected_backward:
            mismatches.append((mask, first, second, forward, backward, expected, expected_backward))
    for mask, (_, expected_count) in references.items():
        if counts[mask] != expected_count:
            raise SystemExit(f"Missing material pairs for DLC mask {mask}: "
                             f"{counts[mask]} != {expected_count}")
    if mismatches:
        for mismatch in itertools.islice(mismatches, 20):
            print("Mismatch:", mismatch)
        raise SystemExit(f"{len(mismatches)} fusion reference mismatches")
    print(f"Verified {sum(counts.values()):,} material pairs across 8 DLC combinations, "
          "both material orders; zero differences from upstream.")
    print("Pairs per DLC mask:", ", ".join(f"{mask}={counts[mask]}" for mask in range(8)))


if __name__ == "__main__":
    main()
