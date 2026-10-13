#!/usr/bin/env python3
"""Compile the single P3R v3 rule pack (schema ``p3r.rules.v3``).

Inputs (never shipped in the APK):
  rules/sources/**           pinned third-party snapshots (aqiu game-data tables, aqiu calendar page,
                             Steam zh-Hans strings, CN guide page); hashes in rules/sources/SOURCES.json
  rules/p3r/*.json           earlier research/evidence files (candidates, conflicts, witnesses)
  android/app/src/main/assets/data.json
                             the original Simplified Chinese walkthrough (location names, wording,
                             timing witnesses)

Outputs:
  android/app/src/main/assets/p3r/p3r_rules.json   the compiled pack (deterministic, < 1 MB)
  rules/build/REPORT.md                            decisions, conflicts, coverage and known gaps

Usage:
  python3 rules/build/build_p3r_rules.py            # rebuild pack + report
  python3 rules/build/build_p3r_rules.py --check    # fail if the committed files are stale

Python 3 standard library only. The output is a pure function of the input files: no clock, no
randomness, sorted keys everywhere.
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import html
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCES_DIR = ROOT / "rules" / "sources"
EVIDENCE_DIR = ROOT / "rules" / "p3r"
GUIDE_PATH = ROOT / "android" / "app" / "src" / "main" / "assets" / "data.json"
PACK_PATH = ROOT / "android" / "app" / "src" / "main" / "assets" / "p3r" / "p3r_rules.json"
REPORT_PATH = ROOT / "rules" / "build" / "REPORT.md"

SCHEMA = "p3r.rules.v3"
PACK_VERSION = "3.0.0"
GAME = "Persona 3 Reload (Xrd777, base game; excludes Episode Aigis/Astrea)"

EPOCH = datetime.date(2009, 4, 1)
FIRST_DAY = 6  # 2009-04-07, first in-game day with a save
LAST_DAY = 305  # 2010-01-31, finale
MAX_PACK_BYTES = 1_000_000

CONF_VALUES = ("game_data", "verified", "single", "estimate", "conflict")
STAT_IDS = ("academics", "charm", "courage")
WEEKDAY_ABBR = {"Mon": 1, "Tue": 2, "Wed": 3, "Thu": 4, "Fri": 5, "Sat": 6, "Sun": 7}

# Canonical link order (arcana order 0..20, then Aeon). Index == Rules.Link.idx.
LINK_IDS = (
    "fool", "magician", "priestess", "empress", "emperor", "hierophant", "lovers", "chariot",
    "justice", "hermit", "fortune", "strength", "hanged_man", "death", "temperance", "devil",
    "tower", "star", "moon", "sun", "judgement", "aeon",
)
STORY_LINKS = ("fool", "death", "judgement")
EVENING_LINKS = ("devil", "tower")
SCHOOL_LINKS = (
    "magician", "priestess", "empress", "emperor", "lovers", "chariot", "justice", "fortune",
    "strength", "temperance", "aeon",
)
# English arcana names as used by DatRaceDataAsset / ArcanaID (= arcana number + 1, Aeon = 22).
ARCANA_EN = (
    "Fool", "Magician", "Priestess", "Empress", "Emperor", "Hierophant", "Lovers", "Chariot",
    "Justice", "Hermit", "Fortune", "Strength", "Hanged", "Death", "Temperance", "Devil", "Tower",
    "Star", "Moon", "Sun", "Judgement", "Aeon",
)
NUMERALS = (
    "0", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII", "XIII", "XIV",
    "XV", "XVI", "XVII", "XVIII", "XIX", "XX", "XX",
)

# Official zh-Hans spellings that older Chinese texts got wrong. Applied to every string that is
# copied from the CN guide so the pack never contains the outdated forms.
NAME_FIXES = (
    ("伊丽莎白", "伊莉莎白"),
    ("贝贝", "贝培"),
    ("大桥舞子", "舞子"),
    ("Y子", "Ｙ子"),
)
FORBIDDEN_STRINGS = ("伊丽莎白", "贝贝", "大桥舞子", "女帝", "倒吊人")


class BuildError(Exception):
    """Raised when the evidence contradicts an invariant the pack relies on."""


# --------------------------------------------------------------------------------------------
# Calendar helpers


def day_of(month: int, dom: int) -> int:
    """Day ordinal (days since 2009-04-01) for an in-game month/day (Jan-Mar are in 2010)."""
    year = 2010 if month <= 3 else 2009
    return (datetime.date(year, month, dom) - EPOCH).days


def day_of_md(md: str) -> int:
    month, dom = md.replace("/", "-").split("-")
    return day_of(int(month), int(dom))


def md_of(d: int) -> str:
    date = EPOCH + datetime.timedelta(days=d)
    return f"{date.month:02d}-{date.day:02d}"


def cn_date(d: int) -> str:
    date = EPOCH + datetime.timedelta(days=d)
    return f"{date.month}月{date.day}日"


def weekday_of(d: int) -> int:
    return (EPOCH + datetime.timedelta(days=d)).isoweekday()


def day_range(first_md: str, last_md: str) -> list[int]:
    return list(range(day_of_md(first_md), day_of_md(last_md) + 1))


def fix_names(text: str) -> str:
    for wrong, right in NAME_FIXES:
        text = text.replace(wrong, right)
    return text


# --------------------------------------------------------------------------------------------
# Decision / conflict ledger


class Ledger:
    """Collects decisions, conflicts, audit observations and dropped calendar cells for REPORT.md."""

    def __init__(self) -> None:
        self.decisions: list[tuple[str, str, str]] = []  # (area, id, text)
        self.conflicts: list[dict] = []
        self.audit: list[str] = []
        self.dropped: dict[str, list[str]] = {}
        self.step_days: dict[str, str] = {}  # unlock step id -> which meeting days it uses (report only)

    def decide(self, area: str, ident: str, text: str) -> None:
        self.decisions.append((area, ident, text))

    def conflict(self, ident: str, topic: str, chosen: str, alternates: list[str], sources: list[str]) -> None:
        self.conflicts.append({
            "id": ident, "topic": topic, "chosen": chosen,
            "alternates": alternates, "sources": sources,
        })


# --------------------------------------------------------------------------------------------
# Source loading


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_tsv(name: str) -> list[list[str]]:
    path = SOURCES_DIR / "aqiu_gamedata" / f"{name}.tsv"
    rows = [line.split("\t") for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    return rows


def read_evidence(name: str) -> dict:
    return json.loads((EVIDENCE_DIR / name).read_text(encoding="utf-8"))


def html_to_text(fragment: str) -> str:
    text = re.sub(r"<script.*?</script>", "", fragment, flags=re.S)
    text = re.sub(r"</t[dh]>\s*", " | ", text)
    text = re.sub(r"</tr>", "\n", text)
    text = re.sub(r"<br\s*/?>", "\n", text)
    text = re.sub(r"</(p|h\d|li)>", "\n", text)
    text = re.sub(r"<[^>]+>", "", text)
    text = html.unescape(text)
    return re.sub(r"\n\s*\n+", "\n", text)


class AqiuPage:
    """Parsed aqiu social-events page (pinned snapshot, sha256 404dea95…)."""

    DAY_COLUMNS = (
        "magician", "priestess", "empress", "emperor", "hierophant", "lovers", "chariot", "justice",
        "hermit", "fortune", "strength", "hanged_man", "temperance", "star", "moon", "sun", "aeon",
    )
    NIGHT_COLUMNS = {"De": "devil", "To": "tower"}

    def __init__(self, path: Path) -> None:
        self.html = path.read_text(encoding="utf-8")
        start = self.html.find('id="daytime"')
        mid = self.html.find('id="nighttime"')
        end = self.html.find('id="social-stats"')
        if min(start, mid, end) < 0:
            raise BuildError("aqiu page layout changed: calendar headings not found")
        self.day_header, self.day_rows = self._tables(self.html[start:mid])
        self.night_header, self.night_rows = self._tables(self.html[mid:end])
        self.text = html_to_text(self.html[end:])

    @staticmethod
    def _tables(segment: str) -> tuple[list[str], list[list[str]]]:
        header: list[str] = []
        rows: list[list[str]] = []
        for table in re.findall(r"<table>(.*?)</table>", segment, flags=re.S):
            header = [html.unescape(x).strip() for x in re.findall(r"<th(?:\s[^>]*)?>(.*?)</th>", table, flags=re.S)]
            for tr in re.findall(r"<tr>(.*?)</tr>", table, flags=re.S):
                cells = [html.unescape(re.sub(r"<[^>]+>", "", x)).strip()
                         for x in re.findall(r"<td(?:\s[^>]*)?>(.*?)</td>", tr, flags=re.S)]
                if cells:
                    rows.append(cells)
        return header, rows

    def day_cells(self) -> dict[int, dict[str, str]]:
        """day ordinal -> link id -> cell symbol ('O', 'D', '-', '')."""
        expected = ["Ma", "Pr", "Em", "Em", "Hi", "Lo", "Ch", "Ju", "He", "Fo", "St", "Ha", "Te", "St", "Mo", "Su", "Ae"]
        if self.day_header[2:19] != expected:
            raise BuildError(f"aqiu daytime header changed: {self.day_header}")
        out: dict[int, dict[str, str]] = {}
        for row in self.day_rows:
            d = day_of_md(row[1])
            if WEEKDAY_ABBR[row[0]] != weekday_of(d):
                raise BuildError(f"aqiu daytime row {row[1]} weekday {row[0]} does not match the 2009 calendar")
            out[d] = {link: row[2 + i] for i, link in enumerate(self.DAY_COLUMNS)}
        return out

    def night_cells(self) -> dict[int, dict[str, str]]:
        out: dict[int, dict[str, str]] = {}
        for row in self.night_rows:
            d = day_of_md(row[1])
            cells = dict(zip(self.night_header, row))
            out[d] = {link: cells[col] for col, link in self.NIGHT_COLUMNS.items()}
        return out

    def column(self, evening: bool, name: str) -> dict[int, str]:
        """day ordinal -> raw cell of one named column of the daytime or nighttime calendar."""
        header = self.night_header if evening else self.day_header
        rows = self.night_rows if evening else self.day_rows
        if name not in header:
            raise BuildError(f"aqiu {'nighttime' if evening else 'daytime'} calendar has no column {name!r}: {header}")
        idx = header.index(name)
        out = {}
        for row in rows:
            d = day_of_md(row[1])
            if WEEKDAY_ABBR[row[0]] != weekday_of(d):
                raise BuildError(f"aqiu row {row[1]} weekday {row[0]} does not match the 2009 calendar")
            out[d] = row[idx]
        return out

    def tartarus_cells(self) -> dict[int, str]:
        """Nighttime 'Tartarus' column: '-' = full party, names = members who cannot go, 'Everyone' = nobody can."""
        return self.column(True, "Tartarus")

    def section(self, name: str) -> str:
        """Text of an aqiu <h4> section (social links)."""
        start = self.html.find(f'<h4 id="{name}">')
        if start < 0:
            raise BuildError(f"aqiu section {name} not found")
        end = self.html.find("<h4", start + 5)
        return html_to_text(self.html[start:end])


def check_day_calendar_json(page_cells: dict[int, dict[str, str]]) -> None:
    """The derived day-calendar JSON must agree cell-for-cell with the HTML table."""
    rows = json.loads((SOURCES_DIR / "aqiu_pages" / "social_events_day_calendar.json").read_text(encoding="utf-8"))
    names = {"Hanged": "hanged_man"}
    for row in rows:
        d = day_of_md(row["Date"])
        for key, value in row.items():
            if key in ("Day", "Date", "Junpei", "Koromaru", "Shinjiro", "Ryoji"):
                continue
            link = names.get(key, key.lower())
            if page_cells[d][link] != value:
                raise BuildError(f"day calendar JSON disagrees with HTML on {row['Date']} {key}")


# --------------------------------------------------------------------------------------------
# Source registry


def build_sources() -> list[dict]:
    """Every source referenced by a record's src list, with url and pinned sha256."""
    manifest = json.loads((SOURCES_DIR / "SOURCES.json").read_text(encoding="utf-8"))
    pinned = {}
    for entry in manifest["files"]:
        path = SOURCES_DIR / entry["path"]
        actual = sha256_file(path)
        if actual != entry["sha256"]:
            raise BuildError(f"{entry['path']} does not match its pinned sha256 in SOURCES.json")
        pinned[entry["path"]] = entry

    def snap(ident: str, rel: str, kind: str, note: str) -> dict:
        entry = pinned[rel]
        return {"id": ident, "kind": kind, "note": note, "path": "rules/sources/" + rel,
                "sha256": entry["sha256"], "url": entry.get("url") or entry.get("derivedFrom")}

    def evidence(ident: str, name: str, note: str) -> dict:
        return {"id": ident, "kind": "evidence", "note": note, "path": "rules/p3r/" + name,
                "sha256": sha256_file(EVIDENCE_DIR / name), "url": None}

    def web(ident: str, url: str, note: str) -> dict:
        return {"id": ident, "kind": "web", "note": note, "path": None, "sha256": None, "url": url}

    gd = "aqiu_gamedata/"
    sources = [
        snap("gd_activity", gd + "ActivityDataAsset.tsv", "game_data", "Tartarus block opening dates and floor ranges"),
        snap("gd_arbeit_help", gd + "BMD_ArbeitHelp.tsv", "game_data", "Part-time job help text: slot and minimum pay"),
        snap("gd_community_event", gd + "CommunityEventDataAsset.tsv", "game_data", "Social Link points needed per rank"),
        snap("gd_disappear_help", gd + "BMD_DisappearHelp.tsv", "game_data", "Missing-person board text"),
        snap("gd_general_coefficient", gd + "DT_GeneralCoefficient.tsv", "game_data", "Social Link bonus multipliers"),
        snap("gd_hero_parameter", gd + "HeroParameterDataAsset.tsv", "game_data", "Social stat level thresholds"),
        snap("gd_holiday_schedule", gd + "HolidayScheduleDataAsset.tsv", "game_data", "Sunday/holiday invitation dates"),
        snap("gd_player_name", gd + "DatPlayerNameDataAsset.tsv", "game_data", "Official zh-Hans character names"),
        snap("gd_race", gd + "DatRaceDataAsset.tsv", "game_data", "Official zh-Hans arcana names"),
        snap("gd_social_link", gd + "DatSocialLinkDataAsset.tsv", "game_data", "Official zh-Hans Social Link labels"),
        snap("aqiu_social_events", "aqiu_pages/social-events.html", "community_db", "aqiu P3R social-events page: day/night calendars, activities, unlocks, story ranks"),
        snap("aqiu_day_calendar", "aqiu_pages/social_events_day_calendar.json", "community_db", "Parsed daytime calendar (cross-checked against the HTML)"),
        snap("aqiu_overworld", gd + "overworld.md", "community_db", "aqiu overworld notes: missing people windows"),
        snap("aqiu_tartarus", gd + "tartarus.md", "community_db", "aqiu Tartarus block list"),
        snap("steam_sc_achievements", "steam_schinese/ach_schinese.txt", "official_text", "ATLUS zh-Hans achievement strings (社群, 个人能力值, 打工, 失踪者…)"),
        snap("steam_thread_hierophant", "steam_schinese/d_4206993639701168068.html", "forum", "Player report of a Hierophant rank hold"),
        snap("steam_thread_rankup", "steam_schinese/d_4206994023684392768.html", "forum", "Player reports on rank-up cues and matching Personas"),
        snap("cn_3dm", "cn_guides/3dm_p42.html", "cn_guide", "3DM SC guide: Tartarus block names"),
        {"id": "cn_guide", "kind": "cn_guide", "note": "Original Simplified Chinese walkthrough shipped with the legacy guide (wording, places, timing witnesses)",
         "path": "android/app/src/main/assets/data.json", "sha256": sha256_file(GUIDE_PATH), "url": None},
        evidence("ev_calendar_constraints", "calendar_global_constraints_candidate.json", "Locks, exams, full moons, school-closure candidates"),
        evidence("ev_slot_locks", "global_slot_locks_expansion_candidate.json", "Whole-slot lock claims, Tartarus closures, witnesses"),
        evidence("ev_tartarus_supplement", "tartarus_entry_availability_supplement.json", "Tartarus entry witnesses and closures"),
        evidence("ev_stats_actions", "stats_actions.json", "Stat thresholds and evening stat actions"),
        evidence("ev_stat_expansion", "stat_activity_expansion_candidate.json", "Restaurants, karaoke, movies, daytime coffee"),
        evidence("ev_jobs", "jobs_wage_rules_candidate.json", "Part-time job wage tiers and conflicts"),
        evidence("ev_manual_models", "manual_link_models.json", "Normalised per-rank thresholds and answer gains"),
        evidence("ev_rank_points", "reported_rank_points.json", "aqiu per-rank best scene gains"),
        evidence("ev_simple_unlock", "simple_unlock_rules_candidate.json", "Single-slot link introductions"),
        evidence("ev_multistep_unlock", "multistep_unlock_rules_candidate.json", "Multi-step link openings (Justice, Devil, Hanged Man, Tower, Moon, Sun, Strength)"),
        evidence("ev_story_links", "story_link_transitions_candidate.json", "Fool/Death/Judgement transitions"),
        evidence("ev_dialogue", "guide_dialogue_reference_map.json", "CN guide dialogue fragments bound to target ranks"),
        evidence("ev_social_links", "social_links.json", "Link catalogue: weekdays, unlock claims, conflicts"),
        evidence("ev_identity_labels", "social_goal_identity_labels.json", "Selector labels and gender"),
        evidence("ev_affinity", "affinity_model_evidence.json", "Multiplier and carry-over evidence"),
        evidence("ev_ordinary_gain", "ordinary_affinity_numeric_followup.json", "Ordinary hangout +10 single-source claim"),
        evidence("ev_romance", "romance_rank_rules_candidate.json", "Romanceable links and romance flags"),
        evidence("ev_reversal", "reversal_repair_research_candidate.json", "Reversal/repair research"),
        web("wiki420", "https://w.atwiki.jp/wiki10_persona3/pages/420.html", "atwiki Reload all-MAX route chart (cited through evidence files)"),
        web("wiki453", "https://w.atwiki.jp/wiki10_persona3/pages/453.html", "atwiki Reload Social Link page (cited through evidence files)"),
        web("wiki455", "https://w.atwiki.jp/wiki10_persona3/pages/455.html", "atwiki Reload Social Link schedule notes"),
        web("wiki469", "https://w.atwiki.jp/wiki10_persona3/pages/469.html", "atwiki Reload parameters page"),
        web("gf81375", "https://gamefaqs.gamespot.com/pc/409941-persona-3-reload/faqs/81375/calendar", "GameFAQs Reload calendar (Tartarus closures)"),
        web("gf81159", "https://gamefaqs.gamespot.com/ps5/410283-persona-3-reload/faqs/81159", "GameFAQs social stats guide"),
        web("megatenwiki_jobs", "https://megatenwiki.com/index.php?title=Chagall_Cafe&oldid=423201", "MegaTen Wiki job wage tables"),
        web("siliconera_jobs", "https://www.siliconera.com/how-to-get-a-job-in-persona-3-reload/", "Siliconera job article (wages cross-check only)"),
        web("lpbeach", "https://lpbeach.co.uk/viewtopic.php?t=1712#p15208", "Single Reload playthrough source for the +10 ordinary hangout"),
        web("siliconera_urls", "https://www.siliconera.com/how-to-get-shared-computer-urls-in-persona-3-reload/",
            "Shared-computer URLs: Dating Site note from November, 3,000 yen, can be purchased multiple times"),
        evidence("ev_dating_note", "affinity_actions_candidate.json", "Dating-site note: evening, +10 points, 3,000 yen, never a rank-up"),
        evidence("ev_hangout_rules", "ordinary_hangout_rules_candidate.json", "Dating-site note sources (aqiu, game8, atwiki 455, Steam guide)"),
    ]
    return sorted(sources, key=lambda s: s["id"])


# --------------------------------------------------------------------------------------------
# Game-data tables


def load_names() -> dict:
    race = {row[0]: row[3] for row in read_tsv("DatRaceDataAsset")[1:]}
    people = {}
    for row in read_tsv("DatPlayerNameDataAsset")[1:]:
        people[row[0]] = row[3].replace(" ", "")
    labels = {row[0]: row[3] for row in read_tsv("DatSocialLinkDataAsset")[1:]}
    return {"race": race, "people": people, "labels": labels}


def load_thresholds() -> dict[str, list[int]]:
    rows = read_tsv("CommunityEventDataAsset")
    header, body = rows[0], rows[1:]
    if header != [f"Rank{i}" for i in range(11)]:
        raise BuildError("CommunityEventDataAsset header changed")
    out = {}
    for idx, link in enumerate(LINK_IDS):
        arcana_id = idx + 1 if link != "aeon" else 22
        out[link] = [int(x) for x in body[arcana_id]]
    return out


def load_stat_thresholds() -> dict[str, list[int]]:
    rows = read_tsv("HeroParameterDataAsset")
    if rows[0] != ["Academics", "Charm", "Courage"]:
        raise BuildError("HeroParameterDataAsset header changed")
    increments = [[int(x) for x in r] for r in rows[1:]]
    out = {}
    for col, stat in enumerate(STAT_IDS):
        cumulative = [0, 0]
        total = 0
        for lv in range(2, 7):
            total += increments[lv][col]
            cumulative.append(total)
        out[stat] = cumulative
    return out


def load_multipliers() -> dict[str, float]:
    return {row[0]: float(row[1]) for row in read_tsv("DT_GeneralCoefficient")[1:]}


def load_invites() -> dict[int, list[str]]:
    """Holiday invitation day -> link ids (game data, ArcanaID = arcana number + 1)."""
    out: dict[int, list[str]] = {}
    for row in read_tsv("HolidayScheduleDataAsset")[1:]:
        d = day_of(int(row[0]), int(row[1]))
        links = [LINK_IDS[int(x) - 1] for x in row[2:5] if int(x) > 0]
        out[d] = links
    return out


def load_blocks_table() -> list[tuple[int, int, int]]:
    rows = read_tsv("ActivityDataAsset")[1:]
    return [(day_of(int(r[0]), int(r[1])), int(r[2]), int(r[3])) for r in rows]


def load_pay_help() -> dict[str, str]:
    return {row[0]: row[1] for row in read_tsv("BMD_ArbeitHelp")[1:]}


# --------------------------------------------------------------------------------------------
# Calendar: day types, locks, moon, Tartarus


LOCK_REASONS = {
    "exam": "考试期间",
    "festival": "夏日祭",
    "finale": "最终决战",
    "forced_event": "剧情事件",
    "full_moon": "满月作战",
    "new_year": "新年参拜",
    "prologue": "序章剧情",
    "reserved": "可能有剧情（先空出）",
    "school_trip": "修学旅行",
    "story": "剧情事件",
    "summer_classes": "暑期补习",
    "track_training": "田径社集训与比赛",
    "typhoon_illness": "台风停课与感冒休养",
    "work_experience": "职场体验",
    "yakushima": "屋久岛旅行",
}

# Japanese public holidays of 2009-10 that fall on a non-Sunday inside the horizon. The game
# follows them (Ｙ子 is online and invitations arrive on these dates); the 2009 substitute holiday
# 5/6 is NOT observed in-game (class answer on 5/6, school links meet that day).
HOLIDAYS = {
    "04-29": "昭和之日", "05-04": "绿之日", "05-05": "儿童节", "07-20": "海之日",
    "09-21": "敬老日", "09-22": "国民休息日", "09-23": "秋分日", "10-12": "体育节",
    "11-03": "文化节", "11-23": "勤劳感谢日", "12-23": "天皇诞辰", "01-01": "元旦",
    "01-11": "成人节",
}
VACATIONS = (("07-26", "08-31", "暑假"), ("12-27", "01-07", "寒假"))
EXAM_RESULTS = {"05-25": "期中考试放榜", "07-24": "期末考试放榜", "10-19": "期中考试放榜", "12-21": "期末考试放榜"}

BLOCK_NAMES = (
    ("thebel", "世俗之庭 台伯勒"),
    ("arqa_1", "奇颜之庭 阿尔卡"),
    ("arqa_2", "奇颜之庭 阿尔卡"),
    ("yabbashah_1", "鄙俗之庭 亚巴扎"),
    ("yabbashah_2", "鄙俗之庭 亚巴扎"),
    ("tziah_1", "奢豪之庭 茨尔阿"),
    ("tziah_2", "奢豪之庭 茨尔阿"),
    ("harabah_1", "焦炎之庭 哈拉瓦"),
    ("harabah_2", "焦炎之庭 哈拉瓦"),
    ("adamah", "忧郁之庭 亚达玛"),
)

RESCUES = (
    # id, zh, missing from, deadline, link
    ("bunkichi", "北村文吉", "09-12", "10-03", "hierophant"),
    ("maiko", "舞子", "10-21", "11-02", "hanged_man"),
)


class Lock:
    """One lock claim: the slots of the listed days are unavailable for free actions."""

    def __init__(self, days: list[int], slots: tuple[str, ...], reason: str, conf: str, src: list[str], why: str) -> None:
        self.days = days
        self.slots = slots
        self.reason = reason
        self.conf = conf
        self.src = src
        self.why = why


def collect_locks(ledger: Ledger) -> list[Lock]:
    """All slot locks: evidence-file claims first, then the documented conservative decisions."""
    locks: list[Lock] = []
    slot_locks = read_evidence("global_slot_locks_expansion_candidate.json")
    kinds = {
        "prologue": "prologue", "examination_core": "exam", "summer_classes": "summer_classes",
        "typhoon_and_illness": "typhoon_illness", "kyoto_trip": "school_trip",
        "work_experience": "work_experience", "full_moon_operation": "full_moon",
    }
    for claim in slot_locks["newWholeSlotLockClaims"]:
        reason = kinds[claim["eventKind"]]
        days = []
        for x in claim["dates"]:
            if md_of(x["ordinal"]) != x["mmdd"]:
                raise BuildError(f"ordinal/mmdd mismatch in {claim['id']}")
            days.append(x["ordinal"])
        locks.append(Lock(days, ("day", "eve"), reason, "verified", ["ev_slot_locks", "wiki420", "aqiu_social_events"],
                          f"{claim['id']}: explicit whole-slot claim; aqiu shows no link opportunity"))
    for claim in slot_locks["retainedLockClaims"]:
        ident = claim["id"]
        d = claim["date"]["ordinal"]
        slots = tuple("day" if s == "daytime" else "eve" for s in claim["slots"])
        if ident.startswith("p3r.calendar.explicit_lock.04-"):
            reason = "prologue"
        elif ident.startswith("p3r.calendar.explicit_lock."):
            reason = "story"
        elif ident == "p3r.calendar.festival_0816":
            reason = "festival"
        elif ident == "p3r.calendar.magician_forced_0422":
            reason = "forced_event"
        else:
            raise BuildError(f"unmapped retained lock claim {ident}")
        locks.append(Lock([d], slots, reason, "verified", ["ev_slot_locks", "wiki420"], f"{ident}: explicit source lock"))
    summary = []
    for lock in locks:
        span = md_of(lock.days[0]) if len(lock.days) == 1 else f"{md_of(lock.days[0])}..{md_of(lock.days[-1])}"
        summary.append(f"{span} {'+'.join(lock.slots)} {lock.reason}")
    ledger.decide("calendar", "evidence locks", "Taken over from explicit whole-slot claims in "
                  "global_slot_locks_expansion_candidate.json (wiki420 route notes, aqiu all-dash rows): "
                  + "; ".join(summary) + ".")

    def add(first: str, last: str, slots: tuple[str, ...], reason: str, conf: str, src: list[str], why: str) -> None:
        locks.append(Lock(day_range(first, last), slots, reason, conf, src, why))
        span = first if first == last else f"{first}..{last}"
        ledger.decide("calendar", f"lock {span} {'+'.join(slots)} = {reason}", f"({conf}) {why}")

    both = ("day", "eve")
    add("04-18", "04-18", both, "prologue", "estimate", ["ev_slot_locks", "cn_guide"],
        "4/18 SEES joins (Fool R1 that evening); no source lists a free action before 4/21")
    add("04-20", "04-20", both, "prologue", "estimate", ["ev_slot_locks", "cn_guide"],
        "4/20 is the forced Tartarus tutorial; the aqiu calendar and the CN guide start free time on 4/21")
    add("05-01", "05-01", ("day",), "story", "single", ["aqiu_social_events", "ev_calendar_constraints"],
        "Akihiko hospital visit: every link cell is '-' (even Moon/Hierophant); the evening has a CN-guide arcade witness")
    add("06-13", "06-13", ("eve",), "story", "single", ["aqiu_social_events", "ev_tartarus_supplement"],
        "Saturday evening without Devil/Tower cells; the Tartarus supplement lists 6/13 as a mandatory (non-optional) night")
    add("06-20", "06-20", ("day",), "story", "single", ["aqiu_social_events", "ev_calendar_constraints"],
        "Koromaru introduction: all daytime cells '-'; the evening has Devil/Tower cells")
    add("07-18", "07-18", ("day",), "story", "single", ["aqiu_social_events", "ev_slot_locks"],
        "Ken introduction after the exams: all daytime cells '-'; evening witnesses exist")
    add("07-20", "07-22", both, "yakushima", "estimate", ["ev_slot_locks", "aqiu_social_events"],
        "DESIGN §10.5: the unresolved Yakushima context is locked until disproven; all cells '-'")
    add("07-23", "07-23", ("day",), "yakushima", "estimate", ["ev_slot_locks", "aqiu_social_events"],
        "Yakushima return day; the evening is free (Tower cell, wiki420 and CN-guide Wakatsu meal)")
    add("07-27", "08-02", ("day",), "track_training", "single", ["ev_calendar_constraints", "aqiu_social_events"],
        "Track-club training 7/27-8/1 and meet 8/2: every daytime cell '-' (even Moon/Hierophant); evenings have Devil/Tower cells")
    add("08-15", "08-15", ("day",), "summer_classes", "single", ["aqiu_social_events", "ev_slot_locks"],
        "Last summer-school day: all daytime cells '-'; evening witnesses exist")
    add("09-02", "09-02", ("day",), "story", "single", ["aqiu_social_events", "ev_calendar_constraints"],
        "Shinjiro returns: all daytime cells '-'")
    add("11-04", "11-04", both, "story", "estimate", ["ev_calendar_constraints", "cn_guide", "aqiu_social_events"],
        "DESIGN §10.5: unresolved story context (Death MAX in the early morning, Fool R7 in the evening; the CN guide lists only the automatic event)")
    add("11-09", "11-09", ("day",), "story", "single", ["aqiu_social_events", "cn_guide"],
        "Ryoji's first episode is automatic after school (CN guide 绫时自动剧情); all daytime cells '-'")
    add("11-20", "11-20", ("day",), "school_trip", "single", ["aqiu_social_events", "ev_slot_locks"],
        "Return from the school trip: all daytime cells '-'; the evening is free")
    add("11-27", "11-27", ("day",), "work_experience", "single", ["aqiu_social_events", "ev_slot_locks"],
        "Last work-experience day: all daytime cells '-'; the evening is free")
    add("12-31", "12-31", ("eve",), "story", "verified", ["cn_guide", "ev_slot_locks", "aqiu_social_events"],
        "Forced decision scene in the evening (CN guide; Fool R10 and Judgement R1); the daytime is free (CN guide 自由安排)")
    add("01-01", "01-01", ("day",), "new_year", "estimate", ["ev_calendar_constraints", "aqiu_social_events"],
        "DESIGN §10.5: the group New Year shrine visit is locked until disproven; the evening is free (Tower cell, CN guide 自由安排)")
    add("01-21", "01-21", ("day",), "reserved", "estimate", ["ev_slot_locks", "cn_guide"],
        "DESIGN §10.5: possible Chidori event if Junpei received white flowers; conservative daytime lock")
    add("01-31", "01-31", ("eve",), "finale", "verified", ["ev_slot_locks", "cn_guide"],
        "Final operation; the daytime is free (aqiu cells, CN guide shopping)")
    ledger.decide("calendar", "default", "A slot without a lock is free (handoff: absence of an annotation is not proof "
                  "of unavailability). Locks come from explicit whole-slot claims (prologue, exams, full moons, trips, "
                  "summer classes, typhoon/illness, festival, 4/22 forced Magician intro, 10/5, 11/5 and 12/3 daytime) "
                  "and from days on which aqiu lists no opportunity at all, not even for the always-available Moon and "
                  "Hierophant. Days after a full moon are free when aqiu has cells for them (5/10, 6/9, 7/8, 8/7, 9/6); "
                  "10/5 daytime and 11/4 stay locked.")
    ledger.decide("calendar", "12-24 free", "Christmas Eve stays free by default (all invitations declined, DESIGN §10.5); "
                  "aqiu has ordinary cells that day and wiki420 says both slots are free when every invitation is refused.")
    return locks


FULL_MOONS = ("04-09", "05-09", "06-08", "07-07", "08-06", "09-05", "10-04", "11-03")
# No operations between 11/3 and 1/31: two estimated full moons (real 2009 dates) keep the cycle going.
ESTIMATED_FULL_MOONS = ("12-02", "01-01", "01-31")


def full_moon_days() -> list[int]:
    return [day_of_md(md) for md in FULL_MOONS + ESTIMATED_FULL_MOONS]


def moon_phases() -> dict[int, int]:
    """0..7 phase per day: 4 only on a full-moon day, 0 around the new moon, 5-7 waning, 1-3 waxing."""
    anchors = full_moon_days()
    anchors = [anchors[0] - 30] + anchors
    out = {}
    for d in range(FIRST_DAY, LAST_DAY + 1):
        prev = max(a for a in anchors if a <= d)
        if d == prev:
            out[d] = 4
            continue
        nxt = min(a for a in anchors if a > d)
        step = int((d - prev) / (nxt - prev) * 8 + 0.5)
        out[d] = (4 + min(7, max(1, step))) % 8
    return out


def build_calendar(ledger: Ledger, day_cells: dict, night_cells: dict, tartarus_cells: dict) -> tuple[list[dict], dict]:
    """Day records (contiguous FIRST_DAY..LAST_DAY) and the Tartarus section."""
    locks = collect_locks(ledger)
    day_lock: dict[int, Lock] = {}
    eve_lock: dict[int, Lock] = {}
    for lock in locks:
        for d in lock.days:
            for slot in lock.slots:
                target = day_lock if slot == "day" else eve_lock
                previous = target.get(d)
                if previous is not None and previous.reason != lock.reason:
                    raise BuildError(f"{md_of(d)} {slot}: conflicting lock reasons {previous.reason} / {lock.reason}")
                target[d] = lock

    table = load_blocks_table()
    if len(table) != 11 or table[-1][0] != LAST_DAY:
        raise BuildError("ActivityDataAsset no longer has 11 rows ending on 1/31")
    blocks = []
    for idx, (start, low, high) in enumerate(table[:-1]):
        ident, zh = BLOCK_NAMES[idx]
        blocks.append({
            "conf": "game_data", "floorsZh": f"{max(low, 2)}–{high}F", "fromDay": start, "id": ident,
            "src": ["gd_activity", "aqiu_tartarus", "cn_3dm"], "toDay": table[idx + 1][0] - 1, "top": high, "zh": zh,
        })
    ledger.decide("tartarus", "blocks", "Block opening dates and floor ranges come from ActivityDataAsset "
                  "(4/21, 5/10, 6/13, 7/9, 8/8, 9/10, 10/6, 11/6, 12/3, 1/1). The 1/31 row (257F+) is the finale and "
                  "is not a plannable block. zh names come from the 3DM SC guide (single source); the two halves of a "
                  "zone share its name and are told apart by floor range.")

    closed = {x["date"]["ordinal"] for x in read_evidence("global_slot_locks_expansion_candidate.json")["dungeonEntryOnlyRestrictions"]}
    supplement_closed = {x["ordinal"] for x in read_evidence("tartarus_entry_availability_supplement.json")["knownDungeonOnlyClosedDates"]}
    if closed != supplement_closed or len(closed) != 20:
        raise BuildError("Tartarus closure lists disagree between evidence files")
    # aqiu's nighttime "Tartarus" column names the party members who cannot go; "Everyone" = no party.
    aqiu_closed = {d for d, cell in tartarus_cells.items() if cell == "Everyone"}
    if not closed <= aqiu_closed:
        raise BuildError(f"GameFAQs closures not marked 'Everyone' by aqiu: {sorted(md_of(d) for d in closed - aqiu_closed)}")
    party = {"Yukari", "Junpei", "Akihiko", "Mitsuru", "Fuuka", "Aigis", "Koromaru", "Ken", "Shinjiro"}
    for cell in sorted({c for c in tartarus_cells.values() if c not in ("-", "Everyone")}):
        names = {x.strip() for x in cell.split(",")}
        if not names <= party:
            raise BuildError(f"aqiu Tartarus cell {cell!r} is neither '-', 'Everyone' nor party member names")
    closed_all = closed | aqiu_closed

    holidays = {day_of_md(md): name for md, name in HOLIDAYS.items()}
    vacation_days: dict[int, str] = {}
    for first, last, name in VACATIONS:
        for d in day_range(first, last):
            vacation_days[d] = name
    exam_days = {d for lock in locks if lock.reason == "exam" for d in lock.days}
    moons = moon_phases()
    rescue_notes: dict[int, list[str]] = {}
    for _ident, zh, first, last, _link in RESCUES:
        deadline = cn_date(day_of_md(last))
        rescue_notes.setdefault(day_of_md(first), []).append(f"{zh}失踪了，要在{deadline}前到塔尔塔罗斯把人救出来")
        rescue_notes.setdefault(day_of_md(last), []).append(f"今天是救出{zh}的最后期限")

    days = []
    for d in range(FIRST_DAY, LAST_DAY + 1):
        md = md_of(d)
        wd = weekday_of(d)
        dl = day_lock.get(d)
        el = eve_lock.get(d)
        if d in exam_days:
            dtype = "exam"
        elif dl is not None and el is not None:
            dtype = "story"
        elif d in vacation_days:
            dtype = "vacation"
        elif d in holidays:
            dtype = "holiday"
        elif wd == 7:
            dtype = "sunday"
        else:
            dtype = "school"
        block = next((i for i, b in enumerate(blocks) if b["fromDay"] <= d <= b["toDay"]), -1)
        notes = []
        if d in holidays:
            notes.append(holidays[d])
        if md in EXAM_RESULTS:
            notes.append(EXAM_RESULTS[md])
        if md == "12-24":
            notes.append("平安夜：默认按婉拒所有邀约来安排")
        if md == "01-21":
            notes.append("如果之前把「白色切花」送给了顺平，这天放学后会有剧情，所以先空出来")
        for b in blocks:
            if b["fromDay"] == d:
                notes.append(f"塔尔塔罗斯的新区块开放了：{b['zh']}（{b['floorsZh']}）")
        notes.extend(rescue_notes.get(d, []))
        confs = [x.conf for x in (dl, el) if x is not None]
        aqiu_only_closure = block >= 0 and el is None and d in aqiu_closed and d not in closed
        if aqiu_only_closure:
            confs.append("single")
        conf = next((c for c in ("estimate", "conflict", "single") if c in confs), "verified")
        src = sorted({s for x in (dl, el) if x is not None for s in x.src} | {"aqiu_social_events", "gd_activity"})
        days.append({
            "block": block,
            "conf": conf,
            "d": d,
            "dayLock": dl.reason if dl else None,
            "eveLock": el.reason if el else None,
            "fullMoon": md in FULL_MOONS or d == LAST_DAY,
            "md": md,
            "moon": moons[d],
            "noteZh": "；".join(notes) if notes else None,
            "src": src,
            "tartarus": block >= 0 and d not in closed_all and el is None,
            "type": dtype,
            "wd": wd,
        })

    audit_calendar(ledger, days, day_cells, night_cells, holidays, vacation_days)
    ledger.decide("calendar", "day types", "school/sunday/holiday/vacation/exam/story with precedence exam > story "
                  "(both slots locked) > vacation > holiday > sunday > school. Summer vacation 7/26–8/31 and winter break "
                  "12/27–1/7 follow the aqiu cells: school links still meet on 7/24–7/25 (exam results, Lovers opening in "
                  "the classroom on 7/25) and on 12/24–12/26, and resume on 1/8 (class answer that day). Holidays are the "
                  "2009-10 Japanese public holidays; every weekday Hermit cell falls on one of them (audited). The 5/6 "
                  "substitute holiday is not observed in-game (class answer and school-link cells on 5/6).")
    ledger.decide("calendar", "moon", "Moon phase 0..7 (4 = full, only on the full-moon day itself) is interpolated "
                  "between the operation full moons 4/9 … 11/3 and 1/31, with two estimated full moons 12/2 and 1/1 "
                  "(real 2009 dates) because the game has no operations in between. Decorative only; fullMoon is true "
                  "only on operation days and 1/31.")
    rescues = []
    for ident, zh, first, last, link in RESCUES:
        rescues.append({"conf": "verified", "fromDay": day_of_md(first), "id": ident, "link": link,
                        "src": ["aqiu_overworld", "gd_disappear_help"], "toDay": day_of_md(last), "zh": zh})
    ledger.decide("tartarus", "rescues", "Only the two missing people that gate a Social Link are rescues: 北村文吉 "
                  "9/12–10/3 (教皇) and 舞子 10/21–11/2 (倒悬者) (aqiu overworld; the game board text says Bunkichi was "
                  "reported missing on 9/11, but the aqiu calendar still has a Hierophant cell that day). The other 19 "
                  "missing people only reward items and are left out.")
    by_day = {x["d"]: x for x in days}
    # Every GameFAQs date, plus the aqiu-only closures that actually remove a plannable Tartarus evening.
    closed_nights = sorted(closed | {d for d in aqiu_closed if by_day[d]["block"] >= 0 and by_day[d]["eveLock"] is None})
    newly = [md_of(d) for d in closed_nights if d not in closed]
    ledger.decide("tartarus", "closed nights", "A night is closed to Tartarus when the GameFAQs closure list "
                  f"(20 dates, both evidence files agree) or aqiu's nighttime 'Tartarus' column says so. That column names "
                  "the party members who cannot go; 'Everyone' means nobody can, so there is no trip (every GameFAQs date is "
                  "'Everyone'; no wiki420 or CN-guide Tartarus night is; 10/4 'Ken, Shinjiro', 11/6-11/13 'Mitsuru' and "
                  "12/10-12/29 'Aigis' match story absences; a named member means a partial party and keeps the tower open). "
                  f"Nights closed by aqiu alone (conf=single): {', '.join(newly)}.")
    tartarus = {"blocks": blocks, "closedNights": closed_nights, "rescues": rescues}
    return days, tartarus


def audit_calendar(ledger: Ledger, days: list[dict], day_cells: dict, night_cells: dict, holidays: dict, vacations: dict) -> None:
    """Cross-check the derived calendar against the aqiu cells; violations stop the build."""
    by_day = {x["d"]: x for x in days}
    errors = []
    for d, cells in sorted(day_cells.items()):
        day = by_day[d]
        moon_expected = d >= day_of_md("04-28")
        hiero_expected = d >= day_of_md("04-25") and day["wd"] != 1
        all_dash = all(v in ("-", "") for v in cells.values())
        if all_dash and (moon_expected or hiero_expected) and day["dayLock"] is None:
            errors.append(f"{day['md']} daytime: every link cell is '-' but the slot is not locked")
        if day["wd"] != 7 and cells["hermit"] == "O" and d not in holidays:
            errors.append(f"{day['md']}: Hermit cell on a weekday that is not in HOLIDAYS")
        if d in vacations and day["dayLock"] is None:
            school_o = [k for k in SCHOOL_LINKS if cells[k] == "O"]
            if school_o:
                errors.append(f"{day['md']}: school links {school_o} meet during {vacations[d]}")
    for d, cells in sorted(night_cells.items()):
        day = by_day[d]
        if day["eveLock"] is not None:
            continue
        missing = []
        if d >= day_of_md("05-16") and day["wd"] in (2, 6) and cells["devil"] != "O":
            missing.append("Devil")
        if d >= day_of_md("05-23") and day["wd"] in (4, 5, 6, 7) and cells["tower"] != "O":
            missing.append("Tower")
        if missing:
            ledger.audit.append(f"{day['md']} evening: no {'/'.join(missing)} cell although that weekday usually has one; "
                                "slot kept free (no contrary evidence for other evening activities)")
    if errors:
        raise BuildError("calendar audit failed:\n  " + "\n  ".join(errors))


# --------------------------------------------------------------------------------------------
# Social Links


PERSON_EN = {
    "magician": "Kenji Tomochika", "priestess": "Fuuka Yamagishi", "empress": "Mitsuru Kirijo",
    "emperor": "Hidetoshi Odagiri", "lovers": "Yukari Takeba", "chariot": "Kazushi Miyamoto",
    "justice": "Chihiro Fushimi", "hermit": "Maya", "fortune": "Keisuke Hiraga", "strength": "Yuko Nishiwaki",
    "hanged_man": "Maiko", "death": "Pharos", "temperance": "Bebe", "devil": "President Tanaka",
    "tower": "Mutatsu", "star": "Mamoru Hayase", "moon": "Nozomi Suemitsu", "sun": "Akinari Kamiki",
    "aeon": "Aigis",
}
FEMALE = ("priestess", "empress", "lovers", "justice", "hermit", "strength", "hanged_man", "aeon")
GENDER_SPECIAL = {"fool": "团体", "hierophant": "夫妇", "judgement": "团体"}

LOCATIONS = {
    "fool": "宿舍",
    "magician": "月光馆学园 · 2 楼教室",
    "priestess": "月光馆学园 · 教室前走廊",
    "empress": "月光馆学园 · 1 楼教职员室前走廊",
    "emperor": "月光馆学园 · 学生会活动室",
    "hierophant": "严户台商店街 · 旧书店「书虫」",
    "lovers": "月光馆学园 · 教室",
    "chariot": "月光馆学园 · 教室／田径社",
    "justice": "月光馆学园 · 学生会活动室附近",
    "hermit": "宿舍 · 自己房间的电脑",
    "fortune": "月光馆学园 · 美术教室",
    "strength": "月光馆学园 · 2 楼教室外",
    "hanged_man": "长鸣神社 · 游乐场",
    "death": "宿舍 · 自己的房间",
    "temperance": "月光馆学园 · 家政教室附近走廊",
    "devil": "桐叶购物中心 · 喷泉广场",
    "tower": "桐叶购物中心 · 放纵俱乐部 2 楼",
    # aqiu "Iwatodai Strip Mall 1F > Talk to Mamoru"; the strip mall and the station are separate map destinations.
    "star": "严户台商店街 · 1 楼",
    "moon": "桐叶购物中心 · 喷泉广场",
    "sun": "长鸣神社 · 长椅",
    "judgement": "塔尔塔罗斯",
    "aeon": "月光馆学园 · 教室",
}
# Location fragments that do not occur verbatim in the CN guide (natural wording for new UI text).
LOCATION_WHITELIST = ("自己房间的电脑", "自己的房间", "图书室", "卡拉OK", "饰品店", "2 楼", "操场", "学生会活动室门口")

# Shared wording for steps that leave the slot usable for something else.
NO_TIME = "这一步不会消耗时间。"


def step(ident: str, zh: str, how: str, slot: str, days, location: str, *, consumes: bool = True,
         establishes: bool = False, gap: int = 0, cost: int = 0, from_md: str | None = None) -> dict:
    """Unlock step spec. ``days`` is "avail" (the link's own meeting days), ("avail", [day types]),
    "any", ("fixed", link) (another link's meeting days) or ("weekdays", [..])."""
    return {"id": ident, "zh": zh, "howZh": how, "slot": slot, "days": days, "locationZh": location,
            "consumesSlot": consumes, "establishes": establishes, "minGapDays": gap, "cost": cost, "fromMd": from_md}


UNLOCKS = {
    "magician": {
        "earliest": "04-22", "steps": [], "conf": "verified",
        "src": ["aqiu_social_events", "cn_guide", "ev_simple_unlock"],
        "noteZh": "4月22日放学后会自动认识友近健二，魔术师社群随之解锁，不需要另外安排。",
    },
    "priestess": {
        "earliest": "06-19", "stat": {"courage": 6}, "link": {"fortune": 1}, "conf": "verified",
        "src": ["aqiu_social_events", "ev_simple_unlock", "cn_guide"],
        "steps": [step("priestess.open", "找山岸风花说话", "勇气 MAX、命运社群解锁以后，放学后到教室前的走廊和山岸风花说话，女教皇社群就解锁了。",
                       "day", "avail", "月光馆学园 · 教室前走廊", establishes=True)],
    },
    "empress": {
        # Policy (REPORT §2 "empress exam"): the first-place requirement is not forecastable, so it is shown
        # to the player (here, in the step and in the pack notes) instead of being modelled.
        "earliest": "11-21", "stat": {"academics": 6}, "conf": "conflict",
        "src": ["aqiu_social_events", "ev_simple_unlock", "ev_romance", "cn_guide"],
        "noteZh": "除了学力 MAX，还要至少有一次考试拿过第一名。考试名次没法预测，这里按「学力 MAX 就能解锁」来安排；"
                  "如果一次第一都没拿过，请把女皇的目标改成「未选」。",
        "steps": [step("empress.open", "去教职员室前找桐条美鹤",
                       "学力 MAX、并且考试拿过第一名以后，放学后到 1 楼教职员室前的走廊和桐条美鹤说话，女皇社群就解锁了。",
                       "day", "avail", "月光馆学园 · 1 楼教职员室前走廊", establishes=True)],
    },
    "emperor": {
        "earliest": "04-27", "conf": "verified", "src": ["aqiu_social_events", "ev_simple_unlock", "cn_guide"],
        "steps": [step("emperor.open", "加入学生会", "放学后先到教职员室报名，再去教室对面的学生会活动室，皇帝社群就解锁了。",
                       "day", "avail", "月光馆学园 · 学生会活动室", establishes=True)],
    },
    "hierophant": {
        "earliest": "04-25", "conf": "verified", "src": ["aqiu_social_events", "ev_simple_unlock", "cn_guide"],
        "steps": [step("hierophant.open", "帮旧书店的老夫妇找柿子树叶",
                       "要在上学日的放学后做：先到严户台商店街的旧书店「书虫」和老夫妇说话，再回学校连接走廊调查柿子树，"
                       "拿到「柿子树叶」后带回书店交给他们，教皇社群就解锁了。",
                       "day", ("avail", ["school"]), "严户台商店街 · 旧书店「书虫」", establishes=True)],
    },
    "lovers": {
        "earliest": "07-25", "stat": {"charm": 6}, "conf": "verified",
        "src": ["aqiu_social_events", "ev_simple_unlock", "cn_guide"],
        "steps": [step("lovers.open", "在教室和岳羽由加莉说话", "魅力 MAX 以后，放学后在教室和岳羽由加莉说话，恋爱社群就解锁了。",
                       "day", "avail", "月光馆学园 · 教室", establishes=True)],
    },
    "chariot": {
        "earliest": "04-23", "conf": "verified", "src": ["aqiu_social_events", "ev_simple_unlock", "cn_guide"],
        "steps": [step("chariot.open", "加入田径社",
                       "放学后从运动场前的走廊进入操场，加入田径社，战车社群就解锁了。活动结束后在大厅和西胁结子打招呼、"
                       "邀她一起回家，为力量社群做准备。",
                       "day", "avail", "月光馆学园 · 操场", establishes=True)],
    },
    "justice": {
        "earliest": "04-28", "link": {"emperor": 1}, "conf": "verified",
        "src": ["aqiu_social_events", "ev_multistep_unlock", "cn_guide"],
        "steps": [
            step("justice.talk1", "和伏见千寻搭话（第 1 次）", "放学后到学生会活动室门口找伏见千寻，选「有话想和你说」。" + NO_TIME,
                 "day", "avail", "月光馆学园 · 学生会活动室门口", consumes=False),
            step("justice.talk2", "和伏见千寻搭话（第 2 次）", "改天再去学生会活动室门口找她，选「我也是男性」。" + NO_TIME,
                 "day", "avail", "月光馆学园 · 学生会活动室门口", consumes=False, gap=1),
            step("justice.talk3", "和伏见千寻搭话（第 3 次）", "再改天去找她，选「一起出去玩吧」。" + NO_TIME,
                 "day", "avail", "月光馆学园 · 学生会活动室门口", consumes=False, gap=1),
            step("justice.open", "和伏见千寻一起回家", "第三次搭话之后的另一天，放学后到教室前的走廊找伏见千寻，正义社群就解锁了。",
                 "day", "avail", "月光馆学园 · 教室前走廊", establishes=True, gap=1, from_md="05-07"),
        ],
    },
    "hermit": {
        "earliest": "04-29", "conf": "verified", "src": ["aqiu_social_events", "ev_simple_unlock", "cn_guide"],
        "steps": [step("hermit.open", "用房间里的电脑玩网络游戏",
                       "星期日或节假日的白天，调查自己房间书桌上的电脑，登录网络游戏，隐者社群就解锁了。",
                       "day", "avail", "宿舍 · 自己房间的电脑", establishes=True)],
    },
    "fortune": {
        "earliest": "06-17", "conf": "verified", "src": ["aqiu_social_events", "ev_simple_unlock", "cn_guide"],
        "steps": [step("fortune.open", "加入美术社", "放学后到 1 楼实习室前的走廊，进入美术教室加入美术社，命运社群就解锁了。",
                       "day", "avail", "月光馆学园 · 美术教室", establishes=True)],
    },
    "strength": {
        "earliest": "04-24", "link": {"chariot": 2}, "steps": [], "conf": "verified",
        "src": ["aqiu_social_events", "ev_multistep_unlock", "cn_guide"],
        "noteZh": "战车社群的两次升级剧情结束后，都在大厅和西胁结子打招呼、邀她一起回家（选「和她打招呼」「要不要一起走？」）。"
                  "战车升到 RANK 2 时力量社群就解锁了，不会额外消耗时间。",
    },
    "hanged_man": {
        "earliest": "05-06", "conf": "verified", "src": ["aqiu_social_events", "ev_multistep_unlock", "aqiu_overworld", "cn_guide"],
        "steps": [
            step("hanged_man.gift", "给神社的小女孩送吃的",
                 "在严户台商店街的章鱼烧店买一份「神秘章鱼烧」，在宿舍的自动贩卖机买「莫乐蜜G」，带到长鸣神社的游乐场交给小女孩。"
                 "两样一共约 510 日元。" + NO_TIME,
                 "day", "avail", "长鸣神社 · 游乐场", consumes=False, cost=510),
            step("hanged_man.open", "改天再去长鸣神社陪她玩", "送过吃的之后的另一天，再去长鸣神社的游乐场找她，倒悬者社群就解锁了。",
                 "day", "avail", "长鸣神社 · 游乐场", establishes=True, gap=1),
        ],
    },
    "temperance": {
        "earliest": "05-08", "stat": {"academics": 2}, "link": {"hierophant": 3}, "conf": "verified",
        "src": ["aqiu_social_events", "ev_simple_unlock"],
        "steps": [step("temperance.open", "去家政教室找贝培", "学力 Lv.2、教皇社群 RANK 3 以后，放学后到 1 楼家政教室附近找贝培，节制社群就解锁了。",
                       "day", "avail", "月光馆学园 · 家政教室附近走廊", establishes=True)],
    },
    "devil": {
        "earliest": "05-16", "stat": {"charm": 4}, "link": {"hermit": 4}, "conf": "conflict",
        "src": ["aqiu_social_events", "ev_multistep_unlock", "ev_social_links", "cn_guide"],
        "noteZh": "隐者社群的要求按较严格的 RANK 4 计算（也有资料说 RANK 3）。",
        "steps": [
            step("devil.pay1", "在喷泉前把 20,000 日元交给田中社长",
                 "需要魅力 Lv.4 和隐者社群 RANK 4。晚上到桐叶购物中心的喷泉前找田中社长，就是那个穿西装的男人，交给他 20,000 日元。"
                 + NO_TIME,
                 "eve", "avail", "桐叶购物中心 · 喷泉广场", consumes=False, cost=20000),
            step("devil.pay2", "再交给田中社长 10,000 日元", "改天晚上再去喷泉前交给他 10,000 日元。" + NO_TIME,
                 "eve", "avail", "桐叶购物中心 · 喷泉广场", consumes=False, cost=10000, gap=1),
            step("devil.open", "交出最后的 10,000 日元", "再改天晚上交最后的 10,000 日元，恶魔社群就解锁了。",
                 "eve", "avail", "桐叶购物中心 · 喷泉广场", establishes=True, cost=10000, gap=1),
        ],
    },
    "tower": {
        "earliest": "05-23", "stat": {"courage": 4}, "link": {"strength": 4}, "conf": "conflict",
        "src": ["aqiu_social_events", "ev_multistep_unlock", "ev_social_links", "cn_guide"],
        "noteZh": "勇气要求按较严格的 Lv.4 计算（也有资料说 Lv.2 或 Lv.3）。",
        "steps": [
            step("tower.rumor", "听西胁结子说起奇怪的和尚",
                 "力量社群 RANK 4 以后，放学后和西胁结子聊天，会听到放纵俱乐部里有个奇怪和尚的传闻。" + NO_TIME,
                 "day", ("fixed", "strength"), "月光馆学园 · 2 楼教室外", consumes=False),
            step("tower.open", "到放纵俱乐部 2 楼找无达",
                 "勇气 Lv.4 以后，晚上到放纵俱乐部 2 楼找无达说话，再到 1 楼向酒保点单，依次选「基尔酒」「血腥玛丽」"
                 "「卡西斯橙汁」「乌龙茶」，最后回去找他，塔社群就解锁了。",
                 "eve", "avail", "桐叶购物中心 · 放纵俱乐部", establishes=True),
        ],
    },
    "star": {
        "earliest": "08-05", "stat": {"courage": 4}, "conf": "verified",
        "src": ["aqiu_social_events", "ev_simple_unlock", "cn_guide"],
        "steps": [step("star.open", "在严户台商店街找早濑护说话", "勇气 Lv.4 以后，到严户台商店街 1 楼找早濑护说话，星社群就解锁了。",
                       "day", "avail", "严户台商店街 · 1 楼", establishes=True)],
    },
    "moon": {
        "earliest": "04-28", "stat": {"charm": 2}, "link": {"magician": 3}, "conf": "verified",
        "src": ["aqiu_social_events", "ev_multistep_unlock", "cn_guide"],
        "noteZh": "需要身上带着「神秘食品」，塔尔塔罗斯的宝箱里有机会拿到，拿到后先别卖掉。",
        "steps": [
            step("moon.rumor", "听友近健二说起「美食大王」",
                 "魔术师社群 RANK 3 以后，放学后在教室和友近健二聊天，听到「美食大王」的消息。" + NO_TIME,
                 "day", ("fixed", "magician"), "月光馆学园 · 2 楼教室", consumes=False),
            step("moon.open", "去喷泉广场找末光望美",
                 "魅力 Lv.2 以后，带着「神秘食品」到桐叶购物中心的喷泉广场和末光望美说话，依次回答「费洛蒙咖啡」「红色」"
                 "「叶隐盖饭」，再把神秘食品给他，月亮社群就解锁了。",
                 "day", "avail", "桐叶购物中心 · 喷泉广场", establishes=True),
        ],
    },
    "sun": {
        "earliest": "08-09", "stat": {"academics": 4}, "link": {"hanged_man": 3}, "conf": "conflict",
        "src": ["aqiu_social_events", "ev_multistep_unlock", "cn_guide"],
        "noteZh": "最早按 8月9日开始（日历和原版攻略都是这一天；也有资料写 8月23日）。",
        "steps": [
            step("sun.ask", "在长鸣神社和神木秋成说话",
                 "倒悬者社群 RANK 3、学力 Lv.4 以后，星期日白天到长鸣神社的长椅找神木秋成，他会提到丢失的红色钢笔。" + NO_TIME,
                 "day", "avail", "长鸣神社 · 长椅", consumes=False),
            step("sun.pen", "从虎狼丸那里拿到红色钢笔", "晚上回宿舍 2 楼找虎狼丸，拿到「红色钢笔」。" + NO_TIME,
                 "eve", "any", "宿舍 · 2 楼", consumes=False),
            step("sun.open", "把红色钢笔还给神木秋成", "之后的星期日白天带着钢笔回长鸣神社找他，太阳社群就解锁了。",
                 "day", "avail", "长鸣神社 · 长椅", establishes=True, gap=1),
        ],
    },
    "aeon": {
        "earliest": "01-08", "conf": "verified", "src": ["aqiu_social_events", "ev_simple_unlock", "cn_guide"],
        "steps": [step("aeon.open", "放学后和埃癸斯一起回家", "1月8日起，放学后在教室和埃癸斯说话，答应一起回家，永劫社群就解锁了。",
                       "day", "avail", "月光馆学园 · 教室", establishes=True)],
    },
}

LINK_NOTES = {
    "fool": "随主线剧情自动升级，不需要安排。",
    "death": "随主线剧情自动升级，不需要安排。",
    "judgement": "12月31日解锁，之后随你在塔尔塔罗斯「忧郁之庭 亚达玛」到达的楼层（227F～255F）自动升级；"
                 "具体日期看探索进度，这里按最晚的日期估计。",
    "emperor": "11月3日～11月29日小田桐秀利不在学生会，见不到面。",
    "empress": "考试前一周（12月7日～12月12日）她每天放学后都在。",
    "hierophant": "9月12日北村文吉失踪，要在10月3日前到塔尔塔罗斯救出他，否则教皇社群无法继续。",
    "hanged_man": "10月21日舞子失踪，要在11月2日前到塔尔塔罗斯救出她，否则倒悬者社群无法继续。",
    "hermit": "只在星期日和节假日的白天上线。",
    "devil": "解锁之后每次见面都会升级，不用攒点数。",
    "sun": "只在星期日白天见面；解锁之后每次见面都会升级。",
    "moon": "RANK 9 的剧情结束时社群会短暂逆转，之后照常见面就能恢复。",
    "aeon": "1月25日埃癸斯不在；升到 RANK 9 之前每次见面都会升级。",
}


def event_gains(ledger: Ledger) -> tuple[dict[str, list[int]], dict[str, list[int] | None]]:
    """Best-answer scene gain per rank (friendship route) and the romance variant when it differs."""
    reported: dict[tuple[str, int], int] = {}
    romantic: dict[tuple[str, int], int] = {}
    for row in read_evidence("reported_rank_points.json")["rows"]:
        link = row["entityId"].split(".")[-1]
        gain = row["reportedOptimalSceneGain"]
        if gain is None:
            continue
        key = (link, row["sourceSceneRankLabel"])
        if row["branch"] == "romantic":
            romantic[key] = gain
        else:
            reported[key] = gain
    modelled: dict[tuple[str, int], list[int]] = {}
    for entity in read_evidence("manual_link_models.json")["entities"]:
        link = entity["entity_id"].split(".")[-1]
        for event in entity["rank_events"]:
            if event.get("optimal_base_gain") is not None:
                modelled.setdefault((link, event["to_rank"]), []).append(event["optimal_base_gain"])
    gains: dict[str, list[int]] = {}
    romance: dict[str, list[int] | None] = {}
    for link in LINK_IDS:
        row = [0] * 11
        for rank in range(2, 10):
            candidates = []
            if (link, rank) in reported:
                candidates.append(reported[(link, rank)])
            candidates.extend(modelled.get((link, rank), []))
            if not candidates:
                continue
            row[rank] = min(candidates)
            if len(set(candidates)) > 1 and rank >= 3:
                ledger.conflict(f"gain.{link}.r{rank}", f"{link} rank-{rank} best scene gain",
                                f"{row[rank]} (lowest claim)", [str(c) for c in sorted(set(candidates))],
                                ["ev_rank_points", "ev_manual_models"])
        gains[link] = row
        alt = list(row)
        changed = False
        for (rlink, rank), value in romantic.items():
            if rlink == link and value != row[rank]:
                alt[rank] = value
                changed = True
        romance[link] = alt if changed else None
    ledger.decide("links", "eventGain", "eventGain[r] = best-answer points of the rank-r scene (they count toward r+1), "
                  "lowest of aqiu's reported optimum and the normalised model; ranks 0-1 and 10 are 0, rank 2 never matters "
                  "because every link needs 0 points for ranks 1-3 (game data). Romantic and platonic rank-9 scenes give the "
                  "same points for every romanceable link, so eventGainRomance is null everywhere.")
    return gains, romance


def dialogue_map() -> dict[str, dict[str, list[str]]]:
    out: dict[str, dict[str, list[str]]] = {}
    for ref in read_evidence("guide_dialogue_reference_map.json")["references"]:
        link = ref["entityId"].split(".")[-1]
        rank = ref["targetRank"]
        frags = ref.get("dialogueChoices") or [f["text"] for f in ref.get("displayFragments", [])]
        frags = [fix_names(f) for f in frags if f.strip()]
        if not frags:
            continue
        bucket = out.setdefault(link, {}).setdefault(str(rank), [])
        for frag in frags:
            if frag not in bucket:
                bucket.append(frag)
    return out


def story_ranks(page: AqiuPage, ledger: Ledger) -> dict[str, list[dict]]:
    """Fool / Death / Judgement automatic ranks from the aqiu page, cross-checked with the evidence file."""
    pattern = re.compile(r"Rank (\d+): Auto (?:(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun) (\d+)/(\d+) (Evening|Daytime)|(Skipped)|(Reach|Clear) Adamah (\d+))")
    expected = {}
    for t in read_evidence("story_link_transitions_candidate.json")["transitions"]:
        if t["expectedMonthDay"]:
            expected[(t["entityId"].split(".")[-1], t["toRank"])] = t["expectedMonthDay"]
    adamah_latest = day_of_md("01-30")
    out: dict[str, list[dict]] = {}
    for link in STORY_LINKS:
        events = []
        for m in pattern.finditer(page.section(link)):
            rank = int(m.group(1))
            if m.group(2):
                d = day_of(int(m.group(2)), int(m.group(3)))
                if (link, rank) in expected and day_of_md(expected[(link, rank)]) != d:
                    raise BuildError(f"{link} rank {rank}: aqiu {md_of(d)} vs evidence {expected[(link, rank)]}")
                events.append({"conf": "verified", "day": d, "rank": rank, "src": ["aqiu_social_events", "ev_story_links"]})
            elif m.group(6):
                floor = int(m.group(7))
                verb = "到达" if m.group(6) == "Reach" else "通过"
                events.append({"conf": "estimate", "day": adamah_latest, "rank": rank, "src": ["aqiu_social_events", "ev_story_links"],
                               "noteZh": f"在塔尔塔罗斯{verb} {floor}F 时升级"})
        if not events:
            raise BuildError(f"no story ranks parsed for {link}")
        out[link] = events
    ledger.decide("links", "storyRanks", "Fool and Death ranks are dated story events (aqiu, matching the evidence file; "
                  "skipped ranks are reached on the next event's day). Judgement R1 is 12/31; R2-R10 depend on Adamah floors "
                  "227-255, so they carry the conservative date 1/30 (last Tartarus evening) with conf=estimate.")
    return out


def location_tokens_ok(text: str, guide_text: str) -> list[str]:
    bad = []
    for part in text.split(" · "):
        stripped = part
        for allowed in LOCATION_WHITELIST:
            stripped = stripped.replace(allowed, "")
        stripped = stripped.strip()
        if stripped and stripped not in guide_text:
            bad.append(part)
    return bad


def build_links(ledger: Ledger, page: AqiuPage, day_records: list[dict], guide_text: str) -> list[dict]:
    names = load_names()
    thresholds = load_thresholds()
    gains, romance = event_gains(ledger)
    dialogue = dialogue_map()
    stories = story_ranks(page, ledger)
    day_cells = page.day_cells()
    night_cells = page.night_cells()
    invites = load_invites()
    by_day = {x["d"]: x for x in day_records}
    romanceable = {e["entity_id"].split(".")[-1] for e in read_evidence("romance_rank_rules_candidate.json")["entities"]}
    identity = {e["slug"]: e for e in read_evidence("social_goal_identity_labels.json")["entities"]}
    rescue_of = {link: (ident, first, last) for ident, _zh, first, last, link in RESCUES}

    # Invitations: game table vs aqiu D cells.
    game_pairs = {(d, link) for d, links in invites.items() for link in links}
    aqiu_pairs = {(d, link) for d, cells in day_cells.items() for link, v in cells.items() if v == "D"}
    if not aqiu_pairs <= game_pairs:
        raise BuildError(f"aqiu invitation cells missing from game data: {sorted(aqiu_pairs - game_pairs)}")
    extra = sorted((md_of(d), link) for d, link in game_pairs - aqiu_pairs)
    if extra != [("08-03", "lovers"), ("08-09", "lovers")]:
        raise BuildError(f"unexpected game-only invitations: {extra}")
    ledger.decide("links", "invites", f"Invitations come from HolidayScheduleDataAsset: {len(invites)} dates, "
                  f"{len(game_pairs)} link-invitations. All 75 aqiu D cells are in the table; the table adds Lovers on "
                  "8/3 and 8/9 (resolving the old aqiu-internal discrepancy in favour of game data).")
    ledger.conflict("threshold.lovers.r6", "Lovers points needed for rank 6", "22 (game data)", ["0 (wiki Rank_Up_Events_3 anchor)"],
                    ["gd_community_event", "ev_manual_models"])

    for link in LINK_IDS:
        t = thresholds[link]
        if t[1] or t[2] or t[3]:
            raise BuildError(f"{link}: ranks 1-3 need points in game data")
    if thresholds["lovers"][6] != 22 or thresholds["emperor"][4] != 0 or thresholds["aeon"][4:10] != [0] * 6 or thresholds["aeon"][10] != 30:
        raise BuildError("game-data thresholds changed (Lovers r6 / Emperor r4 / Aeon)")

    out = []
    for idx, link in enumerate(LINK_IDS):
        arcana_zh = names["race"][ARCANA_EN[idx]]
        if link == "fool":
            person = names["labels"]["SEES"]
        elif link == "judgement":
            person = "伙伴们"
        elif link == "hierophant":
            person = names["people"]["Bunkichi Kitamura"] + "、" + names["people"]["Mitsuko Kitamura"][2:]
        else:
            person = names["people"][PERSON_EN[link]]
        gender = GENDER_SPECIAL.get(link) or ("女" if link in FEMALE else "男")
        label_gender = identity[link]["genderLabelZhHans"]
        if link not in GENDER_SPECIAL and label_gender != gender:
            raise BuildError(f"{link}: gender {gender} disagrees with identity label {label_gender}")
        story = link in STORY_LINKS
        evening = link in EVENING_LINKS
        slot_key = "eveLock" if evening else "dayLock"

        avail: list[int] = []
        dropped: list[str] = []
        if not story:
            cells = night_cells if evening else day_cells
            for d in sorted(cells):
                if cells[d].get(link) != "O":
                    continue
                reason = None
                if by_day[d][slot_key] is not None:
                    reason = f"locked ({by_day[d][slot_key]})"
                elif link == "empress" and by_day[d]["wd"] in (1, 5) and not (day_of_md("12-07") <= d <= day_of_md("12-12")):
                    reason = "Empress Mon/Fri unconfirmed"
                elif link == "emperor" and day_of_md("11-03") <= d <= day_of_md("11-29"):
                    reason = "Emperor away 11/3-11/29"
                if reason:
                    dropped.append(f"{md_of(d)} {reason}")
                else:
                    avail.append(d)
        link_invites = []
        for d, links in sorted(invites.items()):
            if link in links:
                if by_day[d]["dayLock"] is not None:
                    raise BuildError(f"{link} invitation on locked day {md_of(d)}")
                link_invites.append(d)

        spec = UNLOCKS.get(link)
        if story:
            events = stories[link]
            unlock = {"conf": "verified", "earliestDay": min(e["day"] for e in events if e["rank"] == 1),
                      "link": {}, "noteZh": None, "src": ["aqiu_social_events", "ev_story_links"], "stat": {}, "steps": []}
        else:
            unlock = build_unlock(spec, link)

        missing = None
        if link in rescue_of:
            ident, first, last = rescue_of[link]
            missing = {"conf": "verified", "fromDay": day_of_md(first), "rescueId": ident,
                       "src": ["aqiu_overworld", "gd_disappear_help"], "toDay": day_of_md(last)}

        src = ["gd_community_event", "gd_race", "aqiu_social_events"]
        src.append("gd_social_link" if link == "fool" else "ev_identity_labels" if link == "judgement" else "gd_player_name")
        if link_invites:
            src.append("gd_holiday_schedule")
        if story:
            src.append("ev_story_links")
        else:
            src += ["ev_rank_points", "ev_manual_models", "cn_guide"]
        if link in dialogue:
            src.append("ev_dialogue")
        record = {
            "arcanaZh": arcana_zh,
            "avail": avail,
            "conf": "verified",
            "dialogue": dialogue.get(link, {}),
            "eventGain": gains[link],
            "eventGainRomance": romance[link],
            "genderZh": gender,
            "holds": [],
            "id": link,
            "invite": link_invites,
            "kind": "story" if story else "manual",
            "labelZh": f"{arcana_zh} · {person}",
            "locationZh": LOCATIONS[link],
            "missing": missing,
            "no": idx,
            "noteZh": LINK_NOTES.get(link),
            "numeral": NUMERALS[idx],
            "personZh": person,
            "romanceable": link in romanceable,
            "slot": None if story else ("eve" if evening else "day"),
            "src": src,
            "storyRanks": stories.get(link) if story else None,
            "threshold": thresholds[link],
            "unlock": unlock,
        }
        places = [record["locationZh"] or ""] + [s["locationZh"] for s in unlock["steps"]]
        bad = [part for place in places for part in location_tokens_ok(place, guide_text)]
        if bad:
            raise BuildError(f"{link}: location fragments {bad} not found in the CN guide")
        ledger.dropped[link] = dropped
        out.append(record)

    ledger.decide("links", "names", "Arcana and person names come from the zh-Hans game tables (DatRaceDataAsset, "
                  "DatPlayerNameDataAsset): 女皇, 倒悬者, 月亮, 贝培, Ｙ子, 舞子 (no surname in-game), 北村文吉、光子. "
                  "Judgement shows the spoiler-safe 伙伴们 instead of the official label 倪克斯讨伐队; Fool uses the "
                  "official 特别课外活动部.")
    ledger.decide("links", "avail", "avail = aqiu O cells minus locked slots, minus Empress Monday/Friday cells outside "
                  "the pre-exam week 12/7-12/12 (DESIGN conflict policy), minus Emperor cells 11/3-11/29 (aqiu note; one "
                  "contrary cell on 11/16 dropped as the stricter claim). Cells inside rescue windows are kept: the "
                  "missing window, not the calendar, decides whether the person is there.")
    ledger.conflict("avail.emperor.11-16", "Emperor on 11/16", "unavailable (aqiu availability note)", ["available (aqiu cell + route witness)"],
                    ["aqiu_social_events", "ev_calendar_constraints"])
    ledger.conflict("avail.devil.10-03", "Devil on Saturday 10/3", "no meeting (aqiu has no cell that evening)",
                    ["meeting (the CN guide meets Tanaka on the evening of 10/3)"], ["aqiu_social_events", "cn_guide"])
    ledger.conflict("avail.empress.monfri", "Empress on Mondays/Fridays", "only during 12/7-12/12", ["every school day in Nov-Dec (aqiu cells, game8 EN)"],
                    ["aqiu_social_events", "ev_calendar_constraints"])
    ledger.conflict("unlock.devil.hermit", "Devil needs Hermit rank", "4", ["3 (aqiu)"], ["ev_multistep_unlock", "ev_social_links"])
    ledger.conflict("unlock.tower.courage", "Tower needs Courage Lv", "4", ["3 (wiki453, game8.jp)", "2 (kamigame)"],
                    ["ev_multistep_unlock", "ev_social_links"])
    ledger.conflict("unlock.sun.earliest", "Sun earliest opening", "8/9", ["8/23 (aqiu overview)"], ["aqiu_social_events", "ev_multistep_unlock"])
    ledger.conflict("unlock.empress.exam", "Empress extra requirement",
                    "Academics Lv6 is modelled; first place in an exam is told to the player, not modelled",
                    ["Academics Lv6 + first place in an exam (aqiu)"], ["aqiu_social_events", "ev_romance"])
    ledger.decide("links", "empress exam", "aqiu requires Academics Lv6 and first place in one exam for Empress Rank 1 "
                  "(the CN guide only says 学力MAX, an omission). Exam placement cannot be forecast and is not part of the "
                  "player's recorded progress, so the pack models Academics Lv6 only and tells the player instead: the "
                  "unlock note, the step how-to and the 【女皇】 pack note all say a first place is needed and that the goal "
                  "should be set to 未选 when the player never placed first. This is a deliberate exception to the "
                  "strictest-claim rule: modelling it as unmet would drop Empress from every plan, and as met it is only "
                  "wrong for players the note addresses.")
    ledger.decide("links", "holds", "No mid-chain holds are encoded. Moon R9: aqiu says the link 'will momentarily "
                  "reverse'; the CN guide lists only one later meeting (MAX on 11/23) and no repair step, so the reversal "
                  "is treated as part of the scene and the 15-point R10 threshold already asks for extra meetings. Hierophant R4->R5: a single Steam report of a rumour gate is contradicted by the CN guide "
                  "(R4 on 5/14, R5 on 5/15). Both are documented in noteZh/notes; players can still report a hold.")
    return out


def build_unlock(spec: dict, link_id: str) -> dict:
    """Unlock record from an UNLOCKS spec; "fixed" day lists are filled in by resolve_fixed_days.

    Steps done at the link's own place ("avail") are emitted as explicit day lists too: the link's
    ordinary meeting days only (never its holiday invitations, which come from an established link),
    optionally narrowed to some day types (("avail", ["school"]) for steps that need the school open).
    """
    steps = []
    for s in spec.get("steps", []):
        days_spec = s["days"]
        entry = {
            "consumesSlot": s["consumesSlot"], "cost": s["cost"], "establishes": s["establishes"],
            "fromDay": day_of_md(s["fromMd"]) if s["fromMd"] else day_of_md(spec["earliest"]),
            "howZh": s["howZh"], "id": s["id"], "locationZh": s["locationZh"], "minGapDays": s["minGapDays"],
            "slot": s["slot"], "zh": s["zh"],
        }
        if days_spec == "any":
            entry["days"] = "any"
        elif days_spec == "avail" or days_spec[0] in ("avail", "fixed"):
            entry["days"] = "fixed"
            entry["fixedDays"] = []
            if days_spec == "avail":
                entry["fixedFrom"], entry["fixedTypes"] = link_id, []
            elif days_spec[0] == "avail":
                entry["fixedFrom"], entry["fixedTypes"] = link_id, list(days_spec[1])
            else:
                entry["fixedFrom"], entry["fixedTypes"] = days_spec[1], []
        else:
            entry["days"] = "weekdays"
            entry["weekdays"] = list(days_spec[1])
        steps.append(entry)
    return {
        "conf": spec["conf"], "earliestDay": day_of_md(spec["earliest"]), "link": dict(spec.get("link", {})),
        "noteZh": spec.get("noteZh"), "src": spec["src"], "stat": dict(spec.get("stat", {})), "steps": steps,
    }


def resolve_fixed_days(links: list[dict], days: list[dict], ledger: Ledger) -> None:
    """Fill fixedDays: the meeting days (avail, not invitations) of the link whose place the step uses."""
    by_id = {x["id"]: x for x in links}
    dtype = {d["d"]: d["type"] for d in days}
    for link in links:
        for s in link["unlock"]["steps"]:
            if s["days"] != "fixed":
                continue
            source_id = s.pop("fixedFrom")
            types = s.pop("fixedTypes")
            s["fixedDays"] = [d for d in by_id[source_id]["avail"]
                              if d >= s["fromDay"] and (not types or dtype[d] in types)]
            if not s["fixedDays"]:
                raise BuildError(f"{s['id']}: no day left for the step")
            basis = "own meeting days" if source_id == link["id"] else f"{source_id} meeting days"
            if types:
                basis += f" ({'/'.join(types)} only)"
            ledger.step_days[s["id"]] = basis
    ledger.decide("links", "unlock step days", "Unlock steps are emitted with explicit day lists (days=fixed): the "
                  "ordinary meeting days (aqiu O cells after filtering) of the link whose place the step uses, never the "
                  "holiday-invitation days (an invitation needs an established link, and on Sundays/vacations the school "
                  "is closed). hierophant.open is limited to school days: the persimmon leaf is in the school corridor "
                  "(aqiu Rank 1 steps, CN guide 4/25 after school), so Sundays, holidays and vacation days do not work "
                  "for that step even though the bookstore meetings do.")


# --------------------------------------------------------------------------------------------
# Activities (stat training, meals, jobs)

ALL_WEEK = [1, 2, 3, 4, 5, 6, 7]


def activity(ident: str, zh: str, kind: str, slots: list[str], weekdays: list[int], location: str, *,
             cost: int = 0, gain: dict | None = None, day_types: list[str] | None = None, from_md: str = "04-21",
             pay: list[list[int]] | None = None, requires: dict | None = None, conf: str = "verified",
             src: list[str], note: str | None = None, alt: list[str] | None = None, aqiu: str | None = None,
             evidence: tuple[str, str] | None = None) -> dict:
    return {
        "spec": {"aqiu": aqiu, "evidence": evidence},
        "record": {
            "alt": alt or [], "conf": conf, "cost": cost, "dayTypes": day_types or [], "fromDay": day_of_md(from_md),
            "gain": gain or {}, "id": ident, "kind": kind, "locationZh": location, "noteZh": note, "once": False,
            "pay": pay or [], "requires": requires or {}, "slots": slots, "src": src, "toDay": LAST_DAY,
            "weekdays": weekdays, "zh": zh,
        },
    }


ACTIVITIES = (
    activity("study_room", "在自己房间学习", "stat", ["eve"], ALL_WEEK, "宿舍 · 自己的房间", gain={"academics": 2},
             src=["aqiu_social_events", "ev_stats_actions", "cn_guide"], aqiu="Study at your desk*",
             evidence=("stats_actions.json", "study_room_night")),
    activity("study_room_day", "在自己房间学习", "stat", ["day"], ALL_WEEK, "宿舍 · 自己的房间", gain={"academics": 2},
             day_types=["sunday", "holiday", "vacation"], src=["aqiu_social_events"], conf="single",
             note="没课的日子白天也能在房间学习。"),
    activity("library", "到学校图书室自习", "stat", ["day"], [1, 2, 3, 4, 5, 6], "月光馆学园 · 图书室",
             gain={"academics": 2}, day_types=["school"], conf="conflict", src=["aqiu_social_events", "ev_stats_actions"],
             note="资料对学力增加量说法不一（+2 或 +4），这里按较少的 +2 计算。", alt=["+4 academics (aqiu, gf81170)"],
             aqiu="Study at library*"),
    activity("meal_wakatsu", "到定食若津点餐", "stat", ["day", "eve"], [1, 3, 4, 5, 6, 7], "严户台商店街 · 定食若津",
             cost=680, gain={"academics": 3}, src=["aqiu_social_events", "ev_stat_expansion", "cn_guide"],
             aqiu="Wakatsu: Prodigy Platter", evidence=("stat_activity_expansion_candidate.json", "meal_wakatsu_regular")),
    activity("meal_hagakure", "在叶隐拉面吃拉面", "stat", ["day", "eve"], [1, 2, 3, 4, 5], "严户台商店街 · 叶隐拉面",
             cost=900, gain={"charm": 3}, src=["aqiu_social_events", "ev_stat_expansion", "cn_guide"],
             aqiu="Hagakure: Pork Ramen", evidence=("stat_activity_expansion_candidate.json", "meal_hagakure_regular")),
    activity("meal_wilduck", "在怀老鸭汉堡吃汉堡", "stat", ["day", "eve"], [1, 2, 3, 5, 6, 7], "严户台商店街 · 怀老鸭汉堡",
             cost=1000, gain={"courage": 3}, src=["aqiu_social_events", "ev_stat_expansion", "cn_guide"],
             aqiu="Wilduck: Mystery Burger", evidence=("stat_activity_expansion_candidate.json", "meal_wilduck_regular")),
    activity("karaoke", "到卡拉OK唱歌", "stat", ["day", "eve"], [1, 2, 3, 4, 5, 6], "桐叶购物中心 · 卡拉OK",
             cost=800, gain={"courage": 2}, src=["aqiu_social_events", "ev_stat_expansion"],
             aqiu="Karaoke", evidence=("stat_activity_expansion_candidate.json", "karaoke_mall")),
    activity("arcade_answer", "在电玩大巡游游玩「你就是答案」", "stat", ["eve"], [3, 6], "桐叶购物中心 · 电玩大巡游",
             cost=3000, gain={"academics": 4}, conf="conflict", src=["aqiu_social_events", "ev_stats_actions", "cn_guide"],
             alt=["also daytime (aqiu B cells)"], aqiu="Arcade: You're the Answer",
             evidence=("stats_actions.json", "arcade_academics_night")),
    activity("arcade_youth", "在电玩大巡游游玩「青春高校」", "stat", ["eve"], [1, 4], "桐叶购物中心 · 电玩大巡游",
             cost=1500, gain={"charm": 4}, conf="conflict", src=["aqiu_social_events", "ev_stats_actions", "cn_guide"],
             alt=["also daytime (aqiu B cells)"], aqiu="Arcade: High School of Youth",
             evidence=("stats_actions.json", "arcade_charm_night")),
    activity("arcade_deceased", "在电玩大巡游游玩「死亡之怖」", "stat", ["eve"], [2, 5], "桐叶购物中心 · 电玩大巡游",
             cost=3000, gain={"courage": 4}, conf="conflict", src=["aqiu_social_events", "ev_stats_actions", "cn_guide"],
             alt=["also daytime (aqiu B cells)"], aqiu="Arcade: House of the Deceased",
             evidence=("stats_actions.json", "arcade_courage_night")),
    activity("movie_academics", "看提升学力的电影", "stat", ["day"], [3, 6], "港湾人工岛站 · 电影院",
             cost=1500, gain={"academics": 4}, src=["aqiu_social_events", "ev_stat_expansion"],
             note="星期三、六放映的片子能提升学力。", aqiu="Screenshot: Watch movies#academics",
             evidence=("stat_activity_expansion_candidate.json", "movie_academics_day")),
    activity("movie_charm", "看提升魅力的电影", "stat", ["day"], [2, 5], "港湾人工岛站 · 电影院",
             cost=1500, gain={"charm": 4}, src=["aqiu_social_events", "ev_stat_expansion"],
             note="星期二、五放映的片子能提升魅力。", aqiu="Screenshot: Watch movies#charm",
             evidence=("stat_activity_expansion_candidate.json", "movie_charm_day")),
    activity("movie_courage", "看提升勇气的电影", "stat", ["day"], [1, 4], "港湾人工岛站 · 电影院",
             cost=1500, gain={"courage": 4}, src=["aqiu_social_events", "ev_stat_expansion"],
             note="星期一、四放映的片子能提升勇气。", aqiu="Screenshot: Watch movies#courage",
             evidence=("stat_activity_expansion_candidate.json", "movie_courage_day")),
    activity("coffee_chagall", "在夏卡尔咖啡店喝费洛蒙咖啡", "stat", ["eve"], ALL_WEEK, "桐叶购物中心 · 夏卡尔咖啡店",
             cost=500, gain={"charm": 2}, src=["aqiu_social_events", "ev_stats_actions", "cn_guide"],
             aqiu="Chagall: Pheremone Coffee", evidence=("stats_actions.json", "coffee_chagall_night")),
    activity("coffee_chagall_day", "在夏卡尔咖啡店喝费洛蒙咖啡", "stat", ["day"], [3, 4, 5, 6, 7], "桐叶购物中心 · 夏卡尔咖啡店",
             cost=500, gain={"charm": 2}, src=["aqiu_social_events", "ev_stat_expansion", "cn_guide"],
             aqiu="Chagall: Pheremone Coffee", evidence=("stat_activity_expansion_candidate.json", "coffee_chagall_day")),
    activity("job_chagall", "在夏卡尔咖啡店打工", "job", ["eve"], [1, 2, 3], "桐叶购物中心 · 夏卡尔咖啡店",
             gain={"charm": 1, "courage": 1}, pay=[[1, 2500], [5, 3000], [15, 3500], [30, 5000]], from_md="05-04",
             src=["gd_arbeit_help", "aqiu_social_events", "ev_jobs", "megatenwiki_jobs", "cn_guide"],
             note="星期二客人多、工钱更高，这里按最低工钱估计。", alt=["+2 each stat (siliconera)"], aqiu="Chagall: Part-time Job"),
    activity("job_accessory", "在饰品店打工", "job", ["day"], [1, 2, 3, 4, 5], "桐叶购物中心 · 饰品店",
             gain={"academics": 1}, pay=[[1, 3500], [3, 4500], [6, 5500], [10, 7000]], from_md="07-24", conf="conflict",
             src=["gd_arbeit_help", "aqiu_social_events", "ev_jobs", "megatenwiki_jobs"],
             note="7月24日起招人。资料对另一项能力值说法不一（魅力或勇气），这里只计学力 +1。",
             alt=["+1 courage (aqiu)", "+1 charm (wiki469/megatenwiki)", "Saturdays too (aqiu)", "unlock 7/22-7/23 (siliconera, wiki469)"],
             aqiu="Be Blue V: Part-time Job*"),
    activity("job_cinema", "在电影院打工", "job", ["day"], [7], "港湾人工岛站 · 电影院",
             gain={"academics": 1}, pay=[[1, 5000], [3, 6000], [6, 7000], [10, 9000]], conf="conflict",
             src=["gd_arbeit_help", "aqiu_social_events", "ev_jobs", "megatenwiki_jobs"],
             note="星期日白天才招人。资料对另一项能力值说法不一（魅力或勇气），这里只计学力 +1。",
             alt=["+1 charm (aqiu)", "+1 courage (wiki469/megatenwiki; game help text mentions courage)", "Saturdays without school (aqiu)"],
             aqiu="Screenshot: Part-time Job*"),
)


def parse_aqiu_activities(page: AqiuPage) -> dict[str, dict]:
    """aqiu 'Social Stat Regular Activities' table: label -> gains, price, slots per ISO weekday."""
    start = page.text.find("Activity | Academics | Charm | Courage | Price")
    end = page.text.find("Social Stat Scheduled Activities")
    table = {}
    order = [7, 1, 2, 3, 4, 5, 6]  # Sun..Sat columns
    for line in page.text[start:end].splitlines()[1:]:
        cells = [c.strip() for c in line.split("|")]
        if len(cells) < 12:
            continue
        gains = {}
        for i, stat in enumerate(STAT_IDS):
            if cells[1 + i] != "-":
                gains[stat] = int(cells[1 + i])
        slots = {}
        for i, wd in enumerate(order):
            code = cells[5 + i].rstrip("*")
            slots[wd] = {"B": {"day", "eve"}, "D": {"day"}, "E": {"eve"}}.get(code, set())
        label = cells[0]
        if label == "Screenshot: Watch movies":
            label += "#" + next(iter(gains))
        price = 0 if cells[4] == "-" else int(cells[4])
        table[label] = {"gains": gains, "price": price, "slots": slots}
    return table


def audit_fallbacks(activities: list[dict], days: list[dict]) -> None:
    """Every free slot must offer a zero-cost activity that raises a stat (no forced idling)."""
    missing = []
    for day in days:
        for slot, lock in (("day", day["dayLock"]), ("eve", day["eveLock"])):
            if lock is not None:
                continue
            ok = any(
                a["cost"] == 0 and a["kind"] == "stat" and slot in a["slots"] and day["wd"] in a["weekdays"]
                and a["fromDay"] <= day["d"] <= a["toDay"] and (not a["dayTypes"] or day["type"] in a["dayTypes"])
                for a in activities
            )
            if not ok:
                missing.append(f"{day['md']} {slot}")
    if missing:
        raise BuildError("free slots without a zero-cost activity: " + ", ".join(missing))


def build_activities(ledger: Ledger, page: AqiuPage, guide_text: str, days: list[dict]) -> list[dict]:
    aqiu = parse_aqiu_activities(page)
    help_text = load_pay_help()
    jobs = {j["ruleId"]: j for j in read_evidence("jobs_wage_rules_candidate.json")["jobs"]}
    job_tiers = {
        "job_chagall": ("p3r.job.chagall", "MSG_ARBEIT_CAFE", "Night"),
        "job_accessory": ("p3r.job.be_blue_v", "MSG_ARBEIT_SKILLSHOP", "Day"),
        "job_cinema": ("p3r.job.screen_shot", "MSG_ARBEIT_MOVIE", "Day"),
    }
    out = []
    for entry in ACTIVITIES:
        rec = dict(entry["record"])
        spec = entry["spec"]
        if spec["aqiu"]:
            row = aqiu[spec["aqiu"]]
            for stat, value in rec["gain"].items():
                if value > row["gains"].get(stat, 0):
                    raise BuildError(f"{rec['id']}: gain {stat}+{value} exceeds aqiu {row['gains']}")
            if rec["kind"] == "stat" and rec["cost"] != row["price"]:
                raise BuildError(f"{rec['id']}: cost {rec['cost']} != aqiu {row['price']}")
            for wd in rec["weekdays"]:
                for slot in rec["slots"]:
                    if slot not in row["slots"][wd]:
                        raise BuildError(f"{rec['id']}: aqiu has no {slot} slot on weekday {wd}")
        if spec["evidence"]:
            name, action_id = spec["evidence"]
            ev = read_evidence(name)
            action = next(a for a in ev["actions"] if a["actionId"] == action_id)
            if action["yenCost"] != rec["cost"] or action["statDelta"] != rec["gain"]:
                raise BuildError(f"{rec['id']}: evidence {action_id} cost/gain differ")
            if not set(rec["weekdays"]) <= set(action["isoWeekdays"]):
                raise BuildError(f"{rec['id']}: weekdays exceed evidence {action_id}")
        if rec["id"] in job_tiers:
            rule_id, msg, slot_word = job_tiers[rec["id"]]
            normal = [[t["shiftInclusiveMin"], t["normal"]] for t in jobs[rule_id]["reportedWageTiers"]]
            if normal != rec["pay"]:
                raise BuildError(f"{rec['id']}: pay tiers differ from {rule_id}")
            help_line = help_text[msg]
            if f"Time: {slot_word}" not in help_line or f"¥{rec['pay'][0][1]:,}+" not in help_line:
                raise BuildError(f"{rec['id']}: game help text {msg} disagrees on slot or base pay")
        bad = location_tokens_ok(rec["locationZh"], guide_text)
        if bad:
            raise BuildError(f"{rec['id']}: location fragments {bad} not found in the CN guide")
        out.append(rec)
    ledger.decide("activities", "selection", "Planned activities: desk study (evening; daytime on days without school), "
                  "library (school days, +2 lower bound), Wakatsu/Hagakure/Wilduck regular meals, karaoke, the three arcade "
                  "games (evening only, the stricter reading), the three weekday movie programmes, Chagall coffee, and the "
                  "three jobs. Every row is checked against the aqiu table (gains never above aqiu, same price, slots a "
                  "subset) and, where present, the evidence files and the game's job help text.")
    ledger.decide("activities", "excluded", "Not planned: Wakatsu seafood course, Hagakure bowl and the weekend Wilduck set "
                  "(their unlock needs an observed menu event or Request #11 that the model cannot express), the one-off "
                  "burger challenge (needs Requests #1/#9/#11), shrine donations (single source, unclear Reload value), "
                  "dorm hangouts (DESIGN §10.10), film-festival hangouts and shrine fortunes. Link Episodes are opt-in tracks "
                  "(`episodes`) and the dating-site note is `datingNote`, both outside `activities`.")
    audit_fallbacks(out, days)
    ledger.decide("activities", "no idle slots", "Every unlocked slot from 4/21 offers at least one free productive "
                  "activity (evening desk study; library after school; desk study in the daytime when there is no "
                  "school), so a plan never has to leave a slot idle; jobs are the only forecast income and beat "
                  "idling whenever money is useful. The builder fails if a free slot lacks such a fallback. Reload has "
                  "no fatigue or sickness, so a Tartarus night carries no next-day penalty.")
    ledger.decide("activities", "jobs", "Jobs pay the conservative non-busy tier per completed shift (wage tables from "
                  "megatenwiki/wiki469; base pay confirmed by the game help text: café ¥2,500+ at night, accessory shop "
                  "¥3,500+ by day, cinema ¥5,000+ by day). Chagall from 5/4 (first CN-guide witness; earliest date "
                  "otherwise unknown), accessory shop Mon–Fri from 7/24, cinema on Sundays. Where sources disagree on "
                  "the second stat only Academics +1 is credited.")
    return out


# --------------------------------------------------------------------------------------------
# Link Episodes (羁绊剧情) and the dating-site note (结缘网站的字条)


# Opt-in Link Episode tracks. Each comes from one aqiu calendar column whose cells name the episode
# numbers that can be watched that day ("1", "1-4"); the per-episode windows and rewards come from the
# aqiu "Linked Episodes" tables. Ryoji (preset dates, first and last episodes automatic) and Takaya
# (not in the calendar, mostly automatic) are story-driven and are not tracks.
EPISODE_TRACKS = (
    {
        "id": "junpei", "column": "Jun", "evening": False, "section": "junpei", "name": "Junpei Iori",
        "location": "月光馆学园 · 2 楼教室",
        "how": "放学后到 2 楼教室找伊织顺平说话；有的几话是他发消息约你，打开手机答应就行。",
        "note": "第 3 话之后可以到港湾人工岛的花店买「白色切花」（250 日元）送给顺平，不会消耗时间；送了的话 1月21日放学后"
                "会有剧情，这里已经先把那天空出来了。",
        "reward": "每一话都有小奖励（能力值或顺平的能力），看完第 5 话可以合体人格面具「苏尔特尔」。",
        "fusion": ("Surt", "苏尔特尔"),
        # CN guide (assets/data.json): ep1 5/12 classroom, ep2 8/9 invitation, ep3 11/7 invitation,
        # ep4 12/19 classroom, ep5 1/15 invitation (苏尔特尔).
        "witness": {1: "05-12", 2: "08-09", 3: "11-07", 4: "12-19", 5: "01-15"},
    },
    {
        "id": "akihiko", "column": "Aki", "evening": True, "section": "akihiko", "name": "Akihiko Sanada",
        "location": "严户台站",
        "how": "晚上打开手机，答应真田明彦的邀约就行。",
        "note": None,
        "reward": "每一话都有小奖励（主人公的能力、魅力或道具），看完第 5 话可以合体人格面具「荷鲁斯」。",
        "fusion": ("Horus", "荷鲁斯"),
        # CN guide: invitations 5/29, 7/24, 10/9, 12/12 and 1/8 (荷鲁斯).
        "witness": {1: "05-29", 2: "07-24", 3: "10-09", 4: "12-12", 5: "01-08"},
    },
    {
        "id": "koromaru", "column": "Kor", "evening": False, "section": "koromaru", "name": "Koromaru",
        "location": "宿舍 · 大门口",
        "how": "白天到宿舍大门口找虎狼丸；有时队友会先发消息叫你去找它。",
        "note": None,
        "reward": "每一话都有小奖励（能力值或虎狼丸的能力），看完第 5 话可以合体人格面具「白虎」。",
        "fusion": ("Byakko", "白虎"),
        # CN guide: 9/22 and 1/5 at the dorm entrance, 1/13 and 1/19 (白虎) after a message.
        "witness": {5: "01-19"},
    },
    {
        "id": "ken", "column": "Ken", "evening": True, "section": "ken", "name": "Ken Amada",
        "location": "宿舍",
        "how": "晚上在宿舍找天田乾：前两话在 2 楼他的房间，之后他会发消息约你，打开手机答应就行。",
        "note": None,
        "reward": "每一话都有小奖励（能力值或主人公的能力），看完第 5 话可以合体人格面具「米迦勒」。",
        "fusion": ("Michael", "米迦勒"),
        # CN guide: 9/9 and 9/16 in his room, invitations 11/10, 12/22 and 1/19 (米迦勒).
        "witness": {1: "09-09", 2: "09-16", 3: "11-10", 4: "12-22", 5: "01-19"},
    },
    {
        "id": "shinjiro", "column": "Shi", "evening": False, "section": "shinjiro", "name": "Shinjiro Aragaki",
        "location": "港湾人工岛站 · 站前广场角落",
        "how": "到港湾人工岛站前广场的角落找荒垣真次郎。第 1 话要在上学日的放学后，先到 1 楼教职员室前找桐条美鹤拿申请书再去；"
               "有一话是他发消息约你，打开手机答应就行。",
        "note": "看第 4 话之前，要先在放学后到 1 楼教职员室前找桐条美鹤，最后问「真的要把这个交给她吗？」时选「还是再等等」，"
                "不会消耗时间。第 5 话不用另外安排：10月5日以后到宿舍 2 楼他的房间调查书桌抽屉就行，之后可以合体人格面具「地狱天使」。",
        "reward": "每一话都有小奖励（能力值），之后的第 5 话可以合体人格面具「地狱天使」。",
        "fusion": ("Hell Biker", "地狱天使"),
        # Episodes 1 and 4 start at the school (Mitsuru in the faculty hallway): school days only.
        "school_only": (1, 4),
        # Episode 5 (his desk drawer from 10/5) is not a calendar slot; the note explains it.
        "beyond_calendar": (5,),
        # CN guide: 9/11 after school (Mitsuru, then him), 9/13 invitation, 9/21 at the plaza, 9/24 Mitsuru + plaza.
        "witness": {1: "09-11", 2: "09-13", 3: "09-21", 4: "09-24"},
    },
)
EPISODE_STAT_WORDS = {"Academics": "academics", "Charm": "charm", "Courage": "courage"}
STAT_ZH = {"academics": "学力", "charm": "魅力", "courage": "勇气"}


def parse_cell_numbers(cell: str) -> set[int]:
    """Episode numbers allowed by one calendar cell: '-' none, '3' one, '1-4' a range."""
    if cell in ("-", ""):
        return set()
    m = re.fullmatch(r"(\d)(?:-(\d))?", cell)
    if not m:
        raise BuildError(f"unexpected Link Episode calendar cell {cell!r}")
    first = int(m.group(1))
    last = int(m.group(2) or first)
    return set(range(first, last + 1))


def episode_table(page: AqiuPage, section: str) -> dict[int, dict]:
    """aqiu Linked Episodes table of one character: episode number -> window, gains, fusion, other rewards."""
    rows: dict[int, dict] = {}
    for line in page.section(section).splitlines():
        m = re.match(r"^(\d+(?:\.5)?) \| (.*?) \| (.*?) \|", line.strip())
        if not m or "." in m.group(1):
            continue
        no = int(m.group(1))
        items, availability = m.group(2), m.group(3)
        w = re.match(r"(\d+)/(\d+)(?:-(\d+)/(\d+))?", availability)
        if not w:
            raise BuildError(f"{section} episode {no}: no availability window in {availability!r}")
        first = day_of(int(w.group(1)), int(w.group(2)))
        last = day_of(int(w.group(3)), int(w.group(4))) if w.group(3) else first
        gain: dict[str, int] = {}
        fusion = None
        other = False
        for item in [x.strip() for x in re.split(r",\s*(?:and\s+)?|\s+and\s+", items) if x.strip() and x.strip() != "-"]:
            sm = re.fullmatch(r"(Academics|Charm|Courage) \+(\d+)", item)
            fm = re.fullmatch(r"(.+) unlocked", item)
            if sm:
                gain[EPISODE_STAT_WORDS[sm.group(1)]] = int(sm.group(2))
            elif fm:
                fusion = fm.group(1)
            else:
                other = True
        rows[no] = {"from": first, "to": last, "gain": gain, "fusion": fusion, "other": other}
    if not rows:
        raise BuildError(f"aqiu Linked Episodes table {section} not found")
    return rows


def build_episodes(ledger: Ledger, page: AqiuPage, days: list[dict], guide_text: str) -> list[dict]:
    names = load_names()["people"]
    by_day = {x["d"]: x for x in days}
    out = []
    for spec in EPISODE_TRACKS:
        cells = page.column(spec["evening"], spec["column"])
        table = episode_table(page, spec["section"])
        allowed = {d: parse_cell_numbers(c) for d, c in cells.items()}
        count = max(n for ns in allowed.values() for n in ns)
        slot_key = "eveLock" if spec["evening"] else "dayLock"
        parts = []
        dropped = []
        for no in range(1, count + 1):
            if no not in table:
                raise BuildError(f"{spec['id']} episode {no} is in the calendar but not in the episode table")
            row = table[no]
            kept = []
            for d in sorted(d for d, ns in allowed.items() if no in ns):
                if by_day[d][slot_key] is not None:
                    dropped.append(f"#{no} {md_of(d)} locked ({by_day[d][slot_key]})")
                elif not row["from"] <= d <= row["to"]:
                    dropped.append(f"#{no} {md_of(d)} outside the episode window {md_of(row['from'])}-{md_of(row['to'])}")
                elif no in spec.get("school_only", ()) and by_day[d]["type"] != "school":
                    dropped.append(f"#{no} {md_of(d)} not a school day")
                else:
                    kept.append(d)
            if not kept:
                raise BuildError(f"{spec['id']} episode {no}: no day left")
            reward = []
            for stat, value in sorted(row["gain"].items(), key=lambda kv: STAT_IDS.index(kv[0])):
                reward.append(f"{STAT_ZH[stat]} +{value}")
            if row["fusion"]:
                if row["fusion"] != spec["fusion"][0]:
                    raise BuildError(f"{spec['id']}: fusion {row['fusion']} != {spec['fusion'][0]}")
                reward.append(f"可以合体人格面具「{spec['fusion'][1]}」")
            if row["other"] and reward:
                reward.append("还有其他小奖励")
            parts.append({"days": kept, "gain": row["gain"], "no": no, "rewardZh": "，".join(reward) if reward else None})
        for no, md in sorted(spec["witness"].items()):
            if day_of_md(md) not in parts[no - 1]["days"]:
                raise BuildError(f"{spec['id']} episode {no}: CN guide witness {md} is not an allowed day")
        fusion_zh = spec["fusion"][1]
        if fusion_zh not in guide_text:
            raise BuildError(f"{spec['id']}: Persona name {fusion_zh} not found in the CN guide")
        if spec["fusion"][0] not in {table[n]["fusion"] for n in table}:
            raise BuildError(f"{spec['id']}: the episode table never unlocks {spec['fusion'][0]}")
        person = names[spec["name"]]
        bad = location_tokens_ok(spec["location"], guide_text)
        if bad:
            raise BuildError(f"{spec['id']}: location fragments {bad} not found in the CN guide")
        out.append({
            "conf": "verified",
            "count": count,
            "evening": spec["evening"],
            "howZh": spec["how"],
            "id": spec["id"],
            "locationZh": spec["location"],
            "noteZh": spec["note"],
            "parts": parts,
            "personZh": person,
            "rewardZh": spec["reward"],
            "src": ["aqiu_social_events", "cn_guide", "gd_player_name"],
            "titleZh": f"羁绊剧情 · {person}",
        })
        ledger.dropped["episode." + spec["id"]] = dropped
        beyond = sorted(n for n in table if n > count)
        if beyond != list(spec.get("beyond_calendar", ())):
            raise BuildError(f"{spec['id']}: episodes {beyond} are in the table but never in the calendar")
    ledger.decide("episodes", "tracks", "Link Episodes (羁绊剧情) are opt-in tracks for Junpei, Akihiko, Koromaru, Ken and "
                  "Shinjiro, parsed from the aqiu calendar columns Jun/Kor/Shi (daytime) and Aki/Ken (nighttime). A cell names "
                  "the episode numbers that can be watched that day ('3' or '1-4'); each episode must also fall inside its "
                  "window in the aqiu Linked Episodes table, and locked slots are dropped. Episodes run in order, take one slot "
                  "of the track's kind and give no Social Link points; `gain` holds the Academics/Charm/Courage points of the "
                  "table (other rewards are only described). Every CN-guide witness date (5/12, 8/9, 11/7, 12/19, 1/15 Junpei; "
                  "5/29, 7/24, 10/9, 12/12, 1/8 Akihiko; 9/9, 9/16, 11/10, 12/22, 1/19 Ken; 9/11, 9/13, 9/21, 9/24 Shinjiro; "
                  "1/19 Koromaru) is an allowed day.")
    ledger.decide("episodes", "excluded", "Ryoji's column is not a track: his availability is 'Preset', episode 1 (11/9) "
                  "and episode 5 (12/31) are automatic story scenes, and the slots in between are fixed dates around the "
                  "story. Takaya's episodes are automatic or a single club meeting and are not in the calendar. Shinjiro's "
                  "episode 5 (his desk drawer from 10/5, the CN guide does it on 10/28) is not a calendar slot, so his track "
                  "has 4 plannable episodes; the note explains the drawer. Nothing here claims that an episode changes "
                  "Shinjiro's story: in Reload it does not.")
    ledger.decide("episodes", "school days", "Shinjiro episodes 1 and 4 start with Mitsuru in the faculty hallway (aqiu "
                  "steps; CN guide 9/11 and 9/24 after school), so they are kept on school days only.")
    return out


def build_dating_note(ledger: Ledger, days: list[dict], guide_text: str) -> dict:
    """结缘网站的字条: evening, +10 points for one established non-MAX link, never a rank-up by itself."""
    first = day_of_md("11-11")
    by_day = {x["d"]: x for x in days}
    if by_day[first]["eveLock"] is not None:
        raise BuildError("11/11 evening is locked, but the CN guide uses the dating-site note that evening")
    for word in ("结缘网站的字条", "放纵俱乐部", "公用电脑"):
        if word not in guide_text:
            raise BuildError(f"dating-site note wording {word!r} not found in the CN guide")
    affinity = read_evidence("affinity_actions_candidate.json")
    buy = next(a for a in affinity["actions"] if a["ruleId"] == "p3r.affinity.buy_and_use_dating_note")
    if buy["yenCost"] != 3000 or buy["affinityEffect"]["value"] != 10 or buy["rankDelta"] != 0 or buy["slot"] != "evening":
        raise BuildError("dating-site note evidence changed (cost / gain / slot)")
    if buy["sourceEarlierDateClaim"] != "11-11":
        raise BuildError("dating-site note sale date evidence changed")
    note = {
        "conf": "estimate",
        "cost": 3000,
        "fromDay": first,
        "gain": 10,
        "howZh": "晚上到桐叶购物中心的放纵俱乐部买「结缘网站的字条」，回宿舍用 1 楼公用电脑选一位社群对象使用。",
        "locationZh": "桐叶购物中心 · 放纵俱乐部",
        "maxUses": None,
        "noteZh": "只能给已经解锁、还没 MAX 的社群加好感，约 +10 点（估计值）；不会直接升级，会用掉这个晚上。可以反复购买。",
        "requires": {"courage": 2},
        "slot": "eve",
        "src": ["aqiu_overworld", "cn_guide", "ev_dating_note", "ev_hangout_rules", "siliconera_urls", "wiki455"],
        "zh": "结缘网站的字条",
    }
    bad = location_tokens_ok(note["locationZh"], guide_text)
    if bad:
        raise BuildError(f"dating-site note location fragments {bad} not found in the CN guide")
    ledger.decide("datingNote", "rule", "结缘网站的字条 (Dating Site Note): bought from the URL seller in Club Escapade for "
                  "¥3,000 and used the same evening on the dorm's shared PC; +10 points to one established, non-MAX, "
                  "non-reversed manual link (aqiu overworld, game8, affinity_actions_candidate.json); it never ranks the link "
                  "up by itself. Points are added without multipliers (conf=estimate: the Persona/Charm bonus on notes is not "
                  "proven). The club needs Courage Lv2 at night (siliconera; the CN guide notes the club opens at night from Courage Lv2).")
    ledger.decide("datingNote", "sale date", "From 11/11: aqiu overworld, game8 and the Steam guide say 11/11 and the CN "
                  "guide uses a note on the evening of 11/11 for 山岸风花 (a play witness). atwiki 455 says 11/16; that "
                  "stricter claim is outvoted by the witness.")
    ledger.decide("datingNote", "repeatable", "maxUses = null (no limit): siliconera ('can be purchased multiple times'), "
                  "atwiki 455 ('infinitely purchasable', via ordinary_hangout_rules_candidate.json) and PC Invasion agree "
                  "that the note can be bought again; each use still takes an evening.")
    ledger.conflict("datingNote.from", "Dating-site note sale date", "11/11 (aqiu, game8, Steam guide, CN guide witness)",
                    ["11/16 (atwiki 455)"], ["aqiu_overworld", "cn_guide", "wiki455"])
    return note


# --------------------------------------------------------------------------------------------
# Pack assembly


# Player-facing summary of the planning assumptions (shown under 规划说明). Developer detail lives in REPORT.md.
NOTES_ZH = (
    "【数据来源】社群升级所需点数、个人能力值门槛、加成倍率、休息日邀约日期和塔尔塔罗斯各区块的开放日期，都取自从游戏本体"
    "提取的数据（不含付费追加剧情）；每天能见到谁取自玩家社区整理的日历，并和原版中文攻略逐日核对。",
    "【普通见面】没有升级的见面按 +10 点估计（只有一份实况记录提到，未经验证）；魅力 MAX 以后点数再 ×1.51（游戏数据）。",
    "【人格面具】见面时带着同阿尔卡那的人格面具，点数 ×1.51（游戏数据）。默认按不带来估计；可以在规划设置里改成"
    "「只给目标社群带」或「基本都带」。",
    "【日历】资料里没提到有剧情的白天和晚上，都按可以自由活动来安排。还没确认有没有剧情的日子"
    "（屋久岛 7月20日～23日、11月4日、1月1日白天、1月21日放学后）先空出来，不排别的事。",
    "【平安夜】12月24日默认按婉拒所有邀约来安排，白天和晚上都可以自由活动。",
    "【塔尔塔罗斯】队友都去不了的晚上（考试前后、剧情前后等）不会安排探索塔尔塔罗斯。",
    "【失踪者】北村文吉（9月12日～10月3日）和舞子（10月21日～11月2日）失踪期间见不到人，期限前没有在塔尔塔罗斯救出，"
    "对应的社群就没法继续了。",
    "【女皇】解锁除了学力 MAX，还要至少有一次考试拿过第一名。名次没法预测，这里只按学力 MAX 来安排；如果一次第一都没拿过，"
    "请把女皇的目标改成「未选」。星期一、五能不能见面没有确认，只按星期二、三、四、六和考试前一周（12月7日～12日）安排。",
    "【教皇】解锁时要回学校调查柿子树，所以只安排在上学日的放学后。有玩家反映 RANK 4 → 5 前要先听到柿子树的传闻，"
    "但原版中文攻略是连续两天直接升级，所以不额外限制。",
    "【恶魔】隐者社群的要求按较严格的 RANK 4 计算（也有资料说 RANK 3）；三次付钱要分在不同的晚上，前两次不会消耗时间。",
    "【塔】勇气要求按较严格的 Lv.4 计算（也有资料说 Lv.2 或 Lv.3）。",
    "【太阳】最早按 8月9日开始（也有资料说 8月23日）。",
    "【月亮】RANK 9 的剧情结束时社群会短暂逆转，之后照常见面就能恢复（只有一份资料提到），按普通升级处理。",
    "【图书室】自习的学力增加量资料不一（+2 或 +4），按 +2 计算。",
    "【打工】工钱按最低档估计；饰品店和电影院打工的第二项能力值资料不一，只计学力 +1。",
    "【结缘网站的字条】11月11日起，晚上可以在放纵俱乐部买结缘网站的字条（3,000 日元），回宿舍用公用电脑给一位已经解锁、"
    "还没 MAX 的社群加好感，约 +10 点（估计值）。它不会直接升级，会用掉这个晚上，可以反复购买。",
    "【羁绊剧情】伊织顺平、真田明彦、虎狼丸、天田乾和荒垣真次郎的羁绊剧情可以选择加进安排：每一话用掉一个白天或晚上，"
    "不加社群点数，奖励是能力值和道具，看完最后一话还能合体新的人格面具。望月绫时的羁绊剧情跟着主线在固定日子发生，不单独安排。",
    "【对话】对话提示只引用原版中文攻略的原文；括号里的数字是攻略的标注，不一定对应游戏里的选项顺序。",
    "【人名】采用官方简体中文译名：贝培、伊莉莎白、Ｙ子、舞子；审判社群为避免剧透显示为「伙伴们」。",
    "【未计入】课堂答题、考试名次、暑期补习和宿舍活动带来的能力值不在预测里，请在「同步现状」时更新。",
)


def build_pack() -> tuple[dict, Ledger]:
    ledger = Ledger()
    ledger.decide("scope", "editions", "Only the main game (Xrd777) is described. Astrea/Episode Aigis tables are "
                  "not used; FES/Portable material that the evidence files already rejected stays out (gorillawiki "
                  "entry 103, the FES July calendar, atwiki page 36, P3P rank tables, samurai-gamers reversal "
                  "mechanics, the Persona 4 '+10' paragraph, megatenwiki's Reload calendar with wrong full moons). "
                  "Reload has no female-protagonist route, so no such links exist.")
    ledger.decide("scope", "precedence", "Evidence order: game-data tables > aqiu calendar cells cross-checked "
                  "with the atwiki tables and the CN guide > single reports. When sources conflict the strictest "
                  "Reload claim is used (fewer meeting days, higher requirements, lower gains) and the conflict is "
                  "listed in section 3.")
    page = AqiuPage(SOURCES_DIR / "aqiu_pages" / "social-events.html")
    day_cells = page.day_cells()
    night_cells = page.night_cells()
    check_day_calendar_json(day_cells)
    guide_text = json.dumps(json.loads(GUIDE_PATH.read_text(encoding="utf-8")), ensure_ascii=False)

    sources = build_sources()
    days, tartarus = build_calendar(ledger, day_cells, night_cells, page.tartarus_cells())
    links = build_links(ledger, page, days, guide_text)
    resolve_fixed_days(links, days, ledger)
    activities = build_activities(ledger, page, guide_text, days)
    episodes = build_episodes(ledger, page, days, guide_text)
    dating_note = build_dating_note(ledger, days, guide_text)

    stat_thresholds = load_stat_thresholds()
    expected = {"academics": [0, 0, 20, 55, 100, 155, 230], "charm": [0, 0, 15, 30, 45, 70, 100], "courage": [0, 0, 15, 30, 45, 60, 80]}
    if stat_thresholds != expected:
        raise BuildError(f"HeroParameterDataAsset thresholds changed: {stat_thresholds}")
    stats = [
        {"conf": "game_data", "id": "academics", "src": ["gd_hero_parameter", "ev_stats_actions"], "thresholds": stat_thresholds["academics"], "zh": "学力"},
        {"conf": "game_data", "id": "charm", "src": ["gd_hero_parameter", "ev_stats_actions"], "thresholds": stat_thresholds["charm"], "zh": "魅力"},
        {"conf": "game_data", "id": "courage", "src": ["gd_hero_parameter", "ev_stats_actions"], "thresholds": stat_thresholds["courage"], "zh": "勇气"},
    ]
    mult = load_multipliers()
    policy = {
        "charmMult": mult["CMM_POINTBONUS_CHARM"],
        "conf": "estimate",
        "examGoodMult": mult["EVT_EXAM_RESULT_GOOD"],
        "examTopMult": mult["EVT_EXAM_RESULT_TOP"],
        "multConf": "game_data",
        "ordinaryGain": 10,
        "ordinaryGainConf": "estimate",
        "personaMult": mult["CMM_POINTBONUS_ARCANA"],
        "rounding": "floor_per_meeting",
        "src": ["gd_general_coefficient", "ev_ordinary_gain", "lpbeach", "ev_affinity"],
    }
    ledger.decide("policy", "ordinaryGain", "Ordinary (non-rank-up) meeting = +10 points: a single Reload playthrough "
                  "report, consistent with the two notes shown for a normal hangout at 5 points per note (atwiki 455). "
                  "conf=estimate. Multipliers 1.51 (matching Persona), 1.51 (Charm Lv6), 1.51/1.21 (exam) are game data; "
                  "rounding is unknown, so the engine floors per meeting.")
    pack = {
        "activities": activities,
        "datingNote": dating_note,
        "days": days,
        "episodes": episodes,
        "firstDay": FIRST_DAY,
        "game": GAME,
        "invitesMeaning": "Holiday invitation (D cell): the link calls you on that day; accepting uses the daytime slot "
                          "and counts as a normal meeting/rank-up.",
        "lastDay": LAST_DAY,
        "links": links,
        "lockReasons": {k: {"zh": v} for k, v in LOCK_REASONS.items()},
        "notes": list(NOTES_ZH),
        "packVersion": PACK_VERSION,
        "policy": policy,
        "schema": SCHEMA,
        "sources": sources,
        "stats": stats,
        "tartarus": tartarus,
    }
    validate_pack(pack)
    return pack, ledger


def validate_pack(pack: dict) -> None:
    """Self-check of the invariants the Java loader enforces, plus content rules."""
    days = pack["days"]
    if [d["d"] for d in days] != list(range(FIRST_DAY, LAST_DAY + 1)):
        raise BuildError("days are not contiguous")
    if [x["id"] for x in pack["links"]] != list(LINK_IDS):
        raise BuildError("links are not in arcana order")
    source_ids = {s["id"] for s in pack["sources"]}
    by_day = {d["d"]: d for d in days}

    def check_src(where: str, record: dict) -> None:
        if record.get("conf") not in CONF_VALUES:
            raise BuildError(f"{where}: conf {record.get('conf')!r} invalid")
        unknown = [s for s in record.get("src", []) if s not in source_ids]
        if unknown or not record.get("src"):
            raise BuildError(f"{where}: unknown or empty src {unknown}")

    for d in days:
        check_src(f"day {d['md']}", d)
        for key in ("dayLock", "eveLock"):
            if d[key] is not None and d[key] not in pack["lockReasons"]:
                raise BuildError(f"day {d['md']}: unknown lock {d[key]}")
    for link in pack["links"]:
        check_src(f"link {link['id']}", link)
        check_src(f"link {link['id']}.unlock", link["unlock"])
        if len(link["threshold"]) != 11 or len(link["eventGain"]) != 11:
            raise BuildError(f"link {link['id']}: arrays must have 11 entries")
        slot_key = "eveLock" if link["slot"] == "eve" else "dayLock"
        for d in link["avail"]:
            if by_day[d][slot_key] is not None:
                raise BuildError(f"link {link['id']}: meeting on locked {md_of(d)}")
        for d in link["invite"]:
            if by_day[d]["dayLock"] is not None:
                raise BuildError(f"link {link['id']}: invitation on locked {md_of(d)}")
    for act in pack["activities"]:
        check_src(f"activity {act['id']}", act)
        if not act["zh"] or not act["locationZh"]:
            raise BuildError(f"activity {act['id']}: missing Chinese name or location")
    for block in pack["tartarus"]["blocks"]:
        check_src(f"block {block['id']}", block)
    for rescue in pack["tartarus"]["rescues"]:
        check_src(f"rescue {rescue['id']}", rescue)
    check_src("policy", pack["policy"])
    for stat in pack["stats"]:
        check_src(f"stat {stat['id']}", stat)
    ids = set()
    for track in pack["episodes"]:
        where = f"episode {track['id']}"
        check_src(where, track)
        if track["id"] in ids or not re.fullmatch(r"[a-z_]+", track["id"]):
            raise BuildError(f"{where}: duplicated or malformed id")
        ids.add(track["id"])
        if [x["no"] for x in track["parts"]] != list(range(1, track["count"] + 1)) or track["count"] > 8:
            raise BuildError(f"{where}: parts must be numbered 1..count (count <= 8)")
        if not track["titleZh"].startswith("羁绊剧情 · ") or not track["locationZh"] or not track["howZh"]:
            raise BuildError(f"{where}: title, location and how-to are required")
        slot_key = "eveLock" if track["evening"] else "dayLock"
        for part in track["parts"]:
            if not part["days"] or part["days"] != sorted(set(part["days"])):
                raise BuildError(f"{where} #{part['no']}: days must be non-empty and strictly ascending")
            for d in part["days"]:
                if by_day[d][slot_key] is not None:
                    raise BuildError(f"{where} #{part['no']}: episode on locked {md_of(d)}")
            if not set(part["gain"]) <= set(STAT_IDS):
                raise BuildError(f"{where} #{part['no']}: unknown stat in gain {part['gain']}")
    note = pack["datingNote"]
    check_src("datingNote", note)
    if note["slot"] != "eve" or note["cost"] <= 0 or note["gain"] <= 0 or by_day[note["fromDay"]]["eveLock"] is not None:
        raise BuildError("datingNote: evening use, positive cost/gain and a free first evening are required")
    if note["maxUses"] is not None and note["maxUses"] < 1:
        raise BuildError("datingNote.maxUses must be null (no limit) or positive")
    text = json.dumps(pack, ensure_ascii=False)
    for word in FORBIDDEN_STRINGS:
        if word in text:
            raise BuildError(f"pack contains the outdated term {word!r}")
    for where, value in player_texts(pack):
        latin = [w for w in re.findall(r"[A-Za-z]+", value) if w not in ALLOWED_LATIN]
        if latin:
            raise BuildError(f"{where}: player-facing text contains Latin words {latin}: {value}")
        plain = value
        for allowed in GLOSSARY_ALLOWED:
            plain = plain.replace(allowed, "")
        off = [w for w in GLOSSARY_AVOID if w in plain]
        if off:
            raise BuildError(f"{where}: player-facing text uses {off} (docs/GLOSSARY_zh.md): {value}")


# Words docs/GLOSSARY_zh.md tells us not to use in player-facing text (解锁, 见面, 安排, MAX, 羁绊剧情 …),
# and the menu names that legitimately contain 规划.
GLOSSARY_AVOID = (
    "开启", "建立", "练满", "满级", "计划", "规划", "羁绊剧场", "估算", "规则包", "社交链接", "交友网站", "牵绊",
    "不占用时段", "锁定", "约会", "会面", "加深关系", "Lv.6",
)
GLOSSARY_ALLOWED = ("规划设置", "规划说明")

# Latin tokens allowed in player-facing Chinese text: stat levels, ranks, floors, karaoke, and the
# item name 莫乐蜜G as written in the CN guide.
ALLOWED_LATIN = ("Lv", "RANK", "MAX", "F", "OK", "G")


def player_texts(node, path: str = "") -> list[tuple[str, str]]:
    """(path, text) of every player-facing string: keys 'zh', '*Zh' and 'notes'; dialogue is verbatim guide text."""
    found = []
    if isinstance(node, dict):
        for key, value in node.items():
            child = f"{path}.{key}" if path else key
            if key == "dialogue":
                continue
            if (key == "zh" or key.endswith("Zh")) and isinstance(value, str):
                found.append((child, value))
            elif key == "notes" and isinstance(value, list):
                found.extend((f"{child}[{i}]", v) for i, v in enumerate(value))
            else:
                found.extend(player_texts(value, child))
    elif isinstance(node, list):
        for i, value in enumerate(node):
            found.extend(player_texts(value, f"{path}[{i}]"))
    return found


# --------------------------------------------------------------------------------------------
# Output


def compact(value) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def dump_pack(pack: dict) -> str:
    """Deterministic layout: one top-level key per line, one record per line for record lists."""
    lines = ["{"]
    keys = sorted(pack)
    for i, key in enumerate(keys):
        value = pack[key]
        tail = "," if i < len(keys) - 1 else ""
        if isinstance(value, list) and value and isinstance(value[0], (dict, str)):
            lines.append(f"{json.dumps(key)}:[")
            for j, item in enumerate(value):
                lines.append(compact(item) + ("," if j < len(value) - 1 else ""))
            lines.append("]" + tail)
        elif isinstance(value, dict):
            lines.append(f"{json.dumps(key)}:{{")
            sub = sorted(value)
            for j, sub_key in enumerate(sub):
                lines.append(f"{json.dumps(sub_key)}:{compact(value[sub_key])}" + ("," if j < len(sub) - 1 else ""))
            lines.append("}" + tail)
        else:
            lines.append(f"{json.dumps(key)}:{compact(value)}{tail}")
    lines.append("}")
    return "\n".join(lines) + "\n"


KNOWN_GAPS = (
    "Ordinary (non-rank-up) hangout gain +10 rests on one Reload playthrough report (conf=estimate). The rank-up cue "
    "the game shows on the map is the reliable signal; the engine/UI should prefer it over the estimate.",
    "Multiplier rounding order (per answer vs per meeting) and the exam-bonus expiry are unknown; the pack says "
    "floor_per_meeting.",
    "Free stat gains (class answers, exam top-of-class Charm +4, summer school Academics +18, Link Episodes, dorm "
    "hangouts, nurse's office) are not forecast (DESIGN §10.9); the player syncs stats.",
    "Not modelled: Wakatsu seafood course, Hagakure bowl, weekend Wilduck set, burger challenge, shrine donations, "
    "shrine fortunes, gifts, film-festival hangouts, Koromaru walks, Ryoji's and Takaya's Link Episodes. All of them "
    "can only add points/stats, so plans stay conservative.",
    "Dating-site note: +10 points is the reported base; whether the Persona/Charm multipliers apply is unknown "
    "(the pack applies none). The 11/11 sale date rests on aqiu/game8/Steam plus the CN-guide witness (atwiki: 11/16).",
    "Link Episodes: the aqiu calendar has a few cells outside the episode's own window (Junpei #3 on 11/13, Akihiko "
    "#4 on 12/26); they are dropped. Shinjiro's episode 5 (desk drawer from 10/5) is not a slot and is not a part.",
    "No official zh-Hans names were found for the library, karaoke venue, Be Blue V (shown as 饰品店) and the "
    "Screen Shot cinema (shown as 电影院), or for stat level titles (UI shows Lv.N).",
    "Judgement R2-R10 depend on Adamah floors 227-255, not on dates; the pack dates them 1/30 (conf=estimate).",
    "Empress: aqiu adds 'first place in one exam' to Academics Lv6. Not modelled (cannot be forecast); the unlock "
    "note, the step how-to and the 【女皇】 pack note tell the player to set the goal to 未选 without a first place.",
    "Mid-chain holds: none encoded. Moon R9 'momentary reversal' and the single Steam report of a Hierophant "
    "R4->R5 rumour gate are documented only; the result sheet's 'temporarily cannot rank up' outcome covers both.",
    "Item preconditions are not modelled as inventory: Odd Morsel (神秘食品, random Tartarus chest) for Moon, "
    "Weird Takoyaki + Mad Bull for Hanged Man (their 510 yen is charged on the gift step), the red fountain pen for Sun.",
    "Devil payments 1-2, Justice talks 1-3, the Moon/Tower rumours and Sun's pen steps are marked consumesSlot=false "
    "because the CN guide does another action in the same slot; Sun's pen retrieval timing is not proven.",
    "Unresolved story days are locked conservatively (Yakushima 7/20-7/23, 11/4, 1/1 daytime, 1/21 daytime, track "
    "training daytimes 7/27-8/2). Owner verification in the game can free them.",
    "Christmas Eve invitations and the summer-festival partner choice are not modelled (12/24 free, 8/16 locked).",
    "6/13 evening is locked as a mandatory story Tartarus night and does not count as a block visit.",
    "Missing-person floors are not in the data; the pack only knows the rescue windows.",
    "The analysis report mentioned 34 invitation dates; HolidayScheduleDataAsset has 35 dates (77 link-invitations).",
    "Several evenings lack the usual Devil/Tower cell (see audit list) but are kept free because nothing says other "
    "evening activities are unavailable.",
    "Tartarus closures: 20 GameFAQs dates plus the aqiu 'Everyone' nights (conf=single). Nights where aqiu names only "
    "some party members stay open (partial party).",
    "Almost every numeric calendar fact traces back to the aqiu/marendarade lineage; 'verified' means the atwiki "
    "weekday tables or the CN guide agree, not that the game was replayed.",
    "Tartarus income is not forecast (DESIGN §10.4); jobs are the only forecast income.",
)


FIELD_SEMANTICS = (
    "`day` = days since 2009-04-01 (4/7 = 6, 1/31 = 305); `wd` is the ISO weekday. Slot `day` is the after-school "
    "slot on school days and the daytime otherwise; `eve` is the evening.",
    "`dayLock`/`eveLock`: reason id (see `lockReasons`) or null. A null slot is free; nothing else is implied.",
    "`moon` is the decorative phase (0 new … 4 full … 7); `fullMoon` marks the operation full moons "
    "(4/9 … 11/3) and the finale on 1/31; the estimated full moons 12/2 and 1/1 only show phase 4.",
    "`tartarus` (per day): an optional Tartarus visit is possible that evening (block open, not a closed night, "
    "evening not locked). `block` is the block index for the day, -1 outside the plannable blocks.",
    "`avail`: days with an ordinary meeting cell (already filtered by locks). `invite`: holiday invitation days "
    "from game data; accepting uses the daytime slot and counts as a meeting. Both require the link to be "
    "established (rank >= 1) and the person not to be missing.",
    "`threshold[r]` = points needed while at rank r-1 to reach rank r (0 means the next meeting ranks up). "
    "`eventGain[r]` = best-answer points of the rank-r scene, counted toward rank r+1.",
    "`unlock`: `stat`/`link` requirements apply to every step. Steps run in order; `minGapDays` is measured from "
    "the previous step's day (0 = same day allowed). `consumesSlot=false` steps need a free slot of their kind on "
    "an allowed day but leave that slot usable for another action; they must still respect `minGapDays`. "
    "`days`: fixed = `fixedDays` (the ordinary meeting days of the link whose place the step uses, never its "
    "holiday invitations; Hierophant's opening only on school days), weekdays, any = any free slot; the builder no "
    "longer emits avail. The last step (`establishes`) sets rank 1. No steps = automatic: rank 1 once `earliestDay` "
    "is reached and the requirements hold (Magician on 4/22; Strength when Chariot reaches R2; story links by date).",
    "`missing`: the person is gone from `fromDay` to `toDay` unless the matching rescue (`tartarus.rescues`) "
    "happened earlier; without a rescue by `toDay` the link is lost for the rest of the game.",
    "`storyRanks` (story links) list dated rank events; the loader turns them into `storyRankDay[r]` = first day "
    "with rank >= r (skipped ranks share the next event's day).",
    "`activities`: `slots`, `weekdays`, `dayTypes` (empty = all), `fromDay..toDay`, `cost` (yen), `gain` "
    "(hidden stat points), `requires` (stat Lv). Jobs pay `pay` = [[minShiftNumber, minimum pay], …] for the "
    "N-th completed shift.",
    "`episodes` (Link Episodes, 羁绊剧情): opt-in tracks. `parts[n-1].days` = days on which episode n can be watched "
    "(slot kind = `evening`), in order, one slot each; `gain` = Academics/Charm/Courage points of that episode; no "
    "Social Link points. The loader turns the parts into `dayMask[day]` (bit n-1 = episode n allowed).",
    "`datingNote` (结缘网站的字条): from `fromDay`, an evening (club + dorm PC) that costs `cost` yen and adds `gain` "
    "points (no multipliers) to one established manual link at rank 1-9; it never ranks the link up by itself. "
    "`requires` = stat Lv to enter the club; `maxUses` = null means no limit.",
    "`conf`: game_data > verified > single > estimate; conflict = strictest claim chosen, alternatives in `alt` "
    "(activities) or in this report.",
)


def month_label(month: int) -> str:
    return f"{month:02d}"


def render_report(pack: dict, ledger: Ledger, pack_text: str) -> str:
    sha = hashlib.sha256(pack_text.encode("utf-8")).hexdigest()
    out = []
    out.append("# P3R rule pack v3 — build report")
    out.append("")
    out.append("Generated by `rules/build/build_p3r_rules.py` (do not edit by hand; rerun the builder). ")
    out.append(f"Pack: `android/app/src/main/assets/p3r/p3r_rules.json`, schema `{SCHEMA}`, packVersion `{PACK_VERSION}`, "
               f"{len(pack_text.encode('utf-8')):,} bytes, sha256 `{sha[:12]}…` (Rules.version = `{PACK_VERSION}|{sha[:12]}`).")
    out.append("")
    out.append("Rebuild: `python3 rules/build/build_p3r_rules.py`; check that the committed files are current: "
               "`python3 rules/build/build_p3r_rules.py --check`; self-test: `python3 -m unittest rules/build/test_build.py`.")
    out.append("")
    out.append("Confidence vocabulary: `game_data` (tables extracted from the P3R game files), `verified` (two or more "
               "independent Reload sources agree), `single` (one Reload source), `estimate` (policy choice), `conflict` "
               "(sources disagree; the strictest Reload claim is used and the others are listed).")
    out.append("")

    out.append("## 0. Field semantics for consumers")
    out.append("")
    for line in FIELD_SEMANTICS:
        out.append(f"- {line}")
    out.append("")

    out.append("## 1. Sources")
    out.append("")
    out.append("| id | kind | file / url | sha256 |")
    out.append("|---|---|---|---|")
    for s in pack["sources"]:
        where = s["path"] or s["url"]
        out.append(f"| `{s['id']}` | {s['kind']} | {where} | {(s['sha256'] or '—')[:16]} |")
    out.append("")

    out.append("## 2. Decisions")
    out.append("")
    areas: list[str] = []
    for decision_area, _ident, _text in ledger.decisions:
        if decision_area not in areas:
            areas.append(decision_area)
    for decision_area in areas:
        out.append(f"### {decision_area}")
        out.append("")
        for area, ident, text in ledger.decisions:
            if area == decision_area:
                out.append(f"- **{ident}** — {text}")
        out.append("")

    out.append("## 3. Conflicts and resolutions")
    out.append("")
    out.append("| id | topic | chosen | alternatives | sources |")
    out.append("|---|---|---|---|---|")
    for c in ledger.conflicts:
        out.append(f"| `{c['id']}` | {c['topic']} | {c['chosen']} | {'; '.join(c['alternates'])} | {', '.join(c['sources'])} |")
    for act in pack["activities"]:
        if act["alt"]:
            out.append(f"| `activity.{act['id']}` | {act['zh']} | conf={act['conf']}, gain={compact(act['gain'])}, "
                       f"slots={act['slots']}, weekdays={act['weekdays']} | {'; '.join(act['alt'])} | {', '.join(act['src'])} |")
    out.append("")

    out.append("## 4. Coverage")
    out.append("")
    out.append("### 4.1 Meeting days per link")
    out.append("")
    out.append("`avail` = ordinary meeting days kept (aqiu O cells after lock/policy filtering), `dropped` = O cells "
               "removed, `invite` = holiday invitations (game data). Story links rank by date.")
    out.append("")
    out.append("| # | link | slot | avail | dropped | invite | first | last | unlock from | steps |")
    out.append("|---|---|---|---|---|---|---|---|---|---|")
    for link in pack["links"]:
        avail = link["avail"]
        first = md_of(avail[0]) if avail else "—"
        last = md_of(avail[-1]) if avail else "—"
        steps = len(link["unlock"]["steps"])
        out.append(f"| {link['no']} | {link['labelZh']} | {link['slot'] or 'story'} | {len(avail)} | "
                   f"{len(ledger.dropped.get(link['id'], []))} | {len(link['invite'])} | {first} | {last} | "
                   f"{md_of(link['unlock']['earliestDay'])} | {steps} |")
    out.append("")
    out.append("Dropped cells:")
    out.append("")
    for link_id, dropped in ledger.dropped.items():
        if dropped:
            out.append(f"- {link_id}: " + ", ".join(dropped))
    out.append("")

    out.append("### 4.2 Unlock paths")
    out.append("")
    out.append("Requirements apply to every step. `days`: fixed = explicit meeting days of the link named in "
               "brackets (own = the link itself; rumours are heard at another link's place), never invitation days; "
               "any = any free slot. Steps with consumes=no do not use up the slot (the CN guide does another action "
               "in the same slot).")
    out.append("")
    out.append("| link | earliest | requires | steps | conf |")
    out.append("|---|---|---|---|---|")
    for link in pack["links"]:
        unlock = link["unlock"]
        reqs = [f"{k} Lv{v}" for k, v in sorted(unlock["stat"].items())]
        reqs += [f"{k} R{v}" for k, v in sorted(unlock["link"].items())]
        if link["kind"] == "story":
            steps = "story ranks by date"
        elif not unlock["steps"]:
            steps = "automatic"
        else:
            parts = []
            for s in unlock["steps"]:
                extra = []
                if not s["consumesSlot"]:
                    extra.append("consumes=no")
                if s["minGapDays"]:
                    extra.append(f"gap {s['minGapDays']}")
                if s["cost"]:
                    extra.append(f"¥{s['cost']:,}")
                if s["fromDay"] != unlock["earliestDay"]:
                    extra.append(f"from {md_of(s['fromDay'])}")
                days_label = s["days"]
                if s["id"] in ledger.step_days:
                    days_label += f" [{ledger.step_days[s['id']]}, {len(s['fixedDays'])} days]"
                parts.append(f"{s['id']} ({s['slot']}, {days_label}{', ' + ', '.join(extra) if extra else ''})")
            steps = " → ".join(parts)
        out.append(f"| {link['id']} | {md_of(unlock['earliestDay'])} | {', '.join(reqs) or '—'} | {steps} | {unlock['conf']} |")
    out.append("")

    out.append("### 4.3 Locked slots per month")
    out.append("")
    out.append("| month | days | daytime locked | evening locked | Tartarus-open evenings |")
    out.append("|---|---|---|---|---|")
    months: dict[int, list[dict]] = {}
    for d in pack["days"]:
        months.setdefault(int(d["md"][:2]), []).append(d)
    for month in (4, 5, 6, 7, 8, 9, 10, 11, 12, 1):
        ds = months[month]
        out.append(f"| {month_label(month)} | {len(ds)} | {sum(1 for d in ds if d['dayLock'])} | "
                   f"{sum(1 for d in ds if d['eveLock'])} | {sum(1 for d in ds if d['tartarus'])} |")
    total_day = sum(1 for d in pack["days"] if d["dayLock"])
    total_eve = sum(1 for d in pack["days"] if d["eveLock"])
    out.append(f"| total | {len(pack['days'])} | {total_day} | {total_eve} | {sum(1 for d in pack['days'] if d['tartarus'])} |")
    out.append("")
    reasons: dict[str, int] = {}
    for d in pack["days"]:
        for key in ("dayLock", "eveLock"):
            if d[key]:
                reasons[d[key]] = reasons.get(d[key], 0) + 1
    out.append("Locked slots by reason: " + ", ".join(f"{k} {v}" for k, v in sorted(reasons.items())) + ".")
    out.append("")
    types: dict[str, int] = {}
    for d in pack["days"]:
        types[d["type"]] = types.get(d["type"], 0) + 1
    out.append("Day types: " + ", ".join(f"{k} {v}" for k, v in sorted(types.items())) + ".")
    out.append("")

    out.append("### 4.4 Tartarus blocks")
    out.append("")
    out.append("| block | zh | floors | from | to | open evenings |")
    out.append("|---|---|---|---|---|---|")
    for idx, b in enumerate(pack["tartarus"]["blocks"]):
        open_eves = sum(1 for d in pack["days"] if d["block"] == idx and d["tartarus"])
        out.append(f"| {b['id']} | {b['zh']} | {b['floorsZh']} | {md_of(b['fromDay'])} | {md_of(b['toDay'])} | {open_eves} |")
    out.append("")

    out.append("### 4.5 Activities per weekday")
    out.append("")
    out.append("Number of activity records offered per ISO weekday and slot (before day-type and date filters).")
    out.append("")
    out.append("| slot | Mon | Tue | Wed | Thu | Fri | Sat | Sun |")
    out.append("|---|---|---|---|---|---|---|---|")
    for slot in ("day", "eve"):
        counts = []
        for wd in range(1, 8):
            counts.append(str(sum(1 for a in pack["activities"] if slot in a["slots"] and wd in a["weekdays"])))
        out.append(f"| {slot} | " + " | ".join(counts) + " |")
    out.append("")
    out.append("| id | zh | slots | weekdays | cost | gain | from | conf |")
    out.append("|---|---|---|---|---|---|---|---|")
    for a in pack["activities"]:
        out.append(f"| {a['id']} | {a['zh']} | {','.join(a['slots'])} | {','.join(map(str, a['weekdays']))} | "
                   f"{a['cost']} | {compact(a['gain'])} | {md_of(a['fromDay'])} | {a['conf']} |")
    out.append("")

    out.append("### 4.6 Link Episodes (羁绊剧情)")
    out.append("")
    out.append("| track | person | slot | episodes | days per episode | first | last | gains |")
    out.append("|---|---|---|---|---|---|---|---|")
    for t in pack["episodes"]:
        counts = "/".join(str(len(x["days"])) for x in t["parts"])
        gains = "; ".join(f"#{x['no']} {compact(x['gain'])}" for x in t["parts"] if x["gain"])
        out.append(f"| {t['id']} | {t['personZh']} | {'eve' if t['evening'] else 'day'} | {t['count']} | {counts} | "
                   f"{md_of(t['parts'][0]['days'][0])} | {md_of(t['parts'][-1]['days'][-1])} | {gains or '—'} |")
    out.append("")
    note = pack["datingNote"]
    out.append("### 4.7 Dating-site note (结缘网站的字条)")
    out.append("")
    uses = "no limit" if note["maxUses"] is None else str(note["maxUses"])
    out.append(f"From {md_of(note['fromDay'])}, evening, ¥{note['cost']:,}, +{note['gain']} points, requires "
               f"{compact(note['requires'])}, uses: {uses}, conf={note['conf']}, src: {', '.join(note['src'])}.")
    out.append("")

    out.append("## 5. Audit observations")
    out.append("")
    if ledger.audit:
        for line in ledger.audit:
            out.append(f"- {line}")
    else:
        out.append("- none")
    out.append("")

    out.append("## 6. Known gaps")
    out.append("")
    for gap in KNOWN_GAPS:
        out.append(f"- {gap}")
    out.append("")
    return "\n".join(out)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--check", action="store_true", help="verify that the committed pack and report are current")
    parser.add_argument("--out", type=Path, default=PACK_PATH, help="pack output path")
    parser.add_argument("--report", type=Path, default=REPORT_PATH, help="report output path")
    args = parser.parse_args(argv)
    try:
        pack, ledger = build_pack()
    except BuildError as err:
        print(f"build failed: {err}", file=sys.stderr)
        return 2
    pack_text = dump_pack(pack)
    size = len(pack_text.encode("utf-8"))
    if size >= MAX_PACK_BYTES:
        print(f"build failed: pack is {size} bytes (limit {MAX_PACK_BYTES})", file=sys.stderr)
        return 2
    report_text = render_report(pack, ledger, pack_text)
    if args.check:
        stale = [p for p, text in ((args.out, pack_text), (args.report, report_text))
                 if not p.exists() or p.read_text(encoding="utf-8") != text]
        if stale:
            print("stale: " + ", ".join(str(p) for p in stale), file=sys.stderr)
            return 1
        print("pack and report are up to date")
        return 0
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(pack_text, encoding="utf-8")
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(report_text, encoding="utf-8")
    print(f"wrote {args.out} ({size:,} bytes) and {args.report}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

