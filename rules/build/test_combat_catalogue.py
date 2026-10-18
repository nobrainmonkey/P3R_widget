"""Combat tips preserve complete source coverage and the game's resistance distinctions."""

from __future__ import annotations

import copy
import importlib.util
import unittest
from pathlib import Path
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("combat_catalogue_builder", Path(__file__).with_name("build_p3r_rules.py"))
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class CombatCatalogueTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.items = {x["id"]: x for x in builder.combat_catalogue(builder.Ledger())}

    def test_each_fixed_guardian_floor_and_every_monad_group_is_present(self) -> None:
        expected = {5, 11, 17, 28, 35, 36, 42, 47, 54, 60, 77, 82, 90, 91, 99, 105, 112,
                    125, 126, 132, 136, 143, 151, 155, 161, 170, 171, 179, 184, 188, 193,
                    203, 212, 218, 225, 230, 236, 241, 246, 247, 253, 254, 255}
        self.assertEqual(expected, {x["floor"] for x in self.items.values() if x["kind"] == "guardian"})
        self.assertEqual(39, sum(x["kind"] == "monad" for x in self.items.values()))
        self.assertEqual(97, len(self.items))
        self.assertIn("魔法之手｜弱点：火炎", self.items["combat.thebel.11"]["detailZh"])
        self.assertIn("依存之搭档｜弱点：贯穿", self.items["combat.yabbashah_1.91"]["detailZh"])

    def test_all_seven_operation_nights_include_both_november_encounters(self) -> None:
        operations = [x for x in self.items.values() if x["kind"] == "full_moon"]
        self.assertEqual(8, len(operations))
        self.assertEqual({"05-09", "06-08", "07-07", "08-06", "09-05", "10-04", "11-03"},
                         {builder.md_of(x["fromDay"]) for x in operations})
        november = [x for x in operations if builder.md_of(x["fromDay"]) == "11-03"]
        self.assertTrue(any("隆也与阵" in x["titleZh"] for x in november))
        self.assertTrue(any("倒悬者" in x["titleZh"] for x in november))
        self.assertEqual("story", self.items["combat.harabah_1.full_moon"]["kind"])

    def test_v_is_double_damage_without_a_knockdown_weakness_and_star_is_not_null_damage(self) -> None:
        priestess = self.items["combat.thebel.full_moon"]["detailZh"]
        line = next(line for line in priestess.splitlines() if line.startswith("女教皇｜"))
        self.assertIn("弱点：无", line)
        self.assertIn("火炎伤害×2（不会因此倒地）", line)
        self.assertIn("无效：冰冻", line)
        self.assertNotIn("电击", line)
        self.assertNotIn("光", line)
        self.assertNotIn("暗", line)
        hermit = self.items["combat.yabbashah_2.full_moon"]["detailZh"].splitlines()[0]
        self.assertIn("无效：电击、疾风、暗", hermit)
        self.assertNotIn("光", hermit)
        elizabeth = self.items["combat.ultimate.4p"]["detailZh"]
        self.assertIn("冰冻伤害×2（不会因此倒地）", elizabeth)

    def test_changing_weaknesses_and_all_nyx_arcana_phases_are_kept_separate(self) -> None:
        emperor = self.items["combat.arqa_1.full_moon"]["detailZh"]
        for element in ("电击", "冰冻", "火炎", "疾风"):
            self.assertIn("弱点：" + element, emperor)
        self.assertIn("变换后重新分析", emperor)
        nyx = self.items["combat.adamah_final.264"]["detailZh"]
        phases = [x for x in nyx.splitlines() if x.startswith("倪克斯化身·")]
        self.assertEqual(14, len(phases))
        self.assertIn("魔术师｜弱点：无；无效：火炎", nyx)
        self.assertIn("正义｜弱点：无；无效：火炎、冰冻、电击、疾风、光、暗", nyx)
        self.assertIn("死神｜弱点：无\n", nyx)
        self.assertNotIn("无效：", phases[-1])
        self.assertLess(len(self.items["combat.adamah_final.264"]["shortZh"]), 100)

    def test_concrete_operation_mechanics_and_enemy_resistances_are_available_without_a_plan(self) -> None:
        self.assertIn("倒计时", self.items["combat.thebel.full_moon"]["detailZh"])
        chariot = self.items["combat.yabbashah_1.full_moon"]["detailZh"]
        for phrase in ("全员防御", "同轮击杀", "避免复活"):
            self.assertIn(phrase, chariot)
        fortune = self.items["combat.tziah_1.full_moon"]["detailZh"]
        self.assertIn("力量反弹打击", fortune)
        self.assertIn("命运｜弱点：打击、电击", fortune.replace("·形态1", ""))
        self.assertTrue(all(x["src"] and x["aliases"] for x in self.items.values()))

    def test_structured_lineups_preserve_all_nine_raw_symbols_and_witnessed_counts(self) -> None:
        raven = self.items["combat.thebel.5"]["enemies"][0]
        self.assertEqual(2, raven["count"])
        self.assertEqual(["-", "-", "-", "w", "s", "-", "s", "*", "*"], raven["affinities"])
        wheel = self.items["combat.thebel.11"]["enemies"]
        self.assertEqual([1, 2], [enemy["count"] for enemy in wheel])
        priestess = next(enemy for enemy in self.items["combat.thebel.full_moon"]["enemies"]
                         if enemy["nameZh"] == "女教皇")
        self.assertEqual("v", priestess["affinities"][3])
        self.assertEqual("*", priestess["affinities"][5])
        self.assertEqual("n", priestess["affinities"][4])
        self.assertEqual(21, sum(item["encounterType"] == "door" for item in self.items.values()))
        self.assertEqual(18, sum(item["encounterType"] == "passage" for item in self.items.values()))
        for item in self.items.values():
            self.assertNotIn("部分敌名译意", item["detailZh"])
            self.assertNotIn("源表", item["tacticsZh"])
            for enemy in item["enemies"]:
                self.assertEqual(9, len(enemy["affinities"]))

    def test_awakening_is_present_without_invented_resistances_and_false_lineup_counts_fail(self) -> None:
        awakening = self.items["combat.prologue.awakening"]
        self.assertEqual("04-09", builder.md_of(awakening["fromDay"]))
        self.assertEqual("story", awakening["kind"])
        self.assertEqual([], awakening["enemies"])
        self.assertEqual("按剧情提示推进，觉醒后继续。", awakening["tacticsZh"])
        evidence = builder.read_evidence("combat_tips_evidence.json")
        evidence["entries"][0]["enemyCounts"]["Ruthless Ice Raven"] = 3
        with patch.object(builder, "read_evidence", return_value=evidence):
            with self.assertRaisesRegex(builder.BuildError, "source lineup witness"):
                builder.combat_catalogue(builder.Ledger())

    def test_passages_have_real_floor_and_battle_order_and_optional_challenges_stay_separate(self) -> None:
        expected = {91: [1, 2], 117: [1, 2, 3], 143: [1, 2, 3], 171: [1, 2, 3],
                    197: [1, 2], 225: [1, 2], 255: [1, 2, 3]}
        actual = {}
        for item in self.items.values():
            if item["encounterType"] == "passage":
                actual.setdefault(item["floor"], []).append(item["battleOrder"])
                self.assertIn(f'{item["floor"]}F蒙纳多通道·第{item["battleOrder"]}战', item["titleZh"])
            elif item["encounterType"] == "door":
                self.assertEqual(0, item["floor"])
                self.assertEqual(0, item["battleOrder"])
                self.assertIn("蒙纳多门：", item["titleZh"])
        self.assertEqual(expected, actual)
        reaper = self.items["combat.adamah.p4"]
        self.assertEqual(("optional", 0, 0), (reaper["kind"], reaper["floor"], reaper["battleOrder"]))
        self.assertEqual("06-13", builder.md_of(reaper["fromDay"]))
        self.assertIn("死神", reaper["aliases"])
        elizabeth = self.items["combat.ultimate.4p"]
        self.assertEqual(("optional", 255, 0), (elizabeth["kind"], elizabeth["floor"], elizabeth["battleOrder"]))
        self.assertIn("隐藏挑战", elizabeth["titleZh"])
        evidence = builder.read_evidence("combat_tips_evidence.json")
        next(item for item in evidence["entries"] if item["id"] == "combat.yabbashah_1.p1")["floor"] = 117
        with patch.object(builder, "read_evidence", return_value=evidence):
            with self.assertRaisesRegex(builder.BuildError, "passage floor and battle order"):
                builder.combat_catalogue(builder.Ledger())

    def test_changed_source_rows_missing_encounters_and_unknown_symbols_fail_closed(self) -> None:
        original = builder.read_evidence("combat_tips_evidence.json")
        for mutate, message in (
                (lambda d: d["entries"].pop(0), "every fixed-floor"),
                (lambda d: d["entries"][0]["sourceResistances"][0]["row"].__setitem__(5, "?"), "changed, missing"),
                (lambda d: d.__setitem__("sourceSha256", "0" * 64), "source changed")):
            evidence = copy.deepcopy(original)
            mutate(evidence)
            with patch.object(builder, "read_evidence", return_value=evidence):
                with self.assertRaisesRegex(builder.BuildError, message):
                    builder.combat_catalogue(builder.Ledger())


if __name__ == "__main__":
    unittest.main()
