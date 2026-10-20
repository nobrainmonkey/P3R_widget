"""Keep dorm tracks tied to Reload's dated opportunities and actual rewards."""

import collections
import datetime
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
EPOCH = datetime.date(2009, 4, 1)
SPEC = importlib.util.spec_from_file_location(
    "dorm_activity_builder", Path(__file__).with_name("build_p3r_rules.py"))
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)


class DormActivitiesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.evidence = json.loads(
            (ROOT / "rules/p3r/dorm_activities_evidence.json").read_text())
        cls.calendar = json.loads(
            (ROOT / "rules/p3r/calendar_full_candidate.json").read_text())
        cls.pack, _ = builder.build_pack()
        cls.activities = {a["id"]: a for a in cls.pack["activities"]
                          if a["id"].startswith("dorm_")}
        cls.page = builder.AqiuPage(
            ROOT / "rules/sources/aqiu_pages/social-events.html")

    @staticmethod
    def day(iso):
        return (datetime.date.fromisoformat(iso) - EPOCH).days

    def test_all_eighteen_tracks_match_the_full_calendar_and_html_snapshot(self):
        symbols = {"yukari": "Yuk", "junpei": "Jun", "akihiko": "Aki",
                   "mitsuru": "Mit", "fuuka": "Fuu", "aigis": "Aig",
                   "koromaru": "Kor", "ken": "Ken", "shinjiro": "Shi"}
        categories = {"dvd": ("dvd", "DVD"), "reading": ("reading", "Rea"),
                      "gardening": ("gardening", "Gar"),
                      "cooking": ("cooking", "Coo"), "tea": ("cooking", "Coo"),
                      "coffee": ("cooking", "Coo")}
        entries = self.evidence["activities"]
        self.assertEqual(18, len(entries))
        self.assertEqual({a["id"] for a in entries}, set(self.activities))
        for entry in entries:
            category = entry["category"]
            if category == "brushing":
                entity, column, symbol = "p3r.activity.koromaru_walk", "Walking", "Brushing"
            else:
                entity_tail, column = categories[category]
                entity, symbol = "p3r.dorm." + entity_tail, symbols[entry["partner"]]
            cells = [c for c in self.calendar["auxiliaryCells"]
                     if c["slot"] == "evening" and c["entityId"] == entity
                     and c["rawSymbol"] == symbol]
            expected = [("2010-" if c["date"]["mmdd"].startswith("01-") else "2009-")
                        + c["date"]["mmdd"] for c in cells]
            html_days = [d for d, value in self.page.column(True, column).items()
                         if value == symbol]
            runtime = self.activities[entry["id"]]
            with self.subTest(activity=entry["id"]):
                self.assertEqual(expected, entry["dates"])
                self.assertEqual([self.day(d) for d in expected], html_days)
                self.assertEqual(html_days, runtime["fixedDays"])
                self.assertEqual(len(html_days), len(set(html_days)))
                self.assertEqual((html_days[0], html_days[-1]),
                                 (runtime["fromDay"], runtime["toDay"]))
        self.assertEqual(278, sum(len(a["fixedDays"]) for a in self.activities.values()))

    def test_source_guards_and_references_resolve(self):
        guards = self.evidence["localSourceGuards"]
        self.assertIn("rules/p3r/calendar_full_candidate.json", {g["path"] for g in guards})
        self.assertIn("rules/sources/aqiu_pages/social-events.html", {g["path"] for g in guards})
        for guard in guards:
            with self.subTest(source=guard["path"]):
                actual = hashlib.sha256((ROOT / guard["path"]).read_bytes()).hexdigest()
                self.assertEqual(guard["sha256"], actual)
        evidence_sources = {s["id"] for s in self.evidence["sources"]}
        runtime_sources = {s["id"] for s in self.pack["sources"]}
        for entry in self.evidence["activities"]:
            self.assertTrue(set(entry["sourceRefs"]) <= evidence_sources)
            runtime = self.activities[entry["id"]]
            self.assertTrue(set(runtime["src"]) <= runtime_sources)
            self.assertIn("ev_dorm_activities", runtime["src"])

    def test_each_partner_has_two_separate_three_completion_tracks(self):
        partners = collections.Counter(a["partner"] for a in self.evidence["activities"])
        self.assertEqual(9, len(partners))
        self.assertEqual({2}, set(partners.values()))
        runtime_partners = collections.Counter(a["dormPartnerZh"]
                                              for a in self.activities.values())
        self.assertEqual(9, len(runtime_partners))
        self.assertEqual({2}, set(runtime_partners.values()))
        for entry in self.evidence["activities"]:
            with self.subTest(activity=entry["id"]):
                self.assertEqual(3, entry["maxProgress"])
                self.assertEqual(entry["partnerZh"],
                                 self.activities[entry["id"]]["dormPartnerZh"])
                self.assertFalse(self.activities[entry["id"]]["once"])
        self.assertEqual(3, self.evidence["milestones"]["perTrackCap"])

    def test_only_eight_female_link_projects_receive_raw_affinity(self):
        evidence = json.loads((ROOT / "rules/p3r/dorm_affinity_evidence.json").read_text())
        expected = evidence["activities"]
        self.assertEqual(8, len(expected))
        self.assertEqual({"lovers", "priestess", "empress", "aeon"}, {v["link"] for v in expected.values()})
        for ident, activity in self.activities.items():
            with self.subTest(activity=ident):
                if ident in expected:
                    self.assertEqual(expected[ident]["link"], activity["affinityLink"])
                    self.assertEqual(1, activity["affinityPts"])
                    self.assertIn("ev_dorm_affinity", activity["src"])
                    self.assertEqual("conflict", activity["conf"])
                else:
                    self.assertNotIn("affinityLink", activity)
                    self.assertNotIn("affinityPts", activity)
        self.assertFalse(evidence["policy"]["multipliers"])
        self.assertFalse(evidence["policy"]["bankBeforeUnlock"])
        self.assertTrue(evidence["policy"]["afterThirdCompletion"])

    def test_eight_stat_rewards_match_the_source_and_item_rewards_add_no_stats(self):
        start = self.page.html.index('id="dorm-hangouts"')
        end = self.page.html.index('id="learned-skills"', start)
        _, rows = self.page._tables(self.page.html[start:end])
        rewards = {}
        for person, category, reward, _weekday in rows:
            category = {"DVDs": "dvd", "Reading*": "reading"}.get(
                category, category.lower())
            match = re.fullmatch(r"(Academics|Charm|Courage) \+(\d+)", reward)
            rewards[f"dorm_{person.lower()}_{category}"] = (
                {match[1].lower(): int(match[2])} if match else {})
        self.assertEqual(set(self.activities), set(rewards))
        self.assertEqual(8, sum(bool(gain) for gain in rewards.values()))
        for ident, gain in rewards.items():
            with self.subTest(activity=ident):
                self.assertEqual(gain, self.activities[ident]["gain"])
                self.assertEqual(0, self.activities[ident]["cost"])
                self.assertEqual(["eve"], self.activities[ident]["slots"])
                if gain:
                    self.assertEqual([2], list(gain.values()))

    def test_only_four_gardening_tracks_require_prepared_crops(self):
        gardens = {a["id"] for a in self.evidence["activities"]
                   if a["requiresPlantedCrop"]}
        self.assertEqual({f"dorm_{partner}_gardening"
                          for partner in ("junpei", "fuuka", "aigis", "shinjiro")}, gardens)
        for entry in self.evidence["activities"]:
            runtime = self.activities[entry["id"]]
            with self.subTest(activity=entry["id"]):
                self.assertIs(entry["id"] in gardens, runtime["requiresPlantedCrop"])
                if entry["id"] in gardens:
                    self.assertIn("种苗", runtime["noteZh"])
                    self.assertEqual({}, runtime["gain"])

    def test_notes_keep_their_own_facts_without_repeating_the_trait_rule(self):
        # 提示 and 队友特性 state the rule once; a plan step already counts the plays left.
        for entry in self.evidence["activities"]:
            note = self.activities[entry["id"]]["noteZh"]
            with self.subTest(activity=entry["id"]):
                self.assertTrue(note is None or "特性" not in note or "散步不计入特性进度" in note, note)
        self.assertIsNone(self.activities["dorm_yukari_dvd"]["noteZh"])
        self.assertEqual("能获得食物或饮品。", self.activities["dorm_yukari_cooking"]["noteZh"])
        self.assertEqual("先确认菜园里种了作物；已成熟就先收获、再种苗。种苗另需准备。",
                         self.activities["dorm_fuuka_gardening"]["noteZh"])

    def test_koromaru_brushing_does_not_import_any_walks(self):
        brushing = set(self.activities["dorm_koromaru_brushing"]["fixedDays"])
        walking_column = self.page.column(True, "Walking")
        self.assertEqual(17, len(brushing))
        self.assertEqual({d for d, value in walking_column.items() if value == "Brushing"},
                         brushing)
        self.assertTrue(brushing.isdisjoint(
            d for d, value in walking_column.items() if value not in ("-", "Brushing", "")))
        self.assertNotIn("dorm_koromaru_walk", self.activities)

    def test_shinjiro_cutoff_and_aigis_reading_unlock(self):
        for category, first, last in (("cooking", "2009-09-03", "2009-10-03"),
                                      ("gardening", "2009-09-07", "2009-10-01")):
            dates = self.activities[f"dorm_shinjiro_{category}"]["fixedDays"]
            with self.subTest(category=category):
                self.assertEqual(8, len(dates))
                self.assertEqual((self.day(first), self.day(last)), (min(dates), max(dates)))
                self.assertTrue(all(d < self.day("2009-10-04") for d in dates))
        self.assertEqual(self.day("2009-09-02"),
                         min(self.activities["dorm_aigis_reading"]["fixedDays"]))

    def test_story_exceptions_cannot_be_replaced_with_usual_weekdays(self):
        # Both Mondays are inside the same activity's range, but only June 29 is offered.
        junpei = self.activities["dorm_junpei_reading"]
        self.assertIn(self.day("2009-06-29"), junpei["fixedDays"])
        self.assertNotIn(self.day("2009-07-06"), junpei["fixedDays"])
        self.assertEqual(datetime.date(2009, 6, 29).weekday(),
                         datetime.date(2009, 7, 6).weekday())
        # Fuuka normally gardens on Tuesday; this Wednesday exception is real.
        fuuka = self.activities["dorm_fuuka_gardening"]
        self.assertIn(self.day("2009-09-09"), fuuka["fixedDays"])
        self.assertNotIn(self.day("2009-09-22"), fuuka["fixedDays"])
        self.assertEqual(2, datetime.date(2009, 9, 9).weekday())


if __name__ == "__main__":
    unittest.main()
