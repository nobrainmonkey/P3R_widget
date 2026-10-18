"""Numbered request references remain complete, actionable and tied to their sources."""

from __future__ import annotations

import copy
import importlib.util
import unittest
from pathlib import Path
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("request_catalogue_builder", Path(__file__).with_name("build_p3r_rules.py"))
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class RequestCatalogueTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.pack, cls.ledger = builder.build_pack()
        cls.items = {x["number"]: x for x in cls.pack["reminders"]["requestTips"]}

    def test_every_number_is_a_separate_named_actionable_reference(self) -> None:
        self.assertEqual(list(self.items), list(range(1, 102)))
        for number, item in self.items.items():
            self.assertTrue(item["titleZh"].strip(), number)
            self.assertIn("开放：", item["detailZh"], number)
            self.assertIn("奖励：", item["detailZh"], number)
            self.assertIn("ev_request_catalogue", item["src"], number)
            self.assertTrue(item["aliases"], number)
        self.assertEqual(self.items[1]["titleZh"], "想喝肌肉饮料")
        self.assertEqual(self.items[101]["titleZh"], "打倒最强之人")

    def test_real_deadlines_are_distinct_from_opening_dates(self) -> None:
        expected = {12: "06-06", 13: "06-06", 27: "07-05", 28: "07-05", 29: "07-05",
                    43: "08-04", 58: "08-31", 68: "10-02", 69: "10-02", 76: "11-01",
                    94: "11-30", 95: "11-30", 97: "12-25"}
        actual = {n: builder.md_of(x["untilDay"]) for n, x in self.items.items() if "untilDay" in x}
        self.assertEqual(actual, expected)
        self.assertNotIn("untilDay", self.items[77])
        self.assertIn("难度星数合计达到25", self.items[77]["detailZh"])
        self.assertIn("前置：完成第80号", self.items[81]["detailZh"])

    def test_outing_unlocks_use_challenge_total_and_three_days_after_completion(self) -> None:
        for number, total in zip(range(77, 82), (25, 60, 100, 150, 185)):
            text = self.items[number]["detailZh"]
            self.assertIn(f"难度星数合计达到{total}", text, number)
            self.assertIn("白天", text, number)
            self.assertIn("不会消耗时间", text, number)
            if number > 77:
                self.assertIn(f"第{number-1}号外出完成后至少经过3天", text, number)
            self.assertNotIn("累计完成80个委托", text)
        self.assertIn("学校开放日", self.items[80]["detailZh"])
        self.assertIn("前置：完成第52号、第78号", self.items[53]["detailZh"])

    def test_taiyaki_needs_one_item_and_the_correct_shop_and_cooling_step(self) -> None:
        text = self.items[38]["detailZh"]
        for part in ("放纵俱乐部", "某学校匿名网站", "公用电脑", "1份", "次日取出", "一个晚上"):
            self.assertIn(part, text)
        for wrong in ("3个月", "至少3份", "凑齐3份", "学校X", "在网吧购买学校"):
            self.assertNotIn(wrong, text)

    def test_missable_preparation_and_multiday_work_are_explicit(self) -> None:
        for number, fragments in {
            44: ("7月20日", "旅行前", "至少一件"),
            75: ("分8天", "不会消耗时间", "学力+16"),
            96: ("京都", "3种", "5,000", "没买齐"),
            97: ("6月18日", "吉本绚子", "感谢信", "7月6日", "晚上", "12月25日"),
        }.items():
            for fragment in fragments:
                self.assertIn(fragment, self.items[number]["detailZh"], number)

    def test_sushi_and_final_battle_keep_the_missing_prerequisites(self) -> None:
        self.assertIn("前置：完成第53号", self.items[74]["detailZh"])
        self.assertIn("学力达到Lv.5", self.items[74]["detailZh"])
        final = self.items[101]["detailZh"]
        self.assertIn("前置：完成第81号、第99号、第100号", final)
        for part in ("独自", "255层", "无效", "反射", "吸收", "9999", "最后一击"):
            self.assertIn(part, final)

    def test_monad_and_mothman_wait_for_the_third_block_and_unlock_conditions(self) -> None:
        self.assertEqual(self.items[37]["fromDay"], builder.day_of_md("07-09"))
        self.assertIn("91层", self.items[37]["detailZh"])
        self.assertIn("80层", self.items[37]["detailZh"])
        self.assertIn("前置：完成第25号", self.items[35]["detailZh"])
        self.assertIn("82层", self.items[35]["detailZh"])

    def test_natural_item_search_aliases_are_retained(self) -> None:
        for alias in ("战国头盔", "战国兜", "教职员室"):
            self.assertIn(alias, self.items[75]["aliases"])
        self.assertIn("冰凉白鲷鱼烧", self.items[38]["aliases"])

    def test_optional_work_that_consumes_an_evening_is_not_called_free(self) -> None:
        for number, fragment in ((10, "使用电脑要用掉一个晚上"), (11, "挑战会用掉一个晚上"),
                                 (38, "查看网站要用掉一个晚上"), (52, "做饭会用掉一个晚上")):
            self.assertIn(fragment, self.items[number]["detailZh"])

    def test_big_eater_challenge_keeps_all_three_stat_gains_separate_from_delivery_rewards(self) -> None:
        detail = self.items[11]["detailZh"]
        self.assertIn("学力、魅力、勇气各+4", detail)
        self.assertIn("1,800", detail)
        self.assertIn("选项2→1→2", detail)
        self.assertIn("黄昏的碎片×3", detail)

    def test_source_changes_and_missing_entries_fail_instead_of_shipping_stale_steps(self) -> None:
        _bullets, rows = builder.overworld_section("Elizabeth Requests")
        changed = copy.deepcopy(rows)
        next(x for x in changed if x[1] == "1")[4] = "Different shop"
        with self.assertRaisesRegex(builder.BuildError, "request #1: catalogue source row changed"):
            builder.request_catalogue(builder.Ledger(), changed)
        evidence = builder.read_evidence("request_catalogue.json")
        evidence["entries"].pop(72)
        with patch.object(builder, "read_evidence", return_value=evidence):
            with self.assertRaisesRegex(builder.BuildError, "1 through 101 exactly once"):
                builder.request_catalogue(builder.Ledger(), rows)


if __name__ == "__main__":
    unittest.main()
