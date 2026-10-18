"""Guaranteed summer-school points must stay distinct from optional free activities."""

import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "automatic_story_builder", Path(__file__).with_name("build_p3r_rules.py"))
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)


class AutomaticStoryStatsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.evidence = json.loads(
            (ROOT / "rules/p3r/automatic_story_stats_evidence.json").read_text())
        cls.pack, cls.ledger = builder.build_pack()

    def test_six_gains_match_every_summer_school_entry_in_pinned_route(self):
        expected = []
        current = None
        for line in (ROOT / "rules/sources/aqiu_pages/ave-walkthrough.md").read_text().splitlines():
            heading = re.fullmatch(r"#### \w+ (\d+)/(\d+)", line)
            if heading:
                current = f"{int(heading[1]):02d}-{int(heading[2]):02d}"
            study = re.fullmatch(r"\* Summer School \(Academics \+(\d+), 2 notes\)", line)
            if study:
                expected.append((current, {"academics": int(study[1])}))
        actual = [(event["date"], event["gain"]) for event in self.evidence["events"]]
        self.assertEqual(expected, actual)
        self.assertEqual([(f"08-{d}", {"academics": 3}) for d in range(10, 16)], actual)
        self.assertEqual(18, sum(g["academics"] for _, g in actual))

    def test_only_six_locked_daytimes_award_automatic_stats(self):
        gains = [day for day in self.pack["days"] if day.get("autoStatGain")]
        self.assertEqual(6, len(gains))
        for day in gains:
            with self.subTest(day=day["md"]):
                self.assertEqual("summer_classes", day["dayLock"])
                self.assertEqual({"academics": 3}, day["autoStatGain"])
                self.assertEqual("single", day["conf"])
                self.assertIn("学力 +3", day["noteZh"])
                self.assertTrue({"aqiu_social_events", "aqiu_walkthrough", "ev_automatic_story_stats"}
                                <= set(day["src"]))
        optional_days = set(self.pack["reminders"]["doze"]["days"])
        optional_days.update(item["day"] for item in self.pack["reminders"]["items"]
                             if item["kind"] in ("class", "exam"))
        self.assertTrue(optional_days.isdisjoint(day["d"] for day in gains))

    def test_source_hashes_and_mandatory_total_are_preserved(self):
        for guard in self.evidence["localSourceGuards"]:
            self.assertEqual(guard["sha256"], hashlib.sha256(
                (ROOT / guard["path"]).read_bytes()).hexdigest())
        page = builder.AqiuPage(ROOT / "rules/sources/aqiu_pages/social-events.html")
        self.assertIn("Mandatory summer school from 8/10-8/15 will grant a total of Academics +18",
                      page.subsection("general"))

    def test_evidence_cannot_invent_an_optional_or_different_gain(self):
        original_read = builder.read_evidence
        changed = copy.deepcopy(self.evidence)
        changed["events"][0]["gain"] = {"academics": 4}

        def read(name):
            return changed if name == "automatic_story_stats_evidence.json" else original_read(name)

        with mock.patch.object(builder, "read_evidence", side_effect=read):
            with self.assertRaisesRegex(builder.BuildError, "must match the pinned route"):
                builder.automatic_story_stats(builder.Ledger())

    def test_validator_rejects_optional_slots_unknown_stats_and_invalid_points(self):
        for value in ({"wisdom": 3}, {"academics": -3}, {"academics": 0},
                      {"academics": 1.5}, {"academics": True}, [3]):
            pack = copy.deepcopy(self.pack)
            next(day for day in pack["days"] if day["md"] == "08-10")["autoStatGain"] = value
            with self.subTest(value=value):
                with self.assertRaisesRegex(builder.BuildError, "autoStatGain"):
                    builder.validate_pack(pack)
        pack = copy.deepcopy(self.pack)
        next(day for day in pack["days"] if day["md"] == "04-21")["autoStatGain"] = {"academics": 3}
        with self.assertRaisesRegex(builder.BuildError, "requires a locked daytime"):
            builder.validate_pack(pack)


if __name__ == "__main__":
    unittest.main()
