"""Keep dated group-study actions tied to the pinned Reload route evidence."""

import datetime
import hashlib
import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]


class GroupStudyEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.evidence = json.loads(
            (ROOT / "rules/p3r/group_study_evidence.json").read_text())

    def test_sessions_match_every_study_session_in_pinned_reload_route(self):
        route = (ROOT / "rules/sources/aqiu_pages/ave-walkthrough.md").read_text()
        expected = []
        current = None
        for line in route.splitlines():
            heading = re.fullmatch(r"#### \w+ (\d+)/(\d+)", line)
            if heading:
                current = f"{int(heading[1]):02d}-{int(heading[2]):02d}"
            study = re.match(r"\* (Dorm )?Study Group \(Academics \+(\d+),", line)
            if study:
                expected.append((current, int(study[2]), bool(study[1])))
        actual = [(row["date"], row["gains"][0], row["wholeTeam"])
                  for row in self.evidence["sessions"]]
        self.assertEqual(expected, actual)
        self.assertEqual(14, len(actual))

    def test_independent_monthly_calendars_corroborate_date_whitelist(self):
        calendars = []
        for key, source in self.evidence["sources"].items():
            if key.startswith("game8_"):
                calendars.extend(source["observedDates"])
        self.assertEqual(sorted(calendars), sorted(
            row["date"] for row in self.evidence["sessions"]))
        self.assertEqual(len(calendars), len(set(calendars)))

    def test_pinned_source_hashes_and_line_references_are_current(self):
        for source in self.evidence["sources"].values():
            if "path" not in source:
                continue
            raw = (ROOT / "rules/sources" / source["path"]).read_bytes()
            self.assertEqual(source["sha256"], hashlib.sha256(raw).hexdigest())
        for row in self.evidence["sessions"]:
            ref = row["sourceRefs"][0]
            lines = (ROOT / "rules/sources" / ref["path"]).read_text().splitlines()
            self.assertIn(f"Academics +{row['gains'][0]}", lines[ref["line"] - 1])

    def test_group_sessions_use_one_evening_and_never_add_unsupported_progress(self):
        policy = self.evidence["policy"]
        self.assertEqual("evening", policy["slot"])
        self.assertEqual(1, policy["slotCost"])
        self.assertEqual(0, policy["yenCost"])
        self.assertEqual(0, policy["affinityPoints"])
        self.assertEqual(0, policy["dormCharacteristicProgress"])
        for row in self.evidence["sessions"]:
            self.assertEqual([0, 0], row["gains"][1:])
            self.assertEqual(5 if row["wholeTeam"] else 4, row["gains"][0])

    def test_whole_team_gains_keep_conflict_and_precede_each_exam(self):
        def ordinal(md):
            return datetime.date(2009, *map(int, md.split("-"))).toordinal()

        exam_eves = {ordinal(md) - 1 for md in self.evidence["coverage"]["examDates"]}
        sessions = [row for row in self.evidence["sessions"] if row["wholeTeam"]]
        self.assertEqual(exam_eves, {ordinal(row["date"]) for row in sessions})
        self.assertTrue(all(row["conf"] == "conflict" for row in sessions))
        decision = self.evidence["gainDecision"]["conflict"]
        self.assertEqual(5, decision["selected"])
        self.assertEqual(6, decision["rejected"])


if __name__ == "__main__":
    unittest.main()
