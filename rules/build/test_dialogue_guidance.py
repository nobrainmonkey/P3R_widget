"""Historic approvals and later completion preserve questions, routes and wording provenance."""

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
        cls.base_hints = builder.approved_dialogue_hints(cls.approved)
        cls.base_dialogue = builder.dialogue_map(cls.approved, {"scenes": []})
        cls.pack, cls.ledger = builder.build_pack()
        cls.links = {link["id"]: link for link in cls.pack["links"]}

    def hint(self, link: str, rank: int) -> dict:
        return self.base_hints[link][str(rank)]

    def test_approval_scope_is_115_scenes_88_new_and_27_repaired(self) -> None:
        scenes = self.approved["scenes"]
        self.assertEqual(len(scenes), 115)
        self.assertEqual(sum(scene["new"] for scene in scenes), 88)
        self.assertEqual(sum(not scene["new"] for scene in scenes), 27)
        expected = {(scene["link"], str(scene["rank"])) for scene in scenes}
        actual = {(link, rank) for link, ranks in self.base_hints.items() for rank in ranks}
        self.assertEqual(actual, expected)
        final = {(link["id"], rank) for link in self.pack["links"] for rank in link.get("dialogueHints", {})}
        self.assertTrue(expected <= final)

    def test_bound_reference_coverage_163_of_171_is_not_a_branch_completeness_claim(self) -> None:
        absent = {(link["id"], rank) for link in self.pack["links"] if link["kind"] == "manual"
                  for rank in range(2, 11) if str(rank) not in self.base_dialogue.get(link["id"], {})}
        self.assertEqual(absent, {("priestess", 2), ("empress", 6), ("hierophant", 9), ("fortune", 5),
                                  ("devil", 10), ("tower", 9), ("star", 3), ("sun", 3)})
        self.assertEqual(19 * 9 - len(absent), 163)

    def test_held_candidates_and_pending_legacy_scenes_receive_no_new_typed_claim(self) -> None:
        for scene in self.approved["heldCandidates"]:
            with self.subTest(scene=scene):
                link = scene["link"]
                self.assertNotIn(str(scene["rank"]), self.base_hints.get(link, {}))
                if scene["new"]:
                    self.assertNotIn(str(scene["rank"]), self.base_dialogue.get(link, {}))
        for link, rank in (("priestess", 2), ("priestess", 8), ("sun", 3), ("devil", 10)):
            self.assertNotIn(str(rank), self.base_hints.get(link, {}))

    def test_repeated_answers_are_distinct_prompts_not_duplicate_labels(self) -> None:
        ref = {"referenceId": "r1", "entityId": "p3r.social_link.justice", "targetRank": 3,
               "dialogueChoices": ["同一个回答", "同一个回答", "任意", "任意"]}
        next_ref = {**ref, "referenceId": "r2", "dialogueChoices": ["同一个回答"]}
        with mock.patch.object(builder, "read_evidence", return_value={"references": [ref, copy.deepcopy(ref), next_ref]}):
            result = builder.dialogue_map({"scenes": []}, {"scenes": []})
        self.assertEqual(result["justice"]["3"], ["同一个回答", "同一个回答", "任意", "任意", "同一个回答"])
        self.assertEqual(self.base_dialogue["hierophant"]["2"], ["我叫XXX", "谢谢", "任意", "任意", "任意"])
        self.assertEqual(self.hint("justice", 7)["common"]["sequence"], ["感觉有什么误会", "得想想办法才行"])
        self.assertEqual(self.base_dialogue["lovers"]["5"], ["任意"] * 4 + ["抱歉"])

    def test_conflicting_duplicate_reference_id_fails(self) -> None:
        ref = {"referenceId": "same", "entityId": "p3r.social_link.justice", "targetRank": 3,
               "dialogueChoices": ["回答"]}
        changed = {**ref, "dialogueChoices": ["不同回答"]}
        with mock.patch.object(builder, "read_evidence", return_value={"references": [ref, changed]}):
            with self.assertRaisesRegex(builder.BuildError, "conflicting duplicate"):
                builder.dialogue_map({"scenes": []}, {"scenes": []})

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
        all_any = {(link, int(rank)) for link, ranks in self.base_hints.items()
                   for rank, hint in ranks.items()
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
        for ranks in self.base_hints.values():
            for hint in ranks.values():
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
        self.assertIn("171/171", report)
        self.assertIn("115 user-approved scenes (88 new, 27 repaired)", report)
        self.assertIn("one guide lineage", report)
        self.assertNotIn("editorial rewrites", report)

class CompletedDialogueTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.completion = builder.load_dialogue_completion()
        cls.hints = builder.completed_dialogue_hints(cls.completion)
        cls.pack, cls.ledger = builder.build_pack()
        cls.links = {link['id']: link for link in cls.pack['links']}
        cls.scenes = {(scene['link'], scene['rank']): scene for scene in cls.completion['scenes']}

    def variant(self, link: str, rank: int, route: str) -> dict:
        return self.hints[link][str(rank)][route]

    def test_completion_overlays_exact_durable_evidence_keys(self) -> None:
        approved = builder.load_approved_dialogue()
        original = {(scene['link'], str(scene['rank'])) for scene in approved['scenes']}
        completed = {(scene['link'], str(scene['rank'])) for scene in self.completion['scenes']}
        actual = {(link['id'], rank) for link in self.pack['links'] for rank in link.get('dialogueHints', {})}
        self.assertEqual(actual, original | completed)
        for link, ranks in self.hints.items():
            for rank, expected in ranks.items():
                self.assertEqual(self.links[link]['dialogueHints'][rank], expected)
        self.assertTrue(all(str(rank) in link['dialogue'] for link in self.pack['links']
                            if link['kind'] == 'manual' for rank in range(2, 11)))

    def test_all_historically_held_scenes_have_new_evidence_not_silent_approval(self) -> None:
        approved = builder.load_approved_dialogue()
        historical = {(scene['link'], scene['rank']) for scene in approved['scenes']}
        for scene in approved['heldCandidates']:
            key = scene['link'], scene['rank']
            self.assertNotIn(key, historical)
            self.assertIn(key, self.scenes)
            self.assertIn('ev_dialogue_completion', self.hints[key[0]][str(key[1])]['src'])

    def test_each_source_is_a_persistent_rendered_excerpt_not_claimed_raw_html(self) -> None:
        self.assertFalse(self.completion['capturePolicy']['rawHtmlCaptured'])
        self.assertFalse(self.completion['capturePolicy']['gameTextExtraction'])
        sources = {source['id']: source for source in self.pack['sources']}
        for source in self.completion['sources']:
            emitted = sources[source['id']]
            self.assertEqual(source['captureKind'], 'web_tool_rendered_excerpt')
            self.assertTrue(source['excerpts'])
            self.assertEqual(emitted['path'], 'rules/p3r/dialogue_guidance_completion.json')
            self.assertEqual(emitted['sha256'], builder.sha256_file(builder.EVIDENCE_DIR / 'dialogue_guidance_completion.json'))
            self.assertEqual(emitted['url'], source['url'])
        for ranks in self.hints.values():
            for hint in ranks.values():
                self.assertEqual(hint['conf'], 'single')
                self.assertIn('ev_dialogue_completion', hint['src'])
                self.assertTrue(set(hint['src']) <= sources.keys())

    def test_source_guard_drift_is_rejected(self) -> None:
        evidence = copy.deepcopy(self.completion)
        evidence['localSourceGuards'][0]['sha256'] = '0' * 64
        with mock.patch.object(builder, 'read_evidence', return_value=evidence):
            with self.assertRaisesRegex(builder.BuildError, 'guarded source changed'):
                builder.load_dialogue_completion()

    def test_guide_or_translation_cannot_claim_unseen_official_localization(self) -> None:
        evidence = copy.deepcopy(self.completion)
        variant = next(v for scene in evidence['scenes'] for v in scene['variants'].values() if v['choices'])
        variant['choiceOrigins'][0]['origin'] = 'official_zh_cn_seen'
        variant['choiceOrigins'][0].pop('officialObservation', None)
        with mock.patch.object(builder, 'read_evidence', return_value=evidence):
            with self.assertRaisesRegex(builder.BuildError, 'official wording requires an inspected'):
                builder.load_dialogue_completion()

    def test_source_labels_stay_out_of_details_and_question_order_is_preserved(self) -> None:
        for scene in self.completion['scenes']:
            for route, raw in scene['variants'].items():
                with self.subTest(link=scene['link'], rank=scene['rank'], route=route):
                    emitted = self.variant(scene['link'], scene['rank'], route)
                    self.assertEqual(emitted['choices'], [builder.fix_names(text) for text in raw['choices']])
                    if builder._contains_translation(raw):
                        self.assertNotIn('译意', emitted.get('noteZh', ''))
                        self.assertNotIn('译义', emitted.get('noteZh', ''))
                        self.assertEqual(emitted.get('shortNoteZh'), raw.get('shortNoteZh', ''))
                    self.assertTrue(set(emitted) <= builder.COMPLETION_VARIANT_FIELDS)
                    for alternative in raw.get('alternatives', []):
                        heading = builder.completion_display_text(f"【{alternative['whenZh']}】")
                        self.assertIn(heading, emitted['sequence'])
                        start = emitted['sequence'].index(heading) + 1
                        self.assertEqual(emitted['sequence'][start:start + len(alternative['sequence'])],
                                         builder.completion_display_text(alternative['sequence']))
                    if 'shortChoices' in emitted:
                        self.assertEqual(len(emitted['shortChoices']), len(emitted['choices']))

    def test_arbitrary_answers_rely_on_complete_option_comparison(self) -> None:
        for key, prompts in ((('hierophant', 9), 2), (('tower', 9), 3), (('star', 3), 2)):
            scene = self.scenes[key]
            variant = self.variant(*key, 'common')
            self.assertTrue(scene['allOptionsEqualBest'])
            self.assertTrue(variant['any'])
            self.assertEqual(variant['choices'], [])
            self.assertEqual(variant['sequence'], ['任意'] * prompts)
            all_option_sources = [source for source in scene['evidence'] if source.get('allOptionEvidence')]
            self.assertGreaterEqual(len(all_option_sources), 2)
        self.assertEqual(self.variant('fortune', 5, 'common')['choices'], ['赶紧向你父亲报告'])

    def test_terminal_scenes_keep_both_routes_and_no_menu_is_invented(self) -> None:
        for link in ('priestess', 'empress', 'lovers', 'justice', 'strength', 'aeon'):
            hint = self.hints[link]['10']
            self.assertEqual(set(hint) & {'common', 'friend', 'romance'}, {'friend', 'romance'})
            for route in ('friend', 'romance'):
                self.assertTrue(hint[route]['sequence'])
        self.assertEqual(self.variant('lovers', 10, 'romance')['choices'], ['我正在想'])
        self.assertEqual(self.variant('empress', 10, 'romance')['choices'], ['我会试试看的'])
        self.assertEqual(self.variant('aeon', 10, 'friend')['sequence'], ['任意'] * 4)
        self.assertEqual(self.variant('aeon', 10, 'romance')['sequence'], ['任意'] * 2)
        devil = self.variant('devil', 10, 'common')
        self.assertFalse(devil.get('any', False))
        self.assertEqual(devil['choices'], [])
        self.assertEqual(devil['shortNoteZh'], '观看剧情即可')

    def test_aeon_four_names_the_condition_instead_of_an_unverified_ordinal(self) -> None:
        variant = self.variant('aeon', 4, 'common')
        self.assertEqual(variant['choices'], ['好'])
        self.assertEqual(variant['shortChoices'], ['好'])
        self.assertEqual(variant['sequence'], ['任意（有没有见过白色花猫）',
                                               '任意（答了「好像见过」才问猫在哪）', '好', '任意'])
        self.assertIn('答「好像见过」会再问猫在哪', variant['noteZh'])
        self.assertNotIn('译意', variant['noteZh'])
        displayed = ' / '.join(variant['sequence']) + variant['noteZh']
        self.assertNotIn('第1项', displayed)
        self.assertNotIn('待核对', displayed)
        self.assertEqual(variant['shortNoteZh'], '')

    def test_justice_friend_preserves_gain_path_then_rejects_at_rank_nine(self) -> None:
        rank5 = self.variant('justice', 5, 'friend')
        self.assertEqual(rank5['choices'], self.variant('justice', 5, 'romance')['choices'])
        self.assertIn('握住她的手', rank5['choices'])
        rank9 = self.variant('justice', 9, 'friend')
        self.assertIn('千寻是我重要的学妹', rank9['choices'])
        self.assertNotIn('我的心意和千寻一样', rank9['choices'])
        self.assertTrue(self.scenes['justice', 5]['variants']['friend']['alternatives'])
        self.assertTrue(self.scenes['justice', 9]['variants']['friend']['alternatives'])

    def test_friend_primary_never_includes_romance_acceptance(self) -> None:
        for link, rank, confession in (('priestess', 9, '我喜欢风花'), ('empress', 9, '我也喜欢美鹤'),
                                      ('lovers', 9, '我喜欢你'), ('strength', 9, '因为我喜欢你'),
                                      ('justice', 9, '我的心意和千寻一样'), ('aeon', 8, '是恋情'),
                                      ('aeon', 9, '我也喜欢你')):
            with self.subTest(link=link, rank=rank):
                hint = self.hints[link][str(rank)]
                self.assertNotIn('common', hint)
                self.assertNotIn(confession, hint['friend']['choices'])
                self.assertIn(confession, hint['romance']['choices'])
        self.assertNotIn('common', self.hints['strength']['8'])
        self.assertEqual(self.variant('strength', 8, 'romance')['choices'], ['男孩或女孩'])
        # Compact notes must not wrap a final lone word on Android 26 answer cards.
        self.assertEqual(self.variant('priestess', 7, 'friend')['shortNoteZh'], '')
        self.assertEqual(self.variant('lovers', 9, 'friend')['shortNoteZh'], '先保持沉默')

    def test_completion_changes_no_numeric_or_calendar_mechanics(self) -> None:
        empty = {'sources': [], 'scenes': []}
        old_value_sources = tuple(row for row in builder.VALUE_SOURCES if 'authorized completion' not in row[0])
        with mock.patch.object(builder, 'load_dialogue_completion', return_value=empty), \
                mock.patch.object(builder, 'VALUE_SOURCES', old_value_sources):
            original, _ledger = builder.build_pack()
        current = copy.deepcopy(self.pack)
        for pack in (original, current):
            pack.pop('sources')
            for link in pack['links']:
                for field in ('dialogue', 'dialogueHints', 'src'):
                    link.pop(field, None)
        self.assertEqual(current, original)

    def test_displayed_dialogue_copy_has_no_japanese_or_raw_point_values(self) -> None:
        def strings(node):
            if isinstance(node, str):
                yield node
            elif isinstance(node, list):
                for item in node:
                    yield from strings(item)
            elif isinstance(node, dict):
                for item in node.values():
                    yield from strings(item)
        for link in self.pack['links']:
            for field in ('dialogue', 'dialogueHints'):
                for text in strings(link.get(field, {})):
                    with self.subTest(link=link['id'], field=field):
                        self.assertIsNone(builder.RESEARCH_SHORTHAND.search(text), text)
        justice = self.variant('justice', 9, 'romance')['sequence']
        self.assertIn('这一场第 2 问只有「千寻是清白的」加好感；第 4 问选「我都看到了」。', justice)
        with self.assertRaisesRegex(builder.BuildError, 'research shorthand'):
            builder.check_player_copy({'noteZh': '另一项为1♪'}, 'sample')
        # The research and planner voice of the notes, too.
        for note in ('已到终级，没有后续升级门槛。', '主序列适用于R7已进入友情分支。', '第三问两个回答同收益。',
                     '本条补齐中文提示，不改变升级规则。', '最后一问属于同伴询问，亦已计入。', '另一组见下文。'):
            with self.assertRaisesRegex(builder.BuildError, 'research shorthand'):
                builder.check_player_copy({'noteZh': note}, 'sample')

    def test_notes_never_point_at_the_other_group_and_each_group_names_its_history(self) -> None:
        # 提示 lists the groups above the note, a plan step below it: a pointer is wrong in one of them.
        for link in self.pack['links']:
            for rank, hint in link.get('dialogueHints', {}).items():
                for route in ('common', 'friend', 'romance'):
                    variant = hint.get(route)
                    if not variant:
                        continue
                    with self.subTest(link=link['id'], rank=rank, route=route):
                        self.assertNotRegex(variant.get('noteZh', ''), '上面|下面|下文|下方')
                        headings = [entry for entry in variant['sequence'] if entry.startswith('【')]
                        for heading in headings[1:]:
                            self.assertRegex(heading, '的话', 'another history reads as a condition')
        # 力量 RANK 5: the friend answer only does not keep eligibility itself (RANK 3 may have).
        five = self.variant('strength', 5, 'friend')['noteZh']
        self.assertIn('「不改比较好」', five)
        self.assertIn('「我相信结子」', five)
        self.assertIn('这一问不会保留恋爱资格', five)
        self.assertNotIn('不会走向恋爱', five)

    def test_flattening_preserves_alternative_without_putting_it_in_primary_choices(self) -> None:
        raw = {'choices': ['主回答'], 'sequence': ['任意', '主回答'],
               'choiceOrigins': [], 'alternatives': [
                   {'whenZh': '此前另一路线', 'choices': ['另一回答'], 'sequence': ['另一回答', '任意'],
                    'choiceOrigins': [{'origin': 'editorial_translation'}]}]}
        result = builder.compile_completion_variant(raw, 'friend')
        self.assertEqual(result['choices'], ['主回答'])
        self.assertNotIn('另一回答', result['choices'])
        self.assertEqual(result['sequence'], ['【只做朋友】', '任意', '主回答',
                                              '【此前另一路线】', '另一回答', '任意'])
        self.assertNotIn('译意', result.get('noteZh', ''))
        self.assertNotIn('alternatives', result)


if __name__ == "__main__":
    unittest.main()
