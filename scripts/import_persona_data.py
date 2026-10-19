#!/usr/bin/env python3
"""Build the offline P3R compendium from pinned, locally cloned upstream data.

This reads JSON/TSV only: it never executes upstream code or downloads files.
Example:
  python3 scripts/import_persona_data.py \
    --tool-repo /workspace/reference-repos/megaten-fusion-tool \
    --database-repo /workspace/reference-repos/megaten-database
Use --check to verify the checked-in asset without writing it.
"""

import argparse
import csv
import hashlib
import json
from pathlib import Path
import re
import subprocess


TOOL_COMMIT = "0b207612f6cdcff092e62af16f5c355271a1d892"
DATABASE_COMMIT = "8f87f212e206748b71298a52eb1eaec1efed6161"
PROJECT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = PROJECT / "android/app/src/main/assets/p3r_compendium.json"
PERSONA_ALIASES = {"Seiten Taisei A": "Seiten Taisei"}
PACK_IDS = {
    "Persona 5 Set 1": "p5r_1",
    "Persona 5 Set 2": "p5r_2",
    "Persona 4 Set": "p4g",
    "Persona 4 Set 1": "p4g",  # Upstream's Izanagi-only label is the same pack.
}
PACK_NAMES = {
    "p5r_1": "P5R Persona Set 1",
    "p5r_2": "P5R Persona Set 2",
    "p4g": "P4G Persona Set",
}
ELEMENTS = {
    "sla": "斩击", "str": "打击", "pie": "贯穿", "fir": "火炎",
    "ice": "冰冻", "ele": "电击", "win": "疾风", "lig": "光明",
    "dar": "黑暗", "alm": "万能", "ail": "异常", "rec": "回复",
    "sup": "辅助", "spe": "特殊", "pas": "被动",
}
TARGETS = {
    "1 foe": "敌方单体", "All foes": "敌方全体", "1 ally": "我方单体",
    "All allies": "我方全体", "Universal": "敌我全体", "-": "自身",
}
TERMS = {
    "-": "", "Slash": "斩击", "Strike": "打击", "Pierce": "贯穿",
    "Fire": "火炎", "Ice": "冰冻", "Elec": "电击", "Wind": "疾风",
    "Light": "光明", "Dark": "黑暗", "Almighty": "万能",
    "Charm": "魅惑", "Poison": "中毒", "Distress": "动摇",
    "Confuse": "混乱", "Fear": "恐惧", "Rage": "愤怒", "Freeze": "冻结",
    "Shock": "触电", "Dizzy": "晕眩", "Down": "倒地", "Instakill": "即死",
    "instakill": "即死", "ailment": "异常状态", "ailments": "异常状态",
    "Dark instakill": "黑暗即死", "Light instakill": "光明即死",
    "HP restore": "HP回复", "SP restore": "SP回复", "HP + SP restore": "HP与SP回复",
    "HP costs": "HP消耗", "SP costs": "SP消耗", "current HP": "当前HP",
    "Tarukaja": "攻击提升", "Rakukaja": "防御提升", "Sukukaja": "命中与回避提升",
    "Matarukaja": "全体攻击提升", "Marakukaja": "全体防御提升",
    "Masukukaja": "全体命中与回避提升", "Rebellion": "暴击率提升",
    "Tarukaja + Rakukaja + Sukukaja": "攻击、防御、命中与回避提升",
    "Tarunda + Rakunda + Sukunda": "攻击、防御、命中与回避降低",
    "Charge + Concentrate": "蓄力与专心", "Diarahan + Amrita": "HP全回复并解除异常",
    "Life Drain + Spirit Drain": "吸取HP与SP", "Patra + Baisudi": "解除全部异常状态",
    "-kaja effects": "能力提升效果", "-kunda effects": "能力降低效果",
    "attack": "攻击", "defense": "防御", "hit and evasion": "命中与回避",
    "crit rate": "暴击率", "incoming crit rate": "被暴击率", "incoming hit rate": "被命中率",
    "dmg dealt": "造成伤害", "dmg taken": "受到伤害", "phys": "物理",
    "magic": "魔法", "non-Almighty magic": "万能以外的魔法", "exp": "经验",
    "single-target skill": "单体技能", "multi-target skill": "全体技能",
    "all ailments except Dizzy": "晕眩以外的异常状态", "survive": "存活",
    "during Full Moon": "满月时", "during New Moon": "新月时", "on Downed foes": "攻击倒地敌人时",
    "when Enraged": "愤怒时", "when receiving Shift": "受到换手时",
}
FMT_ZH = {
    "Base": "$2", "Exact": "$1%概率$2", "Plus": "$2+$1%",
    "Times": "$2×$1", "FracDamage": "削减$1%的$2",
    "InstakillWhen": "敌人$2时，$1%概率即死",
    "PersonaKaja": "$2×$1，持续3回合", "ElemCharge": "下次$2攻击伤害×$1",
    "ElemBreak": "解除$2耐性，持续3回合", "ElemKarn": "反射下次$2攻击",
    "ElemBoost": "$2伤害×$1", "HealBoost": "$2技能效果×$1",
    "DodgeElem": "$2回避率×$1", "ResistElem": "受到$2伤害×$1",
    "NullElem": "$2无效", "RepelElem": "反射$2", "DrainElem": "吸收$2",
    "AilmentBoost": "$2附着率×$1", "ResistAilment": "受到$2的概率×$1",
    "NullAilment": "免疫$2", "CureAilment": "解除$2",
    "PersonaCounterN": "$1%概率反射$2攻击",
    "PersonaLifeDrainN": "造成$1伤害并$2", "AutoSkill": "战斗开始时发动$2",
    "FoulBreathN": "提高$2附着率，持续3回合",
    "RegenerateN": "每回合$2 $1%", "InvigorateN": "每回合$2 $1点",
    "GrowthN": "未装备时获得$1%的$2", "LifeAidN": "战斗后回复$1%的最大HP/SP",
    "Recarm": "复活并回复$1% HP", "Endure": "受到致命伤害时以1 HP存活，每场战斗1次",
    "EnduringSoul": "受到致命伤害时HP全回复，每场战斗1次",
}
DIRECT_ZH = {
    "dmg dealt x$1 $2": "$2，伤害×$1",
    "$1% chance to endure $2": "$1%概率以1 HP承受$2",
    "$1% $2, user's HP reduced to 1": "$2 $1%，自身HP降至1",
    "Next $2 attack x$1 dmg and crit up": "下次$2攻击伤害×$1并提高暴击率",
    "$2 +$1% for 3 turns": "$2+$1%，持续3回合",
    "$1% $2 + Sukukaja": "回复$1% HP并提升命中与回避",
    "$1% $2, ignores resistances": "$1%概率$2，无视耐性",
    "ignores resistances": "无视耐性",
    "9999 $2, reduces user's HP and SP to 1": "造成9999伤害，自身HP与SP降至1",
    "$2 x$1 but cannot dodge": "$2×$1，无法回避",
    "$2 recovery time x$1": "$2恢复所需时间×$1",
    "$2 recovery time reduced to $1 turn": "$2恢复所需时间缩短至$1回合",
    "$2 +$1%, overwrites Crit Rate Boost": "$2+$1%，覆盖暴击率强化",
    "$1 $2 when receiving Shift": "受到换手时$2 $1点",
    "$1 $2 to ally when Shifting to them": "换手给同伴时为其$2 $1点",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def json_file(path):
    return json.loads(path.read_text(encoding="utf-8"))


def tsv_file(path):
    with path.open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle, delimiter="\t"))


def check_commit(repo, expected):
    actual = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip()
    require(actual == expected, f"Expected {repo.name} at {expected}; found {actual}")


def translate_term(term):
    if term in TERMS:
        return TERMS[term]
    if ", " in term:
        return "、".join(translate_term(piece) for piece in term.split(", "))
    wall = re.fullmatch(r"Resist (Fire|Ice|Wind|Elec) for 3 turns", term)
    if wall:
        return f"{translate_term(wall[1])}耐性提升，持续3回合"
    raise ValueError(f"Untranslated effect term: {term}")


def format_number(value):
    value = (value - 1000) / 100 if value >= 1000 else value
    return f"{value:g}"


def skill_effect(row, templates):
    _, _, power, min_hits, max_hits, accuracy, critical, modifier = row["b"]
    effect, condition, _ = row["c"]
    en_parts, zh_parts = [], []
    if power:
        en_parts.append(f"{power} pwr")
        zh_parts.append(f"威力{power}")
    if max_hits > 1:
        hits = str(max_hits) if min_hits == max_hits else f"{min_hits}–{max_hits}"
        en_parts.append(f"{hits} hits")
        zh_parts.append(f"{hits}次")
    if accuracy and not 90 <= accuracy <= 110:
        en_parts.append(f"{accuracy}% acc")
        zh_parts.append(f"命中{accuracy}%")
    if critical > 5:
        en_parts.append(f"{critical}% crit")
        zh_parts.append(f"暴击{critical}%")
    if condition != "-":
        en_template = templates[condition[3:]] if condition.startswith("FMT") else condition
        zh_template = FMT_ZH[condition[3:]] if condition.startswith("FMT") else DIRECT_ZH[condition]
        if condition == "FMTExact" and effect in ("HP restore", "SP restore"):
            zh_template = "回复$1% HP" if effect == "HP restore" else "回复$1% SP"
        mod = format_number(modifier)
        en_parts.append(en_template.replace("$1", mod).replace("$2", effect))
        zh_parts.append(zh_template.replace("$1", mod).replace("$2", translate_term(effect)))
    return ", ".join(en_parts), "；".join(zh_parts)


def build(tool_repo, database_repo):
    check_commit(tool_repo, TOOL_COMMIT)
    check_commit(database_repo, DATABASE_COMMIT)
    data = tool_repo / "src/app/p3r/data"
    raw = database_repo / "src/p3r/Content/Xrd777"
    name_dir = raw / "Blueprints/common/Names"
    demons = json_file(data / "demon-data.json")
    skill_data = json_file(data / "skill-data.json")
    config = json_file(data / "comp-config.json")
    conditions = json_file(data / "demon-unlocks.json")
    recipes = json_file(data / "special-recipes.json")
    chart = json_file(data / "fusion-chart.json")
    effects = json_file(tool_repo / "src/app/compendium/data/skill-effects.json")
    persona_rows = tsv_file(name_dir / "DatPersonaNameDataAsset.tsv")
    skill_rows = tsv_file(name_dir / "DatSkillNameDataAsset.tsv")
    race_rows = tsv_file(name_dir / "DatRaceDataAsset.tsv")
    persona_names = {r["en"]: (i, r) for i, r in enumerate(persona_rows) if r["flag"] == "1"}
    race_names = {r["en"]: r for r in race_rows}
    pack_rows = {r["en"]: r for r in tsv_file(raw / "UI/Tables/AddContentEntitlementNameDataAsset.tsv")}
    skill_by_name = {row["a"][0]: (int(sid), row) for sid, row in skill_data.items()}
    persona_ids = {}
    for key in demons:
        game_id, row = persona_names[PERSONA_ALIASES.get(key, key)]
        require(bool(row["zh-Hans"]), f"Missing official Chinese Persona name: {key}")
        persona_ids[key] = f"persona_{game_id}"
    used_skills = {name for demon in demons.values() for name in demon["skills"]}
    skills, skill_ids = [], {}
    for name in sorted(used_skills, key=lambda x: skill_by_name[x][0]):
        sid, row = skill_by_name[name]
        raw_name = skill_rows[sid]
        require(raw_name["en"] == name and bool(raw_name["zh-Hans"]), f"Skill name/ID mismatch: {name}")
        skill_id = f"skill_{sid}"
        skill_ids[name] = skill_id
        rank, cost, power, min_hits, max_hits, accuracy, critical, modifier = row["b"]
        effect_en, effect_zh = skill_effect(row, effects)
        cost_type = "none" if cost == 0 else "HP" if cost < 1000 else "SP" if cost < 2000 else "theurgy"
        skills.append({
            "id": skill_id, "gameId": sid, "nameEn": name, "nameZh": raw_name["zh-Hans"],
            "element": row["a"][1], "target": row["a"][2], "targetZh": TARGETS[row["a"][2]],
            "costType": cost_type, "cost": cost if cost < 1000 else cost - 1000 if cost < 2000 else 0,
            "power": power, "minHits": min_hits, "maxHits": max_hits,
            "accuracy": accuracy, "critical": critical, "unique": rank == 0 or rank > 89,
            "effectEn": effect_en, "effectZh": effect_zh,
            "raw": {"rank": rank, "modifier": modifier, "effect": row["c"][0], "condition": row["c"][1]},
        })
    unlocks, dlc = {}, {}
    for group in conditions:
        for key, text in group["conditions"].items():
            if group["category"] == "Downloadable Content":
                dlc[key] = PACK_IDS[text]
                continue
            if group["category"] == "Maxed Social Link":
                if text == "Max all Social Links":
                    unlock = {"type": "all_social", "textEn": text, "textZh": "所有社群达到MAX"}
                else:
                    arcana = text.removesuffix(" Rank 10")
                    unlock = {"type": "social", "arcana": arcana, "rank": 10,
                              "textEn": text, "textZh": f"{race_names[arcana]['zh-Hans']}社群MAX"}
            elif group["category"] == "Maxed Teammate Link":
                names = {"Junpei": "顺平", "Akihiko": "真田", "Koromaru": "虎狼丸", "Ken": "天田", "Shinjiro": "荒垣", "Ryoji": "望月绫时"}
                character = text.removesuffix(" Max Link")
                unlock = {"type": "linked", "character": character, "textEn": text,
                          "textZh": f"完成{names[character]}的全部支线事件"}
            elif "Request #" in text:
                request_id = int(text.split("#")[-1])
                unlock = {"type": "request", "requestId": request_id, "textEn": text,
                          "textZh": f"完成伊丽莎白委托#{request_id}"}
            else:
                floor = int(text.split()[-1])
                unlock = {"type": "floor", "floor": floor, "textEn": text,
                          "textZh": f"探索塔尔塔罗斯至{floor}层"}
            unlocks[key] = unlock
    personas = []
    for key, entry in sorted(demons.items(), key=lambda x: int(persona_ids[x[0]].split("_")[1])):
        game_id, row = persona_names[PERSONA_ALIASES.get(key, key)]
        learned, theurgy = [], []
        for name, level in entry["skills"].items():
            if level == 5217:
                theurgy.append(skill_ids[name])
            else:
                require(0 < level <= 99, f"Unknown skill sentinel {key}: {name}={level}")
                learned.append({"id": skill_ids[name], "level": 0 if level < 1 else int(level)})
        persona = {
            "id": persona_ids[key], "gameId": game_id, "key": key,
            "nameEn": row["en"].removesuffix(" A"), "nameZh": row["zh-Hans"],
            "arcana": entry["race"], "level": entry["lvl"], "stats": entry["stats"],
            "resists": entry["resists"], "inherits": entry["inherits"], "dlc": dlc.get(key, ""),
            "skills": learned, "theurgySkillIds": theurgy,
        }
        if key in unlocks:
            persona["unlock"] = unlocks[key]
        if "heart" in entry:
            persona["heartItemEn"] = entry["heart"]
            persona["heartLevel"] = entry["heartlvl"]
        personas.append(persona)
    result = {
        "schemaVersion": 1, "game": "p3r",
        "sources": [
            {"name": "Megami Tensei Fusion Tools", "author": "aqiu384", "url": "https://github.com/aqiu384/megaten-fusion-tool", "commit": TOOL_COMMIT, "license": "Unlicense"},
            {"name": "Megami Tensei Database", "author": "aqiu384", "url": "https://github.com/aqiu384/megaten-database", "commit": DATABASE_COMMIT, "license": "No separate license declared"},
        ],
        "attribution": "图鉴数据与合成规则：aqiu384；游戏名称与文本：ATLUS / SEGA。",
        "elements": config["resistElems"], "elementNamesZh": ELEMENTS,
        "resistCodes": {"w": "弱", "s": "耐", "n": "无", "r": "反", "d": "吸", "-": "—"},
        "arcana": [{"id": name, "nameEn": name, "nameZh": race_names[name]["zh-Hans"], "order": i}
                   for i, name in enumerate(chart["races"])],
        "dlcPacks": [{"id": key, "nameEn": name, "nameZh": pack_rows[name]["zh-Hans"]}
                     for key, name in PACK_NAMES.items()],
        "personas": personas, "skills": skills,
        "fusion": {"arcana": chart["races"], "chart": chart["table"],
                   "specialRecipes": [{"resultId": persona_ids[name], "ingredientIds": [persona_ids[x] for x in ingredients]}
                                      for name, ingredients in recipes.items()]},
        "inheritance": {"elements": config["inheritElems"], "types": config["inheritTypes"]},
    }
    validate(result)
    return result


def validate(data):
    personas, skills = data["personas"], data["skills"]
    persona_ids, skill_ids = {p["id"] for p in personas}, {s["id"] for s in skills}
    require(len(personas) == len(persona_ids) == 194, "Persona count/ID uniqueness mismatch")
    require(sum(not p["dlc"] for p in personas) == 173, "Expected 173 base-game Personas")
    require(len(skills) == len(skill_ids) == 340, "Expected 333 normal + 7 Theurgy skills")
    require(sum(s["costType"] == "theurgy" for s in skills) == 7, "Expected seven Theurgy skills")
    require(len(data["fusion"]["specialRecipes"]) == 22, "Expected 22 special fusion recipes")
    for p in personas:
        require(len(p["resists"]) == 10 and set(p["resists"]) <= set("wsnrd-"), f"Bad resistance data: {p['id']}")
        require(len(p["stats"]) == 5 and len(p["nameZh"]) > 0, f"Missing Persona fields: {p['id']}")
        require(p["inherits"] in data["inheritance"]["types"], f"Unknown inheritance type: {p['id']}")
        require(all(s["id"] in skill_ids and 0 <= s["level"] <= 99 for s in p["skills"]), f"Bad skill reference: {p['id']}")
        require(all(s in skill_ids for s in p["theurgySkillIds"]), f"Bad Theurgy reference: {p['id']}")
    for recipe in data["fusion"]["specialRecipes"]:
        require(recipe["resultId"] in persona_ids and set(recipe["ingredientIds"]) <= persona_ids, "Bad fusion reference")
    require(all(len(row) == i + 1 for i, row in enumerate(data["fusion"]["chart"])), "Bad triangular fusion chart")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tool-repo", type=Path, required=True)
    parser.add_argument("--database-repo", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    content = (json.dumps(build(args.tool_repo, args.database_repo), ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if args.check:
        require(args.output.read_bytes() == content, f"Generated asset differs: {args.output}")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(content)
    print(f"{'Verified' if args.check else 'Generated'} 194 Personas, 340 skills, 22 special recipes; {len(content)} bytes; sha256={hashlib.sha256(content).hexdigest()}")


if __name__ == "__main__":
    main()
