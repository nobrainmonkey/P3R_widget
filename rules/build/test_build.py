"""Self-check for the rule-pack builder (stdlib unittest).

Run from the repository root:
    python3 -m unittest rules/build/test_build.py
"""

from __future__ import annotations

import importlib.util
import json
import unittest
from pathlib import Path
from unittest import mock

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
        for term in ("Dialogue options", "夏卡尔咖啡店", "「结缘网站的字条」", "世俗之庭 台伯勒",
                     "163/171 manual R2-R10 scenes", "115 user-approved scenes (88 new, 27 repaired)",
                     "one guide lineage", "avoid-only friendship guidance is not an optimum answer"):
            self.assertIn(term, section)
        self.assertNotIn("editorial rewrites", section)

    # ---------------------------------------------------------------- 当日提醒 (reminders)

    def reminders(self, kind: str) -> list[dict]:
        return [x for x in self.pack["reminders"]["items"] if x["kind"] == kind]

    def reminder(self, ident: str) -> dict:
        return next(x for x in self.pack["reminders"]["items"] if x["id"] == ident)

    def test_reminder_counts_per_kind(self) -> None:
        rem = self.pack["reminders"]
        # 36 aqiu class dates; 3DM has no zh line for 12/7, 12/9, 12/11 (its December text starts on 12.13) and
        # 1/18 (its text ends on 1.13): those four come from 游民星空's copy of the guide, so none is withheld.
        self.assertEqual(builder.WITHHELD_CLASS, {})
        self.assertEqual(len(self.reminders("class")), 36)
        self.assertEqual(sum(1 for x in self.reminders("class") if x["conf"] == "verified"), 35)  # 4/27 「A」: no position
        exams = self.reminders("exam")
        self.assertEqual(len(exams), 17)
        self.assertEqual(sorted(x["id"] for x in exams if x["conf"] == "estimate"), ["exam.07-17", "exam.12-16"])
        self.assertEqual(len(self.reminders("tv")), 32)
        requests = self.reminders("request")
        self.assertEqual(sum(1 for x in requests if x["id"].count(".") == 1), 9)
        self.assertEqual(sum(1 for x in requests if x["id"].endswith(".deadline")), 8)
        self.assertEqual(len(rem["rescueGroups"]), 13)
        self.assertEqual(sum(g["count"] for g in rem["rescueGroups"]), 20)
        self.assertTrue(30 <= len(rem["doze"]["days"]) <= 36, len(rem["doze"]["days"]))
        self.assertEqual((rem["doze"]["academics"], rem["doze"]["courage"], rem["nurse"]["courage"]), (2, 2, 2))
        self.assertEqual(len(self.reminders("fragment")), 5)
        self.assertEqual(len(self.reminders("trip")), 3)
        self.assertEqual(len(self.reminders("feature")), 1)

    def test_reminder_answers(self) -> None:
        ids = {x["id"] for x in self.pack["reminders"]["items"]}
        self.assertNotIn("tv.01-03", ids)
        for md in builder.WITHHELD_CLASS:
            self.assertNotIn(f"class.{md}", ids)
        cases = (("class.07-08", "马", 2), ("class.10-22", "朱诺", 3), ("exam.05-19", "五月病", 0), ("exam.10-14", "摩西", 0))
        for ident, answer, option in cases:
            x = self.reminder(ident)
            self.assertEqual((x["answerZh"], x["option"]), (answer, option), ident)
            self.assertTrue(any(s.startswith("cn_3dm_p") for s in x["src"]), ident)
            self.assertNotIn("cn_guide", x["src"], ident)
        self.assertEqual(self.reminder("class.07-08")["zh"], "课堂提问：选「马」（第 2 项）")
        self.assertEqual(self.reminder("class.07-08")["shortZh"], "课堂：马（第 2 项）")
        self.assertEqual(self.reminder("exam.05-19")["zh"], "考试：选「五月病」")
        self.assertEqual(self.reminder("class.11-07")["answerZh"], "奥义书")  # 3DM typo 肉义书, dated 11.6 by 3DM

    # ---------------------------------------------------------------- the class dates 3DM has no text for

    def test_class_answers_from_the_gamersky_copy(self) -> None:
        # 游民星空's copy of the guide: answer and position (= aqiu's Choice N), BWIKI's zh text on the same date.
        cases = (("12-07", "臭氧层", 1, "cn_gamersky_p9"), ("12-09", "鬼道", 2, "cn_gamersky_p9"),
                 ("12-11", "孤注一掷", 2, "cn_gamersky_p9"), ("01-18", "喀耳刻", 2, "cn_gamersky_p10"))
        for md, answer, option, page in cases:
            x = self.reminder(f"class.{md}")
            self.assertEqual((x["answerZh"], x["option"], x["conf"]), (answer, option, "verified"), md)
            self.assertEqual(x["src"], ["aqiu_social_events", page, "cn_bwiki_class"], md)
            self.assertEqual(x["zh"], f"课堂提问：选「{answer}」（第 {option} 项）", md)
            self.assertEqual(x["shortZh"], f"课堂：{answer}（第 {option} 项）", md)
            self.assertEqual((x["day"], x["slot"], x["gainZh"], x["priority"]), (builder.day_of_md(md), "day", "魅力 +2", 3))
        # 12/11: the copy types an em dash for 一; the CN guide's English-localisation answer stays out.
        self.assertIn("孤注—掷", (builder.SOURCES_DIR / "cn_guides" / "gamersky_p9.html").read_text(encoding="utf-8"))
        self.assertNotIn("Lives", self.text)
        conflict = next(c for c in self.ledger.conflicts if c["id"] == "answer.12-11")
        self.assertEqual(conflict["chosen"], "游民星空 「孤注一掷」")
        self.assertEqual(conflict["sources"], ["cn_gamersky_p9", "cn_bwiki_class", "cn_guide"])
        sources = {s["id"]: s for s in self.pack["sources"]}
        for ident in ("cn_gamersky_p9", "cn_gamersky_p10", "cn_bwiki_class"):
            self.assertEqual(sources[ident]["edition"], "cn_guide_reload", ident)
        report = builder.render_report(self.pack, self.ledger, self.text)
        self.assertIn("- Withheld class answers: none.", report)
        self.assertIn("On all 32 class dates that both copies answer, 游民星空's answer and position equal 3DM's", report)
        self.assertIn("tieba.baidu.com/p/8883837821", report)

    def test_gamersky_parser(self) -> None:
        gsky = builder.ScheduleGamersky()
        # Every aqiu class date has a line; the June page repeats 6/11-6/17 with the same answers.
        classes = builder.aqiu_choices(builder.AqiuPage(builder.SOURCES_DIR / "aqiu_pages" / "social-events.html"),
                                       "class-answers")
        self.assertEqual({d for d in gsky.lines if gsky.class_answer(d)}, set(classes))
        self.assertEqual(gsky.class_answer(builder.day_of_md("06-15")), ("慧眼独具", 1))
        self.assertEqual(sum(1 for line in gsky.lines[builder.day_of_md("06-15")] if line.startswith("上课问答：")), 2)
        # Half-width brackets, a missing position, the shifted 11.6 row (CN_3DM_ROW_FIXES), the June page's 7月1日.
        self.assertEqual(gsky.class_answer(builder.day_of_md("04-08")), ("鲤鱼旗", 3))
        self.assertEqual(gsky.class_answer(builder.day_of_md("05-15")), ("MAY BLUES", 2))
        self.assertEqual(gsky.class_answer(builder.day_of_md("04-27")), ("A", 0))
        self.assertIsNone(gsky.class_answer(builder.day_of_md("11-06")))
        self.assertEqual(gsky.class_answer(builder.day_of_md("11-07")), ("奥义书", 1))
        self.assertEqual(gsky.source(builder.day_of_md("07-01")), "cn_gamersky_p3")
        self.assertEqual(max(gsky.lines), builder.LAST_DAY)  # 3月4日 (the epilogue) is skipped
        bwiki = builder.bwiki_class_answers()
        self.assertEqual(bwiki[builder.day_of_md("10-30")], "β-淀粉酶")
        self.assertEqual(bwiki[builder.day_of_md("12-11")], "孤注一掷")
        self.assertEqual(len(bwiki), 39)

    def test_gamersky_and_bwiki_pages_are_pinned(self) -> None:
        by_path = {e["path"]: e for e in builder.load_manifest()["files"]}
        for n in builder.GAMERSKY_PAGES:
            entry = by_path[f"cn_guides/gamersky_p{n}.html"]
            suffix = "" if n == 1 else f"_{n}"
            self.assertEqual(entry["url"], f"https://www.gamersky.com/handbook/202402/1706261{suffix}.shtml")
            self.assertEqual(entry["edition"], "cn_guide_reload")
        self.assertEqual(by_path["cn_guides/bwiki_p3r_class.html"]["url"], builder.BWIKI_CLASS_URL)
        manifest = builder.load_manifest()
        wrong = next(e for e in manifest["files"] if e["path"] == "cn_guides/gamersky_p9.html")
        wrong["url"] = builder.gamersky_url(8)
        with self.assertRaisesRegex(builder.BuildError, "expected the 游民星空 page"):
            builder.check_manifest(manifest)
        manifest = builder.load_manifest()
        wrong = next(e for e in manifest["files"] if e["path"] == "cn_guides/bwiki_p3r_class.html")
        wrong["url"] = "https://wiki.biligame.com/persona/P5R/%E8%AF%BE%E5%A0%82"
        with self.assertRaisesRegex(builder.BuildError, "expected the BWIKI page"):
            builder.check_manifest(manifest)

    def test_a_gamersky_option_that_disagrees_with_aqiu_fails_the_build(self) -> None:
        real = builder.aqiu_choices

        def shifted(page, section):
            choices = real(page, section)
            if section == "class-answers":
                choices[builder.day_of_md("12-11")] = 3
            return choices

        with mock.patch.object(builder, "aqiu_choices", shifted):
            with self.assertRaisesRegex(builder.BuildError, r"class 12-11: 游民星空 option 2 != aqiu Choice 3"):
                builder.build_pack()

    def test_gamersky_must_agree_with_3dm_and_bwiki(self) -> None:
        real = builder.ScheduleGamersky.class_answer

        def moved(self, d):
            found = real(self, d)
            return (found[0], 3) if d == builder.day_of_md("07-08") else found

        with mock.patch.object(builder.ScheduleGamersky, "class_answer", moved):
            with self.assertRaisesRegex(builder.BuildError, r"class 07-08: 游民星空 「马」 option 3 != 3DM 「马」 option 2"):
                builder.build_pack()
        real_bwiki = builder.bwiki_class_answers

        def other_text():
            answers = real_bwiki()
            answers[builder.day_of_md("12-09")] = "鬼神"
            return answers

        with mock.patch.object(builder, "bwiki_class_answers", other_text):
            with self.assertRaisesRegex(builder.BuildError, r"class 12-09: 游民星空 「鬼道」 is not BWIKI's 「鬼神」"):
                builder.build_pack()
        with mock.patch.object(builder, "GAMERSKY_TYPO_FIXES", {}):
            with self.assertRaisesRegex(builder.BuildError, r"class 12-11: 游民星空 「孤注—掷」 is not BWIKI's 「孤注一掷」"):
                builder.build_pack()
        with mock.patch.object(builder, "BWIKI_DIVERGENT", {}):
            with self.assertRaisesRegex(builder.BuildError, "BWIKI class answers unlike the shipped text changed"):
                builder.build_pack()
        with mock.patch.object(builder, "WITHHELD_CLASS", {"12-07": "test"}):
            with self.assertRaisesRegex(builder.BuildError, "class 12-07 is withheld but 游民星空 has a line for it"):
                builder.build_pack()

    def test_request_trip_and_nurse_timing(self) -> None:
        # Request #44 is accepted before the trip: 3DM's 7/19 evening, the day before the Yakushima beach.
        accept = self.reminder("trip.07-19.accept")
        self.assertEqual((accept["day"], accept["slot"], accept["priority"]), (builder.day_of_md("07-19"), "eve", 3))
        self.assertEqual(accept["zh"], "明天去屋久岛：出发前先在天鹅绒房间接下伊莉莎白的第 44 号委托")
        self.assertIn("cn_3dm_p5", accept["src"])
        self.assertLess(accept["day"], self.reminder("trip.07-20")["day"])
        # New requests: 5/10 and 1/1 in the evening (3DM's evening column), the other batches after school.
        batches = {x["id"]: x["slot"] for x in self.reminders("request") if not x["id"].endswith(".deadline")}
        self.assertEqual({i for i, slot in batches.items() if slot == "eve"}, {"request.05-10", "request.01-01"})
        # #97 is shown from 12/22: its rescue condition is in the past by then, and the rescue group warns in June.
        deadline = self.reminder("request.12-25.deadline")["detailZh"]
        self.assertIn("只有在 7月6日当天或更早救出了 6月18日失踪的学生才能完成", deadline)
        self.assertIn("只能做到 12月25日", deadline)
        groups = {g["id"]: g for g in self.pack["reminders"]["rescueGroups"]}
        self.assertEqual(groups["rescue.06-18"]["locksZh"], "救不出来的话，伊莉莎白的第 97 号委托就做不了。")
        self.assertEqual([g for g in groups if "locksZh" in groups[g]], ["rescue.06-18"])
        # The story's own Tartarus evening (4/20) is followed by the nurse's office on 4/21 (3DM, aqiu).
        self.assertEqual(self.pack["reminders"]["nurse"]["storyTowerDays"], [builder.day_of_md("04-20")])
        pack = json.loads(self.text)
        pack["reminders"]["nurse"]["storyTowerDays"] = [builder.day_of_md("04-21")]
        with self.assertRaisesRegex(builder.BuildError, "storyTowerDays"):
            builder.validate_pack(pack)
        # Wording: 伊莉莎白的第 N 号委托 everywhere, the club fragment by the stage, the oden bought for 5,000 日元.
        for x in self.pack["reminders"]["items"]:
            if "号委托" in x["zh"]:
                self.assertIn("伊莉莎白的第", x["zh"], x["id"])
            self.assertNotIn("伊莉莎白第", x["detailZh"] or "", x["id"])
            # The widget row has no chip: a short form never names a request by its number alone.
            self.assertNotRegex(x["shortZh"], r"第 \d+ 号委托", x["id"])
        self.assertEqual(accept["shortZh"], "出发前接伊莉莎白的委托")
        self.assertEqual(self.reminder("trip.07-20")["shortZh"], "海边捡东西（伊莉莎白的委托）")
        self.assertEqual(self.reminder("request.06-06.deadline")["shortZh"], "伊莉莎白的委托 6月6日截止")
        self.assertEqual(self.reminder("request.11-30.deadline")["shortZh"], "伊莉莎白的委托 11月30日截止")
        self.assertIn("舞台旁", self.reminder("fragment.05-25.club")["zh"])
        self.assertIn("再花 5,000 日元向她买关东煮", self.reminder("trip.11-17")["detailZh"])
        self.assertIn("把饮料交给一位女学生", self.reminder("trip.11-17")["detailZh"])
        # An app reminder row's chip says the kind: the line after it does not say it again.
        self.assertEqual(self.reminder("tv.07-05")["zh"], "电视购物：有新商品，3,980 日元")
        report = (builder.REPORT_PATH).read_text(encoding="utf-8")
        self.assertIn("{item}是伊莉莎白的第 {n} 号委托要的东西", report)
        self.assertIn("#97 在「12月25日」后加「，而且只有在 7月6日当天或更早救出了 6月18日失踪的学生才能完成」", report)

    def test_missing_person_limit_evening_counts(self) -> None:
        # Game data and aqiu agree on every window; aqiu's tested route rescues on three limit days.
        groups, people = builder.rescue_groups(builder.Ledger())
        self.assertEqual([(a, b) for a, b, _n in people], builder.disappear_windows())
        limits = [builder.day_of_md(md) for md in ("09-04", "12-01", "12-30")]
        self.assertEqual(builder.rescue_limit_witnesses(builder.Ledger(), people), limits)
        self.assertEqual(sum(n for _d, n in builder.walkthrough_rescues()), 20)
        for record in self.pack["tartarus"]["rescues"] + self.pack["reminders"]["rescueGroups"]:
            self.assertEqual(record["src"], list(builder.RESCUE_SRC), record["id"])
        # The copy never words the limit as 「…前」, and never promises the limit evening without saying that
        # nobody checked it in the game (the engine plans the rescue by the day before, DESIGN §10.3).
        days = {d["md"]: d for d in self.pack["days"]}
        self.assertEqual(days["10-03"]["noteZh"],
                         "今天是救出北村文吉的最后期限：按游戏数据和攻略今晚去塔尔塔罗斯还来得及，但没在游戏里核对过")
        self.assertEqual(days["09-12"]["noteZh"], "北村文吉失踪了，期限 10月3日，到塔尔塔罗斯把人救出来")
        links = {link["id"]: link for link in self.pack["links"]}
        self.assertEqual(links["hierophant"]["noteZh"], "9月12日北村文吉失踪，期限 10月3日，要到塔尔塔罗斯救出他，否则教皇社群无法继续。")
        for where, text in builder.player_texts(self.pack):
            if "还来得及" in text:
                self.assertIn("没在游戏里核对过", text, where)
            self.assertNotIn("最晚要在", text, where)
        for where, text in builder.player_texts(self.pack):
            self.assertNotRegex(text, r"不一定来得及|\d+日前(?:没)?(?:救出|到塔尔塔罗斯)", where)
        # Moving the limit-day rescues one evening earlier leaves the reading without its witness.
        earlier = [(d - 1 if d in limits else d, n) for d, n in builder.walkthrough_rescues()]
        with mock.patch.object(builder, "walkthrough_rescues", return_value=earlier):
            with self.assertRaisesRegex(builder.BuildError, "no rescue on a limit day"):
                builder.rescue_limit_witnesses(builder.Ledger(), people)
        # A walkthrough evening must rescue exactly the people missing then.
        wrong = [(builder.day_of_md("09-04"), 2)]
        with mock.patch.object(builder, "walkthrough_rescues", return_value=wrong):
            with self.assertRaisesRegex(builder.BuildError, "rescues 2, but 3 people"):
                builder.rescue_limit_witnesses(builder.Ledger(), people)
        # The other pages are described as they read (review after 3.1.0): RPG Site gives the 9/3 group's
        # deadline as 9/5 (the full moon) and its dates are loose; Game8 JP's ranges and the Steam remark do not
        # settle whether the limit day counts, so none of them is cited as corroboration.
        notes = {s["id"]: s["note"] for s in self.pack["sources"]}
        self.assertIn("deadline 9/5", notes["rpgsite_missing"])
        self.assertNotIn("(limit 9/4)", notes["rpgsite_missing"])
        self.assertIn("not whether the limit day counts", notes["game8jp_missing"])
        self.assertIn("does not say whether", notes["steam_thread_missing"])
        decision = next(d for d in self.ledger.decisions if d[1] == "rescue deadline")
        self.assertNotIn("Corroboration", decision[2])
        self.assertIn("'Rescue Deadline: September 5', the full moon; the game's limit is 9/4", decision[2])
        rpgsite = "web_guides/rpgsite_missing_people.html"
        changed = (builder.RESCUE_LIMIT_QUOTES[rpgsite][0], ("Rescue Deadline: September 4",))
        with mock.patch.dict(builder.RESCUE_LIMIT_QUOTES, {rpgsite: changed}):
            with self.assertRaisesRegex(builder.BuildError, "no longer says 'Rescue Deadline: September 4'"):
                builder.rescue_limit_witnesses(builder.Ledger(), people)
        # The limit must stay the last Tartarus evening of its window (the copy promises it).
        pack = json.loads(self.text)
        next(d for d in pack["days"] if d["md"] == "10-03")["tartarus"] = False
        with self.assertRaisesRegex(builder.BuildError, "last Tartarus evening"):
            builder.validate_pack(pack)

    def test_cn_guide_drift_is_exactly_the_contaminated_dates(self) -> None:
        self.assertEqual(builder.CN_GUIDE_DIVERGENT, frozenset(
            {"07-08", "10-22", "12-11", "05-19", "07-15", "07-17", "10-14", "12-16", "12-18"}))
        report = builder.render_report(self.pack, self.ledger, self.text)
        for md in builder.CN_GUIDE_DIVERGENT:
            self.assertIn(f"| `answer.{md}` |", report)
        with mock.patch.object(builder, "CN_GUIDE_DIVERGENT", builder.CN_GUIDE_DIVERGENT - {"07-08"}):
            with self.assertRaisesRegex(builder.BuildError, "CN guide answer drift changed"):
                builder.build_pack()

    def test_a_3dm_option_that_disagrees_with_aqiu_fails_the_build(self) -> None:
        real = builder.aqiu_choices

        def shifted(page, section):
            choices = real(page, section)
            if section == "class-answers":
                choices[builder.day_of_md("07-08")] = 3
            return choices

        with mock.patch.object(builder, "aqiu_choices", shifted):
            with self.assertRaisesRegex(builder.BuildError, r"class 07-08: 3DM option 2 != aqiu Choice 3"):
                builder.build_pack()

    def test_reminder_text_rules(self) -> None:
        items = self.pack["reminders"]["items"]
        self.assertEqual([(x["day"], x["slot"] == "eve", x["id"]) for x in items],
                         sorted((x["day"], x["slot"] == "eve", x["id"]) for x in items))
        editions = {s["id"]: s["edition"] for s in self.pack["sources"]}
        for x in items:
            self.assertTrue(any(editions[s] in builder.RELOAD_EDITIONS for s in x["src"]), x["id"])
            self.assertTrue(builder.fits_short(x["shortZh"]), x["shortZh"])
            for key in ("labelZh", "zh", "shortZh", "detailZh", "gainZh"):
                text = (x[key] or "").replace(x["answerZh"] or "\0", "")
                for word in builder.GLOSSARY_AVOID + builder.FORBIDDEN_STRINGS:
                    self.assertNotIn(word, text, (x["id"], key))
                self.assertNotRegex(text, r"\d+/\d+|[?!]|\.\.\.", (x["id"], key))
        self.assertLess(len(self.text.encode("utf-8")), builder.MAX_PACK_BYTES)
        pack = json.loads(self.text)
        pack["reminders"]["items"][0]["detailZh"] = "宿舍天台有黄昏碎片"
        with self.assertRaisesRegex(builder.BuildError, "黄昏碎片"):
            builder.validate_pack(pack)
        pack = json.loads(self.text)
        pack["reminders"]["items"][3]["zh"] = "不占用时段的小事"
        with self.assertRaisesRegex(builder.BuildError, "不占用时段"):
            builder.validate_pack(pack)
        pack = json.loads(self.text)
        pack["reminders"]["items"][0]["src"] = ["lpbeach"]
        with self.assertRaisesRegex(builder.BuildError, "relies only on non-Reload"):
            builder.validate_pack(pack)
        pack = json.loads(self.text)
        pack["reminders"]["items"].reverse()
        with self.assertRaisesRegex(builder.BuildError, "sorted"):
            builder.validate_pack(pack)

    def test_3dm_pages_are_pinned(self) -> None:
        by_path = {e["path"]: e for e in builder.load_manifest()["files"]}
        sources = {s["id"]: s for s in self.pack["sources"]}
        for n in builder.CN_3DM_SCHEDULE_PAGES:
            entry = by_path[f"cn_guides/3dm_p{n}.html"]
            self.assertEqual(entry["url"], f"https://www.3dmgame.com/gl/3928716_{n}.html")
            self.assertEqual(entry["edition"], "cn_guide_reload")
            self.assertEqual(sources[f"cn_3dm_p{n}"]["sha256"], entry["sha256"])
        self.assertEqual(sources["gd_mail_order"]["edition"], "game_data_reload")
        self.assertIn("edited derivative of the 3DM walkthrough", sources["cn_guide"]["note"])
        report = builder.render_report(self.pack, self.ledger, self.text)
        for heading in ("### 4.8 Reminders (当日提醒)", "当日提醒 answers", "当日提醒 editorial lines",
                        "当日提醒 not shipped yet"):
            self.assertIn(heading, report)

    def test_calendar_helpers(self) -> None:
        self.assertEqual(builder.day_of(4, 7), 6)
        self.assertEqual(builder.day_of(1, 31), 305)
        self.assertEqual(builder.md_of(305), "01-31")
        self.assertEqual(builder.weekday_of(0), 3)


if __name__ == "__main__":
    unittest.main()
