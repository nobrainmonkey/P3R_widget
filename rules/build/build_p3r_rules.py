#!/usr/bin/env python3
"""Compile the single P3R v3 rule pack (schema ``p3r.rules.v3``).

Inputs (never shipped in the APK):
  rules/sources/**           pinned third-party snapshots (aqiu game-data tables, aqiu calendar page,
                             Steam zh-Hans strings, 3DM CN guide pages: Tartarus names and the schedule
                             pages 2-11 for the 当日提醒 answers; 游民星空's copy of the same schedule guide
                             and the BWIKI class-answer page for the class dates 3DM has no text for; aqiu's
                             Reload walkthrough, a Steam thread and two guide pages for the missing-person limit
                             evening); hashes in rules/sources/SOURCES.json
  rules/sources/cn_guides/data.json
                             the original Simplified Chinese walkthrough of the retired 2.x guide
                             (location names, wording, timing witnesses)
  rules/p3r/*.json           earlier research/evidence files (candidates, conflicts, witnesses)

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
GUIDE_REL = "cn_guides/data.json"
GUIDE_PATH = SOURCES_DIR / GUIDE_REL
PACK_PATH = ROOT / "android" / "app" / "src" / "main" / "assets" / "p3r" / "p3r_rules.json"
REPORT_PATH = ROOT / "rules" / "build" / "REPORT.md"

SCHEMA = "p3r.rules.v3"
PACK_VERSION = "3.4.0"
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
# Outdated names (above) and terms that differ from the official zh-Hans text: the Steam store page says
# 主角 (never 主人公), the achievement text says 休息日的出游邀请短信, so invitations are 休息日邀约, and
# 黄昏的碎片 (never the walkthroughs' 黄昏碎片).
FORBIDDEN_STRINGS = ("伊丽莎白", "贝贝", "大桥舞子", "女帝", "倒吊人", "主人公", "假日邀约", "节日邀约", "黄昏碎片")
# Official zh-Hans wording the pack relies on: (snapshot, text that must occur in it, why).
OFFICIAL_TERMS = (
    ("steam_schinese/p3r_sc.txt", "作为主角的少年", "主角 (store description)"),
    ("steam_schinese/ach_schinese.txt", "休息日的出游邀请短信", "休息日邀约 (achievement 争相邀约)"),
    ("steam_schinese/ach_schinese.txt", "社群", "社群 (achievements)"),
    ("steam_schinese/ach_schinese.txt", "黄昏的碎片", "黄昏的碎片 (achievements)"),
    ("steam_schinese/ach_schinese.txt", "失踪者", "失踪者 (achievement 课外活动的成果)"),
    ("aqiu_gamedata/DatPlayerNameDataAsset.tsv", "伊莉莎白", "伊莉莎白 (character name table)"),
)

# Source editions (pack sources[].edition and rules/sources/SOURCES.json). Only the first three are
# Reload sources: every pack record must cite at least one of them (Reload-only source policy, REPORT §1).
EDITIONS = {
    "reload": "about Persona 3 Reload only (official Steam text, Reload forum threads, Reload-only guides and "
              "community pages, evidence files written for Reload)",
    "game_data_reload": "tables extracted from the Persona 3 Reload (Xrd777) game files",
    "cn_guide_reload": "Simplified Chinese walkthrough of Persona 3 Reload, or evidence derived from it",
    "reload+portable": "states the fact for Reload and Portable alike; corroboration only",
    "mixed": "multi-edition page or post (P3/FES/Portable/Reload) of which only the Reload section is used; "
             "corroboration only",
}
RELOAD_EDITIONS = ("reload", "game_data_reload", "cn_guide_reload")

STEAM_STORE_URL = "https://store.steampowered.com/app/2161700/"
STEAM_THREAD_URL = "https://steamcommunity.com/app/2161700/discussions/0/{}/"
STEAM_ACHIEVEMENTS_URL = "https://steamcommunity.com/stats/2161700/achievements/?l={}"
STEAM_PAGE_LANG = {"english": "en", "japanese": "ja", "schinese": "zh-cn"}
CN_3DM_URL = "https://www.3dmgame.com/gl/3928716_{}.html"
# 游民星空's authorised repost (2024-02-06) of the same schedule guide by 猎猫人老贾: page 1 has no suffix.
GAMERSKY_URL = "https://www.gamersky.com/handbook/202402/1706261{}.shtml"
# BWIKI (bilibili's game wiki) page P3R/课堂问答与考试: class answers per date in zh text, no option positions.
BWIKI_CLASS_URL = ("https://wiki.biligame.com/persona/P3R/"
                   "%E8%AF%BE%E5%A0%82%E9%97%AE%E7%AD%94%E4%B8%8E%E8%80%83%E8%AF%95")
BWIKI_CLASS_PAGE = "P3R/课堂问答与考试"
BWIKI_CLASS_REVISION = 18269


def gamersky_url(number: int) -> str:
    return GAMERSKY_URL.format("" if number == 1 else f"_{number}")


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

    def subsection(self, name: str) -> str:
        """Text of any aqiu heading (h3-h5, e.g. class-answers) up to the next heading of the same or a higher level."""
        m = re.search(rf'<h([1-6]) id="{re.escape(name)}">', self.html)
        if not m:
            raise BuildError(f"aqiu section {name} not found")
        nxt = re.compile(rf"<h[1-{m.group(1)}][ >]").search(self.html, m.end())
        return html_to_text(self.html[m.start():nxt.start() if nxt else len(self.html)])


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


def load_manifest() -> dict:
    return json.loads((SOURCES_DIR / "SOURCES.json").read_text(encoding="utf-8"))


def check_manifest(manifest: dict) -> dict[str, dict]:
    """Validate rules/sources/SOURCES.json: pinned hashes, editions and the URLs of the Steam and 3DM pages.

    Returns path -> entry. The URL checks read the snapshot itself (thread title, page language, canonical
    link, active pager entry) so a copy-pasted URL cannot slip in again."""
    if manifest.get("editions", {}).keys() != EDITIONS.keys():
        raise BuildError(f"SOURCES.json editions vocabulary must be exactly {sorted(EDITIONS)}")
    pinned: dict[str, dict] = {}
    for entry in manifest["files"]:
        rel = entry["path"]
        if rel in pinned:
            raise BuildError(f"SOURCES.json lists {rel} twice")
        if entry.get("edition") not in EDITIONS:
            raise BuildError(f"SOURCES.json {rel}: edition {entry.get('edition')!r} must be one of {sorted(EDITIONS)}")
        if not (entry.get("url") or entry.get("derivedFrom")):
            raise BuildError(f"SOURCES.json {rel}: url or derivedFrom is required")
        path = SOURCES_DIR / rel
        if sha256_file(path) != entry["sha256"]:
            raise BuildError(f"{rel} does not match its pinned sha256 in SOURCES.json")
        url = entry.get("url")
        name = rel.rsplit("/", 1)[-1]
        stem = name.rsplit(".", 1)[0]
        html_path = path.with_suffix(".html")
        page = html_path.read_text(encoding="utf-8", errors="replace") if html_path.exists() else ""
        if rel.startswith("steam_schinese/d_"):
            expected = STEAM_THREAD_URL.format(stem[2:])
            if url != expected:
                raise BuildError(f"SOURCES.json {rel}: a Steam discussion thread must point to {expected}, not {url}")
            if ":: Persona 3 Reload General Discussions</title>" not in page or stem[2:] not in page:
                raise BuildError(f"{rel} is not the Reload General Discussions thread {stem[2:]}")
        elif rel.startswith("steam_schinese/p3r_sc."):
            if url != STEAM_STORE_URL or f'rel="canonical" href="{STEAM_STORE_URL}' not in page:
                raise BuildError(f"SOURCES.json {rel}: the Steam store snapshot must point to {STEAM_STORE_URL}")
        elif rel.startswith("steam_schinese/ach_"):
            lang = stem.split("_", 1)[1]
            if lang not in STEAM_PAGE_LANG or url != STEAM_ACHIEVEMENTS_URL.format(lang) \
                    or f'lang="{STEAM_PAGE_LANG[lang]}"' not in page:
                raise BuildError(f"SOURCES.json {rel}: expected {STEAM_ACHIEVEMENTS_URL.format(lang)} (page language "
                                 f"{STEAM_PAGE_LANG.get(lang)})")
        elif rel.startswith("cn_guides/3dm_p"):
            number = stem.rsplit("_p", 1)[1]
            active = f'<li class="active"><a href="{CN_3DM_URL.format(number)}"'
            if url != CN_3DM_URL.format(number) or active not in page:
                raise BuildError(f"SOURCES.json {rel}: expected the 3DM page {CN_3DM_URL.format(number)}")
        elif rel.startswith("cn_guides/gamersky_p"):
            number = int(stem.rsplit("_p", 1)[1])
            expected = gamersky_url(number)
            if url != expected or f'<link rel="canonical" href="{expected}" />' not in page \
                    or f"var contentPageIndex = {number};" not in page:
                raise BuildError(f"SOURCES.json {rel}: expected the 游民星空 page {expected} (canonical link, page index)")
        elif rel.startswith("cn_guides/bwiki_"):
            if url != BWIKI_CLASS_URL or f'"wgPageName":"{BWIKI_CLASS_PAGE}"' not in page \
                    or f'"wgRevisionId":{BWIKI_CLASS_REVISION}' not in page:
                raise BuildError(f"SOURCES.json {rel}: expected the BWIKI page {BWIKI_CLASS_PAGE} revision "
                                 f"{BWIKI_CLASS_REVISION} at {BWIKI_CLASS_URL}")
        elif rel.startswith("aqiu_gamedata/") and rel.endswith(".tsv") and entry["edition"] != "game_data_reload":
            raise BuildError(f"SOURCES.json {rel}: game-data tables are edition game_data_reload")
        if rel.endswith(".txt") and entry.get("derivedFrom", "").split(" ")[0] != rel[:-4] + ".html":
            raise BuildError(f"SOURCES.json {rel}: a text extraction must name its HTML page in derivedFrom")
        pinned[rel] = entry
    return pinned


def build_sources() -> list[dict]:
    """Every source referenced by a record's src list, with url, pinned sha256 and edition."""
    pinned = check_manifest(load_manifest())

    def snap(ident: str, rel: str, kind: str, note: str) -> dict:
        entry = pinned[rel]
        return {"edition": entry["edition"], "id": ident, "kind": kind, "note": note, "path": "rules/sources/" + rel,
                "sha256": entry["sha256"], "url": entry.get("url") or entry.get("derivedFrom")}

    def evidence(ident: str, name: str, edition: str, note: str) -> dict:
        return {"edition": edition, "id": ident, "kind": "evidence", "note": note, "path": "rules/p3r/" + name,
                "sha256": sha256_file(EVIDENCE_DIR / name), "url": None}

    def web(ident: str, url: str, edition: str, note: str) -> dict:
        return {"edition": edition, "id": ident, "kind": "web", "note": note, "path": None, "sha256": None, "url": url}

    gd = "aqiu_gamedata/"
    reload = "reload"
    sources = [
        snap("gd_activity", gd + "ActivityDataAsset.tsv", "game_data", "Tartarus block opening dates and floor ranges"),
        snap("gd_arbeit_help", gd + "BMD_ArbeitHelp.tsv", "game_data", "Part-time job help text: slot and minimum pay"),
        snap("gd_community_event", gd + "CommunityEventDataAsset.tsv", "game_data", "Social Link points needed per rank"),
        snap("gd_disappear_help", gd + "BMD_DisappearHelp.tsv", "game_data", "Missing-person board text"),
        snap("gd_disappear_data", gd + "DisappearDataAsset.tsv", "game_data",
             "Missing-person table: StartMonth/StartDays and LimitMonth/LimitDays of each of the 20 people"),
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
        snap("aqiu_walkthrough", WALKTHROUGH_REL, "walkthrough",
             "aqiu 'All Voiced Events Walkthrough - Persona 3 Reload' (test-played, no reloads): the evenings it "
             "rescues missing people, three of them on the limit day"),
        snap("steam_thread_missing", "steam_schinese/d_7599331177366570924.html", "forum",
             "Player advice to wait until the countdown 'nears 0 aka the last day'; it does not say whether the "
             "limit evening itself counts (context only)"),
        snap("game8jp_missing", "web_guides/game8jp_missing_people.html", "guide",
             "Game8 JP Reload missing-person deadline list: the 13 windows as 'M/D ~ M/D' (救出期限); it confirms "
             "the windows, not whether the limit day counts"),
        snap("rpgsite_missing", "web_guides/rpgsite_missing_people.html", "guide",
             "RPG Site Reload missing-person guide: loose dates (deadline 9/5, the full moon, for the 9/3 group "
             "whose game limit is 9/4, advising 9/3 or 9/4; 11/27 for a person missing from 11/28; 'head in "
             "before November 2nd' for the 11/2 group); context only, it does not settle the limit evening"),
        snap("steam_sc_achievements", "steam_schinese/ach_schinese.txt", "official_text", "ATLUS zh-Hans achievement strings (社群, 个人能力值, 打工, 失踪者, 休息日的出游邀请短信…)"),
        snap("steam_sc_store", "steam_schinese/p3r_sc.txt", "official_text", "ATLUS zh-Hans Steam store description (主角, 月光馆学园, 塔尔塔罗斯…): terminology check"),
        snap("steam_thread_hierophant", "steam_schinese/d_4206993639701168068.html", "forum", "Player report of a Hierophant rank hold"),
        snap("steam_thread_rankup", "steam_schinese/d_4206994023684392768.html", "forum", "Player reports on rank-up cues and matching Personas"),
        snap("cn_3dm", "cn_guides/3dm_p42.html", "cn_guide", "3DM SC guide, page 42: Tartarus block names (single source)"),
        *[snap(f"cn_3dm_p{n}", f"cn_guides/3dm_p{n}.html", "cn_guide",
               f"3DM SC guide, page {n}: {CN_3DM_MONTHS[n]} schedule (class and exam answers, 上课 lines, TV shopping, "
               "黄昏的碎片)") for n in CN_3DM_SCHEDULE_PAGES],
        *[snap(f"cn_gamersky_p{n}", f"cn_guides/gamersky_p{n}.html", "cn_guide",
               f"游民星空 repost (2024-02-06, authorised) of 猎猫人老贾's Reload schedule guide, the guide 3DM also "
               f"carries; page {n}: {GAMERSKY_MONTHS[n]} (approved Social Link dialogue and class answers)")
          for n in sorted(set(GAMERSKY_SOURCE_PAGES) | set(GAMERSKY_DIALOGUE_SOURCE_PAGES))],
        snap("cn_bwiki_class", "cn_guides/bwiki_p3r_class.html", "cn_guide",
             f"BWIKI page {BWIKI_CLASS_PAGE} (revision {BWIKI_CLASS_REVISION}, 2024-02-04): class answers per date in zh "
             "text without option positions, partly from a 女神异闻录吧 post; corroborates the 游民星空 class answers"),
        snap("gd_mail_order", gd + "DT_FldMailOrderTable.tsv", "game_data", "TV shopping: buy dates, items, quantities, prices"),
        {"edition": "cn_guide_reload", "id": "cn_guide", "kind": "cn_guide",
         "note": "Original Simplified Chinese Reload walkthrough shipped with the legacy guide, an edited derivative of the "
                 "3DM walkthrough (wording, places, dialogue, timing witnesses; never the source of class/exam answers)",
         "path": "rules/sources/" + GUIDE_REL, "sha256": pinned[GUIDE_REL]["sha256"], "url": None},
        evidence("ev_calendar_constraints", "calendar_global_constraints_candidate.json", reload, "Locks, exams, full moons, school-closure candidates"),
        evidence("ev_late_link_availability", "late_link_availability_evidence.json", reload,
                 "Reload dated route witnesses restore five Aeon and three Empress O cells; no weekday-generated meetings"),
        web("powerpyx_100pct", "https://www.powerpyx.com/persona-3-reload-100-walkthrough/", reload,
            "Verified Reload 100% route: Aeon on 1/12, 1/14, 1/19 and 1/26; Empress on 12/4"),
        web("rpgsite_100pct", "https://www.rpgsite.net/guide/15321-persona-3-reload-100-max-social-link-guide", reload,
            "Independent Reload all-MAX route: Aeon on 1/19 and 1/26, Empress on 11/30"),
        web("pushsquare_social_links", "https://www.pushsquare.com/guides/persona-3-reload-all-social-links-and-how-to-unlock-them", reload,
            "Reload Social Link overview: Aeon and Empress available on school days Monday through Saturday"),
        evidence("ev_slot_locks", "global_slot_locks_expansion_candidate.json", reload, "Whole-slot lock claims, Tartarus closures, witnesses"),
        evidence("ev_tartarus_supplement", "tartarus_entry_availability_supplement.json", reload, "Tartarus entry witnesses and closures"),
        evidence("ev_stats_actions", "stats_actions.json", reload, "Stat thresholds and evening stat actions"),
        evidence("ev_group_study", "group_study_evidence.json", reload,
                 "Four exam periods: 14 dated, zero-yen evening study sessions; conservative +4/+5 Academics"),
        evidence("ev_dorm_activities", "dorm_activities_evidence.json", reload,
                 "Exact dorm activity calendars, per-category characteristic progress and gardening preparation"),
        evidence("ev_dorm_affinity", "dorm_affinity_evidence.json", reload,
                 "Female dorm projects: raw +1 affinity lower bound, no multipliers or unopened-link banking"),
        evidence("ev_automatic_story_stats", "automatic_story_stats_evidence.json", reload,
                 "Mandatory summer school: six daytime story sessions from 8/10 to 8/15, Academics +3 each"),
        evidence("ev_combat_tips", "combat_tips_evidence.json", reload,
                 "All 97 Reload combat and story groups, exact source rows, structured lineups and phase-specific resistances"),
        evidence("ev_combat_cn", "combat_cn_name_evidence.json", reload,
                 "Chinese combat names and dated operation mechanics cross-checked against local guide snapshots"),
        evidence("ev_request_catalogue", "request_catalogue.json", reload,
                 "All 101 numbered Elizabeth requests: pinned source rows, Chinese names and actionable steps"),
        evidence("ev_stat_expansion", "stat_activity_expansion_candidate.json", reload, "Restaurants, karaoke, movies, daytime coffee"),
        evidence("ev_jobs", "jobs_wage_rules_candidate.json", reload, "Part-time job wage tiers and conflicts (Reload sections only)"),
        evidence("ev_manual_models", "manual_link_models.json", reload, "Normalised per-rank thresholds and answer gains"),
        evidence("ev_rank_points", "reported_rank_points.json", reload, "aqiu per-rank best scene gains"),
        evidence("ev_simple_unlock", "simple_unlock_rules_candidate.json", reload, "Single-slot link introductions"),
        evidence("ev_multistep_unlock", "multistep_unlock_rules_candidate.json", reload,
                 "Multi-step link openings (Justice, Devil, Hanged Man, Tower, Moon, Sun, Strength); a mixed MegaTen Wiki page appears only as a conflict alternative"),
        evidence("ev_story_links", "story_link_transitions_candidate.json", reload, "Fool/Death/Judgement transitions"),
        evidence("ev_dialogue", "guide_dialogue_reference_map.json", "cn_guide_reload", "CN guide dialogue fragments bound to target ranks"),
        evidence("ev_dialogue_approved", "dialogue_guidance_approved.json", "cn_guide_reload",
                 "User-approved Chinese guide dialogue additions and repairs, with ordered questions, route limits and source hashes"),
        evidence("ev_dialogue_completion", "dialogue_guidance_completion.json", "reload",
                 "Authorized dialogue completion: Chinese guide quotations, labelled editorial translations and per-route sequences"),
        evidence("ev_social_links", "social_links.json", reload, "Link catalogue: weekdays, unlock claims, conflicts"),
        evidence("ev_identity_labels", "social_goal_identity_labels.json", reload, "Selector labels and gender (Reload translation and identity tables)"),
        evidence("ev_affinity", "affinity_model_evidence.json", "mixed",
                 "Multiplier and carry-over evidence; cites MegaTen Wiki pages that mix Reload and Portable guide figures"),
        evidence("ev_ordinary_gain", "ordinary_affinity_numeric_followup.json", reload,
                 "Ordinary hangout +10 single-source claim, pinned to the Reload subsection of the lpbeach post"),
        evidence("ev_romance", "romance_rank_rules_candidate.json", reload, "Romanceable links and romance flags"),
        evidence("ev_reversal", "reversal_repair_research_candidate.json", reload, "Reversal/repair research (old-edition pages rejected)"),
        web("wiki420", "https://w.atwiki.jp/wiki10_persona3/pages/420.html", reload, "atwiki Reload all-MAX route chart (リロード page of the multi-edition wiki)"),
        web("wiki453", "https://w.atwiki.jp/wiki10_persona3/pages/453.html", reload, "atwiki Reload Social Link page (cited through evidence files)"),
        web("wiki455", "https://w.atwiki.jp/wiki10_persona3/pages/455.html", reload, "atwiki Reload Social Link schedule notes"),
        web("wiki469", "https://w.atwiki.jp/wiki10_persona3/pages/469.html", "mixed", "atwiki parameters page; only its Reload section (アルバイト) is used"),
        web("gf81375", "https://gamefaqs.gamespot.com/pc/409941-persona-3-reload/faqs/81375/calendar", reload, "GameFAQs Reload calendar (Tartarus closures)"),
        web("gf81159", "https://gamefaqs.gamespot.com/ps5/410283-persona-3-reload/faqs/81159", reload, "GameFAQs social stats guide"),
        web("megatenwiki_chagall_cafe", "https://megatenwiki.com/index.php?title=Chagall_Cafe&oldid=423201", "mixed",
            "MegaTen Wiki Chagall Cafe: Persona 3 Reload section, café job wage table"),
        web("megatenwiki_be_blue_v", "https://megatenwiki.com/index.php?title=Be_Blue_V&oldid=393238", "mixed",
            "MegaTen Wiki Be Blue V: Persona 3 Reload section, accessory-shop job wage table"),
        web("megatenwiki_port_island_station", "https://megatenwiki.com/index.php?title=Port_Island_Station&oldid=410086", "mixed",
            "MegaTen Wiki Port Island Station: Screen Shot Reload part-time wage table (cinema job)"),
        web("siliconera_jobs", "https://www.siliconera.com/how-to-get-a-job-in-persona-3-reload/", reload, "Siliconera job article (wages cross-check only)"),
        web("lpbeach", "https://lpbeach.co.uk/viewtopic.php?t=1712#p15208", "mixed",
            "Playthrough post whose Reload subsection reports the +10 ordinary hangout (FES/Portable subsections ignored)"),
        web("siliconera_urls", "https://www.siliconera.com/how-to-get-shared-computer-urls-in-persona-3-reload/", reload,
            "Shared-computer URLs: Dating Site note from November, 3,000 yen, can be purchased multiple times"),
        web("game8_social_links", "https://game8.co/games/Persona-3-Reload/archives/435602", reload,
            "Game8 Reload Social Link list: unlock dates, requirements and meeting days (Aeon Mon/Wed/Fri/Sat)"),
        web("pcinvasion_aeon", "https://www.pcinvasion.com/persona-3-reload-aigis-answers-and-aeon-social-link-guide/", reload,
            "PC Invasion Reload Aeon guide: Aigis in classroom 2F on Mondays, Wednesdays, Fridays and Saturdays"),
        evidence("ev_dating_note", "affinity_actions_candidate.json", reload, "Dating-site note: evening, +10 points, 3,000 yen, never a rank-up"),
        evidence("ev_hangout_rules", "ordinary_hangout_rules_candidate.json", reload, "Dating-site note sources (aqiu, game8, atwiki 455, Steam guide)"),
    ]
    completion = load_dialogue_completion()
    for source in completion["sources"]:
        sources.append({"edition": source["edition"], "id": source["id"], "kind": "web_rendered_excerpt",
                        "note": source["note"], "path": "rules/p3r/dialogue_guidance_completion.json",
                        "sha256": sha256_file(EVIDENCE_DIR / "dialogue_guidance_completion.json"), "url": source["url"]})
    snapped = {s["path"][len("rules/sources/"):] for s in sources if s["path"] and s["path"].startswith("rules/sources/")}
    if not snapped <= pinned.keys():
        raise BuildError(f"pack sources not pinned in SOURCES.json: {sorted(snapped - pinned.keys())}")
    for s in sources:
        if s["edition"] not in EDITIONS:
            raise BuildError(f"source {s['id']}: edition {s['edition']!r} must be one of {sorted(EDITIONS)}")
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
    # Not used by any day record: the engine applies it to 12/24 daytime and evening when the player has
    # accepted a Christmas Eve invitation (Profile.christmasLink). 约会 is the glossary word for romance.
    "christmas_date": "平安夜约会",
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
    # id, zh, missing from, deadline (its evening still counts, see rescue_limit_witnesses), link
    ("bunkichi", "北村文吉", "09-12", "10-03", "hierophant"),
    ("maiko", "舞子", "10-21", "11-02", "hanged_man"),
)
# What every missing-person window (rescues, links[].missing, reminders.rescueGroups) rests on: the game's
# missing-person table and board, aqiu's table of them, and aqiu's tested walkthrough for the limit evening.
RESCUE_SRC = ("aqiu_overworld", "aqiu_walkthrough", "gd_disappear_data", "gd_disappear_help")


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
                  "aqiu has ordinary cells that day and wiki420 says both slots are free when every invitation is refused. "
                  "A player who accepted an invitation records the link in Profile.christmasLink; the engine then treats "
                  "12/24 daytime and evening as taken with the lock reason `christmas_date` (平安夜约会). No day record "
                  "carries that lock because it depends on the player's choice, not on the calendar.")
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


def automatic_story_stats(ledger: Ledger) -> dict[int, dict]:
    """Guaranteed story gains only, after the listed daytime slot; never optional free choices."""
    evidence = read_evidence("automatic_story_stats_evidence.json")
    for guard in evidence["localSourceGuards"]:
        if sha256_file(ROOT / guard["path"]) != guard["sha256"]:
            raise BuildError(f"automatic story stats: source changed: {guard['path']}")
    general = AqiuPage(SOURCES_DIR / "aqiu_pages/social-events.html").subsection("general")
    if "Mandatory summer school from 8/10-8/15 will grant a total of Academics +18" not in general:
        raise BuildError("automatic story stats: aqiu mandatory summer-school total changed")
    route = (SOURCES_DIR / WALKTHROUGH_REL).read_text(encoding="utf-8")
    current = None
    attested = []
    for line in route.splitlines():
        heading = re.fullmatch(r"#### \w+ (\d+)/(\d+)", line)
        if heading:
            current = day_of(int(heading[1]), int(heading[2]))
        gain = re.fullmatch(r"\* Summer School \(Academics \+(\d+), 2 notes\)", line)
        if gain:
            attested.append((current, {"academics": int(gain[1])}))
    actual = [(day_of_md(event["date"]), event["gain"]) for event in evidence["events"]]
    if actual != attested or len(actual) != 6 or sum(g["academics"] for _, g in actual) != 18:
        raise BuildError("automatic story stats: six summer-school dates/gains must match the pinned route")
    ledger.decide("calendar", "automatic story stats", "Mandatory summer school on 8/10–8/15 gives Academics +3 "
                  "after each daytime slot (six sessions, +18 before the cap). Exact points are single-source "
                  "aqiu; its general total and tested route agree, while the Chinese guide corroborates story "
                  "attendance on 8/10–8/14. Already-observed days and evening slots award nothing again. Optional "
                  "class answers, dozing, nurse visits and exam placements are not automatic gains.")
    return {day_of_md(event["date"]): event for event in evidence["events"]}


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
        # The deadline includes the evening; the planner still keeps its one-day rescue margin.
        rescue_notes.setdefault(day_of_md(first), []).append(f"{zh}失踪了，期限 {deadline}，到塔尔塔罗斯把人救出来")
        rescue_notes.setdefault(day_of_md(last), []).append(
            f"今天是救出{zh}的最后期限，最晚今晚救出")

    automatic = automatic_story_stats(ledger)
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
            notes.append("平安夜：默认按婉拒所有邀约来安排；选了赴约的对象，白天和晚上就都留给平安夜约会")
        if md == "01-21":
            notes.append("如果之前把「白色切花」送给了顺平，这天放学后会有剧情，所以先空出来")
        story_gain = automatic.get(d)
        if story_gain is not None:
            if dl is None or dl.reason != story_gain["dayLock"]:
                raise BuildError(f"automatic story stats: {md} is not the expected locked summer-class daytime")
            notes.append("暑期补习：学力 +3")
        for b in blocks:
            if b["fromDay"] == d:
                notes.append(f"塔尔塔罗斯的新区块开放了：{b['zh']}（{b['floorsZh']}）")
        notes.extend(rescue_notes.get(d, []))
        confs = [x.conf for x in (dl, el) if x is not None]
        if story_gain is not None:
            confs.append(story_gain["conf"])
        aqiu_only_closure = block >= 0 and el is None and d in aqiu_closed and d not in closed
        if aqiu_only_closure:
            confs.append("single")
        conf = next((c for c in ("estimate", "conflict", "single") if c in confs), "verified")
        src = sorted({s for x in (dl, el) if x is not None for s in x.src} | {"aqiu_social_events", "gd_activity"})
        if story_gain is not None:
            src = sorted(set(src) | {"aqiu_walkthrough", "cn_guide", "ev_automatic_story_stats"})
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
        if story_gain is not None:
            days[-1]["autoStatGain"] = dict(story_gain["gain"])

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
                        "src": list(RESCUE_SRC), "toDay": day_of_md(last), "zh": zh})
    ledger.decide("tartarus", "rescues", "Only the two missing people that gate a Social Link are rescues: 北村文吉 "
                  "9/12–10/3 (教皇) and 舞子 10/21–11/2 (倒悬者) (DisappearDataAsset Start/Limit = aqiu overworld; the "
                  "game board text says Bunkichi was reported missing on 9/11, but the aqiu calendar still has a "
                  "Hierophant cell that day). Both days count: a Tartarus evening from fromDay through toDay rescues "
                  "them (see 'rescue deadline'). The other 18 missing people only reward items (Ayako also gates "
                  "request #97) and are left out.")
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
        "noteZh": "需要学力 MAX，且至少有一次考试拿过第一名。",
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
        "noteZh": "8月9日起可开始解锁。",
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
                 "随探索进度推进。",
    "emperor": "11月3日～11月29日小田桐秀利不在学生会，见不到面。",
    "empress": "考试前一周（12月7日～12月12日）她每天放学后都在。",
    "hierophant": "9月12日北村文吉失踪，期限 10月3日，要到塔尔塔罗斯救出他，否则教皇社群无法继续。",
    "hanged_man": "10月21日舞子失踪，期限 11月2日，要到塔尔塔罗斯救出她，否则倒悬者社群无法继续。",
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


def load_approved_dialogue() -> dict:
    """Persistent, approved wording only; source drift must fail rather than silently change advice."""
    approved = read_evidence("dialogue_guidance_approved.json")
    if approved.get("schema") != "p3r.dialogue_guidance_approved.v1":
        raise BuildError("approved dialogue: unknown evidence schema")
    sources = {}
    for source in approved["sources"]:
        path = source["path"]
        if not path.startswith("rules/sources/") or ".." in Path(path).parts:
            raise BuildError(f"approved dialogue: invalid source path {path}")
        if source["id"] in sources or source.get("edition") != "cn_guide_reload":
            raise BuildError(f"approved dialogue: duplicated or non-Reload source {source['id']}")
        if sha256_file(ROOT / path) != source["sha256"]:
            raise BuildError(f"approved dialogue: source changed: {path}")
        sources[source["id"]] = source
    scenes = approved["scenes"]
    if len(scenes) != 115 or sum(scene["new"] for scene in scenes) != 88:
        raise BuildError("approved dialogue: expected 115 approved scenes (88 new, 27 repaired)")
    held = {(scene["link"], scene["rank"]) for scene in approved["heldCandidates"]}
    if len(held) != 8:
        raise BuildError("approved dialogue: expected eight held candidates")
    seen = set()
    for scene in scenes:
        key = (scene["link"], scene["rank"])
        if scene["link"] not in LINK_IDS or scene["link"] in STORY_LINKS or not 2 <= scene["rank"] <= 10:
            raise BuildError(f"approved dialogue: invalid scene {key}")
        if key in seen or key in held:
            raise BuildError(f"approved dialogue: duplicated or held scene {key}")
        seen.add(key)
        for source in [scene["source"], scene["source"].get("correction")]:
            if source and (source["id"] not in sources or sources[source["id"]]["path"] != source["path"]):
                raise BuildError(f"approved dialogue {key}: unregistered source {source}")
        if not scene["sequence"] or not scene["variants"] or not set(scene["variants"]) <= {"common", "friend", "romance"}:
            raise BuildError(f"approved dialogue {key}: missing sequence or invalid route variants")
        for route, variant in scene["variants"].items():
            where = f"approved dialogue {key}.{route}"
            if not isinstance(variant.get("choices"), list) or not isinstance(variant.get("sequence"), list):
                raise BuildError(f"{where}: ordered choices and sequence arrays required")
            if not variant["choices"] and not (variant.get("any") or variant.get("avoid") or variant.get("noteZh")):
                raise BuildError(f"{where}: empty guidance")
            if any(choice.startswith("任意") or choice == "之后任意" for choice in variant["choices"]):
                raise BuildError(f"{where}: arbitrary prompts belong in sequence, not compact choices")
            if "shortChoices" in variant and len(variant["shortChoices"]) != len(variant["choices"]):
                raise BuildError(f"{where}: shortChoices must preserve question count")
    return approved


def approved_dialogue_hints(approved: dict) -> dict[str, dict[str, dict]]:
    out: dict[str, dict[str, dict]] = {}
    for scene in approved["scenes"]:
        src = ["ev_dialogue_approved", scene["source"]["id"]]
        if scene["source"].get("correction"):
            src.append(scene["source"]["correction"]["id"])
        entry = {"conf": "single", "src": sorted(set(src))}
        # Round-trip the evidence values so later pack edits cannot mutate the approved source object.
        entry.update(json.loads(fix_names(json.dumps(scene["variants"], ensure_ascii=False))))
        out.setdefault(scene["link"], {})[str(scene["rank"])] = entry
    return out


COMPLETION_VARIANT_FIELDS = frozenset({"choices", "shortChoices", "any", "avoid", "noteZh", "shortNoteZh", "sequence"})
WORDING_ORIGINS = frozenset({"existing_zh_guide", "editorial_translation", "official_zh_cn_seen"})


def load_dialogue_completion() -> dict:
    """Read durable quotations/translations; no live web or disposable QA files are build inputs."""
    completion = read_evidence("dialogue_guidance_completion.json")
    if completion.get("schema") != "p3r.dialogue_guidance_completion.v1":
        raise BuildError("dialogue completion: unknown evidence schema")
    for guard in completion["localSourceGuards"]:
        path = guard["path"]
        if not path.startswith("rules/") or ".." in Path(path).parts:
            raise BuildError(f"dialogue completion: invalid guarded source {path}")
        if sha256_file(ROOT / path) != guard["sha256"]:
            raise BuildError(f"dialogue completion: guarded source changed: {path}")
    sources = {}
    for source in completion["sources"]:
        ident = source["id"]
        if ident in sources or not re.fullmatch(r"completion_[a-z0-9_]+", ident):
            raise BuildError(f"dialogue completion: duplicate or invalid source {ident}")
        if source.get("edition") not in RELOAD_EDITIONS or not source.get("url", "").startswith("https://"):
            raise BuildError(f"dialogue completion: non-Reload or missing source URL {ident}")
        if source.get("captureKind") != "web_tool_rendered_excerpt" or not source.get("excerpts"):
            raise BuildError(f"dialogue completion: {ident} needs an explicitly rendered source excerpt")
        if not source.get("note"):
            raise BuildError(f"dialogue completion: {ident} needs a source-scope note")
        sources[ident] = source
    seen = set()
    for scene in completion["scenes"]:
        key = (scene["link"], scene["rank"])
        if scene["link"] not in LINK_IDS or scene["link"] in STORY_LINKS or not 2 <= scene["rank"] <= 10 or key in seen:
            raise BuildError(f"dialogue completion: invalid or duplicated scene {key}")
        seen.add(key)
        if not scene.get("sourceIds") or not set(scene["sourceIds"]) <= sources.keys():
            raise BuildError(f"dialogue completion {key}: unknown source IDs")
        source_urls = {sources[ident]["url"] for ident in scene["sourceIds"]}
        if not scene.get("variants") or not set(scene["variants"]) <= {"common", "friend", "romance"}:
            raise BuildError(f"dialogue completion {key}: missing or invalid variants")
        for route, variant in scene["variants"].items():
            where = f"dialogue completion {key}.{route}"
            if not isinstance(variant.get("choices"), list) or not isinstance(variant.get("sequence"), list):
                raise BuildError(f"{where}: ordered choices and sequence required")
            if not variant["choices"] and not (variant.get("any") or variant.get("avoid") or variant.get("noteZh")):
                raise BuildError(f"{where}: empty guidance")
            if any(choice.startswith("任意") or choice == "之后任意" for choice in variant["choices"]):
                raise BuildError(f"{where}: arbitrary prompts belong in sequence")
            if "shortChoices" in variant and len(variant["shortChoices"]) != len(variant["choices"]):
                raise BuildError(f"{where}: shortChoices changes question count")
            for item in [variant] + variant.get("alternatives", []):
                origins = item.get("choiceOrigins", [])
                if len(origins) != len(item.get("choices", [])):
                    raise BuildError(f"{where}: every concrete choice needs wording provenance")
                for choice, origin in zip(item.get("choices", []), origins):
                    if origin.get("text") != choice or origin.get("origin") not in WORDING_ORIGINS:
                        raise BuildError(f"{where}: invalid choice origin")
                    if not origin.get("sourceUrl", "").startswith("https://") or not origin.get("sourceText"):
                        raise BuildError(f"{where}: choice origin needs source wording and URL")
                    if origin["sourceUrl"] not in source_urls:
                        raise BuildError(f"{where}: wording source is not registered for this scene")
                    if origin["origin"] == "official_zh_cn_seen" and not origin.get("officialObservation"):
                        raise BuildError(f"{where}: official wording requires an inspected in-game observation")
                if item is not variant and (not item.get("whenZh") or not isinstance(item.get("sequence"), list)):
                    raise BuildError(f"{where}: alternate route needs condition and full sequence")
    return completion


def _contains_translation(node) -> bool:
    if isinstance(node, dict):
        return node.get("origin") == "editorial_translation" or any(_contains_translation(value) for value in node.values())
    if isinstance(node, list):
        return any(_contains_translation(value) for value in node)
    return False


# Commentary removed only from player-facing display copy; the original evidence retains it.
GUIDANCE_EDITORIAL_COPY = {
    "后者为译意。": "",
    "此处「我很喜欢」沿用已有中文表达，但友情场景未核对简中画面，按译意标记。": "",
    "这里「那是不被允许的」为日文选项译意，按含义对应。": "",
    "「放心了」「抱歉，但是……」是译意，按含义对应。": "",
    "该具体选项为译意，按含义对应。": "",
    "（译意）": "",
    "，这里给出一套具体回答": "",
    "，不是提示被截断": "",
    "按档案中的朋友／恋人目标展示。途中导入或更改目标时，以实际出现的问题为准；另一场景的完整顺序见下文。":
        "不同前置选择对应的两组剧情见下文。",
    "首问好感标注有来源差异，不宣称此项分数已确定。": "",
    "第二问「这就泄气了？」为依据日文攻略的译义，未核官方简中原句；其他回答沿用中文攻略。": "",
    "括号内的问题与条件为译意说明。": "",
    "这里的「是友情」为译意提示，按含义对应。": "",
    "旧中文固定攻略的三个「任意」未精确区分路线；本处按Reload分支表保存两问。": "",
}


def completion_display_text(value):
    """Compile concise player copy without changing the original source quotations."""
    if isinstance(value, str):
        for before, after in GUIDANCE_EDITORIAL_COPY.items():
            value = value.replace(before, after)
        value = re.sub(r"(?<![A-Za-z])R\s*(\d+)", r"RANK \1", value)
        return fix_names(value.replace("Reload", "本作"))
    if isinstance(value, list):
        return [completion_display_text(item) for item in value]
    if isinstance(value, dict):
        out = {key: completion_display_text(item) for key, item in value.items()}
        if not out.get("noteZh", ""):
            out.pop("noteZh", None)
        return out
    return value


def compile_completion_variant(variant: dict, route: str) -> dict:
    """Keep provenance in evidence; flatten alternate scenes only into the detail sequence."""
    out = {key: json.loads(json.dumps(value, ensure_ascii=False))
           for key, value in variant.items() if key in COMPLETION_VARIANT_FIELDS}
    alternatives = variant.get("alternatives", [])
    if alternatives:
        heading = variant.get("whenZh", "按友情目标的推荐流程" if route == "friend" else "按恋爱目标的推荐流程")
        sequence = [f"【{heading}】"] + list(out["sequence"])
        for alternative in alternatives:
            sequence += [f"【{alternative['whenZh']}】"] + alternative["sequence"]
            if alternative.get("noteZh"):
                sequence.append(alternative["noteZh"])
        out["sequence"] = sequence
    if _contains_translation(variant):
        # Wording provenance stays in evidence; detail-only conditions stay out of compact rows.
        out.setdefault("shortNoteZh", "")
    return completion_display_text(out)


def completed_dialogue_hints(completion: dict) -> dict[str, dict[str, dict]]:
    out: dict[str, dict[str, dict]] = {}
    for scene in completion["scenes"]:
        entry = {"conf": "single", "src": sorted({"ev_dialogue_completion", *scene["sourceIds"]})}
        entry.update({route: compile_completion_variant(variant, route) for route, variant in scene["variants"].items()})
        out.setdefault(scene["link"], {})[str(scene["rank"])] = entry
    return out


def dialogue_map(approved: dict | None = None, completion: dict | None = None) -> dict[str, dict[str, list[str]]]:
    out: dict[str, dict[str, list[str]]] = {}
    seen_references: dict[str, dict] = {}
    for ref in read_evidence("guide_dialogue_reference_map.json")["references"]:
        ident = ref["referenceId"]
        if ident in seen_references:
            if seen_references[ident] != ref:
                raise BuildError(f"dialogue reference {ident}: conflicting duplicate")
            continue
        seen_references[ident] = ref
        link = ref["entityId"].split(".")[-1]
        rank = ref["targetRank"]
        frags = ref.get("dialogueChoices") or [f["text"] for f in ref.get("displayFragments", [])]
        frags = [fix_names(f) for f in frags if f.strip()]
        if not frags:
            continue
        bucket = out.setdefault(link, {}).setdefault(str(rank), [])
        # These are successive questions, not a set of labels: 任意/任意 and 选2/选2
        # each represent two prompts. Only an entire identical reference ID is deduplicated.
        bucket.extend(frags)
    if approved is None:
        approved = load_approved_dialogue()
    for scene in approved["scenes"]:
        out.setdefault(scene["link"], {})[str(scene["rank"])] = [fix_names(text) for text in scene["sequence"]]
    if completion is None:
        completion = load_dialogue_completion()
    for scene in completion["scenes"]:
        # The typed entry is authoritative. This legacy array remains an ordered archive of one
        # available scene, never a fallback that can override another route's typed sequence.
        if scene.get("legacySequence") is not None:
            sequence = scene["legacySequence"]
        else:
            variants = scene["variants"]
            route = next(route for route in ("common", "romance", "friend") if route in variants)
            sequence = compile_completion_variant(variants[route], route)["sequence"]
        out.setdefault(scene["link"], {})[str(scene["rank"])] = [fix_names(text) for text in sequence]
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
    approved_dialogue = load_approved_dialogue()
    completed_dialogue = load_dialogue_completion()
    dialogue = dialogue_map(approved_dialogue, completed_dialogue)
    dialogue_hints = approved_dialogue_hints(approved_dialogue)
    completion_hints = completed_dialogue_hints(completed_dialogue)
    for link, ranks in completion_hints.items():
        # Replacing a scene, rather than merging route fields, is deliberate: an old common
        # answer must not survive when new branch research restricts it to a particular route.
        dialogue_hints.setdefault(link, {}).update(ranks)
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
                       "src": list(RESCUE_SRC), "toDay": day_of_md(last)}

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
        if any(scene["link"] == link for scene in approved_dialogue["scenes"]):
            src.append("ev_dialogue_approved")
        if link in completion_hints:
            src.append("ev_dialogue_completion")
        if link in ("aeon", "empress"):
            src += ["ev_late_link_availability", "powerpyx_100pct", "rpgsite_100pct", "pushsquare_social_links"]
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
        if link in dialogue_hints:
            record["dialogueHints"] = dialogue_hints[link]
        places = [record["locationZh"] or ""] + [s["locationZh"] for s in unlock["steps"]]
        bad = [part for place in places for part in location_tokens_ok(place, guide_text)]
        if bad:
            raise BuildError(f"{link}: location fragments {bad} not found in the CN guide")
        ledger.dropped[link] = dropped
        out.append(record)

    ledger.decide("links", "dialogue", "115 user-approved Chinese-guide scenes (88 new, 27 repairs) are compiled "
                  "from persistent dialogue_guidance_approved.json, guarded by hashes of the original 3DM/游民星空 "
                  "pages and existing corrections. Legacy dialogue arrays preserve ordered and repeated answers. "
                  "Optional dialogueHints are authoritative when present: common or the selected friend/romance "
                  "variant supplies concrete choices and complete sequence; an absent applicable variant means "
                  "unknown guidance, never a reason to reinterpret a different route's legacy answers. "
                  f"A later authorized completion overlays {len(completed_dialogue['scenes'])} scenes from "
                  "dialogue_guidance_completion.json; it preserves source quotations, all-option comparisons "
                  "where arbitrary answers are claimed, editorial-translation provenance and complete route "
                  "alternatives in the detail sequence. The historical 115-scene approval is unchanged. Display-only changes "
                  "do not alter thresholds, event gains or planning scores.")
    ledger.decide("links", "names", "Arcana and person names come from the zh-Hans game tables (DatRaceDataAsset, "
                  "DatPlayerNameDataAsset): 女皇, 倒悬者, 月亮, 贝培, Ｙ子, 舞子 (no surname in-game), 北村文吉、光子. "
                  "Judgement shows the spoiler-safe 伙伴们 instead of the official label 倪克斯讨伐队; Fool uses the "
                  "official 特别课外活动部.")
    ledger.decide("links", "avail", "avail = aqiu O cells minus locked slots, minus Emperor cells 11/3-11/29 (aqiu note; one "
                  "contrary cell on 11/16 dropped as the stricter claim). The aqiu O calendar is a guide table, not a raw "
                  "game-data standing schedule. Reload dated routes supersede the old generic weekday exclusions: keep "
                  "Aeon on 1/12, 1/14, 1/19, 1/26, 1/28 and Empress on 11/30, 12/4, 12/21. No O cell is invented: "
                  "1/21 stays story-locked, Aeon is still absent 1/25, and Empress has no Monday/Friday O cells from 12/25. "
                  "Cells inside rescue windows are kept: the missing window, not the calendar, decides whether the person is there.")
    ledger.conflict("avail.emperor.11-16", "Emperor on 11/16", "unavailable (aqiu availability note)", ["available (aqiu cell + route witness)"],
                    ["aqiu_social_events", "ev_calendar_constraints"])
    ledger.conflict("avail.devil.10-03", "Devil on Saturday 10/3", "no meeting (aqiu has no cell that evening)",
                    ["meeting (the CN guide meets Tanaka on the evening of 10/3)"], ["aqiu_social_events", "cn_guide"])
    ledger.conflict("avail.empress.monfri", "Empress on Mondays/Fridays", "keep original O cells, including 11/30, 12/4, 12/21",
                    ["old generic weekday filter kept only pre-exam 12/7-12/12"],
                    ["aqiu_social_events", "aqiu_walkthrough", "powerpyx_100pct", "rpgsite_100pct", "ev_late_link_availability"])
    ledger.conflict("avail.aeon.tuethu", "Aeon on Tuesdays/Thursdays", "keep original O cells, subject to story locks",
                    ["generic overviews list Mon/Wed/Fri/Sat only"],
                    ["aqiu_social_events", "powerpyx_100pct", "rpgsite_100pct", "pushsquare_social_links",
                     "game8_social_links", "pcinvasion_aeon", "ev_late_link_availability"])
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
             evidence: tuple[str, str] | None = None, once: bool = False,
             requires_activity: str | None = None) -> dict:
    entry = {
        "spec": {"aqiu": aqiu, "evidence": evidence},
        "record": {
            "alt": alt or [], "conf": conf, "cost": cost, "dayTypes": day_types or [], "fromDay": day_of_md(from_md),
            "gain": gain or {}, "id": ident, "kind": kind, "locationZh": location, "noteZh": note, "once": once,
            "pay": pay or [], "requires": requires or {}, "slots": slots, "src": src, "toDay": LAST_DAY,
            "weekdays": weekdays, "zh": zh,
        },
    }
    if requires_activity is not None:
        entry["record"]["requiresActivity"] = requires_activity
    return entry


ACTIVITIES = (
    activity("study_room", "在自己房间学习", "stat", ["eve"], ALL_WEEK, "宿舍 · 自己的房间", gain={"academics": 2},
             src=["aqiu_social_events", "ev_stats_actions", "cn_guide"], aqiu="Study at your desk*",
             evidence=("stats_actions.json", "study_room_night")),
    activity("study_room_day", "在自己房间学习", "stat", ["day"], ALL_WEEK, "宿舍 · 自己的房间", gain={"academics": 2},
             day_types=["sunday", "holiday", "vacation"], src=["aqiu_social_events"], conf="single",
             note="没课的日子白天也能在房间学习。"),
    activity("library", "到学校图书室自习", "stat", ["day"], [1, 2, 3, 4, 5, 6], "月光馆学园 · 图书室",
             gain={"academics": 2}, day_types=["school"], conf="conflict", src=["aqiu_social_events", "ev_stats_actions"],
             note="自习预计学力 +2。", alt=["+4 academics (aqiu, gf81170)"],
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
    activity("meal_wakatsu_menu", "在定食若津用餐，了解限定菜单", "stat", ["eve"], [1, 4, 5, 7], "严户台商店街 · 定食若津",
             cost=680, gain={"academics": 3}, once=True, src=["aqiu_social_events", "ev_stat_expansion", "cn_guide"],
             note="星期一、四、五、日晚上先吃一次普通餐，留意店里关于限定菜单的对话。之后魅力达到 Lv.3，就能点「水产全席套餐」。",
             aqiu="Wakatsu: Prodigy Platter", evidence=("stat_activity_expansion_candidate.json", "meal_wakatsu_regular")),
    activity("meal_wakatsu_special", "在定食若津吃「水产全席套餐」", "stat", ["eve"], [1, 4, 5, 7], "严户台商店街 · 定食若津",
             cost=900, gain={"academics": 4}, requires={"charm": 3}, requires_activity="meal_wakatsu_menu",
             src=["aqiu_social_events", "ev_stat_expansion", "cn_guide"],
             note="魅力 Lv.3，且已经在限定菜单供应的晚上吃过普通餐、了解菜单以后，星期一、四、五、日晚上可点。学力 +4，花费 900 日元。",
             aqiu="Wakatsu: Seafood Full Course*", evidence=("stat_activity_expansion_candidate.json", "meal_wakatsu_secret")),
    activity("meal_hagakure_menu", "在叶隐拉面用餐，了解限定菜单", "stat", ["eve"], [2, 3, 5], "严户台商店街 · 叶隐拉面",
             cost=900, gain={"charm": 3}, once=True, src=["aqiu_social_events", "ev_stat_expansion", "cn_guide"],
             note="星期二、三、五晚上先吃一次普通拉面，留意店里关于限定菜单的对话。之后勇气达到 Lv.3，就能点限定餐。",
             aqiu="Hagakure: Pork Ramen", evidence=("stat_activity_expansion_candidate.json", "meal_hagakure_regular")),
    activity("meal_hagakure_special", "在叶隐拉面点限定餐", "stat", ["eve"], [2, 3, 5], "严户台商店街 · 叶隐拉面",
             cost=1200, gain={"charm": 4}, requires={"courage": 3}, requires_activity="meal_hagakure_menu",
             src=["aqiu_social_events", "ev_stat_expansion", "cn_guide"],
             note="勇气 Lv.3，且已经在限定菜单供应的晚上吃过普通拉面、了解菜单以后，星期二、三、五晚上可点。魅力 +4，花费 1,200 日元。",
             aqiu="Hagakure: Hagakure Bowl*", evidence=("stat_activity_expansion_candidate.json", "meal_hagakure_secret")),
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
             src=["gd_arbeit_help", "aqiu_social_events", "ev_jobs", "megatenwiki_chagall_cafe", "wiki469", "cn_guide"],
             note="星期二客人多，工钱更高。", alt=["+2 each stat (siliconera)"], aqiu="Chagall: Part-time Job"),
    activity("job_accessory", "在饰品店打工", "job", ["day"], [1, 2, 3, 4, 5], "桐叶购物中心 · 饰品店",
             gain={"academics": 1}, pay=[[1, 3500], [3, 4500], [6, 5500], [10, 7000]], from_md="07-24", conf="conflict",
             src=["gd_arbeit_help", "aqiu_social_events", "ev_jobs", "megatenwiki_be_blue_v", "wiki469"],
             note="7月24日起招人，预计学力 +1。",
             alt=["+1 courage (aqiu)", "+1 charm (wiki469/megatenwiki)", "Saturdays too (aqiu)", "unlock 7/22-7/23 (siliconera, wiki469)"],
             aqiu="Be Blue V: Part-time Job*"),
    activity("job_cinema", "在电影院打工", "job", ["day"], [7], "港湾人工岛站 · 电影院",
             gain={"academics": 1}, pay=[[1, 5000], [3, 6000], [6, 7000], [10, 9000]], conf="conflict",
             src=["gd_arbeit_help", "aqiu_social_events", "ev_jobs", "megatenwiki_port_island_station", "wiki469"],
             note="星期日白天招人，预计学力 +1。",
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
                and ("fixedDays" not in a or day["d"] in a["fixedDays"])
                for a in activities
            )
            if not ok:
                missing.append(f"{day['md']} {slot}")
    if missing:
        raise BuildError("free slots without a zero-cost activity: " + ", ".join(missing))


def audit_activity_prerequisites(activities: list[dict]) -> None:
    """Activity prerequisites are prior recorded uses; reject missing identities and cycles."""
    by_id = {a["id"]: a for a in activities}
    for activity in activities:
        seen = set()
        current = activity
        while current is not None:
            ident = current["id"]
            if ident in seen:
                raise BuildError(f"activity {activity['id']}: requiresActivity contains a cycle")
            seen.add(ident)
            prerequisite = current.get("requiresActivity")
            if prerequisite is None:
                break
            if prerequisite not in by_id:
                raise BuildError(f"activity {ident}: requiresActivity references unknown activity {prerequisite}")
            current = by_id[prerequisite]


def build_dated_dorm_activities(ledger: Ledger, days: list[dict]) -> list[dict]:
    """Dated evening opportunities; never expand a dorm calendar into weekly availability."""
    out = []
    study = read_evidence("group_study_evidence.json")
    for session in study["sessions"]:
        day = day_of_md(session["date"])
        gain = {stat: value for stat, value in zip(STAT_IDS, session["gains"]) if value}
        rec = activity(session["id"], session["nameZh"], "stat", ["eve"], ALL_WEEK,
                       "宿舍 · 大厅", gain=gain, src=["aqiu_walkthrough", "ev_group_study"],
                       conf=session["conf"],
                       note=f"考前限定，不花钱，但会用掉晚上。预计学力 +{session['gains'][0]}。"
                            + "在宿舍大厅和队友一起学习。")["record"]
        rec.update(fixedDays=[day], fromDay=day, toDay=day)
        out.append(rec)

    dorm = read_evidence("dorm_activities_evidence.json")
    affinity = read_evidence("dorm_affinity_evidence.json")["activities"]
    if not set(affinity) <= {entry["id"] for entry in dorm["activities"]}:
        raise BuildError("dorm affinity references an unknown dorm activity")
    for entry in dorm["activities"]:
        dates = sorted((datetime.date.fromisoformat(date) - EPOCH).days for date in entry["dates"])
        if not dates or len(dates) != len(set(dates)) or dates[0] < FIRST_DAY or dates[-1] > LAST_DAY:
            raise BuildError(f"{entry['id']}: invalid fixed dorm dates")
        rec = activity(entry["id"], entry["zh"], "stat", ["eve"], ALL_WEEK, entry["locationZh"],
                       gain=entry["gain"], src=["aqiu_social_events", "ev_dorm_activities"],
                       conf="single", note=entry["noteZh"])["record"]
        rec.update(fixedDays=dates, fromDay=dates[0], toDay=dates[-1], dormPartnerZh=entry["partnerZh"],
                   requiresPlantedCrop=entry["requiresPlantedCrop"])
        if entry["id"] in affinity:
            bonus = affinity[entry["id"]]
            if bonus["link"] not in LINK_IDS or bonus["rawPoints"] != 1:
                raise BuildError(f"{entry['id']}: invalid raw dorm affinity policy")
            rec.update(affinityLink=bonus["link"], affinityPts=bonus["rawPoints"])
            rec["conf"] = "conflict"
            rec["src"].append("ev_dorm_affinity")
        out.append(rec)
    ledger.decide("activities", "group study", "Four exam periods offer 14 dated, zero-yen evening sessions: "
                  "Academics +4 normally and a conservative +5 on the four whole-team evenings (another guide says +6). "
                  "No Social Link affinity or characteristic progress is awarded. They use a full evening and respect "
                  "ordinary story locks; stats above Lv6 have no planning value.")
    ledger.decide("activities", "dorm activities", "18 partner/category tracks use exact aqiu calendar cells, "
                  "including Koromaru Brushing (never ordinary walks). Each category's first three completions count "
                  "towards a characteristic; any one category at three unlocks the base effect, both at three upgrade it. "
                  "Only the eight reading/DVD tracks give the sourced +2 social stat. Cooking, tea, coffee, brushing "
                  "and gardening give items, not invented social stats. Eight female Social Link tracks also give "
                  "one raw affinity point within an established link's current gap, without multipliers or banking. "
                  "The affinity remains available after the third completion. Existing used[] counts "
                  "store actual per-category progress; forecasts stay separate. Gardening is disabled by default until "
                  "the player opts into preparing planted crops; seed/inventory costs are outside the cash estimate.")
    return out


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
    out.extend(build_dated_dorm_activities(ledger, days))
    audit_activity_prerequisites(out)
    ledger.decide("activities", "selection", "Planned activities: desk study (evening; daytime on days without school), "
                  "library (school days, +2 lower bound), Wakatsu/Hagakure/Wilduck regular meals, Wakatsu/Hagakure "
                  "special menus after their stat gate and a recorded discovery meal, karaoke, the three arcade "
                  "games (evening only, the stricter reading), the three weekday movie programmes, Chagall coffee, and the "
                  "three jobs. Every row is checked against the aqiu table (gains never above aqiu, same price, slots a "
                  "subset) and, where present, the evidence files and the game's job help text.")
    ledger.decide("activities", "special menus", "Wakatsu Seafood Full Course (Charm Lv3, 900 yen, Academics +4, "
                  "Mon/Thu/Fri/Sun evening) and Hagakure Bowl (Courage Lv3, 1200 yen, Charm +4, Tue/Wed/Fri evening) "
                  "require a prior regular meal on an evening when the special menu is offered (aqiu Reload). "
                  "Each restaurant has a once-only discovery meal with the regular cost/gain and those evening weekdays; "
                  "the special meal requires its recorded activity use. The discovery need not meet the stat gate: "
                  "aqiu's walkthrough eats Wakatsu regular on 5/3 before reaching Charm Lv3 on 5/7. Unknown imported "
                  "menu history conservatively repeats that meal. 水产全席套餐 is CN-guide wording; Hagakure's exact "
                  "SC menu name is unverified, so the UI says 限定餐.")
    ledger.decide("activities", "excluded", "Not planned: the weekend Wilduck set "
                  "(its unlock needs completed Request #11 that the model cannot express), the one-off "
                  "burger challenge (needs Requests #1/#9/#11), shrine donations (single source, unclear Reload value), "
                  "film-festival hangouts and shrine fortunes. Link Episodes are opt-in tracks "
                  "(`episodes`) and the dating-site note is `datingNote`, both outside `activities`.")
    audit_fallbacks(out, days)
    ledger.decide("activities", "no idle slots", "Every unlocked slot from 4/21 offers at least one free productive "
                  "activity (evening desk study; library after school; desk study in the daytime when there is no "
                  "school), so a plan never has to leave a slot idle; jobs are the only forecast income and beat "
                  "idling whenever money is useful. The builder fails if a free slot lacks such a fallback. Reload has "
                  "no fatigue or sickness, so a Tartarus night carries no next-day penalty.")
    ledger.decide("activities", "jobs", "Jobs pay the conservative non-busy tier per completed shift (wage tables from "
                  "the Persona 3 Reload sections of the MegaTen Wiki pages Chagall_Cafe (café), Be_Blue_V (accessory "
                  "shop) and Port_Island_Station (Screen Shot cinema), and wiki469; base pay confirmed by the game help "
                  "text: café ¥2,500+ at night, accessory shop ¥3,500+ by day, cinema ¥5,000+ by day). Chagall from 5/4 (first CN-guide witness; earliest date "
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
        # CN guide (cn_guides/data.json): ep1 5/12 classroom, ep2 8/9 invitation, ep3 11/7 invitation,
        # ep4 12/19 classroom, ep5 1/15 invitation (苏尔特尔).
        "witness": {1: "05-12", 2: "08-09", 3: "11-07", 4: "12-19", 5: "01-15"},
    },
    {
        "id": "akihiko", "column": "Aki", "evening": True, "section": "akihiko", "name": "Akihiko Sanada",
        "location": "严户台站",
        "how": "晚上打开手机，答应真田明彦的邀约就行。",
        "note": None,
        "reward": "每一话都有小奖励（主角的能力、魅力或道具），看完第 5 话可以合体人格面具「荷鲁斯」。",
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
        "reward": "每一话都有小奖励（能力值或主角的能力），看完第 5 话可以合体人格面具「米迦勒」。",
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
        "noteZh": "给已解锁、未 MAX 的社群增加好感，预计 +10；不直接升级，占用一个晚上，可反复购买。",
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
# 当日提醒 (reminders): slot-free things to answer or do on a game day


# The 3DM walkthrough's schedule pages, pinned unmodified: 4月日程 is page 2 and 1月日程~结局 page 11 (its text
# ends on 1.13); page 12 is 怪物弱点. The December page has no text before 12.13.
CN_3DM_SCHEDULE_PAGES = tuple(range(2, 12))
CN_3DM_MONTHS = dict(zip(CN_3DM_SCHEDULE_PAGES, ("April", "May", "June", "July", "August", "September", "October",
                                                 "November", "December", "January")))
# 游民星空's copy of the same guide (pinned unmodified, pages 1-10 = April … January): its December and January pages
# have the text 3DM lacks. Only the class answers of dates without a 3DM line are taken from it (GAMERSKY_SOURCE_PAGES
# are the pages they are on); every other class line is a zh-vs-zh cross-check of 3DM (answer and option position).
GAMERSKY_PAGES = tuple(range(1, 11))
GAMERSKY_MONTHS = dict(zip(GAMERSKY_PAGES, ("April", "May", "June", "July", "August", "September", "October",
                                            "November", "December", "January")))
GAMERSKY_SOURCE_PAGES = (9, 10)
# Additional pinned pages quoted by the approved dialogue evidence. This does not broaden the
# class-answer import policy above: the two sets have separate purposes and validation.
GAMERSKY_DIALOGUE_SOURCE_PAGES = (6, 7, 8, 9, 10)
# Obvious typos of the 游民星空 copy, each corrected to BWIKI's spelling of the same date (zh vs zh): the copy types an
# em dash for 一 throughout (—起 for 一起), here inside the idiom 孤注一掷.
GAMERSKY_TYPO_FIXES = {("12-11", "孤注—掷"): "孤注一掷"}
# aqiu class dates without a zh answer line on the 3DM pages or the 游民星空 copy. They are never filled in from the CN
# guide, which is an edited copy of 3DM with English-localisation answers on 9 dates (CN_GUIDE_DIVERGENT).
WITHHELD_CLASS: dict[str, str] = {}
WITHHELD_EXAM: dict[str, str] = {}
# Class dates on which BWIKI's zh text is not the shipped answer, date -> BWIKI text (zh vs zh only; reported, the
# 3DM/游民星空 text is kept).
BWIKI_DIVERGENT = {"09-01": "变成零"}
# Dates BWIKI lists under the class months that are not aqiu class dates, date -> BWIKI text (not shipped): 6/4 and
# 1/14 have no class line on 3DM or 游民星空 either; 12/18 is the 12/18 exam answer.
BWIKI_EXTRA = {"06-04": "晕轮效应", "12-18": "做好了拼命的心理准备", "01-14": "岩手县"}
# Exam answers that only 3DM gives and that read like untranslated English (owner decision O4: shown, marked).
ESTIMATE_ANSWERS = frozenset({"07-17", "12-16"})
# Dates on which the CN guide's class/exam answer is not 3DM's: English-localisation text instead (zh vs zh only).
CN_GUIDE_DIVERGENT = frozenset({"05-19", "07-08", "07-15", "07-17", "10-14", "10-22", "12-11", "12-16", "12-18"})
# Obvious 3DM typos, each corrected to the CN guide's spelling on the same date (zh vs zh): (md, 3DM text) -> fix.
ZH_TYPO_FIXES = {
    ("11-07", "肉义书"): "奥义书",
    ("11-08", "人适人"): "人造人",
    ("12-22", "欣快悲"): "欣快感",
}
# 3DM rows whose line belongs to another date: (row md, line prefix) -> (md, why). Dates and option positions only.
# 游民星空's copy of the guide has the same row and gets the same fix.
CN_3DM_ROW_FIXES = {
    ("11-06", "上课问答："): ("11-07", "3DM's early-November rows are shifted (its 11.4 row has the 11/5 club note); aqiu "
                                    "and the CN guide date this class answer 11/7, and 3DM's option (1) is aqiu's Choice 1"),
}
# Explicit corrections of aqiu typos: key -> (aqiu text, corrected text, why).
AQIU_FIXES = {
    "request.44": ("6/20", "7/20", "Request #44 says 'Accept > 6/20: Collect …', but the beach items are on the Yakushima "
                   "trip: 3DM and the CN guide (t088_01) pick them up on 7/20"),
}
# Request batches that appear in the evening (3DM's evening column), date -> (3DM line, why); the others are after school.
REQUEST_BATCH_EVENING = {
    "05-10": (r"解锁任务委托系统", "the request system opens that evening: Elizabeth calls when you are back in your room"),
    "01-01": (r"新的支线更新", "the daytime is the shrine visit (new_year); 3DM lists the new requests in the evening"),
}
# Missing-person groups whose rescue a request needs (aqiu overworld bullets): group start -> (request, aqiu bullet).
RESCUE_LOCKS = {"06-18": (97, "Failing to save Ayako will lock Request #97")}
# Evenings the story spends in Tartarus before the player chooses anything (the 保健室 rule counts them like a visit).
STORY_TOWER_EVENINGS = {"04-20": (r"探索塔尔塔罗斯", "Fool Rank 2: Auto Mon 4/20 Evening")}
REMINDER_KINDS = ("class", "exam", "tv", "request", "fragment", "trip", "feature")
REMINDER_LABELS = {"class": "课堂提问", "exam": "考试", "tv": "电视购物", "request": "委托", "fragment": "黄昏的碎片",
                   "feature": "新开放"}
REMINDER_ID = re.compile(r"[a-z]+\.[0-9]{2}-[0-9]{2}(\.[a-z0-9_]+)?")
# shortZh (small widget): at most 16 characters, at most 12 of them Han.
SHORT_MAX_CHARS = 16
SHORT_MAX_HAN = 12
# Every reminder that gives a stat ends its detail with this sentence (stats are not forecast, DESIGN §10.9).
TV_DETAIL = "在自己房间看电视就能买，不会消耗时间；最好出门前先买。花的钱不在安排的预计里，买了以后记得在「同步现状」里更新现金。"
# The missing people the engine models as rescues (RESCUES), by their name in the aqiu table.
RESCUE_EN = {"bunkichi": "Bunkichi Kitamura", "maiko": "Maiko Oohashi"}
# aqiu's tested Reload route: it rescues every missing person, some of them on their limit day.
WALKTHROUGH_REL = "aqiu_pages/ave-walkthrough.md"
WALKTHROUGH_HEAD = ("# All Voiced Events Walkthrough - Persona 3 Reload", "* Test-played on Merciless",
                    "* No reloads required")
# Reload pages REPORT quotes beside the limit-evening decision: snapshot -> (title fragment, sentences as the
# page writes them). They agree with the windows but do not settle whether the limit evening counts: the
# Steam remark is ambiguous, and RPG Site's dates are loose (its 9/3 group deadline is 9/5, the full moon).
RESCUE_LIMIT_QUOTES = {
    "steam_schinese/d_7599331177366570924.html": (
        "Missing persons :: Persona 3 Reload General Discussions",
        ("Always save exploration until the countdown timer in the top right of the screen nears 0 aka the last day.",)),
    "web_guides/rpgsite_missing_people.html": (
        "Persona 3 Reload Missing Person quest",
        ("Rescue Deadline: September 5",
         "wait for the third missing person to pop - and then head into Tartarus on either September 3rd or 4th.",
         "One gets marked as a missing person on November 27",
         "head in before November 2nd")),
}
# Game8 JP's deadline page writes every window as 'M/D ~ M/D' (救出期限).
GAME8_MISSING_REL = "web_guides/game8jp_missing_people.html"
GAME8_MISSING_TITLE = "【ペルソナ3リロード】失踪者の期限一覧"
# CN guide items (cn_guides/data.json ids) the collectible, trip and shop reminders rest on.
CN_ITEMS = {
    "fragment.city": "t005_03", "fragment.dorm": "t005_07", "fragment.roof": "t035_06", "fragment.operation": "t103_01",
    "feature.mayoido": "t086_01", "trip.yakushima": "t088_01", "trip.kyoto": "t195_01",
}


def han_count(text: str) -> int:
    return sum(1 for ch in text if "一" <= ch <= "鿿")


def fits_short(text: str) -> bool:
    return len(text) <= SHORT_MAX_CHARS and han_count(text) <= SHORT_MAX_HAN


def fit_short(where: str, *candidates: str | None) -> str:
    """The first candidate that fits the small widget (never cut with an ellipsis)."""
    for text in candidates:
        if text and fits_short(text):
            return text
    raise BuildError(f"{where}: no shortZh fits {SHORT_MAX_CHARS} chars / {SHORT_MAX_HAN} Han: {candidates}")


def yen(amount: int) -> str:
    return f"{amount:,} 日元"


def typo_fix(md: str, text: str) -> str:
    for (fix_md, wrong), right in ZH_TYPO_FIXES.items():
        if fix_md == md:
            text = text.replace(wrong, right)
    return text


def gamersky_typo_fix(md: str, text: str) -> str:
    for (fix_md, wrong), right in GAMERSKY_TYPO_FIXES.items():
        if fix_md == md:
            text = text.replace(wrong, right)
    return text


def norm_answer(text: str) -> str:
    """Answer text for zh-vs-zh comparisons: no spaces or quote marks."""
    return re.sub(r"[\s「」“”\"']", "", text)


def class_line(rest: str) -> tuple[str, int]:
    """「X（n），魅力+♪」 (the guide's class line after 上课问答：) -> (X, n); n = 0 without a position."""
    rest = re.sub(r"[，。]?\s*魅力\+♪*$", "", rest).strip()
    n = re.fullmatch(r"(.+?)\s*[（(](\d)[）)]", rest)
    return (n.group(1).strip(), int(n.group(2))) if n else (rest, 0)


def move_rows(lines: dict[int, list[str]], who: str) -> None:
    """CN_3DM_ROW_FIXES on the lines of one copy of the guide (3DM or 游民星空)."""
    for (row_md, prefix), (md, _why) in CN_3DM_ROW_FIXES.items():
        row, target = day_of_md(row_md), day_of_md(md)
        moved = [line for line in lines.get(row, []) if line.startswith(prefix)]
        if len(moved) != 1 or any(line.startswith(prefix) for line in lines.get(target, [])):
            raise BuildError(f"CN_3DM_ROW_FIXES {row_md} {prefix}: {who} no longer has exactly this line there")
        lines[row].remove(moved[0])
        lines.setdefault(target, []).append(moved[0])


class Schedule3dm:
    """The pinned 3DM schedule pages (cn_guides/3dm_p2.html … 3dm_p11.html): the text lines of every date.

    Each page is one table, a row per date (date | class | after school | evening | notes). A page's first row has no
    date when it carries the last day of the previous month (that day's row at the end of the previous page is
    empty); page 2 starts with the column headings instead."""

    DATE = re.compile(r"(\d{1,2})\s*\.\s*(\d{1,2})(?:（.）)?")
    CLASS = re.compile(r"上课问答：(.+)")
    EXAM = re.compile(r"(期中|期末)考试：(.+)")
    DOZE = re.compile(r"上课：(不闭眼|闭不闭眼[都部]行)")
    TV = re.compile(r"电视购物（(.+?)，([\d,]+)(?:日元|元)）")
    TV_ONE = re.compile(r"电视购物，购买(.+?)（委托相关）")

    def __init__(self) -> None:
        self.lines: dict[int, list[str]] = {}
        self.page: dict[int, int] = {}
        self.month_page: dict[int, int] = {}
        last: int | None = None
        for number in CN_3DM_SCHEDULE_PAGES:
            text = (SOURCES_DIR / "cn_guides" / f"3dm_p{number}.html").read_text(encoding="utf-8")
            title = re.search(r"<title>(\d{1,2})月日程", text)
            start = text.find('<div class="news_warp_center">')
            end = text.find("</table>", start)
            if not title or start < 0 or end < 0:
                raise BuildError(f"3DM page {number} is not a schedule page with one table")
            month = int(title.group(1))
            self.month_page[month] = number
            for i, row in enumerate(re.findall(r"<tr[^>]*>(.*?)</tr>", text[start:end], flags=re.S)):
                cells = [self._cell(c) for c in re.findall(r"<td[^>]*>(.*?)</td>", row, flags=re.S)]
                lines = [line for cell in cells[1:] for line in cell]
                m = self.DATE.fullmatch(cells[0][0]) if cells and cells[0] else None
                if m is None:
                    if i == 0 and cells and cells[0] and cells[0][0] == "日期":
                        continue
                    if i == 0 and last is not None and md_of(last + 1)[3:] == "01" and not self.lines[last]:
                        self.lines[last] = lines  # the previous month's last day, carried over to this page
                        self.page[last] = number
                        continue
                    raise BuildError(f"3DM page {number} row {i}: no date ({lines[:2]})")
                if int(m.group(1)) != month:
                    raise BuildError(f"3DM page {number} ({month}月日程) has a row for {m.group(1)}.{m.group(2)}")
                d = day_of(month, int(m.group(2)))
                if d in self.lines:
                    raise BuildError(f"3DM lists {md_of(d)} twice")
                self.lines[d] = lines
                self.page[d] = number
                last = d
        move_rows(self.lines, "3DM")

    @staticmethod
    def _cell(fragment: str) -> list[str]:
        text = re.sub(r"<br\s*/?>|</p>", "\n", fragment)
        text = html.unescape(re.sub(r"<[^>]+>", "", text))
        return [line.strip() for line in text.splitlines() if line.strip()]

    def source(self, d: int) -> str:
        return f"cn_3dm_p{self.page[d]}"

    def matches(self, d: int, pattern: re.Pattern) -> list[re.Match]:
        return [m for line in self.lines.get(d, []) for m in [pattern.search(line)] if m]

    def one(self, d: int, pattern: re.Pattern) -> re.Match | None:
        found = self.matches(d, pattern)
        if len(found) > 1:
            raise BuildError(f"3DM {md_of(d)}: {len(found)} lines match {pattern.pattern}")
        return found[0] if found else None

    def class_answer(self, d: int) -> tuple[str, int] | None:
        """(answer, option 1..4 or 0) of the 3DM class line 「上课问答：X（n）」 of a date."""
        m = self.one(d, self.CLASS)
        if not m:
            return None
        rest = re.sub(r"[，。]?魅力\+♪*$", "", m.group(1)).strip()
        n = re.fullmatch(r"(.+?)（(\d)）", rest)
        return (n.group(1).strip(), int(n.group(2))) if n else (rest, 0)

    def exam_answer(self, d: int) -> str | None:
        """Answer of the 3DM exam line 「期中/期末考试：X」 of a date (a stray closing quote mark dropped)."""
        m = self.one(d, self.EXAM)
        return m.group(2).strip().strip("“”\"「」").strip() if m else None


class ScheduleGamersky:
    """游民星空's copy of the guide (cn_guides/gamersky_p1.html … gamersky_p10.html): the text lines of every date.

    A page is one month (page 1 = April, page 10 = January): a 「M月D日」 heading per date (div.GsWeTxt2), then slot
    headings and paragraphs. Range headings (the exam weeks) carry no class line; the June page repeats 6/11-6/17, so a
    date may have two rows, whose class lines must agree."""

    HEAD = re.compile(r'<div class="GsWeTxt2">(.*?)</div>', flags=re.S)
    DATE = re.compile(r"(\d{1,2})月(\d{1,2})日")
    CLASS = re.compile(r"上课问答：(.+)")

    def __init__(self) -> None:
        self.lines: dict[int, list[str]] = {}
        self.page: dict[int, int] = {}
        for number in GAMERSKY_PAGES:
            text = (SOURCES_DIR / "cn_guides" / f"gamersky_p{number}.html").read_text(encoding="utf-8")
            title = re.search(r"<title>[^<]*全社群MAX日程攻略[^<]*_(\d{1,2})月-游民星空", text)
            start = text.find('<div class="Mid2L_con">')
            end = text.find("<!--Mid2L_con end-->", start)
            if not title or start < 0 or end < 0:
                raise BuildError(f"游民星空 page {number} is not a page of the schedule guide")
            month = int(title.group(1))
            if month != (number + 2) % 12 + 1:
                raise BuildError(f"游民星空 page {number} is {month}月, not {GAMERSKY_MONTHS[number]}")
            parts = self.HEAD.split(text[start:end])
            for head, body in zip(parts[1::2], parts[2::2]):
                head = html.unescape(re.sub(r"<[^>]+>", "", head)).strip()
                lines = self._lines(body)
                m = self.DATE.fullmatch(head)
                if m is None:
                    if any(self.CLASS.search(line) for line in lines):
                        raise BuildError(f"游民星空 page {number}: a class line under the heading {head!r}")
                    continue
                row_month = int(m.group(1))
                d = day_of(row_month, int(m.group(2)))
                if d > LAST_DAY and month == 1:  # the January page ends with the epilogue (3月4日)
                    if any(self.CLASS.search(line) for line in lines):
                        raise BuildError(f"游民星空 page {number}: a class line after the last day ({head})")
                    continue
                if row_month != month and (row_month != month % 12 + 1 or int(m.group(2)) != 1):
                    raise BuildError(f"游民星空 page {number} ({month}月) has a row for {head}")  # June ends with 7月1日
                if self.page.setdefault(d, number) != number:
                    raise BuildError(f"游民星空 lists {md_of(d)} on pages {self.page[d]} and {number}")
                self.lines.setdefault(d, []).extend(lines)
        move_rows(self.lines, "游民星空")

    @staticmethod
    def _lines(fragment: str) -> list[str]:
        text = re.sub(r"<br\s*/?>|</p>|</div>", "\n", fragment)
        text = html.unescape(re.sub(r"<[^>]+>", "", text))
        return [line.strip() for line in text.splitlines() if line.strip()]

    def source(self, d: int) -> str:
        return f"cn_gamersky_p{self.page[d]}"

    def class_answer(self, d: int) -> tuple[str, int] | None:
        """(answer, option 1..4 or 0) of the class line of a date; a repeated row must say the same."""
        found = {class_line(m.group(1)) for line in self.lines.get(d, []) for m in [self.CLASS.search(line)] if m}
        if len(found) > 1:
            raise BuildError(f"游民星空 {md_of(d)}: its rows disagree on the class answer: {sorted(found)}")
        return next(iter(found), None)


def bwiki_class_answers() -> dict[int, str]:
    """BWIKI P3R/课堂问答与考试: day ordinal -> zh answer of every 「M月D日 answer」 entry (the exam lists are undated)."""
    text = (SOURCES_DIR / "cn_guides" / "bwiki_p3r_class.html").read_text(encoding="utf-8")
    start = text.find('<div class="mw-parser-output">')
    end = text.find("NewPP limit report", start)
    if start < 0 or end < 0:
        raise BuildError("the BWIKI class-answer page has no article body")
    out: dict[int, str] = {}
    for _tag, fragment in re.findall(r"<(li|p)>(.*?)</\1>", text[start:end], flags=re.S):
        entry = " ".join(html.unescape(re.sub(r"<[^>]+>", "", fragment)).split())
        m = re.fullmatch(r"(\d{1,2})月(\d{1,2})日 ?(.+)", entry)
        if not m:
            continue
        d = day_of(int(m.group(1)), int(m.group(2)))
        if d in out:
            raise BuildError(f"BWIKI lists {md_of(d)} twice")
        out[d] = m.group(3).strip()
    if len(out) < 30:
        raise BuildError(f"BWIKI class answers not found ({len(out)} dated entries)")
    return out


def cn_guide_days() -> dict[int, list[dict]]:
    """CN guide items per day ordinal."""
    out: dict[int, list[dict]] = {}
    for block in json.loads(GUIDE_PATH.read_text(encoding="utf-8"))["blocks"]:
        out.setdefault(day_of(block["month"], block["day"]), []).extend(block["items"])
    return out


def cn_guide_item(guide: dict[int, list[dict]], ident: str, md: str, *words: str) -> dict:
    """A CN guide item by id; it must be on ``md`` and mention every word (title, steps, substeps, hint)."""
    for d, items in guide.items():
        for item in items:
            if item.get("id") != ident:
                continue
            text = json.dumps(item, ensure_ascii=False)
            if md_of(d) != md or any(w not in text for w in words):
                raise BuildError(f"CN guide {ident} is on {md_of(d)} / lacks {words}")
            return item
    raise BuildError(f"CN guide item {ident} not found")


CN_ANSWER = (
    re.compile(r"课堂 · (?!不闭眼|闭不闭眼)(.+?)(?:（\d）)?(?:[，。].*)?"),
    re.compile(r"课堂问答：选择?「?(.+?)」?"),
    re.compile(r"考试 · 选择 ?「?(.+?)」?"),
    re.compile(r"\d+月\d+日 期[中末]考试：选择「(.+)」"),
)


def cn_guide_answers(guide: dict[int, list[dict]]) -> dict[int, str]:
    """The CN guide's class/exam answer per day (only for the zh-vs-zh drift check, never shipped)."""
    out: dict[int, str] = {}
    for d, items in guide.items():
        for item in items:
            if item.get("kind") not in ("class", "exam"):
                continue
            for pattern in CN_ANSWER:
                m = pattern.fullmatch(item["title"])
                if m:
                    out[d] = m.group(1).strip()
                    break
    return out


def aqiu_choices(page: AqiuPage, section: str) -> dict[int, int]:
    """aqiu class or exam answers: day ordinal -> Choice N (the English answer text is never used)."""
    out = {}
    for line in page.subsection(section).splitlines():
        m = re.match(r"(\d+)/(\d+) \| Choice (\d): ", line.strip())
        if m:
            out[day_of(int(m.group(1)), int(m.group(2)))] = int(m.group(3))
    if not out:
        raise BuildError(f"aqiu {section} table not found")
    return out


def overworld_section(title: str) -> tuple[list[str], list[list[str]]]:
    """Bullets and table rows (without header) under one heading of aqiu overworld.md."""
    lines = (SOURCES_DIR / "aqiu_gamedata" / "overworld.md").read_text(encoding="utf-8").splitlines()
    start = next((i for i, line in enumerate(lines) if re.fullmatch(r"#+ " + re.escape(title), line.strip())), None)
    if start is None:
        raise BuildError(f"aqiu overworld section {title!r} not found")
    bullets: list[str] = []
    rows: list[list[str]] = []
    for line in lines[start + 1:]:
        s = line.strip()
        if s.startswith("#"):
            break
        if s.startswith("* "):
            bullets.append(s[2:])
        elif s.startswith("|"):
            rows.append([c.strip() for c in s.strip("|").split("|")])
    if len(rows) < 3:
        raise BuildError(f"aqiu overworld section {title!r} has no table")
    return bullets, rows[2:]


def md_slash(md: str) -> int:
    """Day ordinal of an aqiu "M/D" date."""
    month, dom = md.split("/")
    return day_of(int(month), int(dom))


def reminder(ident: str, kind: str, d: int, slot: str, zh: str, short: str, *, conf: str, src: list[str],
             label: str | None = None, answer: str | None = None, option: int = 0, detail: str | None = None,
             gain: str | None = None, priority: int = 1, cost: int = 0, until: int | None = None,
             requires: dict | None = None, first_eligible: bool = False) -> dict:
    return {
        "answerZh": answer, "conf": conf, "costYen": cost, "day": d, "detailZh": detail, "firstEligible": first_eligible,
        "gainZh": gain, "id": ident, "kind": kind, "labelZh": label or REMINDER_LABELS[kind], "option": option,
        "priority": priority, "requires": requires, "shortZh": short, "slot": slot, "src": src, "untilDay": until,
        "zh": zh,
    }


def answer_reminders(ledger: Ledger, page: AqiuPage, sched: Schedule3dm, guide: dict[int, list[dict]]) -> list[dict]:
    """课堂提问 and 考试: aqiu dates and option positions, zh text from the pinned 3DM pages and, on the class dates 3DM
    has no text for, from 游民星空's copy of the same guide corroborated by BWIKI (zh vs zh)."""
    general = page.subsection("class-answers")
    m = re.search(r"All correct answers give Charm \+(\d+)", general)
    if not m:
        raise BuildError("aqiu class-answer gain not found")
    charm = int(m.group(1))
    classes = aqiu_choices(page, "class-answers")
    exams = aqiu_choices(page, "exam-answers")
    gsky = ScheduleGamersky()
    bwiki = bwiki_class_answers()
    out = []
    shipped: dict[int, str] = {}
    zh_src: dict[int, list[str]] = {}  # the zh sources of each shipped answer
    cross_checked: list[str] = []  # class dates on which 3DM and 游民星空 both answer (and agree)
    for d, choice in sorted(classes.items()):
        md = md_of(d)
        found = sched.class_answer(d)
        copy = gsky.class_answer(d)
        if copy is not None:
            copy = (gamersky_typo_fix(md, copy[0]), copy[1])
        if (found or copy) and md in WITHHELD_CLASS:
            raise BuildError(f"class {md} is withheld but {'3DM' if found else '游民星空'} has a line for it")
        if found is not None:
            who, answer, option = "3DM", typo_fix(md, found[0]), found[1]
            src = [sched.source(d)]
            if copy is not None:
                if norm_answer(copy[0]) != norm_answer(answer) or copy[1] != option:
                    raise BuildError(f"class {md}: 游民星空 「{copy[0]}」 option {copy[1]} != 3DM 「{answer}」 "
                                     f"option {option}")
                cross_checked.append(md)
        elif copy is not None:
            who, (answer, option) = "游民星空", copy
            if norm_answer(bwiki.get(d, "")) != norm_answer(answer):
                raise BuildError(f"class {md}: 游民星空 「{answer}」 is not BWIKI's 「{bwiki.get(d)}」 on a date 3DM has "
                                 "no text for")
            src = [gsky.source(d), "cn_bwiki_class"]
        else:
            if md not in WITHHELD_CLASS:
                raise BuildError(f"class {md}: no 3DM or 游民星空 line; withhold it explicitly (WITHHELD_CLASS) or fix "
                                 "the parser")
            continue
        if option and option != choice:
            raise BuildError(f"class {md}: {who} option {option} != aqiu Choice {choice}")
        mark = f"（第 {option} 项）" if option else ""
        out.append(reminder(
            f"class.{md}", "class", d, "day", f"课堂提问：选「{answer}」{mark}",
            fit_short(f"class.{md}", f"课堂：{answer}{mark}", f"课堂：{answer}"),
            answer=answer, option=option, gain=f"魅力 +{charm}", priority=3,
            detail=f"上课被点名提问时选「{answer}」{mark}，答对了魅力 +{charm}。",
            conf="verified" if option else "single", src=["aqiu_social_events"] + src))
        shipped[d] = answer
        zh_src[d] = src
    for d in sorted(set(d for d in sched.lines if sched.class_answer(d)) - set(classes)):
        raise BuildError(f"3DM has a class answer on {md_of(d)}, which aqiu does not list")
    for d in sorted(set(d for d in gsky.lines if gsky.class_answer(d)) - set(classes)):
        raise BuildError(f"游民星空 has a class answer on {md_of(d)}, which aqiu does not list")
    for d in sorted(exams):
        md = md_of(d)
        found = sched.exam_answer(d)
        if not found:
            if md not in WITHHELD_EXAM:
                raise BuildError(f"exam {md}: no 3DM line")
            continue
        answer = typo_fix(md, found)
        estimate = md in ESTIMATE_ANSWERS
        detail = f"这天考试有一道题要自己选，选「{answer}」。"
        out.append(reminder(
            f"exam.{md}", "exam", d, "day", f"考试：选「{answer}」", fit_short(f"exam.{md}", f"考试：{answer}"),
            answer=answer, detail=detail, priority=3, conf="estimate" if estimate else "single",
            src=["aqiu_social_events", sched.source(d)]))
        shipped[d] = answer
        zh_src[d] = [sched.source(d)]
    if len([x for x in out if x["kind"] == "exam"]) != len(exams) - len(WITHHELD_EXAM):
        raise BuildError("exam answers missing")
    from_copy = [md_of(d) for d in sorted(classes) if d in zh_src and zh_src[d][0].startswith("cn_gamersky_p")]
    pages = {int(zh_src[day_of_md(md)][0][len("cn_gamersky_p"):]) for md in from_copy}
    if pages != set(GAMERSKY_SOURCE_PAGES):
        raise BuildError(f"游民星空 answers {from_copy} are on pages {sorted(pages)}, not GAMERSKY_SOURCE_PAGES "
                         f"{GAMERSKY_SOURCE_PAGES}")
    for (md, wrong), right in GAMERSKY_TYPO_FIXES.items():
        d = day_of_md(md)
        if not any(wrong in line for line in gsky.lines.get(d, [])):
            raise BuildError(f"GAMERSKY_TYPO_FIXES {md}: 游民星空 does not write {wrong!r}")
        if bwiki.get(d) != right:
            raise BuildError(f"GAMERSKY_TYPO_FIXES {md}: BWIKI does not write {right!r} that day")

    # BWIKI (zh text without positions, a lineage apart from the guide) against every shipped class answer.
    bwiki_off = {md_of(d): bwiki.get(d) for d in classes
                 if norm_answer(bwiki.get(d, "")).casefold() != norm_answer(shipped.get(d, "")).casefold()}
    if bwiki_off != BWIKI_DIVERGENT:
        raise BuildError(f"BWIKI class answers unlike the shipped text changed: {bwiki_off} != {BWIKI_DIVERGENT}")
    extra = {md_of(d): text for d, text in bwiki.items() if d not in classes}
    if extra != BWIKI_EXTRA or norm_answer(extra.get("12-18", "")) != norm_answer(shipped.get(day_of_md("12-18"), "")):
        raise BuildError(f"BWIKI entries off the class dates changed: {extra} != {BWIKI_EXTRA} (12-18 = the exam answer)")

    # CN guide drift (zh vs zh only): where its answer is not the shipped one, the CN text is never shipped.
    cn = cn_guide_answers(guide)
    for (md, wrong), right in ZH_TYPO_FIXES.items():
        d = day_of_md(md)
        if not any(wrong in line for line in sched.lines.get(d, [])):
            raise BuildError(f"ZH_TYPO_FIXES {md}: 3DM does not write {wrong!r}")
        guide_text = json.dumps(guide.get(d, []), ensure_ascii=False)
        if right not in guide_text:
            raise BuildError(f"ZH_TYPO_FIXES {md}: the CN guide does not write {right!r} that day")
    divergent = {md_of(d) for d, text in cn.items()
                 if d in classes or d in exams if norm_answer(text) != norm_answer(shipped.get(d, ""))}
    if divergent != CN_GUIDE_DIVERGENT:
        raise BuildError(f"CN guide answer drift changed: {sorted(divergent)} != {sorted(CN_GUIDE_DIVERGENT)}")
    for md in sorted(CN_GUIDE_DIVERGENT, key=day_of_md):
        d = day_of_md(md)
        who = "游民星空" if zh_src[d][0].startswith("cn_gamersky_p") else "3DM"
        ledger.conflict(f"answer.{md}", f"{'exam' if d in exams else 'class'} answer on {md} (zh text)",
                        f"{who} 「{shipped[d]}」", [f"CN guide 「{cn[d]}」"], zh_src[d] + ["cn_guide"])
    copy_spells = [right for (md, _wrong), right in ZH_TYPO_FIXES.items()
                   if any(right in line for line in gsky.lines.get(day_of_md(md), []))]
    ledger.decide("reminders", "answers", f"课堂提问 ({len(classes)} aqiu dates) and 考试 ({len(exams)}) take the date and the "
                  "option position from aqiu and the zh answer text from the pinned 3DM schedule pages. 3DM has no text "
                  f"for 12/1-12/12 and after 1/13, so the class answers of {', '.join(from_copy)} come from 游民星空's "
                  "authorised copy of the same guide (猎猫人老贾; pages "
                  f"{', '.join(str(n) for n in GAMERSKY_SOURCE_PAGES)}), each with its option position and with the "
                  "same zh text on BWIKI that date. Every shipped position equals aqiu's Choice N (checked). On all "
                  f"{len(cross_checked)} class dates that both copies answer, 游民星空's answer and position equal 3DM's "
                  "(checked, zh vs zh; it already spells " + "、".join(copy_spells) + " as the 3DM typo fixes below "
                  "do). BWIKI (zh text without positions, partly from a 女神异闻录吧 post) agrees on "
                  f"{len(classes) - len(BWIKI_DIVERGENT)} of {len(classes)} class dates; it writes "
                  + "; ".join(f"{md} 「{text}」" for md, text in BWIKI_DIVERGENT.items())
                  + " where 3DM and 游民星空 agree on the shipped text, and also lists "
                  + ", ".join(f"{md} 「{text}」" for md, text in BWIKI_EXTRA.items())
                  + ", which are not aqiu class dates (not shipped: 3DM and 游民星空 have no class line on 06-04 or "
                  "01-14, and 12-18 is that day's exam answer). Only dates, prices and option positions are compared "
                  "with English sources, never answer text. The CN guide is an edited copy of 3DM: its answers differ "
                  f"from the shipped text on {', '.join(sorted(CN_GUIDE_DIVERGENT, key=day_of_md))} (English-localisation "
                  "text, §3), so it is never used for answers. Withheld class dates: "
                  + ("; ".join(f"{md} ({why})" for md, why in WITHHELD_CLASS.items()) or "none")
                  + f". Exam answers {', '.join(sorted(ESTIMATE_ANSWERS))} ship verbatim with conf=estimate (owner "
                  "decision O4). 3DM typos fixed to the CN guide's spelling of the same day: "
                  + "; ".join(f"{md} {wrong} → {right}" for (md, wrong), right in ZH_TYPO_FIXES.items())
                  + "; 游民星空 typo fixed to BWIKI's spelling of the same day: "
                  + "; ".join(f"{md} {wrong} → {right}" for (md, wrong), right in GAMERSKY_TYPO_FIXES.items())
                  + "; 12/14 drops a stray closing quote (数字0”).")
    return out


def tv_names(text: str) -> list[tuple[str, int]]:
    """「玫瑰花束+2个高级巧克力」 -> [(玫瑰花束, 1), (高级巧克力, 2)]."""
    out = []
    for part in text.split("+"):
        part = part.strip()
        m = re.fullmatch(r"(\d+)个(.+)", part) or re.fullmatch(r"(.+?)(\d+)个", part)
        if m is None:
            out.append((part, 1))
        elif m.group(1).isdigit():
            out.append((m.group(2), int(m.group(1))))
        else:
            out.append((m.group(1), int(m.group(2))))
    return out


def tv_reminders(ledger: Ledger, sched: Schedule3dm, guide: dict[int, list[dict]]) -> list[dict]:
    """电视购物: dates, items and prices from the game table; zh item names from 3DM, else the CN guide."""
    rows = read_tsv("DT_FldMailOrderTable")
    if rows[0][:2] != ["BuyMonth", "BuyDay"] or rows[0][9] != "Price":
        raise BuildError("DT_FldMailOrderTable header changed")
    game = {}
    for r in rows[1:]:
        game[day_of(int(r[0]), int(r[1]))] = {"items": [(int(r[4]), int(r[5])), (int(r[7]), int(r[8]))], "price": int(r[9])}
    if len(game) != 32:
        raise BuildError(f"DT_FldMailOrderTable has {len(game)} rows, expected 32")
    bullets, aqiu_rows = overworld_section("TV Shopping")
    aqiu = {md_slash(r[0]): (r[2], int(r[3])) for r in aqiu_rows}
    if {d: p for d, (_items, p) in aqiu.items()} != {d: g["price"] for d, g in game.items()}:
        raise BuildError("aqiu TV shopping dates/prices disagree with the game table")
    for d, (items, _price) in aqiu.items():
        counts = [int(m.group(1)) if m else 1 for m in (re.search(r" x(\d+)$", x.strip()) for x in items.split(" + "))]
        if counts != [n for _id, n in game[d]["items"]]:
            raise BuildError(f"aqiu TV shopping {md_of(d)}: quantities disagree with the game table")

    # zh names per buy date: (names with quantities, source ids); 3DM first, then the CN guide.
    named: dict[int, tuple[list[tuple[str, int]], list[str]]] = {}
    extra: list[str] = []
    for d in sorted(set(sched.lines) | set(guide)):
        md = md_of(d)
        m = sched.one(d, Schedule3dm.TV) if d in sched.lines else None
        src_names = None
        if m:
            src_names = (tv_names(typo_fix(md, m.group(1))), int(m.group(2).replace(",", "")), [sched.source(d)])
        else:
            for item in guide.get(d, []):
                t = re.fullmatch(r"电视购物 · (.+?)(?:，([\d,]+) 日元)?", item.get("title", ""))
                if t and "+" in t.group(1):
                    price = int(t.group(2).replace(",", "")) if t.group(2) else None
                    src_names = (tv_names(t.group(1)), price, ["cn_guide"])
        if src_names is None:
            continue
        names, price, src = src_names
        if d not in game:
            extra.append(f"{md} {'+'.join(n for n, _q in names)} ({src[0]})")
            continue
        if price is not None and price != game[d]["price"]:
            raise BuildError(f"TV shopping {md}: zh price {price} != game price {game[d]['price']}")
        if [q for _n, q in names] != [q for _id, q in game[d]["items"]]:
            raise BuildError(f"TV shopping {md}: zh quantities {names} disagree with the game table")
        named[d] = (names, src)
    if [x.split(" ")[0] for x in extra] != ["01-03"]:
        raise BuildError(f"zh TV shopping dates outside the game table: {extra} (expected only 1/3)")
    # Same game item -> same zh name everywhere; a date that names only its request item borrows the other name.
    by_id: dict[int, tuple[str, list[str]]] = {}
    for d, (names, src) in sorted(named.items()):
        for (item_id, _q), (name, _n) in zip(game[d]["items"], names):
            known = by_id.setdefault(item_id, (name, src))
            if known[0] != name:
                raise BuildError(f"TV item {item_id} is called {known[0]!r} and {name!r}")
    requests: dict[int, tuple[int, int, bool]] = {}  # day -> (item position, request number, the request names it)
    _b, request_rows = overworld_section("Elizabeth Requests")
    titles = {r[1]: r[3] for r in request_rows}
    for bullet in bullets:
        m = re.fullmatch(r"(.+?): Highly recommended for Request #(\d+): (.+)", bullet)
        if not m:
            raise BuildError(f"unexpected TV shopping note {bullet!r}")
        hits = [(d, i) for d, (items, _p) in aqiu.items() for i, x in enumerate(items.split(" + ")) if x.strip() == m.group(1)]
        if len(hits) != 1 or titles.get(m.group(2)) != m.group(3):
            raise BuildError(f"TV shopping note {bullet!r} does not match one item / request")
        requests[hits[0][0]] = (hits[0][1], int(m.group(2)), m.group(1) in m.group(3))
    for d, (pos, _no, _named) in requests.items():
        if d in named:
            continue
        one = sched.one(d, Schedule3dm.TV_ONE)
        if not one:
            raise BuildError(f"TV shopping {md_of(d)}: 3DM does not name the request item")
        names = []
        src = [sched.source(d)]
        for i, (item_id, qty) in enumerate(game[d]["items"]):
            if i == pos:
                name = one.group(1)
            elif item_id in by_id:
                name, borrowed = by_id[item_id]
                src += [s for s in borrowed if s not in src]
            else:
                raise BuildError(f"TV shopping {md_of(d)}: no zh name for item {item_id}")
            names.append((name, qty))
        named[d] = (names, src)

    out = []
    for d, g in sorted(game.items()):
        md = md_of(d)
        price = yen(g["price"])
        detail = TV_DETAIL
        priority = 1
        if d in named:
            names, src = named[d]
            listed = "、".join(n if q == 1 else f"{n} ×{q}" for n, q in names)
            zh = f"电视购物：{listed}，{price}"
            if d in requests:
                pos, no, required = requests[d]
                item = names[pos][0]
                detail += f"{item}是伊莉莎白的第 {no} 号委托要的东西。" if required else f"{item}在伊莉莎白的第 {no} 号委托里很有用。"
                priority = 2
        else:
            src = []
            zh = f"电视购物：有新商品，{price}"
            if d in requests:
                raise BuildError(f"TV shopping {md}: a request item without a zh name")
        out.append(reminder(f"tv.{md}", "tv", d, "day", zh, fit_short(f"tv.{md}", f"电视购物 {price}"), detail=detail,
                            priority=priority, cost=g["price"], conf="single",
                            src=["gd_mail_order", "aqiu_overworld"] + src))
    unnamed = [md_of(d) for d in sorted(game) if d not in named]
    ledger.decide("reminders", "tv", f"电视购物: the {len(game)} rows of DT_FldMailOrderTable (game data: buy date, items, "
                  "quantities, price; the aqiu table agrees) are the dates; delivery dates are not shown. zh item names come "
                  "from the 3DM line of the buy date, else the CN guide's; quantities and prices must equal the game's, and "
                  "one game item always has the same zh name (7/12: 3DM names only the request item, the other name comes "
                  f"from the same item on another date). Without zh names: {', '.join(unnamed) or 'none'} (shown as 有新商品). "
                  f"zh-source dates outside the game table are dropped: {'; '.join(extra) or 'none'}. Request items (aqiu "
                  "notes): " + ", ".join(f"{md_of(d)} #{no}" for d, (_p, no, _r) in sorted(requests.items()))
                  + " (priority 2).")
    return out


def request_work_tips(rows: list[list[str]]) -> dict[int, str]:
    """Editorial how-to text, guarded by the pinned Reload request's hint and prerequisite.

    These are reminders, not completed-request state or extra simulated actions. In particular a request that
    needs a meal, computer session or cooking must not be described as entirely slot-free just because accepting
    it and handing over its item take no time.
    """
    specs = {
        9: ("Buy 12 different drinks from the Dorms 2F + Dorms 3F + Iwatodai 3F + Port Island Station Vending Machines (Y1320)", "1",
            "先完成第 1 号，再接第 9 号：到宿舍 2、3 楼、严户台商店街 3 楼和港湾人工岛站的自动贩卖机，"
            "各买不同种类的饮料，凑齐 12 种后交付。"),
        11: ("Wilduck Burger > Big Eater Challenge (Y1800) > Choice 2: Look away from the burgers. > Choice 1: Eat without stopping. > Choice 2: Imagine something sour.", "9",
             "交付第 9 号后才有第 11 号大胃王挑战。挑战要花 1,800 日元，并用掉一个晚上；"
             "接到委托后再单独安排，不能和当晚的主要活动同时做。"),
        12: ("Talk to Yukari", "-", "先接第 12 号，晚上回宿舍 1 楼找岳羽由加莉拿物品，再交给伊莉莎白。"),
        13: ("Talk to Junpei", "12", "交付第 12 号后再接第 13 号，回宿舍找伊织顺平拿物品并交付。两件都要在 6月6日或更早交完。"),
        19: ("Crane Game x3 (Y2400)", "18", "交付第 18 号后，第 19 号要到桐叶购物中心的娃娃机凑齐 3 个玩偶，只有 1 个还不够。"),
        20: ("Nurse's Office", "-", "第 20 号：先接委托，上学日到学校 1 楼保健室领取物品，再回去交付。"),
        27: ("Talk to Mitsuru", "-", "先接第 27 号，晚上回宿舍找桐条美鹤拿物品并交付。"),
        28: ("Talk to Akihiko", "27", "交付第 27 号后再接第 28 号，回宿舍找真田明彦拿物品并交付。"),
        29: ("Club Escapade Vendor > Black Quartz + (Y10000)", "-", "第 29 号：先到放纵俱乐部找店员询问，再回去找伊莉莎白；"
             "准备黑水晶和 10,000 日元，到店员处取得物品后交付。"),
        42: ("Pharmacy > Super Cat Food x4 (Y2800) > Port Island Outskirts > Feed over 4 days", "-",
             "第 42 号：先接委托，在蓝胡子药房买 4 份猫罐头，共 2,800 日元；到港湾人工岛站前广场角落找猫，分 4 天喂食。"),
        43: ("Talk to Fuuka", "-", "第 43 号：先接委托，晚上回宿舍找山岸风花拿物品，8月4日或更早交付。"),
        44: ("Accept > 6/20: Collect Pretty Seashell + Strange Seaweed + Yakushima Wood + Small Crab", "-",
             "第 44 号要在去屋久岛之前接下；7月20日到海边时至少捡一件委托物品，没先接就捡不到。"),
        54: ("Try praying at the shrine over 3 days", "-", "第 54 号：先接委托，再分 3 天到长鸣神社调查神社尝试参拜，"
             "这几次尝试不会消耗时间。"),
        58: ("Port Island Outskirts > Port Island Station > Iwatodai 1F", "-", "第 58 号：先接委托，到港湾人工岛站前广场角落找小混混，"
             "接着找车站咖啡店门口的学生，再找严户台商店街喜欢猫的少年，依次交换物品后交付；8月31日截止。"),
        68: ("Talk to Shinjiro", "-", "先接第 68 号，晚上回宿舍找荒垣真次郎拿物品并交付。"),
        69: ("Talk to Aigis", "68", "交付第 68 号后再接第 69 号，回宿舍找埃癸斯拿物品并交付；两件都要在 10月2日或更早交完。"),
        74: ("Naganaki Shrine > Inari Sushi", "-", "第 74 号：先完成第 53 号，学力达到 Lv.5；接下委托后到长鸣神社调查稻荷祠堂，取得寿司后交付。"),
        75: ("Visit the Faculty Office over 8 days", "-", "第 75 号：先完成第 66 号并接下委托，从上学日的放学后开始，到学校 1 楼教职员室分 8 天触发对话；"
             "最后拿到物品再交付，不是进门一次就完成。"),
        76: ("Talk to Ikutsuki", "-", "第 76 号：先接委托，晚上回宿舍找几月修司拿眼镜布，11月1日或更早交付。"),
        92: ("Port Island Station > Bathroom > Choice 1: Scrub harder. > Choice 1: Scrub harder.", "67",
             "先完成第 67 号，接下第 92 号后到港湾人工岛站的厕所调查清扫，再回去交付。"),
        93: ("School Roof > Second planter from right", "-", "第 93 号：先完成第 75、80 号并接下委托，再到学校天台，给从右数第 2 个花坛浇水后交付。"),
        94: ("Talk to Koromaru", "-", "先接第 94 号，晚上回宿舍找虎狼丸拿物品并交付。"),
        95: ("Talk to Ken", "94", "交付第 94 号后再接第 95 号，回宿舍找天田乾拿物品并交付；两件都要在 11月30日或更早交完。"),
        96: ("Bought all 3 drinks from Kyoto vending machine > School Outdoor Corridor > Drink Maniac > Oden Juice (Y5000)", "-",
             "第 96 号：修学旅行时先买齐旅馆自动贩卖机的 3 种饮料；回来后在学校连接走廊交给女学生，"
             "再花 5,000 日元买关东煮并交付。旅行时没买齐就会错过。"),
        97: ("Rescued Ayako > Iwatodai 2F > Eccentric Man", "-", "第 97 号：只有先前救出了 6月18日失踪的学生才可完成；"
             "接委托后到严户台商店街 2 楼找古怪的男子拿物品，12月25日或更早交付。"),
    }
    by_no = {int(r[1]): r for r in rows if r[1].isdigit()}
    for number, (hint, prereq, _text) in specs.items():
        row = by_no.get(number)
        if row is None or (row[4], row[2]) != (hint, prereq):
            raise BuildError(f"request #{number}: how-to source or prerequisite changed")
    return {number: text for number, (_hint, _prereq, text) in specs.items()}


def request_catalogue(ledger: Ledger, rows: list[list[str]]) -> list[dict]:
    """One independent, searchable reference for each numbered base-game request.

    These records are a reference catalogue, not release-day reminders or inferred completion state.
    Every authored instruction is tied to the complete source row so changed conditions fail visibly.
    """
    data = read_evidence("request_catalogue.json")
    if data.get("schema") != "p3r.request_catalogue.v1":
        raise BuildError("request catalogue: unknown evidence schema")
    by_no = {int(row[1]): row for row in rows if row[1].isdigit()}
    entries = data.get("entries", [])
    expected = list(range(1, 102))
    if sorted(by_no) != expected or [x.get("number") for x in entries] != expected:
        raise BuildError("request catalogue: expected each numbered base-game request 1 through 101 exactly once")
    out = []
    for entry in entries:
        number = entry["number"]
        row = by_no[number]
        if entry.get("sourceRow") != row:
            raise BuildError(f"request #{number}: catalogue source row changed")
        for field in ("titleZh", "stepsZh", "rewardZh"):
            if not isinstance(entry.get(field), str) or not entry[field].strip():
                raise BuildError(f"request #{number}: {field} is required")
        aliases = entry.get("aliases", [])
        if not isinstance(aliases, list) or any(not isinstance(x, str) or not x.strip() for x in aliases):
            raise BuildError(f"request #{number}: aliases must contain nonempty strings")
        if len(aliases) != len(set(aliases)):
            raise BuildError(f"request #{number}: duplicate search aliases")
        match = re.fullmatch(r"(\d+/\d+)(?:-(\d+/\d+))?", row[0])
        if not match:
            raise BuildError(f"request #{number}: invalid catalogue availability")
        first = md_slash(match.group(1))
        if "fromMd" in entry:
            if not isinstance(entry["fromMd"], str) or not re.fullmatch(r"\d{2}-\d{2}", entry["fromMd"]):
                raise BuildError(f"request #{number}: fromMd must be MM-DD")
            first = day_of_md(entry["fromMd"])
        last = md_slash(match.group(2)) if match.group(2) else None
        requirements = entry.get("prerequisiteNumbers", [] if row[2] == "-" else [int(row[2])])
        if (not isinstance(requirements, list) or requirements != sorted(set(requirements))
                or any(not isinstance(n, int) or n not in by_no or n == number for n in requirements)):
            raise BuildError(f"request #{number}: invalid prerequisiteNumbers")
        window = f"开放：{cn_date(first)}起"
        if last is not None:
            window += f"；截止：{cn_date(last)}"
        if requirements:
            window += "\n前置：完成" + "、".join(f"第{n}号" for n in requirements)
        detail = window + "\n" + entry["stepsZh"] + "\n奖励：" + entry["rewardZh"]
        item = {"number": number, "titleZh": entry["titleZh"], "aliases": aliases,
                "detailZh": detail, "fromDay": first, "conf": "single",
                "src": ["aqiu_overworld", "aqiu_walkthrough", "cn_guide", "ev_request_catalogue"]}
        if last is not None:
            item["untilDay"] = last
        out.append(item)
    ledger.decide("reminders", "request-catalogue", "All 101 numbered Reload base-game requests have an independent "
                  "searchable reference (number, Chinese title, English aliases, availability/deadline, prerequisite "
                  "hand-ins, actionable instructions and rewards). The unnumbered Platinum Brooch negotiation is "
                  "excluded from numbered coverage. Authored text is pinned to each complete aqiu source row; "
                  "Chinese names and corrected conditions are documented in request_catalogue.json. Reference "
                  "entries do not schedule actions, infer acceptance/completion or award stats. Conditional "
                  "preparation for #38, #44, #75, #96 and #97 remains explicit; consumable evenings are identified.")
    return out


def request_extra_reminders(ledger: Ledger, rows: list[list[str]], sched: Schedule3dm) -> list[dict]:
    """The source's eight faculty-office visits and a conditional reminder of the outing chain."""
    page = AqiuPage(SOURCES_DIR / "aqiu_pages" / "social-events.html")
    if "Completing Request #75: Bring me a Sengoku-era helm. over 8 days will grant a total of Academics +16 without taking up any time" not in page.subsection("general"):
        raise BuildError("request #75: eight slot-free faculty visits / Academics +16 source changed")
    faculty = [d for d in sorted(sched.lines) if sched.matches(d, re.compile(r"教职员室.*（支线75）|教职员室触发剧情（支线75）"))]
    if len(faculty) != 8 or md_of(faculty[0]) != "10-06":
        raise BuildError("request #75: expected eight faculty-office visits from 10/6 in 3DM")
    out = []
    for d in faculty:
        out.append(reminder(
            f"request.{md_of(d)}.faculty", "request", d, "day", "伊莉莎白的第 75 号委托：放学后顺路去教职员室",
            "顺路去教职员室（委托用）", priority=2, conf="single",
            src=["aqiu_overworld", "aqiu_social_events", "cn_guide", sched.source(d)],
            detail="如果还没完成第 75 号，先到天鹅绒房间接下委托，再到学校 1 楼教职员室触发对话。"
                   "要分 8 天去，最后拿到物品后交付；这几次对话不会消耗时间，累计学力 +16。"))
    by_no = {int(r[1]): r for r in rows if r[1].isdigit()}
    source_counts = (15, 30, 45, 60, 80)
    outing_evidence = read_evidence("request_catalogue.json")["outings"]
    thresholds = outing_evidence["difficultyTotals"]
    wait = outing_evidence["minimumDaysAfterPreviousCompletion"]
    if thresholds != [25, 60, 100, 150, 185] or wait != 3:
        raise BuildError("request outings: difficulty totals or three-day interval evidence changed")
    places = ("桐叶购物中心", "严户台站", "长鸣神社", "月光馆学园", "自己的房间")
    for number, count in zip(range(77, 82), source_counts):
        row = by_no.get(number)
        if row is None or row[4] != f"Completed {count} requests" or row[2] != ("-" if number == 77 else str(number - 1)):
            raise BuildError(f"request #{number}: outing threshold or predecessor changed")
    d = day_of_md("05-29")
    if not sched.matches(d, re.compile(r"接取77号任务.*没时间限制")):
        raise BuildError("request #77: 3DM's conditional outing reminder changed")
    detail = "外出邀请按已完成委托的难度星数合计解锁。\n"
    detail += "\n".join(f"第 {number} 号：难度星数合计 {total}，去{place}。"
                          for number, total, place in zip(range(77, 82), thresholds, places))
    detail += f"\n第 78～81 号还要先完成前一次外出，并至少经过 {wait} 天。白天到天鹅绒房间接邀请，再对话出发；外出不会消耗时间。"
    detail += "第 80 号须在学校开放日进行。"
    out.append(reminder("request.05-29.outings", "request", d, "day", "伊莉莎白的外出邀请：先看委托难度星数",
                        "伊莉莎白的外出邀请", detail=detail, conf="single",
                        src=["aqiu_overworld", "cn_guide", "ev_request_catalogue", sched.source(d)]))
    ledger.decide("reminders", "request-tips", "Request tips give item sources, prerequisite hand-ins, multi-day steps "
                  "and missable travel preparation in each release batch and relevant deadline. #11 explicitly consumes "
                  "an evening; accepting/handing in a request does not make its other steps slot-free. #75 reminders use "
                  "3DM's eight school visits (10/6-10/20), conditional on still unfinished; aqiu social-events explicitly "
                  "gives eight slot-free visits and total Academics +16 (not forecast). #77-81 use completed-request "
                  "Challenge Level totals 25/60/100/150/185, plus three days since the preceding outing for #78-81, "
                  "rather than the aqiu route's approximate completed-request counts. Reload Game8 and atwiki agree; "
                  "the daytime outings consume no slot, and #80 needs school open. Request completion "
                  "and faculty visit counts are not tracked, so reminders say to skip completed work.")
    return out


def request_reminders(ledger: Ledger, people: list[tuple[int, int, str]], sched: Schedule3dm) -> list[dict]:
    """伊莉莎白的委托: releases/deadlines with practical steps, multi-day visits and conditional outings."""
    _bullets, rows = overworld_section("Elizabeth Requests")
    tips = request_work_tips(rows)
    batches: set[int] = set()
    batch_tips: dict[int, list[str]] = {}
    windows: dict[int, tuple[int, list[int]]] = {}
    prereq: dict[int, str] = {}
    for row in rows:
        avail, number, hint = row[0], row[1], row[4]
        if avail == "-":
            continue
        m = re.fullmatch(r"(\d+/\d+)(?:-(\d+/\d+))?", avail)
        if not m:
            raise BuildError(f"request #{number}: availability {avail!r}")
        first = md_slash(m.group(1))
        batches.add(first)
        if int(number) in tips:
            batch_tips.setdefault(first, []).append(tips[int(number)])
        if m.group(2):
            last = md_slash(m.group(2))
            start, numbers = windows.setdefault(last, (first, []))
            if start != first:
                raise BuildError(f"requests ending {m.group(2)} start on different days")
            numbers.append(int(number))
            rescued = re.match(r"Rescued (\w+) >", hint)
            if rescued:
                who = [p for p in people if p[2].split(" ")[0] == rescued.group(1)]
                if len(who) != 1:
                    raise BuildError(f"request #{number}: missing person {rescued.group(1)} not found")
                prereq[last] = (f"，而且只有在 {cn_date(who[0][1])}当天或更早救出了 {cn_date(who[0][0])}失踪的学生"
                                "才能完成")
    if len(batches) != 9 or len(windows) != 8:
        raise BuildError(f"Elizabeth requests: {len(batches)} batch dates / {len(windows)} deadlines, expected 9 / 8")
    out = []
    for md, (pattern, _why) in REQUEST_BATCH_EVENING.items():
        d = day_of_md(md)
        if d not in batches or not sched.matches(d, re.compile(pattern)):
            raise BuildError(f"REQUEST_BATCH_EVENING {md}: no batch that day, or 3DM has no line matching {pattern}")
    for d in sorted(batches):
        evening = md_of(d) in REQUEST_BATCH_EVENING
        src = ["aqiu_overworld", "cn_guide", "aqiu_walkthrough"] + ([sched.source(d)] if evening else [])
        detail = "去桐叶购物中心的天鹅绒房间找伊莉莎白查看新委托；接委托和交东西都不会消耗时间。"
        if batch_tips.get(d):
            detail += "已完成的可以跳过，不必今天全部清空。\n" + "\n".join(batch_tips[d])
        detail += "\n已完成委托的难度星数合计达到 25、60、100、150、185 时，分别查看第 77～81 号外出邀请；后一次还要在前一次外出完成后至少等 3 天。"
        detail += "额外花费不在安排的预计里，花过钱后记得在「同步现状」更新现金。"
        out.append(reminder(f"request.{md_of(d)}", "request", d, "eve" if evening else "day", "伊莉莎白有新的委托",
                            "伊莉莎白有新委托", detail=detail,
                            conf="verified", src=src + ["ev_request_catalogue"]))
    for last, (first, numbers) in sorted(windows.items()):
        these = "这些" if len(numbers) > 1 else "这个"
        detail = f"{these}委托只能做到 {cn_date(last)}{prereq.get(last, '')}；接委托和交东西都不会消耗时间。"
        detail += "\n" + "\n".join(tips[n] for n in numbers if n in tips)
        out.append(reminder(
            f"request.{md_of(last)}.deadline", "request", max(first, last - 3), "day",
            f"伊莉莎白的第 {'、'.join(map(str, numbers))} 号委托 {cn_date(last)}截止",
            fit_short(f"request.{md_of(last)}.deadline", f"伊莉莎白的委托 {cn_date(last)}截止", f"委托 {cn_date(last)}截止"),
            detail=detail, priority=2, until=last, conf="verified", src=["aqiu_overworld", "cn_guide", "aqiu_walkthrough"]))
    ledger.decide("reminders", "requests", "伊莉莎白的委托 (aqiu overworld): new requests appear on "
                  + ", ".join(md_of(d) for d in sorted(batches)) + "; the time-limited ones end on "
                  + ", ".join(f"{md_of(last)} (#{'/'.join(map(str, n))})" for last, (_f, n) in sorted(windows.items()))
                  + ", each shown from 3 days before its end (not before its batch). #97 needs the student missing from "
                  "6/18 rescued (by 7/6; the rescue group carries the warning). Batches in the evening: "
                  + "; ".join(f"{md} ({why})" for md, (_p, why) in REQUEST_BATCH_EVENING.items())
                  + ". Practical how-to details are included; #75 and the conditional #77-81 outing chain have separate reminders.")
    out += request_extra_reminders(ledger, rows, sched)
    return out


def rescue_groups(ledger: Ledger) -> tuple[list[dict], list[tuple[int, int, str]]]:
    """失踪者: the 20 missing people of aqiu overworld in (missing from, limit) groups; the engine words the reminders."""
    bullets, rows = overworld_section("Missing People")
    people = [(md_slash(r[0]), md_slash(r[1]), r[2]) for r in rows]
    board = [line.split("\t")[1].split("\\n")[0] for line in
             (SOURCES_DIR / "aqiu_gamedata" / "BMD_DisappearHelp.tsv").read_text(encoding="utf-8").splitlines()[2:]]
    if len(people) != 20 or [p[2] for p in people] != board:
        raise BuildError("aqiu missing people disagree with the game's missing-person board (20 names, in order)")
    if [(first, last) for first, last, _name in people] != disappear_windows():
        raise BuildError("aqiu missing-people windows disagree with DisappearDataAsset (Start/Limit per person)")
    groups: dict[tuple[int, int], list[str]] = {}
    for first, last, name in people:
        groups.setdefault((first, last), []).append(name)
    out = []
    modelled = {RESCUE_EN[ident]: (ident, day_of_md(first), day_of_md(last)) for ident, _zh, first, last, _link in RESCUES}
    for (first, last), names in sorted(groups.items()):
        ids = []
        for name in names:
            if name in modelled:
                ident, m_first, m_last = modelled.pop(name)
                if (m_first, m_last) != (first, last):
                    raise BuildError(f"rescue {ident}: window {md_of(m_first)}-{md_of(m_last)} is not its aqiu group")
                ids.append(ident)
        group = {"conf": "verified", "count": len(names), "fromDay": first, "id": f"rescue.{md_of(first)}",
                 "rescueIds": ids, "src": list(RESCUE_SRC), "toDay": last}
        if md_of(first) in RESCUE_LOCKS:
            number, bullet = RESCUE_LOCKS[md_of(first)]
            who = bullet.split(" ")[3]
            if not any(b.startswith(bullet) for b in bullets) or not any(n.split(" ")[0] == who for n in names):
                raise BuildError(f"RESCUE_LOCKS {md_of(first)}: aqiu no longer says {bullet!r} of this group")
            group["locksZh"] = f"救不出来的话，伊莉莎白的第 {number} 号委托就做不了。"
        out.append(group)
    if modelled:
        raise BuildError(f"modelled rescues not in the aqiu table: {sorted(modelled)}")
    if len(out) != 13 or len({g["id"] for g in out}) != 13:
        raise BuildError(f"expected 13 missing-person groups, found {len(out)}")
    rescue_limit_witnesses(ledger, people)
    ledger.decide("reminders", "rescue", "失踪者: the 20 people of aqiu overworld (the same names, in the same order, "
                  "as the game's missing-person board; the same (missing, limit) days as DisappearDataAsset) in 13 "
                  "(missing from, limit) groups; 北村文吉 and 舞子 are the modelled rescues (rescueIds). The engine "
                  "words them against the plan: when they go missing, on the first planned Tartarus evening in the "
                  "window (rescued on that visit), or the day before the limit when no Tartarus evening is planned. The "
                  "limit evening still counts by the evidence (tartarus 'rescue deadline') but nobody has checked it in "
                  "the game: the details say both, and a line for a planned visit on the limit evening says it is "
                  "unchecked. A group whose rescue a request needs says so (locksZh): "
                  + "; ".join(f"{md} → #{n} ({bullet})" for md, (n, bullet) in RESCUE_LOCKS.items()) + ".")
    return out, people


def disappear_windows() -> list[tuple[int, int]]:
    """(missing from, limit) of the 20 rows of the game's missing-person table, in table order."""
    rows = read_tsv("DisappearDataAsset")
    head = rows[0]
    if head[:4] != ["StartMonth", "StartDays", "LimitMonth", "LimitDays"] or len(rows) != 21:
        raise BuildError("DisappearDataAsset: expected StartMonth/StartDays/LimitMonth/LimitDays and 20 rows")
    return [(day_of(int(r[0]), int(r[1])), day_of(int(r[2]), int(r[3]))) for r in rows[1:]]


def walkthrough_rescues() -> list[tuple[int, int]]:
    """(day, people) of every evening aqiu's tested Reload walkthrough rescues missing people, in order."""
    lines = (SOURCES_DIR / WALKTHROUGH_REL).read_text(encoding="utf-8").splitlines()
    if [line.strip() for line in lines[:11] if line.strip() in WALKTHROUGH_HEAD] != list(WALKTHROUGH_HEAD):
        raise BuildError(f"{WALKTHROUGH_REL}: not the test-played, no-reload Persona 3 Reload walkthrough")
    out: list[tuple[int, int]] = []
    day = section = None
    tower = False
    for line in lines:
        s = line.strip()
        head = re.fullmatch(r"#### (\w{3}) (\d{1,2})/(\d{1,2})", s)
        if head:
            day = day_of(int(head.group(2)), int(head.group(3)))
            if WEEKDAY_ABBR.get(head.group(1)) != weekday_of(day):
                raise BuildError(f"{WALKTHROUGH_REL}: {s} is not a {head.group(1)} in 2009-10")
            section, tower = None, False
        elif s.startswith("##### "):
            section, tower = s[6:], False
        elif line.startswith("* "):
            tower = s == "* Tartarus"
        rescue = re.fullmatch(r"\* Rescue (\d+) missing (?:people|person)", s)
        if rescue:
            if day is None or section != "Evening" or not tower:
                raise BuildError(f"{WALKTHROUGH_REL}: a rescue outside an evening Tartarus visit ({s})")
            out.append((day, int(rescue.group(1))))
    return out


def rescue_limit_witnesses(ledger: Ledger, people: list[tuple[int, int, str]]) -> list[int]:
    """Days on which aqiu's tested walkthrough rescues someone on their limit day (the limit evening counts).

    Every walkthrough rescue must save exactly the people missing that evening (inside their window, not yet
    saved), and the route must save all 20; the corroborating Reload pages must still say what REPORT quotes."""
    saved: set[int] = set()
    on_limit: list[int] = []
    on_start: list[int] = []
    evenings = walkthrough_rescues()
    for day, count in evenings:
        gone = [i for i, (first, last, _name) in enumerate(people) if first <= day <= last and i not in saved]
        if len(gone) != count:
            raise BuildError(f"{WALKTHROUGH_REL}: {md_of(day)} rescues {count}, but {len(gone)} people are missing then")
        saved.update(gone)
        if any(people[i][1] == day for i in gone):
            on_limit.append(day)
        if any(people[i][0] == day for i in gone):
            on_start.append(day)
    if len(saved) != len(people):
        raise BuildError(f"{WALKTHROUGH_REL}: the route rescues {len(saved)} of {len(people)} missing people")
    if not on_limit:
        raise BuildError(f"{WALKTHROUGH_REL}: no rescue on a limit day; the limit-evening reading has lost its witness")
    for rel, (title, quotes) in RESCUE_LIMIT_QUOTES.items():
        page = (SOURCES_DIR / rel).read_text(encoding="utf-8", errors="replace")
        text = " ".join(html_to_text(page).split())
        for quote in quotes:
            if title not in page or quote not in text:
                raise BuildError(f"{rel}: the Reload page no longer says {quote!r}")
    game8 = " ".join(html_to_text((SOURCES_DIR / GAME8_MISSING_REL).read_text(encoding="utf-8")).split())
    windows = sorted({(first, last) for first, last, _name in people})
    absent = [f"{md_of(a)}~{md_of(b)}" for a, b in windows if f"{md_slash_text(a)} ~ {md_slash_text(b)}" not in game8]
    if GAME8_MISSING_TITLE not in game8 or absent:
        raise BuildError(f"{GAME8_MISSING_REL}: Game8 JP no longer lists the windows {absent}")
    ledger.decide("tartarus", "rescue deadline", "A missing person can still be rescued on the evening of the limit "
                  "day; both ends of a window count. DisappearDataAsset (game data) has StartMonth/StartDays and "
                  "LimitMonth/LimitDays per person, the same days as the aqiu table. aqiu's 'All Voiced Events "
                  "Walkthrough - Persona 3 Reload' ('Test-played on Merciless', 'No reloads required') rescues all 20 "
                  f"on {len(evenings)} evenings, on the limit day itself on "
                  + ", ".join(md_slash_text(d) for d in on_limit) + " and on the day someone went missing on "
                  + (", ".join(md_slash_text(d) for d in on_start) or "none")
                  + ". Every limit evening is enterable in this pack and the next night is not (full moon, closed "
                  "night, story or the finale; checked in validate_pack), so no limit + 1 night could rescue anyone. "
                  "The decision rests on these three. Other Reload pages agree with the windows but do not settle "
                  "whether the limit evening counts: Game8 JP writes 救出期限 as ranges ('9/12 ~ 10/3'); the Steam "
                  "thread 'Missing persons' advises '"
                  + RESCUE_LIMIT_QUOTES["steam_schinese/d_7599331177366570924.html"][1][0]
                  + "'; RPG Site dates the deadline of the group missing from 8/19, 8/29 and 9/3 as 9/5 ('Rescue "
                  "Deadline: September 5', the full moon; the game's limit is 9/4) and advises '… head into Tartarus "
                  "on either September 3rd or 4th.', and its other dates are loose too (November 27 for a person the "
                  "game has missing from 11/28, 'head in before November 2nd' for the group whose limit is 11/2). "
                  "The engine rescues on a Tartarus evening from fromDay through toDay and treats the person as lost "
                  "from toDay + 1. Not replayed by the owner, so the planner keeps a one-day margin (DESIGN §10.3): it "
                  "rescues by toDay - 1 whenever an earlier evening is open, uses the limit evening only when it is the "
                  "last one left, and then says that the evidence has it in time but nobody checked it in the game.")
    return on_limit


def md_slash_text(d: int) -> str:
    """'9/12' for a day ordinal (Game8 and aqiu write dates this way)."""
    month, dom = md_of(d).split("-")
    return f"{int(month)}/{int(dom)}"


def collectible_reminders(ledger: Ledger, page: AqiuPage, sched: Schedule3dm, guide: dict[int, list[dict]],
                          by_day: dict[int, dict]) -> list[dict]:
    """黄昏的碎片, the Yakushima and Kyoto request items, and 真宵堂 (3DM, CN guide and aqiu agree)."""
    src_terms = "steam_sc_achievements"
    city = cn_guide_item(guide, CN_ITEMS["fragment.city"], "04-21", "14 处")
    spots = city["substeps"]
    if len(spots) != 14:
        raise BuildError(f"CN guide lists {len(spots)} city fragment spots on 4/21, expected 14")
    if not sched.matches(day_of_md("04-21"), re.compile(r"黄昏的?碎片")):
        raise BuildError("3DM has no 黄昏的碎片 line on 4/21")
    cn_guide_item(guide, CN_ITEMS["fragment.dorm"], "04-21", "4 楼")
    cn_guide_item(guide, CN_ITEMS["fragment.roof"], "05-25", "宿舍天台", "放纵俱乐部", "勇气达到2级")
    cn_guide_item(guide, CN_ITEMS["fragment.operation"], "08-06", "黄昏的碎片（2个）")
    for md, pattern in (("04-21", r"宿舍4楼.*黄昏碎片"), ("05-25", r"宿舍天台的黄昏碎片"), ("08-06", r"黄昏的碎片（2个）"),
                        ("07-18", r"真宵堂解锁"), ("07-20", r"委托44")):
        if not sched.matches(day_of_md(md), re.compile(pattern)):
            raise BuildError(f"3DM {md}: no line matching {pattern}")
    if not re.search(r"^2 \|[^|]*\|[^|]*\| Club Escapade \|", page.subsection("required-for"), flags=re.M):
        raise BuildError("aqiu Required For no longer says Courage Lv2 opens Club Escapade")
    club_lv = 2
    out = [
        reminder("fragment.04-21", "fragment", day_of_md("04-21"), "day", "今天城里能捡到黄昏的碎片（14 处）",
                 "能捡黄昏的碎片", detail="放学后在这 14 处能捡到：\n" + "\n".join(fix_names(s) for s in spots),
                 conf="single", src=["cn_guide", "cn_3dm_p2", src_terms]),
        reminder("fragment.04-21.dorm", "fragment", day_of_md("04-21"), "eve", "宿舍 4 楼有一块黄昏的碎片",
                 "宿舍 4 楼：黄昏的碎片", detail="在宿舍 4 楼通向屋顶的楼梯旁，台灯附近。",
                 conf="verified", src=["cn_guide", "cn_3dm_p2", src_terms]),
        reminder("fragment.05-25", "fragment", day_of_md("05-25"), "eve", "宿舍屋顶有一块黄昏的碎片",
                 "宿舍屋顶：黄昏的碎片", detail="宿舍屋顶这时开放了，晚上上去就能捡到。",
                 conf="verified", src=["cn_guide", "cn_3dm_p3", src_terms]),
        reminder("fragment.05-25.club", "fragment", day_of_md("05-25"), "eve",
                 f"勇气到 Lv.{club_lv} 了：晚上去放纵俱乐部，舞台旁有一块黄昏的碎片", "放纵俱乐部：黄昏的碎片",
                 detail=f"放纵俱乐部晚上要勇气 Lv.{club_lv} 才能进去，舞台旁有一块黄昏的碎片。",
                 requires={"courage": club_lv}, first_eligible=True, conf="single",
                 src=["cn_guide", "aqiu_social_events", src_terms]),
        reminder("fragment.08-06", "fragment", day_of_md("08-06"), "eve", "满月作战的地图上有 2 块黄昏的碎片，错过就没了",
                 "作战时捡黄昏的碎片", detail="满月作战的地图上，前进的路上有 2 块黄昏的碎片，作战结束后就拿不到了。",
                 priority=3, conf="verified", src=["cn_guide", "cn_3dm_p6", src_terms]),
    ]
    if by_day[day_of_md("08-06")]["eveLock"] != "full_moon":
        raise BuildError("8/6 evening is no longer the full-moon operation")

    # 屋久岛: request #44's beach items (aqiu's "6/20" is a typo for 7/20, AQIU_FIXES).
    _b, request_rows = overworld_section("Elizabeth Requests")
    req = {r[1]: r for r in request_rows}
    wrong, right, _why = AQIU_FIXES["request.44"]
    if f"Accept > {wrong}: Collect" not in req["44"][4]:
        raise BuildError("aqiu request #44 no longer has the 6/20 typo: drop AQIU_FIXES['request.44']")
    trip = cn_guide_item(guide, CN_ITEMS["trip.yakushima"], right.replace("/", "-").zfill(5), "#44", "任意一种")
    things = "、".join(re.match(r"([^：]+)：", s).group(1) for s in trip["substeps"])
    things = things[:things.rfind("、")] + "或" + things[things.rfind("、") + 1:]
    beach = "\n".join(trip["substeps"])
    if by_day[md_slash(right)]["dayLock"] != "yakushima":
        raise BuildError("7/20 is no longer a Yakushima day")
    # #44 has to be accepted before the trip: 3DM's 7/19 evening 「确保接取支线委托44」, aqiu's #44 opens on 7/9.
    eve_before = md_slash(right) - 1
    accept_from = md_slash(req["44"][0])
    if not sched.matches(eve_before, re.compile(r"接取支线委托44")) or not accept_from < eve_before:
        raise BuildError("3DM no longer says to accept request #44 on the evening before Yakushima, or #44 opens later")
    if by_day[eve_before]["eveLock"] is not None:
        raise BuildError(f"{md_of(eve_before)} evening is no longer free")
    out.append(reminder(
        f"trip.{md_of(eve_before)}.accept", "trip", eve_before, "eve", "明天去屋久岛：出发前先在天鹅绒房间接下伊莉莎白的第 44 号委托",
        fit_short(f"trip.{md_of(eve_before)}.accept", "出发前接伊莉莎白的委托"), label="屋久岛", priority=3,
        detail=f"伊莉莎白的第 44 号委托要在屋久岛的海边捡东西，出发前没接下的话，到了海边也捡不到。从 {cn_date(accept_from)}起"
               "就能接，接委托不会消耗时间。",
        conf="verified", src=["aqiu_overworld", sched.source(eve_before), "cn_guide"]))
    out.append(reminder(
        "trip.07-20", "trip", md_slash(right), "day", f"屋久岛：在海边捡一样东西（{things}都行），伊莉莎白的第 44 号委托要用，错过就没了",
        fit_short("trip.07-20", "海边捡东西（伊莉莎白的委托）", "海边捡东西（委托用）"), label="屋久岛", priority=3,
        detail="出发前要先在天鹅绒房间接下伊莉莎白的第 44 号委托。到了海边，下面任意一件都行：\n" + beach,
        conf="verified", src=["aqiu_overworld", "cn_guide", "cn_3dm_p5"]))
    # 修学旅行: request #96 needs all three drinks of the Kyoto vending machine.
    _b, kyoto = overworld_section("Kyoto Vending Machines")
    oden = re.search(r"Bought all 3 drinks from Kyoto vending machine > .* > Oden Juice \(Y(\d+)\)", req["96"][4])
    if len(kyoto) != 3 or not oden:
        raise BuildError("aqiu Kyoto vending machine / request #96 changed")
    cn_guide_item(guide, CN_ITEMS["trip.kyoto"], "11-17", "二楼自动贩卖机全买光")
    if by_day[day_of_md("11-17")]["dayLock"] != "school_trip":
        raise BuildError("11/17 is no longer a school-trip day")
    out.append(reminder(
        "trip.11-17", "trip", day_of_md("11-17"), "day", "修学旅行：把 2 楼自动贩卖机的 3 种饮料都买下，伊莉莎白的第 96 号委托要用",
        "买齐 3 种饮料（委托用）", label="修学旅行", priority=3,
        detail="住的地方 2 楼有自动贩卖机，3 种饮料每种买一瓶。回学校后在连接走廊把饮料交给一位女学生，再花 "
               f"{yen(int(oden.group(1)))}向她买关东煮，交给伊莉莎白就能完成第 96 号委托。这笔钱不在安排的预计里，"
               "买了以后记得在「同步现状」里更新现金。",
        conf="verified", src=["aqiu_overworld", "cn_guide"]))
    # 真宵堂 opens on 7/18 (aqiu Tartarus notes, the CN guide that evening).
    if "Mayoido Antiques opens on 7/18" not in (SOURCES_DIR / "aqiu_gamedata" / "tartarus.md").read_text(encoding="utf-8"):
        raise BuildError("aqiu tartarus.md no longer says Mayoido Antiques opens on 7/18")
    cn_guide_item(guide, CN_ITEMS["feature.mayoido"], "07-18", "真宵堂解锁")
    out.append(reminder("feature.07-18", "feature", day_of_md("07-18"), "eve", "真宵堂今天开张了", "真宵堂开张",
                        detail="真宵堂从今天起开张，可以去看看。", conf="verified",
                        src=["aqiu_tartarus", "cn_guide", "cn_3dm_p5"]))
    ledger.conflict("fragment.04-21", "Twilight Fragments in town on 4/21", "14 places by day (CN guide list) + dorm 4F "
                    "in the evening", ["14 in all, one of them in the dorm at night (3DM note)"], ["cn_guide", "cn_3dm_p2"])
    ledger.decide("reminders", "collectibles", "黄昏的碎片 (official achievement wording; 黄昏碎片 is forbidden): 4/21 the 14 "
                  "town spots of the CN guide by day and the dorm 4F one in the evening, 5/25 the dorm roof, Club Escapade "
                  f"(Courage Lv{club_lv}, aqiu Required For) on the first evening at or after 5/25 that the forecast "
                  "allows, 8/6 the two on the operation map (missable). 屋久岛 7/20: one beach item for request #44 "
                  f"({AQIU_FIXES['request.44'][2]}); 修学旅行 11/17: the three Kyoto vending-machine drinks for request "
                  "#96 (aqiu lists exactly three). 真宵堂 opens on 7/18 (aqiu tartarus notes, CN guide and 3DM on that "
                  "evening; the daytime is a story lock). Not shipped: Mitsuru's results-day reward, shop restocks, URL "
                  "notes going on sale, the exam first-place threshold, 'unlock becomes possible', Persona fusion names; "
                  "dropped: the CN 1/3 TV entry, cat rescues, shrine fortunes, 4/29 山间补习班, "
                  "9/1 tutoring items, the 'slot-free' label on requests #38 and #10.")
    return out


def doze_and_nurse(ledger: Ledger, page: AqiuPage, sched: Schedule3dm, guide: dict[int, list[dict]],
                   by_day: dict[int, dict]) -> tuple[dict, dict]:
    """上课犯困 days (3DM 「上课：不闭眼 / 闭不闭眼都行」 and the CN guide) and the 保健室 rule (aqiu)."""
    general = page.subsection("general")
    awake = re.search(r"stay awake for Academics \+(\d+) or close your eyes for Courage \+(\d+)", general)
    nurse = re.search(r"Tartarus visit or Full Moon Operation, you can visit the Nurse's Office for Courage \+(\d+) "
                      r"without taking up any time", general)
    if not awake or not nurse or awake.group(1) != "2" or awake.group(2) != "2":
        raise BuildError("aqiu scheduled-activities bullets (class dozing / nurse's office) changed")
    from_3dm = {d for d in sched.lines if sched.matches(d, Schedule3dm.DOZE)}
    from_cn = {d for d, items in guide.items() for item in items
               if item.get("kind") == "class" and re.match(r"课堂 · (不闭眼|闭不闭眼都行)", item["title"])}
    both = from_3dm | from_cn
    school = sorted(d for d in both if by_day.get(d, {}).get("type") == "school")
    dropped = sorted(md_of(d) for d in both if d not in school)
    ledger.decide("reminders", "doze", f"上课犯困 on {len(school)} school days: 3DM lines 「上课：不闭眼 / 闭不闭眼都行」 "
                  f"({len(from_3dm)} dates) and the CN guide's ({len(from_cn)}); 3DM only: "
                  f"{', '.join(md_of(d) for d in sorted(from_3dm - from_cn)) or 'none'}; CN guide only (3DM has no text "
                  f"there): {', '.join(md_of(d) for d in sorted(from_cn - from_3dm)) or 'none'}. Not school days, "
                  f"dropped: {', '.join(dropped) or 'none'}. The choice is the engine's: stay awake (Academics "
                  f"+{awake.group(1)}) until Academics is MAX, then close the eyes (Courage +{awake.group(2)}) until "
                  "Courage is MAX (aqiu).")
    ledger.decide("reminders", "nurse", f"保健室: on a school day right after a Tartarus visit or a full-moon operation, "
                  f"the nurse's office gives Courage +{nurse.group(1)} without taking time (aqiu); the engine applies it to "
                  "planned Tartarus evenings, full-moon operations and the latest recorded Tartarus evening.")
    pages = sorted({sched.source(d) for d in from_3dm}, key=lambda s: int(s[len("cn_3dm_p"):]))
    doze = {"academics": int(awake.group(1)), "conf": "single", "courage": int(awake.group(2)), "days": school,
            "src": ["aqiu_social_events", "cn_guide"] + pages}
    story = []
    for md, (pattern, auto) in STORY_TOWER_EVENINGS.items():
        d = day_of_md(md)
        if by_day[d]["eveLock"] is None or not sched.matches(d, re.compile(pattern)) or auto not in page.html:
            raise BuildError(f"STORY_TOWER_EVENINGS {md}: no longer a story evening in Tartarus (3DM / aqiu)")
        if not sched.matches(d + 1, re.compile(r"保健室")):
            raise BuildError(f"STORY_TOWER_EVENINGS {md}: 3DM no longer sends you to the nurse's office the next day")
        story.append(d)
    ledger.decide("reminders", "nurse-story", "保健室 after the story's own Tartarus evenings (the player chooses nothing): "
                  + "; ".join(f"{md} ({auto}; 3DM: the nurse's office the next day)" for md, (_p, auto)
                              in STORY_TOWER_EVENINGS.items()) + ".")
    nurse_src = ["aqiu_social_events", "cn_guide"] + sorted({sched.source(d) for d in story})
    return doze, {"conf": "single", "courage": int(nurse.group(1)), "src": nurse_src, "storyTowerDays": story}



def combat_catalogue(ledger: Ledger) -> list[dict]:
    """Compile every pinned Reload guardian/operation/Monad encounter into a searchable reference.

    Resistance symbols are interpreted mechanically, independently of translated tactical prose:
    v is double damage without the weakness flag; a bare * suppresses only secondary effects.
    """
    data = read_evidence("combat_tips_evidence.json")
    if data.get("schema") != "p3r.combat_tips.v1":
        raise BuildError("combat catalogue: unknown evidence schema")
    source = ROOT / data.get("sourcePath", "")
    if source != SOURCES_DIR / "aqiu_gamedata/tartarus.md" or sha256_file(source) != data.get("sourceSha256"):
        raise BuildError("combat catalogue: pinned Tartarus source changed")
    lines = source.read_text(encoding="utf-8").splitlines()
    sections: dict[str, dict[str, list[dict]]] = {}
    section, mode = "", ""
    for i, line in enumerate(lines):
        if line.startswith("### "):
            section = line[4:]
            sections[section] = {"stats": [], "resistances": []}
        elif line.startswith("#### "):
            mode = line[5:]
        elif line.startswith("| ") and mode in ("Enemy Stats", "Enemy Resistances"):
            row = [value.strip() for value in line.strip("|").split("|")]
            if row[0] not in ("Floors", "---"):
                field = "stats" if mode == "Enemy Stats" else "resistances"
                sections[section][field].append({"line": i + 1, "row": row})
    required = lambda row: row[0].isdigit() or row[0] in ("Full Moon", "4P") or re.fullmatch(r"[DP]\d+", row[0])
    expected_stats = {(section, row["line"]) for section, fields in sections.items()
                      for row in fields["stats"] if required(row["row"])}
    expected_res = {(section, row["line"]) for section, fields in sections.items()
                    for row in fields["resistances"] if required(row["row"])}
    seen_stats, seen_res = set(), set()
    entries = data.get("entries", [])
    ids = set()
    kinds = {"guardian", "full_moon", "monad", "story", "final", "optional"}
    locations = data.get("passageLocations", {})
    expected_locations = {"Yabbashah I": 91, "Yabbashah II": 117, "Tziah I": 143, "Tziah II": 171,
                          "Harabah I": 197, "Harabah II": 225, "Adamah I": 255}
    if {section: witness.get("floor") for section, witness in locations.items()} != expected_locations:
        raise BuildError("combat catalogue: all seven fixed passage floors are required")
    guide = json.loads(GUIDE_PATH.read_text(encoding="utf-8"))
    guide_items = {item["id"]: item for block in guide["blocks"] for item in block["items"]}
    for section, witness in locations.items():
        item = guide_items.get(witness.get("guideItemId"), {})
        if witness.get("text", "") not in json.dumps(item, ensure_ascii=False) or not item:
            raise BuildError(f"combat catalogue: changed fixed-passage floor witness for {section}")
    for field in ("reaperAvailabilityWitness", "elizabethLocationWitness"):
        witness = data.get(field, {})
        path = ROOT / witness.get("path", "")
        if not path.is_file() or not witness.get("text") or witness["text"] not in path.read_text(encoding="utf-8"):
            raise BuildError(f"combat catalogue: changed {field}")
    damage_names = ("斩击", "打击", "贯穿", "火炎", "冰冻", "电击", "疾风", "光", "暗")
    out = []
    for entry in entries:
        ident, kind, section = entry.get("id"), entry.get("kind"), entry.get("section")
        if not isinstance(ident, str) or not re.fullmatch(r"[a-z0-9_.]+", ident) or ident in ids or kind not in kinds:
            raise BuildError("combat catalogue: malformed or duplicated identity/kind")
        ids.add(ident)
        if section not in sections:
            raise BuildError(f"combat {ident}: unknown source section")
        for field, source_field, seen in (("sourceStats", "stats", seen_stats),
                                          ("sourceResistances", "resistances", seen_res)):
            for row in entry.get(field, []):
                key = section, row.get("line")
                if row not in sections[section][source_field] or key in seen:
                    raise BuildError(f"combat {ident}: changed, missing or duplicated {field} row")
                seen.add(key)
        stats = entry.get("sourceStats", [])
        resistances = entry.get("sourceResistances", [])
        if not stats or len(stats) != len(resistances):
            raise BuildError(f"combat {ident}: every enemy needs stats and resistances")
        for heading, witness in entry.get("sourceHints", {}).items():
            line = witness.get("line", 0)
            if not 1 <= line <= len(lines) or lines[line - 1] != "##### " + heading:
                raise BuildError(f"combat {ident}: changed hint heading")
            actual = []
            for text in lines[line:]:
                if text.startswith("#"):
                    break
                if text.startswith("* "):
                    actual.append(text[2:])
            if actual != witness.get("bullets"):
                raise BuildError(f"combat {ident}: changed hint instructions")
        details, concise, enemies = [], [], []
        counts = entry.get("enemyCounts", {})
        if not isinstance(counts, dict) or not set(counts) <= {row["row"][1] for row in stats}:
            raise BuildError(f"combat {ident}: unknown enemy in lineup counts")
        for stat, res in zip(stats, resistances):
            row = res["row"]
            if len(row) != 17 or row[:2] != stat["row"][:2]:
                raise BuildError(f"combat {ident}: mismatched resistance row")
            name = entry.get("names", {}).get(row[1], {}).get("zh")
            if not isinstance(name, str) or not name.strip():
                raise BuildError(f"combat {ident}: every enemy needs a Chinese name")
            codes = row[2:11]
            if any(not re.fullmatch(r"(?:[wzvstnrd-]\*?|\*)", code) for code in codes):
                raise BuildError(f"combat {ident}: unknown damage resistance symbol")
            count = counts.get(row[1], 1)
            if not isinstance(count, int) or isinstance(count, bool) or not 1 <= count <= 99:
                raise BuildError(f"combat {ident}: enemy count must be 1..99")
            if count > 1 and not any(re.search(re.escape(row[1]) + rf" x{count}(?!\d)", heading)
                                     for heading in entry.get("sourceHints", {})):
                raise BuildError(f"combat {ident}: multiple-enemy count needs a source lineup witness")
            enemies.append({"nameZh": name, "count": count, "affinities": codes})
            attrs = lambda symbols: "、".join(damage_names[i] for i, code in enumerate(codes)
                                              if code.strip("*") in symbols)
            weak = attrs(("w", "z"))
            description = "弱点：" + (weak or "无")
            concise.append(name + ("弱" + weak if weak else "无弱点"))
            for symbols, label in ((("s",), "耐性"), (("t",), "强耐性"), (("n",), "无效"),
                                   (("r",), "反弹"), (("d",), "吸收")):
                value = attrs(symbols)
                if value:
                    description += "；" + label + "：" + value
            vulnerable = attrs(("v",))
            if vulnerable:
                description += "；" + vulnerable + "伤害×2（不会因此倒地）"
            details.append(name + "｜" + description)
        note = entry.get("noteZh", "").strip()
        if not note:
            note = "优先打弱点，换手接总攻击。" if any("弱点：无" not in line for line in details) else "避开无效、反弹和吸收，降低敌方攻防后用有效伤害输出。"
        title = entry.get("titleZh", "")
        aliases = entry.get("aliases", [])
        if not isinstance(title, str) or not title.strip() or not isinstance(aliases, list) or not aliases:
            raise BuildError(f"combat {ident}: title and search aliases are required")
        detail = "\n".join(details) + "\n打法：" + note
        first, floor = entry.get("fromDay"), entry.get("floor")
        if not isinstance(first, int) or not FIRST_DAY <= first <= LAST_DAY or not isinstance(floor, int) or not 0 <= floor <= 264:
            raise BuildError(f"combat {ident}: invalid date or floor")
        battle_order = entry.get("battleOrder", 0)
        source_floor = stats[0]["row"][0]
        if kind == "monad" and source_floor.startswith("P"):
            if floor != locations.get(section, {}).get("floor") or battle_order != int(source_floor[1:]):
                raise BuildError(f"combat {ident}: passage floor and battle order must match their witnesses")
        elif battle_order != 0:
            raise BuildError(f"combat {ident}: battle order is only valid for fixed-passage battles")
        if ident == "combat.adamah.p4" and (kind != "optional" or floor != 0 or first != data["reaperAvailabilityWitness"]["fromDay"]):
            raise BuildError("combat catalogue: the Reaper is an optional encounter, not a fourth fixed-passage battle")
        if ident == "combat.ultimate.4p" and (kind != "optional" or floor != data["elizabethLocationWitness"]["floor"]):
            raise BuildError("combat catalogue: Elizabeth is a separate 255F hidden challenge")
        short = "；".join(concise) if len(concise) <= 3 else note.split("。")[0] + "。"
        out.append({"id": ident, "kind": kind, "titleZh": title, "shortZh": short,
                    "detailZh": detail, "tacticsZh": note, "enemies": enemies, "battleOrder": battle_order,
                    "encounterType": ("door" if stats[0]["row"][0].startswith("D") else "passage") if kind == "monad" else "",
                    "aliases": aliases, "fromDay": first, "floor": floor,
                    "conf": "single", "src": ["aqiu_tartarus", "cn_guide", "ev_combat_tips", "ev_combat_cn"]})
    if not expected_stats <= seen_stats or not expected_res <= seen_res:
        raise BuildError("combat catalogue: every fixed-floor, operation and Monad source enemy must be covered exactly once")
    story_entries = data.get("storyEntries", [])
    if len(story_entries) != 1 or story_entries[0].get("id") != "combat.prologue.awakening":
        raise BuildError("combat catalogue: the 4/9 awakening reference is required")
    for story in story_entries:
        if story.get("kind") != "story" or story.get("fromDay") != day_of_md("04-09") or story.get("floor") != 0:
            raise BuildError("combat catalogue: invalid awakening reference")
        for witness in story.get("sourceWitnesses", []):
            path = ROOT / witness.get("path", "")
            if not path.is_file() or sha256_file(path) != witness.get("sha256") or witness.get("text", "") not in path.read_text(encoding="utf-8"):
                raise BuildError("combat catalogue: awakening source witness changed")
        if len(story.get("sourceWitnesses", [])) < 2:
            raise BuildError("combat catalogue: awakening needs its dated walkthrough and official name")
        out.append({key: story[key] for key in ("id", "kind", "titleZh", "shortZh", "tacticsZh", "fromDay", "floor", "aliases")})
        out[-1].update({"detailZh": story["tacticsZh"], "enemies": [], "encounterType": "", "battleOrder": 0, "conf": "single",
                        "src": ["aqiu_walkthrough", "steam_sc_achievements", "ev_combat_tips"]})
    counts = {kind: sum(1 for item in out if item["kind"] == kind) for kind in kinds}
    if counts != {"guardian": 43, "full_moon": 8, "monad": 39, "story": 2, "final": 3, "optional": 2}:
        raise BuildError("combat catalogue: expected all 97 base-game encounter and story groups")
    ledger.decide("reminders", "combat-catalogue", "97 searchable combat references: all 43 fixed guardian floors, "
                  "21 random Monad-door types and 18 battle groups inside seven fixed-floor passages, eight "
                  "encounters over all seven May-November operation nights, 4/9 awakening, Chidori, "
                  "the three final-floor fights, the Reaper and Elizabeth. P1/P2/P3 are battle groups inside one "
                  "fixed-floor passage, not separate passages. The source's Reaper P4 row is an independent optional "
                  "encounter rather than a fourth required passage fight. Every enemy and phase is pinned to its full source "
                  "stat/resistance rows; v means double damage without knockdown weakness, a bare * affects only "
                  "non-damaging effects. Phase-changing bosses retain each row independently. Chinese guide enemy "
                  "names are preferred where available; otherwise editorial translations are documented in the evidence. "
                  "Consulting this catalogue never schedules a battle, changes stats or records quest progress.")
    return out

def build_reminders(ledger: Ledger, page: AqiuPage, days: list[dict]) -> dict:
    """The pack's `reminders` section: static items, 上课犯困 days, the 保健室 rule and missing-person groups."""
    by_day = {x["d"]: x for x in days}
    sched = Schedule3dm()
    guide = cn_guide_days()
    groups, people = rescue_groups(ledger)
    items = answer_reminders(ledger, page, sched, guide)
    items += tv_reminders(ledger, sched, guide)
    items += request_reminders(ledger, people, sched)
    items += collectible_reminders(ledger, page, sched, guide, by_day)
    doze, nurse = doze_and_nurse(ledger, page, sched, guide, by_day)
    items.sort(key=lambda x: (x["day"], x["slot"] == "eve", x["id"]))
    _bullets, requests = overworld_section("Elizabeth Requests")
    return {"doze": doze, "items": items, "nurse": nurse, "rescueGroups": groups,
            "requestTips": request_catalogue(ledger, requests), "combatTips": combat_catalogue(ledger)}


# --------------------------------------------------------------------------------------------
# Pack assembly


# Player-facing summary of the planning assumptions (shown under 规划说明). Developer detail lives in REPORT.md.
NOTES_ZH = (
    "【普通见面】预计好感 +10；魅力 MAX 后点数 ×1.51。",
    "【休息日邀约】收到短信后才赴约，占用主角一个白天，只增加好感。",
    "【人格面具】带同阿尔卡那的人格面具，点数 ×1.51；可在规划设置里选「只给目标社群带」或「基本都带」。",
    "【平安夜】12月24日默认婉拒邀约；选定赴约对象后，当天白天和晚上都留给平安夜约会。",
    "【塔尔塔罗斯】队友不能参加的晚上不安排探索。",
    "【失踪者】北村文吉（9月12日～10月3日）、舞子（10月21日～11月2日）失踪期间不能见面。逾期未救出，社群无法继续；优先提前救援。",
    "【女皇】11月21日起，需要学力 MAX，且至少有一次考试拿过第一名。",
    "【教皇】解锁时需回学校调查柿子树，安排在上学日放学后。",
    "【恶魔】需要隐者 RANK 4；三次付款分不同晚上，前两次不消耗时间。",
    "【塔】需要勇气 Lv.4。",
    "【太阳】8月9日起可开始解锁。",
    "【图书室】自习预计学力 +2。",
    "【打工】显示基础工资，预计能力收益见活动详情。",
    "【结缘网站的字条】11月11日起，晚上在放纵俱乐部购买（3,000 日元），回宿舍用公用电脑给已解锁、未 MAX 的社群增加好感；预计 +10，不直接升级，占用一个晚上。",
    "【羁绊剧情】可选择安排伊织顺平、真田明彦、虎狼丸、天田乾、荒垣真次郎的剧情；每话占用一个时段，奖励能力值和道具，最后一话解锁人格面具合体。",
    "【当天提醒】课堂与考试答案、保健室、电视购物、委托、黄昏的碎片和救援期限会在当天显示，也可在「提示」中搜索。",
    "【剧情学力】8月10日～15日暑期补习每天自动学力 +3。课堂提问、上课犯困、保健室和考试名次的收益，完成后在「同步现状」更新。",
)



def build_pack() -> tuple[dict, Ledger]:
    ledger = Ledger()
    ledger.decide("scope", "editions", "Only the main game (Xrd777) is described. Astrea/Episode Aigis tables are "
                  "not used; FES/Portable material that the evidence files already rejected stays out (gorillawiki "
                  "entry 103, the FES July calendar, atwiki page 36, P3P rank tables, samurai-gamers reversal "
                  "mechanics, the Persona 4 '+10' paragraph, megatenwiki's Reload calendar with wrong full moons). "
                  "Reload has no female-protagonist route, so no such links exist. Every source carries an edition and "
                  "no record or listed value rests on non-Reload sources alone (§1.1).")
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
    reminders = build_reminders(ledger, page, days)

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
        "src": ["gd_general_coefficient", "ev_ordinary_gain", "lpbeach", "wiki455", "ev_affinity"],
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
        "invitesMeaning": "Holiday invitation (D cell): a possible SMS sender, not a guaranteed meeting. "
                          "Only record an actually received invitation; it consumes daytime and raises affinity, "
                          "never directly ranks up. Its unmodeled affinity gain is not credited to forecasts.",
        "lastDay": LAST_DAY,
        "links": links,
        "lockReasons": {k: {"zh": v} for k, v in LOCK_REASONS.items()},
        "notes": list(NOTES_ZH),
        "packVersion": PACK_VERSION,
        "policy": policy,
        "reminders": reminders,
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
        gain = d.get("autoStatGain", {})
        if not isinstance(gain, dict):
            raise BuildError(f"day {d['md']}: autoStatGain must be a stat-point map")
        if gain and d["dayLock"] is None:
            raise BuildError(f"day {d['md']}: autoStatGain requires a locked daytime story slot")
        for stat, points in gain.items():
            if stat not in STAT_IDS or type(points) is not int or not 1 <= points <= 100:
                raise BuildError(f"day {d['md']}: autoStatGain invalid stat/points {stat!r}: {points!r}")
    for link in pack["links"]:
        check_src(f"link {link['id']}", link)
        check_src(f"link {link['id']}.unlock", link["unlock"])
        for rank, hint in link.get("dialogueHints", {}).items():
            where = f"link {link['id']}.dialogueHints[{rank}]"
            check_src(where, hint)
            if not rank.isdigit() or not 2 <= int(rank) <= 10 or hint["conf"] != "single":
                raise BuildError(f"{where}: rank or confidence invalid")
            if not set(hint) <= {"common", "friend", "romance", "conf", "src"}:
                raise BuildError(f"{where}: unknown fields")
            for route in ("common", "friend", "romance"):
                if route not in hint:
                    continue
                variant = hint[route]
                if not isinstance(variant.get("choices"), list) or not isinstance(variant.get("sequence"), list):
                    raise BuildError(f"{where}.{route}: ordered choices and sequence arrays required")
                if "shortChoices" in variant and len(variant["shortChoices"]) != len(variant["choices"]):
                    raise BuildError(f"{where}.{route}: shortChoices must preserve question count")
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
    validate_reminders(pack, check_src)
    check_reload_sources(pack)
    text = json.dumps(pack, ensure_ascii=False)
    for word in FORBIDDEN_STRINGS:
        if word in text:
            raise BuildError(f"pack contains the outdated or unofficial term {word!r}")
    for rel, phrase, why in OFFICIAL_TERMS:
        if phrase not in (SOURCES_DIR / rel).read_text(encoding="utf-8"):
            raise BuildError(f"official wording for {why} not found in {rel}: {phrase!r}")
    if "休息日邀约" not in text or "主角" not in text:
        raise BuildError("pack must use the official terms 休息日邀约 and 主角")
    page_3dm = (SOURCES_DIR / "cn_guides" / "3dm_p42.html").read_text(encoding="utf-8")
    for block in pack["tartarus"]["blocks"]:
        if any(part not in page_3dm for part in block["zh"].split(" ")):
            raise BuildError(f"block {block['id']}: name {block['zh']!r} is not in the 3DM page")
    answers = sorted({x["answerZh"] for x in pack["reminders"]["items"] if x["answerZh"]}, key=len, reverse=True)
    for where, value in player_texts(pack):
        if where.startswith("reminders.items"):
            for answer in answers:  # verbatim 3DM answers (MAY BLUES, MADRID…) are exempt, as dialogue is
                value = value.replace(answer, "")
        latin = [w for w in re.findall(r"[A-Za-z]+", value) if w not in ALLOWED_LATIN]
        if latin:
            raise BuildError(f"{where}: player-facing text contains Latin words {latin}: {value}")
        plain = value
        for allowed in GLOSSARY_ALLOWED:
            plain = plain.replace(allowed, "")
        off = [w for w in GLOSSARY_AVOID if w in plain]
        if off:
            raise BuildError(f"{where}: player-facing text uses {off} (docs/GLOSSARY_zh.md): {value}")


def validate_reminders(pack: dict, check_src) -> None:
    """The reminders section: the rules RulesLoader.parseReminders enforces again when the app loads the pack."""
    rem = pack["reminders"]
    by_day = {d["d"]: d for d in pack["days"]}
    stats = {s["id"] for s in pack["stats"]}
    numbers: set[int] = set()
    for i, item in enumerate(rem.get("requestTips", [])):
        where = f"reminders.requestTips[{i}]"
        check_src(where, item)
        number = item.get("number")
        if not isinstance(number, int) or not 1 <= number <= 1000 or number in numbers:
            raise BuildError(f"{where}.number must be a unique positive request number")
        numbers.add(number)
        if any(not isinstance(item.get(field), str) or not item[field].strip() for field in ("titleZh", "detailZh")):
            raise BuildError(f"{where}: titleZh and detailZh are required")
        first, last = item.get("fromDay"), item.get("untilDay")
        if first not in by_day or (last is not None and (last not in by_day or last < first)):
            raise BuildError(f"{where}: fromDay and untilDay must be ordered calendar days")
        aliases = item.get("aliases", [])
        if (not isinstance(aliases, list) or any(not isinstance(x, str) or not x.strip() for x in aliases)
                or len(aliases) != len(set(aliases))):
            raise BuildError(f"{where}.aliases must be unique nonempty strings")
    combat_ids: set[str] = set()
    for i, item in enumerate(rem.get("combatTips", [])):
        where = f"reminders.combatTips[{i}]"
        check_src(where, item)
        ident = item.get("id")
        if not isinstance(ident, str) or not re.fullmatch(r"combat\.[a-z0-9_]+\.[a-z0-9_]+", ident) or ident in combat_ids:
            raise BuildError(f"{where}.id must be a unique combat identity")
        combat_ids.add(ident)
        if item.get("kind") not in ("guardian", "full_moon", "monad", "story", "final", "optional"):
            raise BuildError(f"{where}.kind is unknown")
        if any(not isinstance(item.get(key), str) or not item[key].strip()
               for key in ("titleZh", "shortZh", "detailZh", "tacticsZh")):
            raise BuildError(f"{where}: titleZh, shortZh, detailZh and tacticsZh are required")
        if item.get("encounterType") not in ("", "door", "passage"):
            raise BuildError(f"{where}.encounterType must be door, passage or empty")
        battle_order = item.get("battleOrder")
        if not isinstance(battle_order, int) or isinstance(battle_order, bool) or not 0 <= battle_order <= 9:
            raise BuildError(f"{where}.battleOrder must be 0..9")
        if item["encounterType"] == "passage":
            if battle_order == 0 or item.get("floor", 0) == 0:
                raise BuildError(f"{where}: fixed passages need floor and battle order")
        elif battle_order != 0:
            raise BuildError(f"{where}.battleOrder is only valid for fixed passages")
        enemies = item.get("enemies")
        if not isinstance(enemies, list) or (not enemies and item["kind"] != "story"):
            raise BuildError(f"{where}.enemies must contain the enemy lineup")
        for e, enemy in enumerate(enemies):
            enemy_where = f"{where}.enemies[{e}]"
            if not isinstance(enemy.get("nameZh"), str) or not enemy["nameZh"].strip():
                raise BuildError(f"{enemy_where}.nameZh is required")
            count = enemy.get("count")
            if not isinstance(count, int) or isinstance(count, bool) or not 1 <= count <= 99:
                raise BuildError(f"{enemy_where}.count must be 1..99")
            affinities = enemy.get("affinities")
            if not isinstance(affinities, list) or len(affinities) != 9:
                raise BuildError(f"{enemy_where}.affinities must have 9 entries")
            if any(not isinstance(code, str) or not re.fullmatch(r"(?:[wzvstnrd-]\*?|\*)", code) for code in affinities):
                raise BuildError(f"{enemy_where}.affinities contains an unknown resistance symbol")
        if item.get("fromDay") not in by_day:
            raise BuildError(f"{where}.fromDay must be a calendar day")
        floor = item.get("floor")
        if not isinstance(floor, int) or not 0 <= floor <= 264:
            raise BuildError(f"{where}.floor must be zero or a fixed floor")
        if item["kind"] in ("guardian", "final") and floor == 0:
            raise BuildError(f"{where}.floor must identify the fixed floor")
        aliases = item.get("aliases")
        if not isinstance(aliases, list) or any(not isinstance(x, str) or not x.strip() for x in aliases) or len(aliases) != len(set(aliases)):
            raise BuildError(f"{where}.aliases must be unique nonempty strings")
    ids: set[str] = set()
    for i, item in enumerate(rem["items"]):
        where = f"reminders.items[{i}]"
        check_src(where, item)
        if not REMINDER_ID.fullmatch(item["id"]) or item["id"] in ids:
            raise BuildError(f"{where}: id {item['id']!r} is malformed or duplicated")
        ids.add(item["id"])
        if item["kind"] not in REMINDER_KINDS or item["slot"] not in ("day", "eve"):
            raise BuildError(f"{where}: kind {item['kind']!r} / slot {item['slot']!r}")
        if item["day"] not in by_day or (item["untilDay"] is not None and not item["day"] <= item["untilDay"] <= LAST_DAY):
            raise BuildError(f"{where}: day {item['day']} / untilDay {item['untilDay']} outside the calendar")
        if not item["zh"] or not item["labelZh"] or not item["shortZh"] or not fits_short(item["shortZh"]):
            raise BuildError(f"{where}: zh, labelZh and a shortZh of at most {SHORT_MAX_CHARS} chars are required")
        answer = item["kind"] in ("class", "exam")
        if bool(item["answerZh"]) != answer or not 0 <= item["option"] <= 4 or (item["option"] and item["kind"] != "class"):
            raise BuildError(f"{where}: answerZh only (and always) for class/exam, option 0..4 for class only")
        if not 1 <= item["priority"] <= 3 or item["costYen"] < 0:
            raise BuildError(f"{where}: priority 1..3 and costYen >= 0")
        requires = item["requires"] or {}
        if any(k not in stats or not 1 <= v <= 6 for k, v in requires.items()) or (item["firstEligible"] and not requires):
            raise BuildError(f"{where}: requires maps stats to Lv 1..6; firstEligible needs requires")
    if [(x["day"], x["slot"] == "eve", x["id"]) for x in rem["items"]] != sorted((x["day"], x["slot"] == "eve", x["id"])
                                                                                  for x in rem["items"]):
        raise BuildError("reminders.items must be sorted by (day, slot, id)")
    doze = rem["doze"]
    check_src("reminders.doze", doze)
    if doze["days"] != sorted(set(doze["days"])) or any(by_day[d]["type"] != "school" for d in doze["days"]):
        raise BuildError("reminders.doze.days must be ascending school days")
    if doze["academics"] < 1 or doze["courage"] < 1:
        raise BuildError("reminders.doze gains must be positive")
    check_src("reminders.nurse", rem["nurse"])
    if rem["nurse"]["courage"] < 1:
        raise BuildError("reminders.nurse.courage must be positive")
    story = rem["nurse"].get("storyTowerDays", [])
    if story != sorted(set(story)) or any(by_day[d]["eveLock"] is None for d in story):
        raise BuildError("reminders.nurse.storyTowerDays must be ascending days whose evening is a story lock")
    rescues = {r["id"] for r in pack["tartarus"]["rescues"]}
    for i, group in enumerate(rem["rescueGroups"]):
        where = f"reminders.rescueGroups[{i}]"
        check_src(where, group)
        if group["count"] < 1 or not set(group["rescueIds"]) <= rescues or not group["fromDay"] <= group["toDay"]:
            raise BuildError(f"{where}: count >= 1, known rescueIds and fromDay <= toDay are required")
        if "locksZh" in group and not group["locksZh"]:
            raise BuildError(f"{where}: locksZh, when present, must not be empty")
    # The engine takes the limit evening as the last chance: it must be a Tartarus evening, and the night after
    # it must not be one (as the walkthrough and the game data have it).
    limits = [(f"reminders.rescueGroups[{g['id']}]", g["toDay"]) for g in rem["rescueGroups"]]
    limits += [(f"tartarus.rescues[{r['id']}]", r["toDay"]) for r in pack["tartarus"]["rescues"]]
    for where, last in limits:
        after = by_day.get(last + 1)
        if not by_day[last]["tartarus"] or (after is not None and after["tartarus"]):
            raise BuildError(f"{where}: the limit {md_of(last)} must be the last Tartarus evening of the window")


# Words docs/GLOSSARY_zh.md tells us not to use in player-facing text (解锁, 见面, 安排, MAX, 羁绊剧情 …),
# and the menu names that legitimately contain 规划.
GLOSSARY_AVOID = (
    "开启", "建立", "练满", "满级", "计划", "规划", "羁绊剧场", "估算", "规则包", "社交链接", "交友网站", "牵绊",
    "不占用时段", "不占时间", "锁定", "约会", "会面", "加深关系", "Lv.6", "上课问答", "医务室",
)
# 约会 is reserved for romance (docs/GLOSSARY_zh.md); the Christmas Eve date lock is one.
GLOSSARY_ALLOWED = ("规划设置", "规划说明", "平安夜约会", "人工岛计划文书")


def _records(pack: dict, kind: str, ident: str) -> list[dict]:
    return [x for x in pack[kind] if x["id"] == ident]


# Values whose own evidence is narrower than the src list of the record that holds them (Reload-only
# source policy, value level): (value, records holding it, source ids the value rests on). Every listed id
# must be in each record's src, and at least one of them must be a Reload source.
VALUE_SOURCES = (
    ("policy.ordinaryGain (+10 per ordinary meeting)", lambda p: [p["policy"]], ("ev_ordinary_gain", "lpbeach", "wiki455")),
    ("policy multipliers (Persona, Charm, exam)", lambda p: [p["policy"]], ("gd_general_coefficient",)),
    ("stats[].thresholds", lambda p: p["stats"], ("gd_hero_parameter",)),
    ("links[].threshold", lambda p: p["links"], ("gd_community_event",)),
    ("links[].arcanaZh", lambda p: p["links"], ("gd_race",)),
    ("links[].avail (manual links)", lambda p: [x for x in p["links"] if x["kind"] == "manual"], ("aqiu_social_events",)),
    ("links[].eventGain (manual links)", lambda p: [x for x in p["links"] if x["kind"] == "manual"],
     ("ev_rank_points", "ev_manual_models")),
    ("links[].invite", lambda p: [x for x in p["links"] if x["invite"]], ("gd_holiday_schedule",)),
    ("links[].dialogue", lambda p: [x for x in p["links"] if x["dialogue"]], ("ev_dialogue", "cn_guide")),
    ("links[].dialogueHints (original approval)",
     lambda p: [x for x in p["links"] if "ev_dialogue_approved" in x["src"]], ("ev_dialogue_approved",)),
    ("links[].dialogueHints (authorized completion)",
     lambda p: [x for x in p["links"] if "ev_dialogue_completion" in x["src"]], ("ev_dialogue_completion",)),
    ("tartarus.blocks[] dates and floors", lambda p: p["tartarus"]["blocks"], ("gd_activity",)),
    ("tartarus.blocks[].zh", lambda p: p["tartarus"]["blocks"], ("cn_3dm",)),
    ("tartarus.rescues[] windows", lambda p: p["tartarus"]["rescues"],
     ("aqiu_overworld", "gd_disappear_data", "gd_disappear_help")),
    ("reminders.rescueGroups[] windows", lambda p: p["reminders"]["rescueGroups"],
     ("aqiu_overworld", "gd_disappear_data", "gd_disappear_help")),
    ("reminders.combatTips[] phase-specific damage resistances and encounter coverage",
     lambda p: [item for item in p["reminders"].get("combatTips", []) if item.get("enemies")],
     ("aqiu_tartarus", "ev_combat_tips")),
    ("missing-person limit evening still counts (rescues, links[].missing, rescueGroups)",
     lambda p: p["tartarus"]["rescues"] + [x["missing"] for x in p["links"] if x["missing"]]
     + p["reminders"]["rescueGroups"], ("aqiu_walkthrough",)),
    ("activities job_chagall pay", lambda p: _records(p, "activities", "job_chagall"),
     ("gd_arbeit_help", "ev_jobs", "megatenwiki_chagall_cafe", "wiki469")),
    ("activities job_accessory pay", lambda p: _records(p, "activities", "job_accessory"),
     ("gd_arbeit_help", "ev_jobs", "megatenwiki_be_blue_v", "wiki469")),
    ("activities job_cinema pay", lambda p: _records(p, "activities", "job_cinema"),
     ("gd_arbeit_help", "ev_jobs", "megatenwiki_port_island_station", "wiki469")),
    ("datingNote cost, gain, sale date", lambda p: [p["datingNote"]],
     ("aqiu_overworld", "ev_dating_note", "siliconera_urls", "wiki455")),
)


def src_records(node, path: str = "", where: str = "") -> list[tuple[str, str, dict]]:
    """(area, where, record) for every pack record that carries a src list. area drops list positions
    ("links.unlock"); where names them by id, md or rank ("links[emperor].unlock")."""
    found = []
    if isinstance(node, dict):
        if isinstance(node.get("src"), list):
            found.append((path, where, node))
        for key, value in node.items():
            if key != "sources":
                found.extend(src_records(value, f"{path}.{key}" if path else key, f"{where}.{key}" if where else key))
    elif isinstance(node, list):
        for i, value in enumerate(node):
            label = i
            if isinstance(value, dict):
                label = value.get("id") or value.get("md") or value.get("rank") or i
            found.extend(src_records(value, path, f"{where}[{label}]"))
    return found


def check_reload_sources(pack: dict) -> None:
    """Reload-only source policy: no record and no listed value may rest on non-Reload sources alone."""
    edition = {s["id"]: s.get("edition") for s in pack["sources"]}
    bad = [ident for ident, ed in edition.items() if ed not in EDITIONS]
    if bad:
        raise BuildError(f"sources without a valid edition: {bad}")
    for _area, where, record in src_records(pack):
        if not any(edition.get(s) in RELOAD_EDITIONS for s in record["src"]):
            raise BuildError(f"{where}: src {record['src']} relies only on non-Reload sources "
                             f"(editions {[edition.get(s) for s in record['src']]})")
    for label, select, ids in VALUE_SOURCES:
        unknown = [s for s in ids if s not in edition]
        if unknown:
            raise BuildError(f"{label}: unknown sources {unknown}")
        if not any(edition[s] in RELOAD_EDITIONS for s in ids):
            raise BuildError(f"{label}: relies only on non-Reload sources {list(ids)}")
        records = select(pack)
        if not records:
            raise BuildError(f"{label}: no record holds this value")
        for record in records:
            missing = [s for s in ids if s not in record["src"]]
            if missing:
                raise BuildError(f"{label}: {record.get('id', '')} does not cite {missing}")

# Latin tokens allowed in player-facing Chinese text: stat levels, ranks, floors, karaoke, and the
# item names 莫乐蜜G and 急救组合M as the CN guide and 3DM write them. Class/exam answers are exempt (verbatim
# 3DM text such as MAY BLUES or MADRID, listed in REPORT §7).
ALLOWED_LATIN = ("Lv", "RANK", "MAX", "F", "OK", "G", "M", "R", "DVD")


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


# Sections whose nested record lists are also written one record per line (readable diffs).
NESTED_RECORD_KEYS = ("reminders",)


def compact(value) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def dump_pack(pack: dict) -> str:
    """Deterministic layout: one top-level key per line, one record per line for record lists (also the record lists
    inside `reminders`)."""
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
                sub_tail = "," if j < len(sub) - 1 else ""
                records = value[sub_key]
                if key in NESTED_RECORD_KEYS and isinstance(records, list) and records and isinstance(records[0], dict):
                    lines.append(f"{json.dumps(sub_key)}:[")
                    lines.extend(compact(r) + ("," if k < len(records) - 1 else "") for k, r in enumerate(records))
                    lines.append("]" + sub_tail)
                else:
                    lines.append(f"{json.dumps(sub_key)}:{compact(records)}{sub_tail}")
            lines.append("}" + tail)
        else:
            lines.append(f"{json.dumps(key)}:{compact(value)}{tail}")
    lines.append("}")
    return "\n".join(lines) + "\n"


KNOWN_GAPS = (
    "Dialogue: the historical 115-scene Chinese-guide approval is retained unchanged; later authorized completion "
    "adds branch-specific research and explicitly labelled editorial translations. Reference coverage does not "
    "prove every source's numeric affinity figures or reconstruct the player's previous romance-eligibility "
    "flags. The current Profile selects the intended relationship; detail sequences retain alternatives for "
    "earlier choices. Guide quotations and editorial translations are not verified Simplified Chinese game text. "
    "Original guide brackets are not verified option ordinals or affinity points; displayed musical notes are "
    "not planner raw points. The original Chinese guide and its authorised repost are one source lineage.",
    "Ordinary (non-rank-up) hangout gain +10 rests on one playthrough report (the Reload subsection of a multi-edition "
    "post, edition mixed) and its Reload-scoped evidence file (conf=estimate). The rank-up cue "
    "the game shows on the map is the reliable signal; the engine/UI should prefer it over the estimate.",
    "Multiplier rounding order (per answer vs per meeting) and the exam-bonus expiry are unknown; the pack says "
    "floor_per_meeting.",
    "Conditional free gains (correct class answers, class dozing choices, exam top-of-class Charm +4 and nurse "
    "visits) are not forecast; the player syncs stats. Mandatory summer school Academics +18 is forecast "
    "after its six daytime story slots, and selected Link Episodes, group study and dorm activities use their "
    "sourced rewards. Viewing reminders itself never awards points.",
    "当日提醒 not shipped yet (P2, same schema later): Mitsuru's reward on results days, shop restocks, URL notes "
    "going on sale, the exam first-place threshold, 'unlock becomes possible', Persona fusion names. Request tips "
    "do not track completed requests, #75 faculty visit counts, or the #77-81 outing chain; their conditions remain "
    "for the player to check. Faculty reminders use 3DM's first eight visit dates, not later catch-up dates. Dropped: "
    "the CN guide's 1/3 TV entry (not in the game table), cat "
    "rescues, shrine fortunes, 4/29 山间补习班, 9/1 tutoring items, the 'slot-free' label on requests #38 and #10.",
    "当日提醒 answers: 3DM's schedule text has nothing for 12/1-12/12 and nothing after 1/13. The class answers of "
    "12/7, 12/9, 12/11 and 1/18 rest on 游民星空's copy of the same guide (one author, 猎猫人老贾; text and option "
    "position) and BWIKI's zh text; nobody has seen them on SC game screens. Searched but not pinned: the "
    "女神异闻录吧 post BWIKI cites (tieba.baidu.com/p/8883837821 answers with a 百度安全验证 page) and 游民星空's "
    "answer table (handbook/202402/1704884, an image; it gives the same four answers). Exam answers 7/17 (MADRID) and "
    "12/16 (JUNE) come from the guide alone and are shown with conf=estimate; BWIKI's undated exam lists also write "
    "Madrid and june (not used). 上课犯困 days 12/12 and 1/15-1/28 rest on the CN guide alone (the 游民星空 copy also "
    "lists them; not used).",
    "当日提醒 missing people: that the limit evening still counts rests on the game's Start/Limit table, aqiu's "
    "tested walkthrough (rescues on limit days) and no limit + 1 night being enterable (see the tartarus 'rescue "
    "deadline' decision), not on an ATLUS statement or a replay by the owner; Game8 JP, RPG Site and a Steam "
    "thread agree with the windows but do not settle the limit evening. Plans do not depend on it when an earlier "
    "evening is open (one-day margin), and the copy calls it unchecked wherever it sends the player there. "
    "Floors are unknown. Rescues follow the plan's Tartarus evenings and the latest recorded one, not nights the "
    "player improvises.",
    "Restaurant special menus require a preceding discovery meal on an eligible evening and the sourced stat "
    "level. Imported saves without recorded discovery conservatively repeat that regular meal once. "
    "Hagakure's special dish has no verified Simplified Chinese item name, so the UI calls it 限定餐.",
    "Not modelled: weekend Wilduck set, burger challenge, shrine donations, "
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
    "Christmas Eve: 12/24 is free by default. The pack does not list who can invite the player that day; an accepted "
    "invitation is the player's input (Profile.christmasLink), and the engine then locks both slots with "
    "`christmas_date`. The summer-festival partner choice is not modelled (8/16 locked).",
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
    "`days[].autoStatGain`: optional map of stat id to guaranteed positive story points, applied once after "
    "that day's daytime slot completes, even when locked. It is not a morning/rollover or evening gain; "
    "missing means none. Only mandatory summer school 8/10–8/15 (+3 Academics daily) is currently populated. "
    "Observed starting stats already include earlier slots, so those slots must not be replayed as new gains.",
    "`lockReasons.christmas_date` (平安夜约会) is used by no day record: when the player has accepted a Christmas "
    "Eve invitation (Profile.christmasLink) the engine treats 12/24 daytime and evening as taken with this reason.",
    "`sources[].edition`: `reload`, `game_data_reload` or `cn_guide_reload` (Reload sources) or `reload+portable` / "
    "`mixed` (corroboration only); every record's `src` names at least one Reload source (§1.1).",
    "`moon` is the decorative phase (0 new … 4 full … 7); `fullMoon` marks the operation full moons "
    "(4/9 … 11/3) and the finale on 1/31; the estimated full moons 12/2 and 1/1 only show phase 4.",
    "`tartarus` (per day): an optional Tartarus visit is possible that evening (block open, not a closed night, "
    "evening not locked). `block` is the block index for the day, -1 outside the plannable blocks.",
    "`avail`: ordinary meeting days (already filtered by locks). `invite`: possible holiday SMS senders "
    "from game data, not guaranteed meetings. Automatic plans use only `avail`; manual `invite:` records "
    "require an actually received SMS, consume daytime, never directly rank up and do not credit unmodeled affinity. "
    "Both ordinary meetings and invitation candidates require an established, non-MAX, non-missing link.",
    "`threshold[r]` = points needed while at rank r-1 to reach rank r (0 means the next meeting ranks up). "
    "`eventGain[r]` = best-answer points of the rank-r scene, counted toward rank r+1.",
    "`links[].dialogue` is a legacy ordered guide sequence; duplicate answers represent separate questions and "
    "must be retained. Optional `dialogueHints[rank]` is authoritative display guidance, conf=single, with its "
    "own source IDs. Select `romance` or `friend` for the planned relationship, otherwise `common`. If an entry "
    "exists but neither the selected variant nor common exists, keep the unknown-answer fallback rather than "
    "reparsing another route's legacy text. `choices` omits arbitrary prompts; optional `shortChoices` preserves "
    "the same cardinality for the existing compact display. `sequence` retains full ordered prompts and conditions. "
    "`any` is an explicitly reviewed all-arbitrary scene; `avoid` only names an unsafe confession, not a friendship "
    "optimum. `noteZh` and optional `shortNoteZh` carry question conditions or optional romance prerequisites.",
    "`unlock`: `stat`/`link` requirements apply to every step. Steps run in order; `minGapDays` is measured from "
    "the previous step's day (0 = same day allowed). `consumesSlot=false` steps need a free slot of their kind on "
    "an allowed day but leave that slot usable for another action; they must still respect `minGapDays`. "
    "`days`: fixed = `fixedDays` (the ordinary meeting days of the link whose place the step uses, never its "
    "holiday invitations; Hierophant's opening only on school days), weekdays, any = any free slot; the builder no "
    "longer emits avail. The last step (`establishes`) sets rank 1. No steps = automatic: rank 1 once `earliestDay` "
    "is reached and the requirements hold (Magician on 4/22; Strength when Chariot reaches R2; story links by date).",
    "`missing`: the person is gone from `fromDay` to `toDay` unless the matching rescue (`tartarus.rescues`) "
    "happened earlier. Both ends count: a Tartarus evening from `fromDay` through `toDay` (the limit evening "
    "included) rescues them; without a rescue by the `toDay` evening the link is lost from `toDay + 1` for the rest "
    "of the game. Every `toDay` evening is a Tartarus evening and the next night is not.",
    "`storyRanks` (story links) list dated rank events; the loader turns them into `storyRankDay[r]` = first day "
    "with rank >= r (skipped ranks share the next event's day).",
    "`activities`: `slots`, `weekdays`, `dayTypes` (empty = all), `fromDay..toDay`, `cost` (yen), `gain` "
    "(hidden stat points), `requires` (stat Lv), optional `requiresActivity` (a previously completed activity id), "
    "optional `fixedDays` (exact calendar dates; no weekly approximation), `dormPartnerZh` (one of a partner's "
    "two three-completion characteristic categories), and `requiresPlantedCrop` (opt-in garden preparation). "
    "Optional `affinityLink` + `affinityPts` add raw established-link affinity within the next-rank gap, with no "
    "Persona/Charm multipliers, unopened-link banking or three-use restriction. "
    "and `once` (a one-off discovery or training). Restaurant discovery is an ordinary meal at its normal "
    "cost/gain on the eligible evenings. Jobs pay `pay` = [[minShiftNumber, minimum pay], …] for the "
    "N-th completed shift.",
    "`episodes` (Link Episodes, 羁绊剧情): opt-in tracks. `parts[n-1].days` = days on which episode n can be watched "
    "(slot kind = `evening`), in order, one slot each; `gain` = Academics/Charm/Courage points of that episode; no "
    "Social Link points. The loader turns the parts into `dayMask[day]` (bit n-1 = episode n allowed).",
    "`datingNote` (结缘网站的字条): from `fromDay`, an evening (club + dorm PC) that costs `cost` yen and adds `gain` "
    "points (no multipliers) to one established manual link at rank 1-9; it never ranks the link up by itself. "
    "`requires` = stat Lv to enter the club; `maxUses` = null means no limit.",
    "`reminders` (当日提醒, display only, never forecast): `items` are static lines for a `day`+`slot` (`untilDay` = "
    "deadline or null; `zh` card/today line, `shortZh` ≤ 16 chars for the small widget, `answerZh`/`option` for "
    "class and exam answers, `requires` stat Lv with `firstEligible` = shown on the first slot of that kind from "
    "`day` on where the forecast meets it), sorted by (day, slot, id). `doze.days` (上课犯困, school days; "
    "`academics`/`courage` = points for staying awake / closing the eyes), `nurse` "
    "(保健室 Courage after a Tartarus or full-moon night) and `rescueGroups` (missing people per (from, limit), both "
    "days included like `missing`; `rescueIds` = modelled rescues) are worded by the engine against the plan.",
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

    out.extend(render_sources(pack))

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
               "removed, `invite` = possible holiday SMS senders (game data; manual recording only). Story links rank by date.")
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
    automatic = [d for d in pack["days"] if d.get("autoStatGain")]
    out.append("Guaranteed daytime story gains (awarded after the listed daytime slot, not again in the evening):")
    out.append("")
    out.append("| date | daytime story | stat points |")
    out.append("|---|---|---|")
    for d in automatic:
        gain = ", ".join(f"{stat} +{points}" for stat, points in sorted(d["autoStatGain"].items()))
        out.append(f"| {d['md']} | {d['dayLock']} | {gain} |")
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
    out.append("Dated opportunities (ordinary story locks still apply):")
    out.append("")
    out.append("| id | exact dates | characteristic partner | crop preparation |")
    out.append("|---|---|---|---|")
    for a in pack["activities"]:
        if "fixedDays" in a:
            out.append(f"| {a['id']} | {', '.join(md_of(d) for d in a['fixedDays'])} | "
                       f"{a.get('dormPartnerZh', '—')} | {'yes' if a.get('requiresPlantedCrop') else 'no'} |")
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
    out.extend(render_reminders(pack))

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
    out.extend(render_unverified(pack))
    return "\n".join(out)


def render_reminders(pack: dict) -> list[str]:
    """REPORT §4.8: count, conf and sources per reminder kind."""
    rem = pack["reminders"]
    out = ["### 4.8 Reminders (当日提醒)", ""]
    combat = rem.get("combatTips", [])
    if combat:
        out.append("Independent combat catalogue: " + ", ".join(
            f"{kind} {sum(1 for item in combat if item['kind'] == kind)}"
            for kind in ("guardian", "full_moon", "monad", "story", "final", "optional"))
            + ". Every enemy and phase retains its own resistance row; v is double damage without knockdown "
              "weakness, and a bare * does not null elemental damage. Display only; no simulated battle or gains.")
        out.append("")
    out.append("Static items per kind; the engine adds 上课犯困, 保健室 and 失踪者 from the blocks below. `first`/`last` "
               "are item days.")
    out.append("")
    out.append("| kind | label | items | priority | conf | sources | first | last |")
    out.append("|---|---|---|---|---|---|---|---|")
    for kind in REMINDER_KINDS:
        items = [x for x in rem["items"] if x["kind"] == kind]
        if not items:
            continue
        confs = ", ".join(f"{c} {sum(1 for x in items if x['conf'] == c)}" for c in CONF_VALUES
                          if any(x["conf"] == c for x in items))
        prios = ", ".join(f"{p}×{sum(1 for x in items if x['priority'] == p)}" for p in (1, 2, 3)
                          if any(x["priority"] == p for x in items))
        labels = "／".join(sorted({x["labelZh"] for x in items}))
        paged = re.compile(r"(cn_3dm_p|cn_gamersky_p)(\d+)")
        sources = sorted({s for x in items for s in x["src"]},
                         key=lambda s: (m.group(1), int(m.group(2))) if (m := paged.fullmatch(s)) else (s, 0))
        pages = sorted(int(s[len("cn_3dm_p"):]) for s in sources if s.startswith("cn_3dm_p"))
        if len(pages) > 2 and pages == list(range(pages[0], pages[-1] + 1)):
            sources = [f"cn_3dm_p{pages[0]}…p{pages[-1]}"] + [s for s in sources if not s.startswith("cn_3dm_p")]
        out.append(f"| {kind} | {labels} | {len(items)} | {prios} | {confs} | {', '.join(sources)} | "
                   f"{md_of(items[0]['day'])} | {md_of(items[-1]['day'])} |")
    out.append("")
    doze = rem["doze"]
    out.append(f"- 上课犯困: {len(doze['days'])} school days, Academics +{doze['academics']} awake / Courage "
               f"+{doze['courage']} eyes closed, conf={doze['conf']}, src: {', '.join(doze['src'])}.")
    nurse = rem["nurse"]
    out.append(f"- 保健室: Courage +{nurse['courage']}, also after the story's Tartarus evenings "
               f"{', '.join(md_of(d) for d in nurse['storyTowerDays']) or 'none'}, conf={nurse['conf']}, "
               f"src: {', '.join(nurse['src'])}.")
    groups = rem["rescueGroups"]
    out.append(f"- 失踪者: {len(groups)} groups, {sum(g['count'] for g in groups)} people, modelled rescues "
               + ", ".join(f"{g['id']} {'/'.join(g['rescueIds'])}" for g in groups if g["rescueIds"])
               + "; a request needs " + (", ".join(f"{g['id']} (「{g['locksZh']}」)" for g in groups if g.get("locksZh"))
                                        or "none")
               + f"; src: {', '.join(groups[0]['src'])}.")
    out.append("- Withheld class answers: " + ("; ".join(f"{md} ({why})" for md, why in WITHHELD_CLASS.items()) or "none")
               + ".")
    out.append("")
    return out


def render_sources(pack: dict) -> list[str]:
    """REPORT §1: the Reload-only source policy, every pack source with its edition and use, and the snapshots."""
    out = ["## 1. Sources and the Reload-only source policy", "", "### 1.1 Reload-only source policy", ""]
    out.append("The pack describes Persona 3 Reload (Xrd777 main game) only. Every source names the edition it "
               "describes (`sources[].edition` in the pack, `edition` in `rules/sources/SOURCES.json`):")
    out.append("")
    for ident, text in EDITIONS.items():
        tag = " — Reload source" if ident in RELOAD_EDITIONS else " — not a Reload source"
        out.append(f"- `{ident}`{tag}: {text}.")
    out.append("")
    out.append("Rules, enforced by the builder (the build fails otherwise):")
    out.append("")
    out.append("1. Every record that carries `src` (days, links, unlock paths, story ranks, missing windows, "
               "activities, episodes, blocks, rescues, stats, policy, dating-site note) cites at least one Reload "
               "source. `RulesLoader` checks the same rule again when the app loads the pack.")
    out.append("2. The values below rest on the sources listed, all of which the holding record cites; at least one "
               "of them is a Reload source. A `mixed` or `reload+portable` source only corroborates.")
    out.append("3. Material that the evidence files tied to FES or Portable is never cited (§2 scope); multi-edition "
               "pages are used only for their Reload section and are marked `mixed`.")
    out.append("4. Snapshot URLs are checked against the snapshot itself: Steam threads point to "
               f"`{STEAM_THREAD_URL.format('<id>')}` and must be Reload General Discussions threads, the store page "
               f"to `{STEAM_STORE_URL}`, achievement pages to their language, the 3DM page to its pager entry, the 游民星空 "
               "pages to their canonical link and page index, the BWIKI page to its page name and revision.")
    out.append("")
    edition = {s["id"]: s["edition"] for s in pack["sources"]}
    out.append("| value | rests on | Reload sources among them |")
    out.append("|---|---|---|")
    for label, _select, ids in VALUE_SOURCES:
        reload = [s for s in ids if edition[s] in RELOAD_EDITIONS]
        out.append(f"| {label} | {', '.join(f'`{s}` ({edition[s]})' for s in ids)} | {', '.join(reload)} |")
    out.append("")

    cited: dict[str, dict[str, int]] = {}
    for area, _where, record in src_records(pack):
        for s in record["src"]:
            per = cited.setdefault(s, {})
            per[area] = per.get(area, 0) + 1
    out.append("### 1.2 Sources")
    out.append("")
    out.append("`cited by` counts the pack records whose `src` names the source; `—` means the source is used only "
               "through evidence files, the conflict table (§3) or a terminology check.")
    out.append("")
    out.append("| id | edition | kind | used for | cited by | file / url | sha256 |")
    out.append("|---|---|---|---|---|---|---|")
    for s in pack["sources"]:
        where = s["path"] or s["url"]
        uses = ", ".join(f"{area} {n}" for area, n in sorted(cited.get(s["id"], {}).items())) or "—"
        out.append(f"| `{s['id']}` | {s['edition']} | {s['kind']} | {s['note']} | {uses} | {where} | "
                   f"{(s['sha256'] or '—')[:16]} |")
    out.append("")

    manifest = load_manifest()
    by_path = {s["path"]: s["id"] for s in pack["sources"] if s["path"]}
    out.append("### 1.3 Pinned snapshots (`rules/sources/SOURCES.json`)")
    out.append("")
    out.append("| file | edition | url | pack source |")
    out.append("|---|---|---|---|")
    for entry in manifest["files"]:
        url = entry.get("url") or "—"
        if entry.get("derivedFrom"):
            url += f" (text of {entry['derivedFrom'].split(' ')[0]})" if entry.get("url") else entry["derivedFrom"]
        ident = by_path.get("rules/sources/" + entry["path"])
        out.append(f"| {entry['path']} | {entry['edition']} | {url} | {f'`{ident}`' if ident else 'not cited (kept for cross-checks)'} |")
    out.append("")
    return out


# Player-facing labels of the app itself (not game text) that appear in 「」 in pack notes.
APP_LABELS = ("只给目标社群带", "基本都带", "同步现状", "未选", "学力 MAX 就能解锁", "伙伴们")


def official_zh_text() -> str:
    """Every official zh-Hans text the repository holds: Steam achievements and store page, name tables."""
    parts = [(SOURCES_DIR / "steam_schinese" / name).read_text(encoding="utf-8") for name in ("ach_schinese.txt", "p3r_sc.txt")]
    for table in ("DatPlayerNameDataAsset", "DatPlayerFirstNameDataAsset", "DatPlayerLastNameDataAsset",
                  "DatRaceDataAsset", "DatSocialLinkDataAsset"):
        rows = read_tsv(table)
        col = rows[0].index("zh-Hans")
        parts.extend(row[col] for row in rows[1:] if len(row) > col)
    return "\n".join(parts)


def render_unverified(pack: dict) -> list[str]:
    """REPORT §7: player-facing Chinese that nobody has compared with Simplified Chinese game screens."""
    official = official_zh_text()
    guide_text = fix_names(json.dumps(json.loads(GUIDE_PATH.read_text(encoding="utf-8")), ensure_ascii=False))
    block_names = {b["zh"] for b in pack["tartarus"]["blocks"]}
    places: set[str] = set()
    quoted: set[str] = set()
    for where, value in player_texts(pack):
        if where.endswith("locationZh"):
            places.update(part for part in value.split(" · ") if part and not re.fullmatch(r"\d+ 楼", part))
        quoted.update(re.findall(r"「([^」]+)」", value))
    quoted -= set(APP_LABELS) | block_names
    fragments = [(link["id"], rank, frag) for link in pack["links"] for rank, frags in link["dialogue"].items() for frag in frags]
    approved = load_approved_dialogue()
    completion = load_dialogue_completion()
    approved_keys = {(scene["link"], str(scene["rank"])) for scene in approved["scenes"]}
    completion_keys = {(scene["link"], str(scene["rank"])) for scene in completion["scenes"]}
    legacy_fragments = [x for x in fragments if (x[0], x[1]) not in approved_keys | completion_keys]
    rewritten = [x for x in legacy_fragments if x[2] not in guide_text]
    dialogue_links = sorted({x[0] for x in fragments}, key=LINK_IDS.index)
    covered_ranks = sum(sum(str(rank) in link["dialogue"] for rank in range(2, 11))
                        for link in pack["links"] if link["kind"] == "manual")

    out = ["## 7. Not verified against SC game screens", ""]
    out.append("Checked against official zh-Hans text: arcana and person names and the SEES label (game tables "
               "DatRaceDataAsset, DatPlayerNameDataAsset, DatSocialLinkDataAsset) and the terms 社群, 主角 and "
               "休息日邀约 (Steam achievement and store text). Nobody has compared the Chinese below with Simplified "
               "Chinese game screens: it is walkthrough wording (CN guide `rules/sources/cn_guides/data.json`, 3DM) "
               "or a plain description, and it stays guide wording until someone checks it in the game.")
    out.append("")
    out.append(f"- **Dialogue options** — {len(fragments)} fragments for {len(dialogue_links)} links (`links[].dialogue`), "
               f"covering {covered_ranks}/171 manual R2-R10 scenes. 115 user-approved scenes (88 new, 27 repaired) "
               "come from `dialogue_guidance_approved.json`: pinned 3DM and 游民星空 guide wording, approved compact "
               "shortening and existing corrections. Ordered duplicate answers and arbitrary-prompt positions are "
               "retained. `dialogueHints` marks these single-source recommendations and separates common, friend "
               "and romance guidance; avoid-only friendship guidance is not an optimum answer. The two websites "
               "carry one guide lineage, not independent confirmation. The original approval remains unchanged; "
               f"{len(completion_keys)} later authorized scene overlays come from `dialogue_guidance_completion.json`, "
               "which preserves rendered source excerpts, per-choice wording provenance, route alternatives and "
               "complete option comparisons where equal-best arbitrary answers are claimed. These are rendered "
               "web excerpts, not raw-HTML captures or game-text extraction. Wording provenance remains in the source evidence; player details show answer conditions without "
               "translation/source disclaimers. Existing Chinese walkthrough text is not relabelled "
               "as official localization. Current Profile friend/romance selects the primary guidance; detail "
               "sequences retain earlier-eligibility alternatives, without new player state or numeric mechanics. "
               "Remaining legacy scenes come through `guide_dialogue_reference_map.json`, whose provenance paths "
               "are retired scratch locations. None of these guide evidence sets verifies SC game screens, option ordinals or "
               f"numeric affinity bindings. Of {len(legacy_fragments)} unchanged legacy fragments, "
               f"{len(rewritten)} differ from the derivative `cn_guides/data.json`: "
               + "; ".join(f"{link} R{rank} 「{frag}」" for link, rank, frag in rewritten) + ". The answers quoted "
               "in unlock how-to texts come from the same guide.")
    found = sorted(p for p in places if p in official)
    out.append("- **Venue and place names** (`locationZh`) — found in official text: " + ("、".join(found) or "none")
               + ". Not verified: " + "、".join(sorted(places - set(found))) + ".")
    found_q = sorted(q for q in quoted if q in official)
    out.append("- **Item, menu, game, Persona and answer names in 「」** (how-to texts, notes, rewards) — found in "
               "official text: " + ("、".join(found_q) or "none") + ". Not verified: "
               + "、".join(f"「{q}」" for q in sorted(quoted - set(found_q))) + ".")
    out.append("- **Tartarus block names** — from the 3DM guide only (`cn_3dm`, single source): "
               + "、".join(sorted(block_names, key=lambda z: [b["zh"] for b in pack["tartarus"]["blocks"]].index(z)))
               + ". The blocks' conf=game_data covers their dates and floors, not these names.")
    out.append("- **Plain descriptions instead of official names** — 饰品店 (Be Blue V), 电影院 (Screen Shot), "
               "图书室, 卡拉OK; stat levels are shown as Lv.N.")
    out.extend(render_reminder_lines(pack))
    out.append("")
    return out


def render_reminder_lines(pack: dict) -> list[str]:
    """REPORT §7, 当日提醒: the answers as 3DM writes them and every editorial line the builder writes."""
    items = pack["reminders"]["items"]
    answers = [x for x in items if x["answerZh"]]
    latin = sorted({x["answerZh"] for x in answers if re.search(r"[A-Za-z]", x["answerZh"])})
    copied = [x for x in answers if any(s.startswith("cn_gamersky_p") for s in x["src"])]
    out = [f"- **当日提醒 answers** — {sum(1 for x in answers if x['kind'] == 'class') - len(copied)} class and "
           f"{sum(1 for x in answers if x['kind'] == 'exam')} exam answers are 3DM text (cn_3dm_p2…p11), "
           f"{len(copied)} class answers are text of 游民星空's copy of the same guide that BWIKI writes alike ("
           + "、".join(f"{md_of(x['day'])} 「{x['answerZh']}」" for x in copied) + "), dated and positioned by aqiu; "
           "nobody has seen them on SC game screens. Latin as 3DM writes it (exempt from the Latin check): "
           + "、".join(f"「{a}」" for a in latin) + ". Typos fixed to the CN guide's spelling of the same day: "
           + "、".join(f"{md} 「{wrong}」→「{right}」" for (md, wrong), right in ZH_TYPO_FIXES.items())
           + "; to BWIKI's: " + "、".join(f"{md} 「{wrong}」→「{right}」" for (md, wrong), right in GAMERSKY_TYPO_FIXES.items())
           + "; 12/14 「数字0”」 loses the stray quote mark. Item names in 电视购物 lines are 3DM's or the CN guide's."]
    templates = {
        "class": "课堂提问：选「{answer}」（第 {n} 项） / 课堂：{answer}（第 {n} 项） / 上课被点名提问时选「{answer}」（第 {n} 项），"
                 "答对了魅力 +2。",
        "exam": "考试：选「{answer}」 / 考试：{answer} / 这天考试有一道题要自己选，选「{answer}」。",
        "tv": "电视购物：{item}、{item} ×{n}，{price} 日元 / 电视购物：有新商品，{price} 日元 / 电视购物 {price} 日元 / " + TV_DETAIL
              + "（+ {item}是伊莉莎白的第 {n} 号委托要的东西。 / {item}在伊莉莎白的第 {n} 号委托里很有用。）",
    }
    lines: list[str] = []
    for item in items:
        if item["kind"] in templates:
            continue
        for text in (item["labelZh"], item["zh"], item["shortZh"], item["detailZh"]):
            if text and text not in lines:
                lines.append(text)
    deadline = next(x for x in items if x["id"].endswith(".deadline"))
    # The deadline whose request needs a rescue first (#97) says so after its date, as the pack writes it.
    gated = []
    for x in items:
        m = re.match(r"这个委托只能做到 (\d+月\d+日)(，而且[^；]+)；", x["detailZh"] or "") if x["id"].endswith(".deadline") else None
        n = re.search(r"第 ([\d、]+) 号委托", x["zh"]) if m else None
        if m and n:
            gated.append(f"#{n.group(1)} 在「{m.group(1)}」后加「{m.group(2)}」")
    lines.append(f"{deadline['zh']} / {deadline['shortZh']} / {deadline['detailZh']}（模板；{'；'.join(gated) or '无附加条件'}）")
    out.append("- **当日提醒 editorial lines** (builder copy, no game text): "
               + "；".join(f"{kind}: {text}" for kind, text in templates.items()) + "；"
               + "；".join(text.replace("\n", " / ") for text in lines)
               + ". The engine's lines (上课犯困, 保健室, 失踪者, the TV cash sentence) are in DESIGN §17.1.")
    return out


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

