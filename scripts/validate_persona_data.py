#!/usr/bin/env python3
"""Validate the committed offline compendium without clones or network access."""

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

from import_persona_data import ACQUISITION_PATHS, DATABASE_COMMIT, DEFAULT_OUTPUT, TOOL_COMMIT, require, validate


def validate_asset(path):
    content = path.read_bytes()
    data = json.loads(content)
    validate(data)
    require(data["schemaVersion"] == 1 and data["game"] == "p3r", "Unsupported compendium format")
    require([s["commit"] for s in data["sources"]] == [TOOL_COMMIT, DATABASE_COMMIT], "Unexpected source version")
    require(data["sources"][0]["license"] == "Unlicense", "Missing fusion-tool license")
    require(data["sources"][1]["license"] != "Unlicense", "Do not assign the tool's license to the database")
    acquisition = data["acquisitionSource"]
    require(acquisition["commit"] == DATABASE_COMMIT and acquisition["root"] == "src/p3r/Content/Xrd777/",
            "Acquisition must use pinned base-game data")
    require(acquisition["paths"] == ACQUISITION_PATHS and acquisition["floorRange"] == [2, 264],
            "Acquisition source table/range mismatch")
    personas = {p["id"]: p for p in data["personas"]}
    skills = {s["id"]: s for s in data["skills"]}
    arcana = {a["id"] for a in data["arcana"]}
    packs = {p["id"] for p in data["dlcPacks"]}
    require(len(arcana) == 22 and len(data["arcana"]) == 22, "Expected 22 unique Arcana")
    require(packs == {"p5r_1", "p5r_2", "p4g"} and len(data["dlcPacks"]) == 3, "Expected three Persona DLC packs")
    require(Counter(p["dlc"] for p in personas.values()) == {"": 173, "p5r_1": 9, "p5r_2": 9, "p4g": 3}, "Wrong DLC distribution")
    for entry in list(personas.values()) + list(skills.values()) + data["arcana"] + data["dlcPacks"]:
        require(all(isinstance(entry.get(f), str) and entry[f].strip() for f in ("nameZh", "nameEn")), f"Missing bilingual name: {entry['id']}")
    require(len({p["key"] for p in personas.values()}) == len(personas), "Duplicate upstream Persona keys")
    normal_skills, theurgy_skills = set(), set()
    for p in personas.values():
        require(p["id"] == f"persona_{p['gameId']}", f"Persona ID mismatch: {p['id']}")
        require(p["arcana"] in arcana and (not p["dlc"] or p["dlc"] in packs), f"Unknown Arcana/DLC: {p['id']}")
        require(1 <= p["level"] <= 99 and all(1 <= stat <= 99 for stat in p["stats"]), f"Invalid Persona level/stats: {p['id']}")
        learned_ids = [s["id"] for s in p["skills"]]
        require(len(learned_ids) == len(set(learned_ids)), f"Duplicate learned skill: {p['id']}")
        for skill in p["skills"]:
            require(skill["level"] == 0 or p["level"] < skill["level"] <= 99, f"Invalid learn level/sentinel: {p['id']}")
            require(skills[skill["id"]]["costType"] != "theurgy", "Theurgy mixed into normal learned skills")
            normal_skills.add(skill["id"])
        for sid in p["theurgySkillIds"]:
            require(skills[sid]["costType"] == "theurgy", "Non-Theurgy in Theurgy list")
            theurgy_skills.add(sid)
        if "unlock" in p:
            require(all(p["unlock"].get(f) for f in ("type", "textZh", "textEn")), f"Incomplete unlock: {p['id']}")
    require(len(normal_skills) == 333 and len(theurgy_skills) == 7 and not normal_skills & theurgy_skills, "Wrong skill categories")
    require(normal_skills | theurgy_skills == set(skills), "Unreferenced global skill")
    for s in skills.values():
        require(s["id"] == f"skill_{s['gameId']}" and s["cost"] >= 0, f"Invalid skill ID/cost: {s['id']}")
        require(s["effectZh"] and s["effectEn"] or s["element"] == "pas", f"Missing skill effect: {s['id']}")
        require(s["costType"] in ("none", "SP", "HP", "theurgy"), f"Invalid cost type: {s['id']}")
        require(s["costType"] != "HP" or s["cost"] < 100, "HP cost must be a percentage")
        require("$1" not in s["effectZh"] and "$2" not in s["effectZh"], "Unexpanded effect template")
    require(len(data["elements"]) == 10 and len(set(data["elements"])) == 10, "Expected ten resistance elements")
    require(data["fusion"]["arcana"] == [a["id"] for a in data["arcana"]], "Fusion/compendium Arcana order mismatch")
    for row in data["fusion"]["chart"]:
        require(set(row) <= arcana | {"-"}, "Unknown fusion result Arcana")
    recipes = data["fusion"]["specialRecipes"]
    require(len({r["resultId"] for r in recipes}) == 22, "Duplicate special fusion result")
    for recipe in recipes:
        require(2 <= len(recipe["ingredientIds"]) <= 6 and len(set(recipe["ingredientIds"])) == len(recipe["ingredientIds"]), "Invalid special ingredients")
    require(personas["persona_310"]["nameZh"] == "齐天大圣" and personas["persona_310"]["dlc"] == "p5r_2", "P5R alias mapping lost")
    def floors_of(persona_id):
        return {floor for origin in personas[persona_id]["origins"]
                for floor in range(origin["floorFrom"], origin["floorTo"] + 1)}
    require(floors_of("persona_156") == set(range(2, 22)) - {5, 11, 17},
            "Pixie acquisition shifted or includes boss floors")
    require(floors_of("persona_108") == set(range(23, 30)) - {28},
            "Jack Frost Shuffle Time rank/floor mapping changed")
    require({227, 257, 260, 261} <= floors_of("persona_208") and 256 not in floors_of("persona_208"),
            "Final-day Adamah acquisition or teleporter exclusion lost")
    require(not personas["persona_64"]["origins"] and not personas["persona_117"]["origins"],
            "Do not invent tower locations from a Persona's level or enemy name")
    require(skills["skill_157"]["nameEn"] == "Diarahan" and "概率" not in skills["skill_157"]["effectZh"], "Healing amount incorrectly treated as probability")
    print(f"Validated offline compendium: 173 base + 21 DLC Personas, 333 skills + 7 Theurgy, 22 special recipes, 111 tower acquisitions; sha256={hashlib.sha256(content).hexdigest()}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", nargs="?", type=Path, default=DEFAULT_OUTPUT)
    validate_asset(parser.parse_args().path)
