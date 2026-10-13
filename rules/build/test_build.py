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

    def test_calendar_helpers(self) -> None:
        self.assertEqual(builder.day_of(4, 7), 6)
        self.assertEqual(builder.day_of(1, 31), 305)
        self.assertEqual(builder.md_of(305), "01-31")
        self.assertEqual(builder.weekday_of(0), 3)


if __name__ == "__main__":
    unittest.main()
