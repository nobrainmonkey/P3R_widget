"""Regression tests for the raw-game-table acquisition join, without upstream clones."""

import unittest

from import_persona_data import acquisition_from_tables


class PersonaAcquisitionTest(unittest.TestCase):
    def setUp(self):
        self.floors = [{"enemyMax": "0", "encountPack": "0"} for _ in range(267)]
        self.packs = [{key: "0" for i in range(1, 9)
                       for key in (f"encNo{i}", f"probability{i}")}]
        self.packs[0].update(encNo1="1", probability1="100")
        self.encounters = [{"shuffleLevel": "0"}, {"shuffleLevel": "2"}]
        # Encounter shuffleLevel 2 selects Persona ranks 4..5, not Persona rank 2.
        self.minor = [{"LowPersonaRankId": "0", "HightPersonaRankId": "0"},
                      {"LowPersonaRankId": "-1", "HightPersonaRankId": "-1"},
                      {"LowPersonaRankId": "4", "HightPersonaRankId": "5"}]
        self.cards = [{"RankId": "2", "PersonaId": "101", "Prob": "100"},
                      {"RankId": "4", "PersonaId": "102", "Prob": "30"},
                      {"RankId": "5", "PersonaId": "103", "Prob": "70"},
                      {"RankId": "4", "PersonaId": "104", "Prob": "0"}]

    def allow(self, *floors):
        for floor in floors:
            self.floors[floor]["enemyMax"] = "3"

    def build(self):
        return acquisition_from_tables(self.floors, self.packs, self.encounters, self.minor, self.cards)

    def test_resolves_encounter_shuffle_level_through_low_high_card_ranks(self):
        self.allow(2)
        result = self.build()
        self.assertEqual({102, 103}, set(result))
        self.assertEqual(2, result[102][0]["floorFrom"])

    def test_zero_weight_encounter_and_card_entries_do_not_add_locations(self):
        self.allow(2)
        self.packs[0].update(encNo2="0", probability2="0")
        self.cards.append({"RankId": "0", "PersonaId": "105", "Prob": "100"})
        self.assertEqual({102, 103}, set(self.build()))
        self.packs[0]["probability2"] = "10"
        self.assertEqual({102, 103, 105}, set(self.build()))

    def test_boss_and_entrance_placeholder_packs_are_not_usable_locations(self):
        self.allow(4, 6)
        # Floor 5 keeps the same nonzero pack, but has no roaming enemies.
        result = self.build()[102]
        self.assertEqual([(4, 4), (6, 6)], [(x["floorFrom"], x["floorTo"]) for x in result])

    def test_groups_contiguous_floors_and_keeps_separate_tower_blocks(self):
        self.allow(20, 21, 22, 23, 24)
        result = self.build()[102]
        self.assertEqual([("thebel", 20, 22), ("arqa", 23, 24)],
                         [(x["blockId"], x["floorFrom"], x["floorTo"]) for x in result])

    def test_excludes_lobby_and_special_table_rows_beyond_main_tower(self):
        self.allow(0, 1, 261, 265, 266)
        result = self.build()[102]
        self.assertEqual([("adamah", 261, 261)],
                         [(x["blockId"], x["floorFrom"], x["floorTo"]) for x in result])


if __name__ == "__main__":
    unittest.main()
