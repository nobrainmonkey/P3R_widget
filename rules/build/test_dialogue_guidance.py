"""Approved source recovery must preserve questions and keep relationship routes separate."""

from __future__ import annotations

import copy
import importlib.util
import unittest
from pathlib import Path
from unittest import mock


SPEC = importlib.util.spec_from_file_location("dialogue_builder", Path(__file__).with_name("build_p3r_rules.py"))
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)


class ApprovedDialogueTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.approved = builder.load_approved_dialogue()
        cls.pack, cls.ledger = builder.build_pack()
        cls.links = {link["id"]: link for link in cls.pack["links"]}

    def hint(self, link: str, rank: int) -> dict:
        return self.links[link]["dialogueHints"][str(rank)]

    def test_approval_scope_is_115_scenes_88_new_and_27_repaired(self) -> None:
        scenes = self.approved["scenes"]
        self.assertEqual(len(scenes), 115)
        self.assertEqual(sum(scene["new"] for scene in scenes), 88)
        self.assertEqual(sum(not scene["new"] for scene in scenes), 27)
        expected = {(scene["link"], str(scene["rank"])) for scene in scenes}
        actual = {(link["id"], rank) for link in self.pack["links"] for rank in link.get("dialogueHints", {})}
        self.assertEqual(actual, expected)

    def test_bound_reference_coverage_163_of_171_is_not_a_branch_completeness_claim(self) -> None:
        absent = {(link["id"], rank) for link in self.pack["links"] if link["kind"] == "manual"
                  for rank in range(2, 11) if str(rank) not in link["dialogue"]}
        self.assertEqual(absent, {("priestess", 2), ("empress", 6), ("hierophant", 9), ("fortune", 5),
                                  ("devil", 10), ("tower", 9), ("star", 3), ("sun", 3)})
        self.assertEqual(19 * 9 - len(absent), 163)

    def test_held_candidates_and_pending_legacy_scenes_receive_no_new_typed_claim(self) -> None:
        for scene in self.approved["heldCandidates"]:
            with self.subTest(scene=scene):
                link = self.links[scene["link"]]
                self.assertNotIn(str(scene["rank"]), link.get("dialogueHints", {}))
                if scene["new"]:
                    self.assertNotIn(str(scene["rank"]), link["dialogue"])
        for link, rank in (("priestess", 2), ("priestess", 8), ("sun", 3), ("devil", 10)):
            self.assertNotIn(str(rank), self.links[link].get("dialogueHints", {}))

    def test_repeated_answers_are_distinct_prompts_not_duplicate_labels(self) -> None:
        ref = {"referenceId": "r1", "entityId": "p3r.social_link.justice", "targetRank": 3,
               "dialogueChoices": ["同一个回答", "同一个回答", "任意", "任意"]}
        next_ref = {**ref, "referenceId": "r2", "dialogueChoices": ["同一个回答"]}
        with mock.patch.object(builder, "read_evidence", return_value={"references": [ref, copy.deepcopy(ref), next_ref]}):
            result = builder.dialogue_map({"scenes": []})
        self.assertEqual(result["justice"]["3"], ["同一个回答", "同一个回答", "任意", "任意", "同一个回答"])
        self.assertEqual(self.links["hierophant"]["dialogue"]["2"], ["我叫XXX", "谢谢", "任意", "任意", "任意"])
        self.assertEqual(self.hint("justice", 7)["common"]["sequence"], ["感觉有什么误会", "得想想办法才行"])
        self.assertEqual(self.links["lovers"]["dialogue"]["5"], ["任意"] * 4 + ["抱歉"])

    def test_conflicting_duplicate_reference_id_fails(self) -> None:
        ref = {"referenceId": "same", "entityId": "p3r.social_link.justice", "targetRank": 3,
               "dialogueChoices": ["回答"]}
        changed = {**ref, "dialogueChoices": ["不同回答"]}
        with mock.patch.object(builder, "read_evidence", return_value={"references": [ref, changed]}):
            with self.assertRaisesRegex(builder.BuildError, "conflicting duplicate"):
                builder.dialogue_map({"scenes": []})

    def test_priestess_early_dialogue_recovers_original_concrete_words(self) -> None:
        self.assertEqual(self.hint("priestess", 3)["common"]["choices"], ["就啪的一撒", "不用心急"])
        self.assertEqual(self.hint("priestess", 4)["common"]["choices"], ["风花很擅长努力", "没那回事"])
        self.assertEqual(self.hint("priestess", 5)["common"]["sequence"],
                         ["下功夫了啊", "我很高兴能帮上忙", "再做给我吃吧"])

    def test_romance_answers_never_become_friendship_recommendations(self) -> None:
        for link, rank, confession in (("priestess", 9, "我喜欢风花"), ("empress", 9, "我也喜欢美鹤"),
                                        ("lovers", 9, "我喜欢你"), ("strength", 9, "因为我喜欢你"),
                                        ("aeon", 8, "是恋情")):
            with self.subTest(link=link, rank=rank):
                hint = self.hint(link, rank)
                self.assertNotIn("common", hint)
                self.assertIn(confession, hint["romance"]["choices"])
                self.assertEqual(hint["friend"]["choices"], [])
                self.assertEqual(hint["friend"]["avoid"], [confession])
                self.assertFalse(hint["friend"].get("any", False))
                self.assertTrue(all("不要选择" in line or "待核对" in line for line in hint["friend"]["sequence"]))

    def test_missing_friend_variant_stays_unknown(self) -> None:
        for link, rank in (("empress", 8), ("aeon", 9)):
            hint = self.hint(link, rank)
            self.assertIn("romance", hint)
            self.assertNotIn("common", hint)
            self.assertNotIn("friend", hint)
        self.assertIn("保留恋爱分支", self.hint("empress", 8)["romance"]["noteZh"])

    def test_priestess_optional_prerequisite_is_not_required_for_friendship(self) -> None:
        hint = self.hint("priestess", 7)
        self.assertEqual(hint["common"]["choices"], ["太过分了"])
        self.assertIn("想保留恋爱分支", hint["common"]["shortNoteZh"])
        self.assertIn("不等于确定恋爱关系", hint["common"]["noteZh"])
        self.assertIn("友情路线", hint["common"]["sequence"][-1])
        self.assertEqual(hint["romance"]["choices"], ["太过分了", "真的是这样吗"])

    def test_aeon_conditional_question_is_retained(self) -> None:
        hint = self.hint("aeon", 4)["common"]
        self.assertEqual(hint["choices"], ["好"])
        self.assertEqual(hint["sequence"], ["任意", "任意（仅在前一问选择第1项时出现）", "好", "任意"])
        self.assertIn("中文原文待核对", hint["noteZh"])

    def test_compact_position_and_silence_are_not_vague_recommendations(self) -> None:
        self.assertEqual(self.hint("lovers", 5)["common"]["shortChoices"], ["抱歉（最后一问）"])
        self.assertEqual(self.hint("chariot", 8)["common"]["choices"], ["……"])
        self.assertEqual(self.hint("chariot", 8)["common"]["shortChoices"], ["选「……」（保持沉默）"])
        all_any = {(link["id"], int(rank)) for link in self.pack["links"]
                   for rank, hint in link.get("dialogueHints", {}).items()
                   if hint.get("common", {}).get("any")}
        self.assertEqual(all_any, {("lovers", 3), ("fortune", 2), ("hanged_man", 7), ("moon", 9)})

    def test_detail_only_conditions_do_not_expand_the_approved_short_preview(self) -> None:
        for link, rank, route in (("aeon", 4, "common"), ("aeon", 8, "romance"),
                                  ("priestess", 7, "romance"), ("priestess", 9, "friend"),
                                  ("empress", 8, "romance"), ("hierophant", 3, "common"),
                                  ("chariot", 8, "common")):
            with self.subTest(link=link, rank=rank, route=route):
                variant = self.hint(link, rank)[route]
                self.assertTrue(variant["noteZh"])
                self.assertEqual(variant["shortNoteZh"], "")
        self.assertEqual(self.hint("aeon", 8)["romance"]["noteZh"],
                         "本句用于保留恋爱分支，不等于现在确定恋爱关系。")

    def test_each_hint_has_pinned_single_source_provenance(self) -> None:
        sources = {source["id"]: source for source in self.pack["sources"]}
        self.assertEqual(sources["ev_dialogue_approved"]["path"], "rules/p3r/dialogue_guidance_approved.json")
        for link in self.pack["links"]:
            for hint in link.get("dialogueHints", {}).values():
                self.assertEqual(hint["conf"], "single")
                self.assertIn("ev_dialogue_approved", hint["src"])
                self.assertTrue(any(ident.startswith(("cn_3dm_p", "cn_gamersky_p")) for ident in hint["src"]))
                for ident in hint["src"]:
                    self.assertIn(ident, sources)
        builder.validate_pack(self.pack)

    def test_source_drift_fails_before_any_dialogue_is_compiled(self) -> None:
        approved = copy.deepcopy(self.approved)
        approved["sources"][0]["sha256"] = "0" * 64
        with mock.patch.object(builder, "read_evidence", return_value=approved):
            with self.assertRaisesRegex(builder.BuildError, "approved dialogue: source changed"):
                builder.load_approved_dialogue()

    def test_report_distinguishes_approved_original_guide_from_derivative_omissions(self) -> None:
        report = "\n".join(builder.render_unverified(self.pack))
        self.assertIn("163/171", report)
        self.assertIn("115 user-approved scenes (88 new, 27 repaired)", report)
        self.assertIn("one guide lineage", report)
        self.assertNotIn("editorial rewrites", report)


if __name__ == "__main__":
    unittest.main()
