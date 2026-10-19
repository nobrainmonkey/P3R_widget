# P3R rule data inventory: rules/p3r compared with the shipped planner_v2 assets

## 0. Summary

- **The source tree and the shipped pack are the same bytes.** `rules/p3r/*.json` is byte-identical to `android/app/src/main/assets/planner_v2/rules_v051/` and to `rules/p3r/snapshots/0.5.1/`. I checked SHA-256 for all 52 entries in `manifest.json` (packVersion 0.5.1, `gameEdition: "P3R-base"`) and none differ.
  - Files only in `rules/p3r`:
    - `coverage_v03_staged.json`
    - `full_horizon_forecast_policy.json`. This one is not in the APK and no Java references it.
    - `social_goal_identity_labels.json`. The APK ships an identical copy at `planner_v2/social_goal_identity_labels.json`.
  - `tartarus_entry_availability_supplement.json` ships in rules_v051, but it is not in the 0.5.1 manifest or snapshot. `NativeProgressionRules.java:33` loads it directly.
- **The runtime loads four rule packs at once:**

  | Pack | Version | Loaded by |
  |---|---|---|
  | `planner_v2/rules/` | 0.2.0 | `PlannerRules.java:18` |
  | `rules_v03/` | 0.3.0 | `NativeExpandedRules.java:29` |
  | `rules_v04/` | 0.4.0 | `NativeManualRules.java:29`, `GoalPlannerHomeActivity.java:13` |
  | `rules_v051/` | 0.5.1 | `NativeUnlockRules.java:21`, `NativeProgressionRules.java:15` |

  These packs hold only 73 unique files, and about 24.4 MB of the asset bytes are duplicates. For example, the 5.9 MB `calendar_full_candidate.json` ships four times. One pack would be enough.
- **Two source artifacts are not in the repo:** `data.candidate.json` (path `/workspace/scratch/.../p3r_guide_audit/`) and `planner_social_rules_research.json`. `social_links.json` and `stat_activity_expansion_candidate.json` both cite them.
- **Almost nothing is executable.** No rule is "game-replay confirmed". Every file has `planningEnabled: false` or `executable: false`, apart from these:
  - the Sun and Devil guaranteed +1 per visit,
  - 5 night stat actions,
  - a guarded minimum-income Chagall job action,
  - tower stage 10 (Jan 1–31),
  - the Dating Site Note action.
- **The data is good enough to *forecast* a full calendar, but not to *prove* one.** It has 1,352 exact-date ordinary-visit candidates, raw thresholds and answer values for all 17 affinity-based links, stat thresholds, and 108 claimed locked slots. Still, 473 of 602 day/evening slots have unknown free/locked status, including all 62 slots in January.

## 1. File-by-file inventory

Every file in `rules/p3r`, with its schema, record counts and how confidence is recorded:

| File | Purpose | Key schema / counts | Confidence fields |
|---|---|---|---|
| `social_links.json` | Catalog of the 22 links | `entities[22]{id,arcana_code,name_zh_cn,availability{mode,iso_weekdays,time_slot,holiday_policy,school_link},unlock{earliest_month_day_claim,all_of[]},rank_transition{mode,executable}}`, `global_guards[3]` | `evidence_level`, `status`, `conflicts[]` |
| `social_goal_identity_labels.json` | Labels for the link selector | `entities[22]{personNameZhHans,gender,entityKind,selectorLabelZhHans}` | `nameEvidenceStatus`, `officialGameScreenshotIndependentlyVerified=false` |
| `reported_rank_points.json` | Raw aqiu point table | `rows[175]{sourceSceneRankLabel,branch,reportedOptimalSceneGain,reportedPointsToNextRank,options[]}` | all `source_observed_claim`, `numericExecutionEnabled=false` |
| `manual_link_models.json` | Normalized point models for 17 links | 153 `incoming_thresholds`, 168 `rank_events`, 897 answer values | `status`, `source_claims`, `sourceOptionOrderVerifiedAgainstSimplifiedChineseGame=false` |
| `magician_base_model.json`, `chariot_base_model.json`, `other_manual_link_base_models_candidate.json` (9 links, 367 answer rows, 88 fixtures), `romance_base_models_candidate.json` (6 links, 64 fixtures), `romance_rank_rules_candidate.json` (65 scenes, 59 thresholds, 121 ordinal rows, 10 romance flags) | Per-link base models that feed the normalized file | `readiness_truth_table`, `state_contract`, `normal_visit_contract` | `conflicts[]`, `excluded_source_tables` |
| `affinity_model_evidence.json`, `ordinary_hangout_rules_candidate.json`, `ordinary_affinity_numeric_followup.json`, `affinity_actions_candidate.json` | Multipliers, ordinary-meeting gain, Dating Note | `claims[8]`, `conflicts[3]`, `decision{}` | `status: unresolved / single_source...` |
| `calendar_full_candidate.json` | Source calendar matrix | `cells[5434]`, `auxiliaryCells[3432]`, `rawRows[572]`, `weeklyWikiClaims[19]` | `verificationStatus`, `availabilityClaim`, `available=null` |
| `calendar_execution_contract.json` / `_extension.json` | Guarded ordinary-visit candidate dates | `minimalTwoLinkCandidateDates[143]`; extension `records[1352]` over 19 links | `status`, `requiredGuardSet` |
| `calendar_global_constraints_candidate.json` | Locks, exams, full moons | `explicitActionLockClaims[12]`, `examPeriods[4]`, `fullMoonStoryEvents[8]`, `eventContextRanges[13]`, `schoolClosureWindowCandidates[6]`, `specialExceptions[8]`, `allNegativeSocialRows[197]` | `planningEnabled=false` |
| `global_slot_locks_expansion_candidate.json` | Day/evening overlay for 301 dates | `dateSlotCoverage[602]`, `newWholeSlotLockClaims[16]`, `dungeonEntryOnlyRestrictions[20]`, `conflicts[5]` | `isFree` / `isBlocked` left null when unknown |
| `calendar_january_slice.json` | Earliest playable slice | 58 cells (Sun and Devil, Jan 2–30) plus 2 exploration nights | — |
| `early_link_calendar_witnesses.json` | Visit witnesses for Magician and Chariot | `records[19]` | — |
| `stats_actions.json` | Stat thresholds and 5 night actions | `statRules[3]`, `actions[5]`, `excludedActions[2]` | `evidenceStatus` |
| `stat_activity_expansion_candidate.json` | 12 more stat actions | `actions[12]`, 2 menu-unlock rules | `planningStatus` |
| `reported_regular_activities.json` | Raw aqiu activity table | `activities[22]` | `planningEnabled=false` |
| `stat_lower_bound_adapter_contract.json`, `conservative_visible_state_defaults.json` | Policy contracts | — | — |
| `jobs_wage_rules_candidate.json` | 3 part-time jobs | 12 wage tiers, 1 guarded action | `exactWageExecutionEnabled=false` |
| `tower_stages.json`, `stage_identification_contract.json`, `tower_boundary_semantics_evidence.json`, `tartarus_entry_availability_supplement.json`, `rescues_january.json` | Tartarus | 11 stages; 16 positive night witnesses; 20 closed nights; 1 rescue event | `verificationStatus` |
| `simple_unlock_rules_candidate.json`, `multistep_unlock_rules_candidate.json`, `unlock_compound_alternatives.json` | Unlock procedures for 19 manual links | 11 simple, 7 multistep, 1 Sun compound | `conflicts[4]` |
| `story_link_transitions_candidate.json` | Fool, Death, Judgement | `transitions[25]` | `conditional_on_trusted_milestone_observation` |
| `observation_gated_rank_actions.json`, `observation_meeting_policy.json`, `playable_actions.json` | Adaptive next actions | 17 / — / 3 | — |
| `guide_dialogue_reference_map.json` | Chinese guide text bound to rank events | `references[79]`, `unbound[19]`, `reviewLedger[691]` | `pointBindingEnabled=false` |
| `missing_rules_ledger.json`, `coverage.json`, `full_horizon_forecast_policy.json`, `reversal_repair_research_candidate.json` | Gaps and policy | 12 ledger items, 7 critical gaps | — |
| `validation*.json`, `test_fixtures.json`, `*.py` | Integrity checks only | all `allPassed: true` | They say outright that they are not a game replay |

## 2. (a) Social links: `social_links.json` plus identity labels

In the table below:

- **Weekdays** use ISO numbering (1 = Mon).
- **Slot** is D = daytime/after school, E = evening.
- **Hol.** is the holiday rule: no = forbidden on holidays, wk = allowed on normal weekdays, any = any weekday, all = all weekdays after unlock.
- **O/D** is the count of ordinary (O) and invitation (D) cells in `calendar_full_candidate.json`.
- English names come only from the megatenwiki URLs. **No file has an English name field.**

| # | Arcana | CN name (gender) | EN | Weekdays / slot | Hol. | Earliest | Unlock requirements (`all_of`) | O/D |
|---|---|---|---|---|---|---|---|---|
| 0 | 愚者 | 特别课外活动部 (mixed group) | SEES | story | – | 04-18 | `story.04_18.completed` | – |
| 1 | 魔术师 | 友近健二 (M) | Kenji Tomochika | 2,4,5 D | no | 04-22 | forced introduction | 64/11 |
| 2 | 女教皇 | 山岸风花 (F) | Fuuka Yamagishi | 1,5,6 D | no | 06-19 | Fortune ≥ 1, Courage 6, invitation | 51/7 |
| 3 | 女皇 | 桐条美鹤 (F) | Mitsuru Kirijo | 2,3,4,6 D, plus Mon/Fri in Nov–Dec, exam-exempt | no | 11-21 | Academics 6, invitation (exam-first gate disputed) | 30/3 |
| 4 | 皇帝 | 小田桐秀利 (M) | Hidetoshi Odagiri | 1,3,5 D | no | 04-27 | teacher permission, joined student council | 54/0 |
| 5 | 教皇 | 北村文吉、光子 (pair) | Bunkichi & Mitsuko | 2–7 D | wk | 04-25 | introduction, persimmon leaf; rescue gate 09-12 to 10-03 | 185/0 |
| 6 | 恋爱 | 岳羽由加莉 (F) | Yukari Takeba | 1,3,4,6 D | no | 07-25 | Charm 6, invitation | 50/8 (+1 empty cell) |
| 7 | 战车 | 宫本一志 (M) | Kazushi Miyamoto | 1,2,4,5 D | no | 04-23 | joined track club (forced by 05-28) | 81/10 |
| 8 | 正义 | 伏见千寻 (F) | Chihiro Fushimi | 2,4,6 D | no | 05-07 | Emperor ≥ 1, 4 conversations on separate dates | 63/11 |
| 9 | 隐者 | Y子 (F, online identity) | Y-ko | 7 D | any | 04-29 | started the online game | 46/0 |
| 10 | 命运 | 平贺庆介 (M) | Keisuke Hiraga | 2,3,4 D | no | 06-17 | joined art club | 41/7 |
| 11 | 力量 | 西胁结子 (F) | Yuko Nishiwaki | 3,6 D | no | 04-24 | Chariot ≥ 2, walked home twice | 43/9 |
| 12 | 倒悬者 | 大桥舞子 (F) | Maiko Oohashi | 1,3,6 D | wk | 05-06 | food and drink given, returned later; rescue gate 10-21 to 11-02 | 89/0 |
| 13 | 死神 | 法洛斯 (M) | Pharos | story | – | 06-12 | story | – |
| 14 | 节制 | 贝贝 (M) | Bebe | 2,3,5 D | no | 05-08 | Hierophant ≥ 3, Academics 2, visited the club | 56/9 |
| 15 | 恶魔 | 田中社长 (M) | President Tanaka | 2,6 E | wk | 05-16 | Hermit ≥ 4 (aqiu says 3), Charm 4, three payments of ¥20k/¥10k/¥10k | 61/0 |
| 16 | 塔 | 无达 (M) | Mutatsu | 4,5,6,7 E | wk | 05-23 | Strength ≥ 4, Courage 3 (aqiu and game8 say 4), rumor, drink orders | 126/0 |
| 17 | 星 | 早濑护 (M) | Mamoru Hayase | 3,5,7 D | wk | 08-05 | Courage 4, invitation | 61/0 |
| 18 | 月亮 | 末光望美 (M) | Nozomi Suemitsu | 1–7 D | all | 04-28 | Magician ≥ 3, Charm 2, rumor, quiz, mystery food | 215/0 |
| 19 | 太阳 | 神木秋成 (M) | Akinari Kamiki | 7 D | Sundays only | 08-09 (aqiu says 08-23) | Hanged Man ≥ 3, Academics 4, the red-pen chain | 23/0 |
| 20 | 审判 | 伙伴们 (mixed group) | Judgement | Tartarus | – | 12-31 | true path chosen | – |
| XX | 永劫 | 埃癸斯 (F) | Aigis | 1–6 D, not 01-25 | no | 01-08 | walked home together | 18/0 |

How ranks advance:

- **Devil and Sun** use `guaranteed_rank_increment` and are the only executable transitions.
- **Fool, Death and Judgement** use `story_or_tartarus`. Their 25 transitions are in `story_link_transitions_candidate.json` and are gated on milestone flags:
  - Death ranks: 06-12, 07-12, 08-07, 09-12, 10-06, then max on 11-04.
  - Judgement ranks: Tartarus Adamah floor milestones (rank 8 at floor 253).
- **The other 17** use `hidden_affinity`.

Weekday claims match the O cells in the matrix. The one exception is a single Magician O on a Wednesday (04-22, the forced introduction). Hermit's O cells on Mon–Wed fall on holidays: 04-29, 05-04/05, 09-21–23, 10-12, 11-23, 12-23 and 01-11.

## 3. (b) Affinity: thresholds, answer points, multipliers

**Incoming thresholds** from `manual_link_models.json`. The value at "n→n+1" is the raw points needed before the n→n+1 rank event:

| Link | 1→2 | 2→3 | 3→4 | 4→5 | 5→6 | 6→7 | 7→8 | 8→9 | 9→10 |
|---|---|---|---|---|---|---|---|---|---|
| magician, chariot | 0 | 0 | 15 | 15 | 15 | 15 | 22 | 22 | 22 |
| priestess | 0 | 0 | 15 | 22 | 30 | 30 | 35 | 40 | 55 |
| empress | 0 | 0 | 22 | 22 | 22 | 22 | 22 | 22 | 22 |
| lovers | 0 | 0 | 15 | 22 | **0 vs 22** | 35 | 35 | 40 | 55 |
| justice | 0 | 0 | 22 | 22 | 22 | 30 | 30 | 30 | 40 |
| strength | 0 | 0 | 15 | 15 | 22 | 22 | 35 | 40 | 40 |
| aeon | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 30 |
| emperor | 0 | 0 | 0 | 15 | 15 | 15 | 15 | 22 | 22 |
| hierophant | 0 | 0 | 20 | 20 | 20 | 20 | 20 | 20 | 30 |
| hermit | 0 | 0 | 20 | 20 | 20 | 20 | 20 | 20 | 20 |
| fortune | 0 | 0 | 15 | 15 | 15 | 15 | 15 | 22 | 22 |
| hanged_man | 0 | 0 | 20 | 20 | 20 | 30 | 15 | 30 | 30 |
| temperance | 0 | 0 | 15 | 15 | 15 | 15 | 10 | 22 | 22 |
| tower | 0 | 0 | 25 | 25 | 25 | 30 | 30 | 40 | 40 |
| star | 0 | 0 | 20 | 20 | 20 | 20 | 30 | 30 | 30 |
| moon | 0 | 0 | 15 | 15 | 22 | 22 | 22 | 22 | 15 |

**Optimal base gain per rank event (to-rank: points).** f = friendship branch, r = romance branch.

- magician: 2:10 3:20 4:15 5:15 6:15 7:15 8:20 9:15 10:20
- chariot: 2:5 3:10 4:15 5:20 6:15 7:20 8:5 9:15 10:20
- priestess: 2:30 3:30 4:20 5:35 6:30 7:5 8:10 9:15 10f:15 10r:30
- empress: 2:5 3:30 4:20 5:45 6:25 7:15 8:35 9:15 10:15
- lovers: 2:20 3:0 4:15 5:15 6:20 7:15 8:15 9:15 10:15
- justice: 2:25 3:10 4:15 5:50 6:20 7:10 8:5 9:15 10f:10 10r:15
- strength: 2:35 3:10 4:5 5:15 6:10 7:20 8:5 9:20 10f:30 10r:20/15
- aeon: 2:15 3:5 4:15 5:15 6:15 7:0 8:15 9:15 10:0
- emperor: 2:15 3:5 4–9:15 10:30
- hierophant: 2–4:20 5:10 6:15 7:15 8:20 9:5 10:15
- hermit: 2:20 3:20 4:15 5:10 6:15 7:20 8:20 9:10 10:25
- fortune: 2:0 3–9:15 (4:20) 10:30
- hanged_man: 2–4:15 5:20 6:20 7:0 8:35 9:15 10:30
- temperance: 2:20 3:20 4:15 5:10 6:25 7–9:15 10:30
- tower: 2:20 3:15 4:15 5:20 6:20 7:15 8:25 9:15 10:15
- star: 2:20 3:10 4:15 5:10 6:15 7:25 8:20 9:20 10:15
- moon: 2–8:15 9:0 10:15

Semantics (`affinity_model_evidence.json` claim `event_points_next_rank`, which is corroborated): the points earned in the Rank-n scene count toward n→n+1. The 897 answer values are stored as `source_option_ordinal` in English source order with `localized_text: null` and `localizedPointBindingEnabled: false`.

**Multipliers** (`claims`, `cross_source_agreement`):

- matching-arcana Persona: ×1.51
- Charm rank 6: ×1.51
- exam, first in class: ×1.51; top ten: ×1.21 (these two do not combine)
- bonuses multiply together
- Rounding (per answer versus per event) and the reset timing are **unresolved**. Reset rests on a single Yahoo Chiebukuro answer.

**Ordinary meeting:**

- Raw gain is `null`. The +10 candidate comes from one Reload playthrough post (`lpbeach.co.uk/viewtopic.php?t=1712`, confidence low_to_medium).
- The claimed +15 with a matching Persona is from the same source.
- The Persona-4 "+10" passage and the Portable guide are explicitly rejected.
- `full_horizon_forecast_policy.json.ordinaryMeetingModel` turns this into an estimate: `ceil(deficit/10)+1` meetings.

**Dating Site Note** (`affinity_actions_candidate.json`): +10 lower bound, costs ¥3000 and one evening, on sale 11/11 (atwiki says 11/16; the conservative start is 11/16).

**Derived estimate (my calculation, not in the data).** Assume optimal answers, no bonuses, the +10 ordinary model, and that surplus resets at each rank-up. The visits needed for ranks 2–10 (excluding the unlock visit) are:

| Links | Visits |
|---|---|
| magician, empress, temperance | 12 |
| chariot | 14 |
| priestess, tower | 20 |
| lovers | 24 |
| justice | 21 |
| strength | 22 |
| aeon, emperor, fortune | 11 |
| hierophant, moon | 15 |
| hermit | 13 |
| hanged man, star | 16 |
| sun, devil | 9 |

The daytime links alone need about 254 visits plus 17 unlocks. Only about 220 daytime slots are usable, so **maxing every link is not feasible without matching-Persona and Charm-6 bonuses**. With ×1.51 the total falls to roughly 193 + 17. The planner must therefore model "carrying a matching Persona" as something the user declares. It must not be assumed silently, per the handoff.

## 4. (c) Calendar: `calendar_full_candidate.json` and the overlays

- **Date axis:** epoch 2009-04-01 = ordinal 0, with `neverUseDeviceYear`.
- **Matrix window:** 04-21 (ordinal 20) to 01-31 (ordinal 305), 286 days, from `aqiu_calendar` (snapshot sha `404dea95…`).
  - 19 link columns: 17 daytime (Ma, Pr, Em, Em, Hi, Lo, Ch, Ju, He, Fo, St, Ha, Te, St, Mo, Su, Ae) and 2 evening (De, To).
  - Symbol counts: `-` 4001, `O` 1357, `D` 75, `""` 1 (Lovers 01-05).
  - `available` is always null by design.
  - 8 recorded uncertainties, including the 04-22 forced Magician, Justice O cells before the 05-07 unlock, and Lovers invitations 08-03/08-09 that appear only in aqiu's dedicated invitation table (a test there is marked `expectedFailure`).
- **Days before the matrix (04-01 to 04-20)** are covered only by `preMatrixTutorialBaseline` in `calendar_execution_contract.json`. It treats them as required story progression.
- **Auxiliary columns** (3,432 cells, Reload-specific, *stored but not interpreted*):
  - Linked Episodes: Junpei, Koromaru, Shinjiro, Ryoji (daytime); Akihiko, Ken (evening)
  - dorm activities: Gardening, Cooking, DVD, Reading
  - Koromaru walk
  - Tartarus party roster ("Everyone" 81 nights, "-" 163)
- **Slot overlay** (`global_slot_locks_expansion_candidate.json`): 301 dates × 2 slots = 602. Of these, 108 are claimed locked, 21 have conditional positive witnesses, and **473 are unknown**. Unknown slots by month: Apr 25, May 49, Jun 58, Jul 50, Aug 45, Sep 52, Oct 50, Nov 41, Dec 41, **Jan 62/62**.

**Whole-slot lock claims** (all `planningEnabled: false`):

- Prologue 04-07–09, and no-action days 04-10–17 and 04-19.
- 04-22 daytime (Magician forced).
- Exam cores: 05-18–22, 07-14–17, 10-13–16, 12-14–18. The final days (05-23, 07-18, 10-17, 12-19) have positive witnesses and are not locked.
- Summer classes 08-10–14; typhoon and illness 09-18–20; Kyoto trip 11-17–19; work experience 11-24–26.
- Full-moon operations 05-09, 06-08, 07-07, 08-06, 09-05, 10-04, 11-03. The 04-09 operation falls inside the prologue.
- Daytime only: 10-05, 11-05, 12-03. Festival 08-16 (whole day).

**Conditional:**

- 12-24 is free if every invitation is declined; an accepted date consumes both slots.
- 01-21 Junpei event (white flowers).
- 01-25 Aigis is unavailable, but this is not a global lock.
- 01-31 evening is the final operation.

**Tartarus-only closures** (20 evenings): 07-11, 07-12, 07-19, 07-23, 07-29, 08-02, 08-07, 08-15, 11-20, 11-22, 11-30, 12-02 through 12-09, and 12-13.

**Not locked even though the story occupies the day:**

- Yakushima 07-20–23. The SL columns show all `-` for 07-20–22, and only 07-23 evening has a positive witness.
- Track-club training 07-27–08-01 and the meet on 08-02.
- 05-01, 06-20, 09-02, 11-04, 11-09 (Ryoji), 01-01.

**School-link closure patterns** (inferred from the matrix, unverified):

- pre-exam: 05-11–17, 07-08–13, 10-07–12, 12-08–13
- summer: 07-26–08-31
- winter: 12-27–01-07

**Holidays: no explicit list exists anywhere.** They are implicit only in Hermit O cells and D cells. D cells fall on 34 dates: 04-26, 05-04, 05-05, 05-24, 05-31, the Sundays in Jun–Jul, 08-04/05/07/09/26/27, 09-13/22/23/27, 10-18/25, 11-08/15/29, and 01-04/05/06/07/10/11/17/24.

## 5. (d) Social stats: `stats_actions.json` and `stat_activity_expansion_candidate.json`

**Thresholds** (status `corroborated_community_claim`; sources atwiki 469, GameFAQs 81170 and 81159):

| Stat | Rank thresholds (ranks 1–6) |
|---|---|
| 学力 Academics | 0 / 20 / 55 / 100 / 155 / 230 |
| 魅力 Charm | 0 / 15 / 30 / 45 / 70 / 100 |
| 勇气 Courage | 0 / 15 / 30 / 45 / 60 / 80 |

`rankLabelsZhHans` is null (`not_imported_no_localization_evidence`), so there are no Chinese rank titles.

**Enabled night actions:**

| Action | Days | Cost | Gain |
|---|---|---|---|
| Study in own room | daily | free | Academics +2 |
| Chagall Pheromone Coffee | daily | ¥500 | Charm +2 |
| Arcade "你就是答案" | Wed/Sat | ¥3000 | Academics +4 |
| Arcade "青春高校" | Mon/Thu | ¥1500 | Charm +4 |
| Arcade "死亡之怖" | Tue/Fri | ¥3000 | Courage +4 |

All five require a free slot, a reachable location, and `temperanceStatBonusActive == false`.

**Expansion** (12 actions):

| Action | Slots / days | Cost | Gain |
|---|---|---|---|
| Wakatsu regular | day+night; Mon, Wed–Sun | ¥680 | Academics +3 |
| Wakatsu secret | night; Mon, Thu, Fri, Sun | ¥900 | Academics +4 |
| Hagakure regular | Mon–Fri | ¥900 | Charm +3 |
| Hagakure secret | night; Tue, Wed, Fri | ¥1200 | Charm +4 |
| Wild Duck regular | not Thu | ¥1000 | Courage +3 |
| Wild Duck weekend set | night; Sat–Sun | ¥1200 | Courage +4 |
| Karaoke | Mon–Sat | ¥800 | Courage +2 |
| Movies (Academics) | day; Wed/Sat | ¥1500 | Academics +4 |
| Movies (Charm) | day; Tue/Fri | ¥1500 | Charm +4 |
| Movies (Courage) | day; Mon/Thu | ¥1500 | Courage +4 |
| Chagall coffee | day; Wed–Sun | ¥500 | Charm +2 |
| Big Bang burger challenge (one use) | night | ¥1800 | +4 to all three |

Secret menus need an observed menu flag.

**Excluded:**

- Library study: +2 according to wiki469 and gf81159, +4 according to gf81170 and aqiu. Not settled by majority vote.
- Chagall job (handled in §6).
- Shrine donations (+2/+3/+4 Academics for ¥100/1000/10000) appear in `reported_regular_activities.json` with `planningEnabled: false`.
- Desk study on Sunday daytime (aqiu "B") was not imported.

## 6. (e) Jobs: `jobs_wage_rules_candidate.json`

| Job | Weekdays / slot | Stats (this file) | Pay tiers by shift count (normal/busy; with software 4300–13000) |
|---|---|---|---|
| Chagall Café | Mon–Wed, evening (Tue busy) | Charm +1, Courage +1 | 1–4: 2500/3500; 5–14: 3000/4000; 15–29: 3500/4500; 30+: 5000/6000 |
| Be Blue V | Mon–Fri, daytime (Fri busy) | Academics +1, Charm +1 | 1–2: 3500; 3–5: 4500; 6–9: 5500; 10+: 7000 |
| Screen Shot | Sun, daytime | Academics +1, Courage +1 | 1–2: 5000; 3–5: 6000; 6–9: 7000; 10+: 9000 |

- Only `p3r.action.job_chagall_minimum` is enabled: +¥2500 lower bound, actual pay reconciled afterwards.
- Unresolved conflicts:
  - Be Blue V daytime only (wiki469) or day or night (megatenwiki).
  - Be Blue V unlock 07-23 (wiki469) or 07-22 (siliconera).
  - Stat gain +1 (wiki) or +2 (siliconera).
  - Chagall earliest date unknown.
- **Cross-file conflict:** aqiu (`reported_regular_activities.json`) has Be Blue V = Academics +1 / **Courage** +1 and Screen Shot = Academics +1 / **Charm** +1. That is the reverse of this file.

## 7. (f) Tartarus: `tower_stages.json` and the identification contract

| Stage | Floors | Nominal window (`tower_stages`) | Operational window (`stage_identification_contract`) |
|---|---|---|---|
| 01 | 2–22 | 04-21 to 05-10 | same |
| 02 | 23–43 | 05-10 to ? | to 06-13 |
| 03 | 44–69 | ? to 07-09 | 06-14 to 07-09 |
| 04 | 70–92 | 07-09 to 08-08 | **07-18** to 08-08 |
| 05 | 93–118 | 08-08 to 09-10 | same |
| 06 | 119–144 | 09-10 to 10-06 | same |
| 07 | 145–172 | 10-06 to 11-06 | same |
| 08 | 173–198 | 11-06 to ? | to 12-03 |
| 09 | 199–226 | ? to 01-01 | **12-10** to 01-01 |
| 10 | 227–256 | 01-01 to 01-31 | same; the only stage with `planningEnabled` |
| 11 | 257+ | final operation | incomplete |

- The gaps 06-13, 07-09–17 and 12-03–09 are ambiguous, with the policy "require observed block".
- Entry tiers: observed, then source-positive, then routine conditional. There are 16 positive night witnesses.
- No cash or floor results are forecast. Returns record net or absolute cash plus highest floor, and each visit is recorded once.
- Rescues: only the January pair (spawn 01-15, deadline 01-30, floors 232/250). The Hierophant and Hanged Man rescue gates live in `social_links.json`.

## 8. (g) `missing_rules_ledger.json` (12 items)

1. `ordinary_hangout_raw_gain`: researched, not established.
2. `modifier_rounding`: conflicting.
3. `manual_unlock_actions`: adapter pending.
4. `story_links`: adapter pending.
5. `chinese_answer_point_binding`: game evidence not collected.
6. `tower_boundaries`: physical flags not observed.
7. `full_global_slot_locks`: 473 slots unknown.
8. `job_exact_wages`: not enabled.
9. `additional_stat_activities`: adapter pending.
10. `holiday_invitation_actions`: 75 D cells not modeled.
11. `reverse_repair_actions`: exact procedure unknown.
12. `tower_income`: intentionally observed only, never forecast.

`coverage.json` lists 7 `criticalGaps`. The ledger misses Linked Episodes, dorm activities, class answers and exams, rescues outside January, holidays, and stat rank labels.

## 9. Source URLs: 82 distinct URLs across 26 domains

Counts are occurrences across the JSON, Markdown and Python files.

| Domain | Occurrences | Distinct URLs | Notes |
|---|---|---|---|
| w.atwiki.jp | 610 | 7 | pages 453 (22 files), 455 (17), 420 (11), 469, 451, 425, 36 (excluded) |
| aqiu384.github.io | 238 | 2 | social-events, overworld; same lineage as marendarade, not independent |
| megatenwiki.com | 65 | 28 | 19 Gameplay pages, Social_Link, Tartarus, shrine, shops, Reload calendar, translations |
| gamefaqs.gamespot.com | 35 | 11 | FAQs 81170 (marendarade), 81375, 81117, 81261, 81159 |
| game8.co | 24 | 8 | monthly walkthrough pages |
| jusgameguide.wordpress.com | 8 | 1 | |
| game8.jp | 5 | 2 | |
| playstationtrophies.org | 4 | 2 | |
| chiebukuro.yahoo.co.jp | 3 | 1 | |
| appmedia.jp | 3 | 2 | |
| neoseeker.com | 3 | 3 | |
| gorillawiki.jp | 2 | 1 | excluded |
| siliconera, lpbeach, primagames | 2 each | 1 each | |
| kadokawa, bookwalker, steamcommunity, youtube, samurai-gamers, asia.sega.com (official CN), wanuxi, tgbus, bilibili, zh.wikipedia, powerpyx | 1 each | 1 each | |

Almost all numeric facts trace back to two community lineages: atwiki (Japanese) and aqiu/marendarade. These are not independent of each other. Nothing is official apart from the CN site, which is used for names only.

## 10. Possible P3 FES/Portable contamination

- **Already caught and excluded (good):**
  - gorillawiki entry/103 (214F, Vision Quest)
  - FES July calendar and old Wiki299 (64F instead of 69F)
  - atwiki page 36 (old broken-link mechanics)
  - samurai-gamers (neglect and jealousy mechanics)
  - P3P tables: Emperor Rank 6 female protagonist, Hermit R7, Fortune R2, Hanged Man R3 Portable, Hanged Man R9 both protagonists
  - Persona-4 "+10" and the Portable reset guide
  - wiki469 fatigue and cold rows; "old game fatigue" rule for Tartarus
- **Residual risks to verify:**
  1. **Lovers rank events use the `Rank_Up_Events_3` anchor**, which is not labeled P3R; the other romance links use `Rank_Up_Events_(P3R)`. The 5→6 threshold is 0 on the wiki and 22 in aqiu.
  2. Many non-romance Gameplay pages share "unchanged male dialogue across Portable and Reload". The Magician and Chariot pages cite both the Reload and Portable official guides. Thresholds were cross-checked with aqiu, but per-answer values for shared captions were not checked against Reload.
  3. The stat thresholds are identical to FES/P3P values. They come from wiki469, which has known legacy rows. Confirm with a separate Reload-only page.
  4. The job system (Chagall, Be Blue V, Screen Shot), its wage tiers and "software" multipliers originate in P3P. Confirm each against Reload, especially the stat gains (which conflict) and the unlock dates.
  5. Shrine +10 comes from a megatenwiki paragraph that mixes editions.
  6. Bunkichi disappearance: 09-09 in a combined Portable/Reload paragraph, against 09-12 in Reload sources. The data keeps 09-12.
  7. Pharos dates come from a table shared across all editions. Reload sources match it.
  8. megatenwiki's Reload calendar gives wrong full-moon dates (09-04 and 11-02). It is excluded.
  9. **I found no data for the female protagonist route** (Rio, Saori, the male SEES links). There are no "Kaiyo" strings anywhere. Tanaka's Tue/Sat evenings agree across wiki455 and aqiu.

## 11. Inconsistencies between files (the data itself is wrong in places)

1. **Missing aqiu points become 0.** `manual_link_models.json` records aqiu as 0 for Aeon 3→9 and Emperor 3→4. `reported_rank_points.json` has null with `missingPointValue: "unknown_not_zero"` for the same rows. The normalization breaks its own rule.
2. **Job stat gains are swapped** between the two files (§6).
3. **Tower stage dates:** `tower_stages.json` disagrees with the identification contract (07-09 vs 07-18 and 12-03 vs 12-10). The contract says it overrides the stage file.
4. **Sun earliest date:** the aqiu matrix has O from 08-09, but aqiu's overview says 08-23. The multistep rules take 08-23 as the conservative date.
5. **Emperor:** a blanket absence for 11-03 to 11-29, but 11-16 has an O and a rank witness.
6. **Unlock dates disagree with the matrix:** Justice has O from 04-28 against an unlock of 05-07; Strength has O from 04-25 against 04-24.
7. **Disputed gates:** Devil needs Hermit 3 or 4; Tower needs Courage 3 or 4; Empress may or may not need first place in exams.
8. **Dating Note sale date:** 11/11 or 11/16.

## 12. Data gaps for a planner that covers the full calendar and every link

**P0: blocks a useful, believable full-horizon plan**

1. **Free/locked status for the 473 unknown slots.** Use a Reload day-by-day free-time source (GameFAQs 81375, Game8 monthly pages, Wiki420) to classify every day and evening. Priority dates:
   - all of January
   - 04-18, 04-20, 04-22 evening
   - Yakushima 07-20–23
   - track training 07-27–08-02
   - 10-30–11-02
   - 12-24–12-31
   - 01-01–03
2. **An explicit holiday and school-break table.** It drives school links, Hermit, invitations and jobs.
3. **Linked Episodes, dorm activities (gardening, cooking, DVD, reading) and the Koromaru walk.** These are Reload-only and compete for slots. Some give stats. Only raw aux columns exist today.
4. **Class answers (69 guide items) and exams (17 items).** Effects on Charm, exam rank against the Academics threshold, and the exam multiplier windows.
5. **Holiday invitations (75 D cells).** Eligibility, slot cost, affinity effect, and whether each is distinct from a rank event.
6. **Ordinary-meeting raw gain.** The +10 is single-source. Corroborate it, or make the plan rely on observed rank-ups.
7. **Rescues for the whole year.** Spawn dates, deadlines and floors. Only January exists, plus the Hierophant and Hanged Man gates.
8. **The two cross-file bugs in §11 items 1 and 2.**

**P1: correctness and clarity**

9. Chinese stat rank titles.
10. Chinese names for jobs and activities (Be Blue V and Screen Shot are `pending_exact_CN_baseline`).
11. Chinese choice binding: 19 references unbound, 0 bound to points.
12. Reversal and repair procedure.
13. Night availability exceptions per activity (closures, movie schedule).
14. Daytime desk study on Sundays; settle the library value.
15. Temperance stat buff and its duration.
16. Tartarus party roster per night (aux column), and floor progress per visit if visits are ever forecast.
17. Rounding and reset rules for multipliers. This is only needed if bonus mode becomes exact.

**P2: housekeeping**

18. Collapse the four rule packs into one (24 MB of duplicates).
19. Ship `full_horizon_forecast_policy.json` or move it into code.
20. Add English names and the official CN press links into `social_links.json`.

## 13. Input and deviation handling (from the user's UI/UX question)

What the data implies about making input easy and recovering when the player goes off-plan:

- **Derive what you can from the date.**
  - The stage and block come from `stage_identification_contract`.
  - Rescue questions only appear inside 09-12–10-03 (Hierophant) or 10-21–11-02 (Hanged Man).
  - Show only links whose `earliest_month_day_claim` is on or before the current date.
  - Ask romance/friendship only for the 6 romanceable links, and only at or above their flag rank.
  - Keep the date picker in-game (month and day, weekday computed). Never use the device year.
- **Visible values only.**
  - Stats as 1–6 rank chips. Store them as `[threshold, next−1]` intervals per `conservative_visible_state_defaults`.
  - Links as a 0–10 stepper with a separate "reversed" toggle.
  - Money as one number.
  - One switch, "通常携带对应人格面具" (usually carry the matching Persona), per link group. This is the input that makes maxing every link feasible (§3).
- **Result entry per action, one tap, with forecast as the default:**
  - **Link visit:** "升级了 (→n+1)", "普通会面（没升级）", or "没去/做了别的". An ordinary meeting sets readiness to observed-unknown, per `observation_meeting_policy` branches.
  - **Rank event:** answers default to "按推荐选项" (follow the recommended choices), with "选了别的" (chose something else) as a fallback. Never show foreign option numbers.
  - **Stat action:** a single confirm, plus "学力等级升了吗?" (did Academics rank up?) only when the forecast crosses a threshold.
  - **Tartarus:** highest floor plus *either* "带回金额" (money brought back) *or* "现在余额" (current balance), exclusive radio. Record the visit once.
  - **Holiday with D cells:** "收到谁的邀请?" (who invited you?) lists only that date's D candidates, plus "都拒绝" (declined all).
- **Coming back after days or weeks off-plan:**
  - Offer "按计划完成了 (Mon–Thu)" (completed per plan) with an exceptions list. Only actions the user confirms become observed facts; anything else stays forecast.
  - Then show only the *differences* the plan predicted (links that should have ranked, the predicted money range) as editable rows prefilled with the forecast.
  - Everything else keeps its saved value. Rebase from the new checkpoint and never replay history (`coverage.json.rebase`).
- **Mark estimates calmly.** Use the shipped `briefUiZh` "预估日程 · 已预留余量" (estimated schedule, with buffer) once per plan, not on every line. Explain the +10 assumption and the unknown slots in the detail screen.

Rule files referenced: /Users/xihehan/P3R_Widget/rules/p3r/*.json (identical to /Users/xihehan/P3R_Widget/android/app/src/main/assets/planner_v2/rules_v051/). Runtime roots: /Users/xihehan/P3R_Widget/android/app/src/main/java/app/p3r/dayplanner/v2/{PlannerRules,NativeExpandedRules,NativeManualRules,NativeUnlockRules,NativeProgressionRules,GoalPlannerHomeActivity}.java.