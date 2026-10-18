"""Behavioral checks for actionable, non-simulated Elizabeth request tips."""

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


spec = importlib.util.spec_from_file_location("request_tips_builder", Path(__file__).with_name("build_p3r_rules.py"))
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class RequestTipsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.pack, cls.ledger = builder.build_pack()
        cls.items = {x["id"]: x for x in cls.pack["reminders"]["items"]}

    def test_prerequisite_hand_ins_and_deadlines_are_actionable(self) -> None:
        expected = {
            "06-06": ("交付第 12 号后再接第 13 号", "岳羽由加莉", "伊织顺平"),
            "07-05": ("交付第 27 号后再接第 28 号", "桐条美鹤", "真田明彦", "黑水晶", "10,000 日元"),
            "10-02": ("交付第 68 号后再接第 69 号", "荒垣真次郎", "埃癸斯"),
            "11-30": ("交付第 94 号后再接第 95 号", "虎狼丸", "天田乾"),
        }
        for md, fragments in expected.items():
            item = self.items[f"request.{md}.deadline"]
            self.assertEqual(item["untilDay"], builder.day_of_md(md))
            self.assertLess(item["day"], item["untilDay"])
            self.assertIn("接委托和交东西都不会消耗时间", item["detailZh"])
            for fragment in fragments:
                self.assertIn(fragment, item["detailZh"], md)

    def test_preparation_is_shown_before_the_missable_action(self) -> None:
        july = self.items["request.07-09"]["detailZh"]
        self.assertIn("分 4 天喂食", july)
        self.assertIn("去屋久岛之前接下", july)
        self.assertIn("没先接就捡不到", july)
        november = self.items["request.11-06"]
        self.assertLess(november["day"], builder.day_of_md("11-17"))
        self.assertIn("3 种饮料", november["detailZh"])
        self.assertIn("没买齐就会错过", november["detailZh"])
        self.assertIn("5,000 日元", november["detailZh"])

    def test_consuming_challenge_is_not_described_as_a_free_side_action(self) -> None:
        text = self.items["request.05-10"]["detailZh"]
        self.assertIn("交付第 9 号后才有第 11 号", text)
        self.assertIn("用掉一个晚上", text)
        self.assertIn("不能和当晚的主要活动同时做", text)
        self.assertIn("额外花费不在安排的预计里", text)

    def test_faculty_visits_do_not_pretend_to_track_completion_or_forecast_stats(self) -> None:
        visits = [x for x in self.items.values() if x["id"].endswith(".faculty")]
        self.assertEqual(len(visits), 8)
        days = {d["d"]: d for d in self.pack["days"]}
        for item in visits:
            self.assertEqual(item["slot"], "day")
            self.assertEqual(days[item["day"]]["type"], "school")
            self.assertIsNone(days[item["day"]]["dayLock"])
            self.assertIn("如果还没完成", item["detailZh"])
            self.assertIn("分 8 天", item["detailZh"])
            self.assertIn("不会消耗时间", item["detailZh"])
            self.assertIn("累计学力 +16", item["detailZh"])
            self.assertNotIn("不代表已经替你记下", item["detailZh"])
            self.assertIn("aqiu_social_events", item["src"])

    def test_outings_use_difficulty_totals_and_wait_after_the_previous_outing(self) -> None:
        item = self.items["request.05-29.outings"]
        text = item["detailZh"]
        self.assertIn("难度星数合计解锁", text)
        self.assertIn("先完成前一次外出", text)
        self.assertIn("至少经过 3 天", text)
        for number, total in zip(range(77, 82), (25, 60, 100, 150, 185)):
            self.assertIn(f"第 {number} 号：难度星数合计 {total}", text)
        self.assertIn("外出不会消耗时间", text)
        self.assertIn("学校开放日", text)
        self.assertIn("25、60、100、150、185", self.items["request.12-04"]["detailZh"])

    def test_how_to_cannot_silently_outlive_changed_source_prerequisites(self) -> None:
        _bullets, rows = builder.overworld_section("Elizabeth Requests")
        rows = [list(r) for r in rows]
        next(r for r in rows if r[1] == "69")[2] = "-"
        with self.assertRaisesRegex(builder.BuildError, "request #69: how-to source or prerequisite changed"):
            builder.request_work_tips(rows)

    def test_outing_threshold_change_fails_instead_of_shipping_a_stale_tip(self) -> None:
        _bullets, rows = builder.overworld_section("Elizabeth Requests")
        rows = [list(r) for r in rows]
        next(r for r in rows if r[1] == "81")[4] = "Completed 90 requests"
        with self.assertRaisesRegex(builder.BuildError, "request #81: outing threshold or predecessor changed"):
            builder.request_extra_reminders(builder.Ledger(), rows, builder.Schedule3dm())


if __name__ == "__main__":
    unittest.main()
