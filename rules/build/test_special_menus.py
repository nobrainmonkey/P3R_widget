"""Reload special menus: stat gates alone must not invent an undiscovered menu."""

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


SPEC = importlib.util.spec_from_file_location("special_menu_builder", Path(__file__).with_name("build_p3r_rules.py"))
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)


class SpecialMenuTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.pack, cls.ledger = builder.build_pack()
        cls.activities = {a["id"]: a for a in cls.pack["activities"]}

    def test_reload_special_menu_prices_gains_and_calendar(self) -> None:
        for ident, cost, stat, weekdays, gate in (
            ("meal_wakatsu_special", 900, "academics", [1, 4, 5, 7], {"charm": 3}),
            ("meal_hagakure_special", 1200, "charm", [2, 3, 5], {"courage": 3}),
        ):
            with self.subTest(ident=ident):
                menu = self.activities[ident]
                self.assertEqual(menu["cost"], cost)
                self.assertEqual(menu["gain"], {stat: 4})
                self.assertEqual(menu["weekdays"], weekdays)
                self.assertEqual(menu["slots"], ["eve"])
                self.assertEqual(menu["requires"], gate)
                self.assertIn("aqiu_social_events", menu["src"])

    def test_discovery_is_a_real_regular_meal_and_can_precede_stat_gate(self) -> None:
        for restaurant in ("wakatsu", "hagakure"):
            regular = self.activities[f"meal_{restaurant}"]
            discovery = self.activities[f"meal_{restaurant}_menu"]
            special = self.activities[f"meal_{restaurant}_special"]
            with self.subTest(restaurant=restaurant):
                self.assertTrue(discovery["once"])
                self.assertEqual(discovery["gain"], regular["gain"])
                self.assertEqual(discovery["cost"], regular["cost"])
                self.assertEqual(discovery["requires"], {})
                self.assertEqual(discovery["slots"], special["slots"])
                self.assertEqual(discovery["weekdays"], special["weekdays"])
                self.assertEqual(special["requiresActivity"], discovery["id"])
                self.assertIn("普通", discovery["noteZh"])
                self.assertIn("限定菜单", discovery["noteZh"])

    def test_untracked_request_does_not_unlock_weekend_wilduck(self) -> None:
        self.assertNotIn("meal_wilduck_weekend", self.activities)
        self.assertNotIn("burger_challenge", self.activities)

    def test_unknown_activity_prerequisite_fails(self) -> None:
        with self.assertRaisesRegex(builder.BuildError, "unknown activity missing"):
            builder.audit_activity_prerequisites([{"id": "special", "requiresActivity": "missing"}])

    def test_cyclic_activity_prerequisites_fail(self) -> None:
        for activities in (
            [{"id": "a", "requiresActivity": "a"}],
            [{"id": "a", "requiresActivity": "b"}, {"id": "b", "requiresActivity": "a"}],
        ):
            with self.subTest(activities=activities), self.assertRaisesRegex(builder.BuildError, "cycle"):
                builder.audit_activity_prerequisites(activities)


if __name__ == "__main__":
    unittest.main()
