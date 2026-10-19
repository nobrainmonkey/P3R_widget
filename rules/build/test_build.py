"""Self-check for the rule-pack builder (stdlib unittest).

Run from the repository root:
    python3 -m unittest rules/build/test_build.py
"""

from __future__ import annotations

import importlib.util
import json
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load_builder():
    spec = importlib.util.spec_from_file_location("build_p3r_rules", HERE / "build_p3r_rules.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


builder = load_builder()


class BuildTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.pack, cls.ledger = builder.build_pack()
        cls.text = builder.dump_pack(cls.pack)

    def test_rebuild_is_byte_identical(self) -> None:
        pack, ledger = builder.build_pack()
        self.assertEqual(self.text, builder.dump_pack(pack))
        self.assertEqual(builder.render_report(self.pack, self.ledger, self.text),
                         builder.render_report(pack, ledger, builder.dump_pack(pack)))

    def test_committed_outputs_are_current(self) -> None:
        self.assertEqual(self.text, builder.PACK_PATH.read_text(encoding="utf-8"),
                         "assets/p3r/p3r_rules.json is stale: run python3 rules/build/build_p3r_rules.py")
        report = builder.render_report(self.pack, self.ledger, self.text)
        self.assertEqual(report, builder.REPORT_PATH.read_text(encoding="utf-8"), "REPORT.md is stale")

    def test_output_is_valid_sorted_json_under_one_megabyte(self) -> None:
        self.assertLess(len(self.text.encode("utf-8")), builder.MAX_PACK_BYTES)
        parsed = json.loads(self.text)
        self.assertEqual(parsed["schema"], "p3r.rules.v3")
        self.assertEqual(list(parsed), sorted(parsed))
        for record in parsed["days"][:5] + parsed["links"][:3] + parsed["activities"][:3]:
            self.assertEqual(list(record), sorted(record))

    def test_core_facts(self) -> None:
        links = {x["id"]: x for x in self.pack["links"]}
        self.assertEqual([x["id"] for x in self.pack["links"]], list(builder.LINK_IDS))
        self.assertEqual(links["lovers"]["threshold"][6], 22)
        self.assertEqual(links["temperance"]["personZh"], "贝培")
        self.assertEqual([d["d"] for d in self.pack["days"]], list(range(builder.FIRST_DAY, builder.LAST_DAY + 1)))
        devil_weekdays = {builder.weekday_of(d) for d in links["devil"]["avail"]}
        self.assertEqual(devil_weekdays, {2, 6})

    def test_review_fixes(self) -> None:
        days = {d["d"]: d for d in self.pack["days"]}
        for md in ("04-29", "07-10", "09-06", "11-05"):
            self.assertFalse(days[builder.day_of_md(md)]["tartarus"], md)
        self.assertTrue(days[builder.day_of_md("06-23")]["tartarus"])
        links = {x["id"]: x for x in self.pack["links"]}
        open_step = links["hierophant"]["unlock"]["steps"][0]
        self.assertTrue(all(days[d]["type"] == "school" for d in open_step["fixedDays"]))
        self.assertEqual(links["star"]["locationZh"], "严户台商店街 · 1 楼")
        self.assertTrue(all(x["locationZh"] for x in self.pack["links"]))
        self.assertIn("第一名", links["empress"]["unlock"]["noteZh"])

    def test_episodes_and_dating_note(self) -> None:
        tracks = {t["id"]: t for t in self.pack["episodes"]}
        self.assertEqual(list(tracks), ["junpei", "akihiko", "koromaru", "ken", "shinjiro"])
        self.assertEqual(tracks["junpei"]["titleZh"], "羁绊剧情 · 伊织顺平")
        self.assertEqual(tracks["shinjiro"]["count"], 4)
        self.assertTrue(tracks["ken"]["evening"])
        note = self.pack["datingNote"]
        self.assertEqual((note["fromDay"], note["cost"], note["gain"], note["maxUses"]),
                         (builder.day_of_md("11-11"), 3000, 10, None))

    def test_player_text_follows_the_glossary(self) -> None:
        for where, value in builder.player_texts(self.pack):
            plain = value
            for allowed in builder.GLOSSARY_ALLOWED:
                plain = plain.replace(allowed, "")
            for word in builder.GLOSSARY_AVOID:
                self.assertNotIn(word, plain, where)

    # ---------------------------------------------------------------- Reload-only source policy

    def test_every_source_has_an_edition_and_every_record_a_reload_source(self) -> None:
        editions = {s["id"]: s["edition"] for s in self.pack["sources"]}
        self.assertEqual(set(editions.values()) - set(builder.EDITIONS), set())
        records = builder.src_records(self.pack)
        self.assertGreater(len(records), 300)
        for _area, where, record in records:
            self.assertTrue(any(editions[s] in builder.RELOAD_EDITIONS for s in record["src"]), (where, record["src"]))
        self.assertEqual(editions["gd_community_event"], "game_data_reload")
        self.assertEqual(editions["cn_guide"], "cn_guide_reload")
        self.assertEqual(editions["cn_3dm"], "cn_guide_reload")
        self.assertEqual(editions["steam_sc_achievements"], "reload")
        self.assertEqual(editions["lpbeach"], "mixed")
        for ident in ("megatenwiki_chagall_cafe", "megatenwiki_be_blue_v", "megatenwiki_port_island_station"):
            self.assertEqual(editions[ident], "mixed")
        manifest = builder.load_manifest()
        self.assertEqual(list(manifest["editions"]), list(builder.EDITIONS))
        for entry in manifest["files"]:
            self.assertIn(entry["edition"], builder.EDITIONS, entry["path"])

    def test_a_record_citing_only_non_reload_sources_fails_the_build(self) -> None:
        pack = json.loads(self.text)
        job = next(a for a in pack["activities"] if a["id"] == "job_accessory")
        job["src"] = ["megatenwiki_be_blue_v", "wiki469"]
        with self.assertRaisesRegex(builder.BuildError, r"activities\[job_accessory\]: .*relies only on non-Reload"):
            builder.check_reload_sources(pack)

        pack = json.loads(self.text)
        pack["links"][4]["unlock"]["src"] = ["lpbeach"]
        with self.assertRaisesRegex(builder.BuildError, r"links\[emperor\]\.unlock: .*relies only on non-Reload sources"):
            builder.check_reload_sources(pack)

        pack = json.loads(self.text)
        next(s for s in pack["sources"] if s["id"] == "gd_hero_parameter")["edition"] = "portable"
        with self.assertRaisesRegex(builder.BuildError, "without a valid edition"):
            builder.check_reload_sources(pack)

    def test_value_sources_must_be_cited_and_include_a_reload_source(self) -> None:
        pack = json.loads(self.text)
        pack["policy"]["src"].remove("wiki455")
        with self.assertRaisesRegex(builder.BuildError, r"policy.ordinaryGain .* does not cite \['wiki455'\]"):
            builder.check_reload_sources(pack)

        pack = json.loads(self.text)
        for source in pack["sources"]:
            if source["id"] in ("gd_arbeit_help", "ev_jobs"):
                source["edition"] = "mixed"
        with self.assertRaisesRegex(builder.BuildError, "relies only on non-Reload sources"):
            builder.check_reload_sources(pack)

    def test_megatenwiki_job_sources_name_each_page(self) -> None:
        sources = {s["id"]: s for s in self.pack["sources"]}
        self.assertNotIn("megatenwiki_jobs", sources)
        pages = {
            "job_chagall": ("megatenwiki_chagall_cafe", "title=Chagall_Cafe&oldid=423201"),
            "job_accessory": ("megatenwiki_be_blue_v", "title=Be_Blue_V&oldid=393238"),
            "job_cinema": ("megatenwiki_port_island_station", "title=Port_Island_Station&oldid=410086"),
        }
        acts = {a["id"]: a for a in self.pack["activities"]}
        for job, (ident, page) in pages.items():
            self.assertTrue(sources[ident]["url"].endswith(page), sources[ident]["url"])
            self.assertIn(ident, acts[job]["src"])
            others = [x for x, _ in pages.values() if x != ident]
            self.assertFalse(set(others) & set(acts[job]["src"]), job)

    def test_steam_and_3dm_urls_match_the_snapshots(self) -> None:
        by_path = {e["path"]: e for e in builder.load_manifest()["files"]}
        for thread in ("4206993388794169488", "4206993639701168068", "4206994023684392768"):
            self.assertEqual(by_path[f"steam_schinese/d_{thread}.html"]["url"],
                             f"https://steamcommunity.com/app/2161700/discussions/0/{thread}/")
        for name in ("p3r_sc.html", "p3r_sc.txt"):
            self.assertEqual(by_path["steam_schinese/" + name]["url"], "https://store.steampowered.com/app/2161700/")
        self.assertEqual(by_path["steam_schinese/ach_japanese.html"]["url"],
                         "https://steamcommunity.com/stats/2161700/achievements/?l=japanese")
        self.assertEqual(by_path["cn_guides/3dm_p42.html"]["url"], "https://www.3dmgame.com/gl/3928716_42.html")
        sources = {s["id"]: s for s in self.pack["sources"]}
        self.assertEqual(sources["steam_thread_hierophant"]["url"],
                         "https://steamcommunity.com/app/2161700/discussions/0/4206993639701168068/")
        self.assertEqual(sources["steam_thread_rankup"]["url"],
                         "https://steamcommunity.com/app/2161700/discussions/0/4206994023684392768/")
        self.assertEqual(sources["steam_sc_store"]["url"], "https://store.steampowered.com/app/2161700/")

    def test_manifest_rejects_wrong_urls_and_missing_editions(self) -> None:
        def broken(path: str, **changes) -> dict:
            manifest = builder.load_manifest()
            entry = next(e for e in manifest["files"] if e["path"] == path)
            entry.update(changes)
            for key in [k for k, v in changes.items() if v is None]:
                del entry[key]
            return manifest

        thread = "steam_schinese/d_4206993639701168068.html"
        with self.assertRaisesRegex(builder.BuildError, "discussions/0/4206993639701168068/"):
            builder.check_manifest(broken(thread, url="https://steamcommunity.com/stats/2161700/achievements/?l=schinese"))
        with self.assertRaisesRegex(builder.BuildError, "store.steampowered.com/app/2161700/"):
            builder.check_manifest(broken("steam_schinese/p3r_sc.html", url="https://steamcommunity.com/stats/2161700/achievements/"))
        with self.assertRaisesRegex(builder.BuildError, "edition"):
            builder.check_manifest(broken(thread, edition=None))
        with self.assertRaisesRegex(builder.BuildError, "edition"):
            builder.check_manifest(broken("aqiu_gamedata/ActivityDataAsset.tsv", edition="reload+portable"))
        with self.assertRaisesRegex(builder.BuildError, "3928716_42.html"):
            builder.check_manifest(broken("cn_guides/3dm_p42.html", url="https://www.3dmgame.com/gl/3928716.html"))

    # ---------------------------------------------------------------- official terms and the Christmas lock

    def test_pack_uses_official_terms(self) -> None:
        for word in ("主人公", "假日邀约", "节日邀约"):
            self.assertNotIn(word, self.text)
        self.assertIn("休息日邀约", self.text)
        tracks = {t["id"]: t for t in self.pack["episodes"]}
        self.assertIn("主角的能力", tracks["akihiko"]["rewardZh"])
        self.assertIn("主角的能力", tracks["ken"]["rewardZh"])
        pack = json.loads(self.text)
        pack["episodes"][1]["rewardZh"] = pack["episodes"][1]["rewardZh"].replace("主角", "主人公")
        with self.assertRaisesRegex(builder.BuildError, "主人公"):
            builder.validate_pack(pack)

    def test_christmas_date_lock_reason(self) -> None:
        self.assertEqual(self.pack["lockReasons"]["christmas_date"], {"zh": "平安夜约会"})
        eve = next(d for d in self.pack["days"] if d["md"] == "12-24")
        self.assertIsNone(eve["dayLock"])
        self.assertIsNone(eve["eveLock"])
        self.assertIn("平安夜约会", eve["noteZh"])
        used = {d[k] for d in self.pack["days"] for k in ("dayLock", "eveLock")}
        self.assertNotIn("christmas_date", used)
        self.assertTrue(any(n.startswith("【平安夜】") and "平安夜约会" in n for n in self.pack["notes"]))

    def test_report_has_the_source_policy_and_unverified_lists(self) -> None:
        report = builder.render_report(self.pack, self.ledger, self.text)
        self.assertIn("### 1.1 Reload-only source policy", report)
        self.assertIn("## 7. Not verified against SC game screens", report)
        for source in self.pack["sources"]:
            self.assertIn(f"| `{source['id']}` | {source['edition']} |", report)
        section = report.split("## 7. Not verified against SC game screens", 1)[1]
        for term in ("Dialogue options", "夏卡尔咖啡店", "「结缘网站的字条」", "世俗之庭 台伯勒", "134 of 140"):
            self.assertIn(term, section)

    def test_calendar_helpers(self) -> None:
        self.assertEqual(builder.day_of(4, 7), 6)
        self.assertEqual(builder.day_of(1, 31), 305)
        self.assertEqual(builder.md_of(305), "01-31")
        self.assertEqual(builder.weekday_of(0), 3)


if __name__ == "__main__":
    unittest.main()
