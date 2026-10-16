# P3R planner repository context

Reference for continuing the owner's existing project with Claude. Updated on 2026-10-16
(UTC), for the 3.2.0 changes and dialogue completion on branch `work`.
This is an orientation snapshot; check newer code and owner decisions before implementing changes.

## Product and current baseline

- **P3R 规划** is a native, offline Android app and home-screen widget for the base game
  Persona 3 Reload. Episode Aigis/Astrea is outside its ruleset.
- The player enters visible game progress and chooses ordered Social Link goals. The app
  simulates and schedules the remaining calendar through January 31, then adapts when the
  player records a different result, changes goals, or returns later and syncs progress.
- Useful, consistent plans matter more than global optimality. An unmet goal is not proof
  that the goal is impossible. Forecasts and confirmed progress must remain separate.
- Current app source: **3.2.0**, version code **36**, application ID **`app.p3r.planner`**;
  debug builds add `.debug`. Java package/Android namespace remains `app.p3r.dayplanner`.
  Java 17, Android Gradle Plugin 8.7.3, Gradle 8.9, minimum API 26, compile/target API 35.
- Player-facing language is natural Simplified Chinese using Reload terminology. Preserve
  the established navy/cyan P3R presentation, slanted panels, condensed numerals, day/night
  widget palettes and fade, readable answers, and action-specific result sheets.
- The old fixed walkthrough and its widget were removed in 3.1.0 by owner decision.
  3.1.1 added read-only export of old checklist progress; 3.1.2 added the rescue margin.
  Do not interpret the original handoff's legacy-widget preservation request as a request
  to restore the removed feature.

## Documents to read first

| Reference | Purpose |
| --- | --- |
| [README](../README.md) | Current product behavior, installation, screenshots and build entry points. |
| [Design](DESIGN_v3.md) | Main architecture contract. Later numbered sections amend earlier sections. |
| [3.2.0 release notes](RELEASE_NOTES_3.2.0.md) | Rescue batching, stat-gated menus and request tips. |
| [3.1.0 specification](specs/SPEC_3.1.0.md) | Full replan, reminders, legacy removal, widget answers and breadth planning. |
| [Chinese glossary](GLOSSARY_zh.md) | Exact UI terms, sentence style and deadline wording. |
| [Rules report](../rules/build/REPORT.md) | Generated source decisions, confidence, coverage and modeling gaps. |
| [Plan metrics](PLAN_METRICS.md) | Recorded benchmark scenarios and tradeoffs between planning styles. |
| [Project audit](HANDOFF_COMPLIANCE.md) | Requirements, historical audits and owner review; some line references and metrics describe earlier revisions. |

The original v2 handoff prompt, snapshot ZIP and handoff directory were removed at the
owner's request on 2026-10-15. They remain available in Git history. `docs/analysis/`
retains historical research and audits; its references to old handoff files, v2 beam
search and unfinished work describe that earlier revision. Use the current design and
code for implementation decisions.

## Code map

Production Java lives under `android/app/src/main/java/app/p3r/dayplanner/v3/`.

| Area | Entry points and responsibility |
| --- | --- |
| [model](../android/app/src/main/java/app/p3r/dayplanner/v3/model/) | `Rules`, `RulesLoader`, `Profile`: rule-pack parsing and validation, goals and preferences. Production `DefaultWiring` loads the asset through `RulesLoader`; `RulesAssets` is a separate cache helper. |
| [engine](../android/app/src/main/java/app/p3r/dayplanner/v3/engine/) | `Engine` is the public facade. `Model`/`Tables` compile rules into arrays; `Kernel` applies transitions; `Simulator` exposes copy-based operations; `Heuristics`/`Planner` search; `PlanValidator` independently replays plans. Pure Java plus `org.json`. |
| [store](../android/app/src/main/java/app/p3r/dayplanner/v3/store/) | `PlanDb`, `Ledger`, `Replayer`, `PackLayout`, `Backup`: SQLite records, replay, rule-update remapping and backup format. |
| [service](../android/app/src/main/java/app/p3r/dayplanner/v3/service/) | `PlannerApi`/`PlannerService`: shared boundary for app and widget. `CommandCheck` validates writes; `ReplanWorker` computes and validates plans in the background. |
| [ui](../android/app/src/main/java/app/p3r/dayplanner/v3/ui/) | `HomeActivity` and four tab pages; `StateActivity`/`StateDraft` for setup and sync; `ResultActivity`/`Outcomes`/`TowerPanel` for results; `FullReplanSheet`/`PlanDiff` for fresh planning. Native Android Views, largely built in Java. |
| [widget](../android/app/src/main/java/app/p3r/dayplanner/v3/widget/) | `PlanWidget`, `PlanView`, `PlanItems`, `CardRenderer`, `CardText`, `WidgetWorker`: RemoteViews, task browsing, result actions, sizing and background work. `PaletteHost`/`FadeGuard` handle palette transitions. |
| [rules builder](../rules/build/build_p3r_rules.py) | Python standard-library compiler for the shipped JSON asset and its generated report. |
| [tests](../android/app/src/test/java/app/p3r/dayplanner/v3/) | JVM engine/model tests and Robolectric service, SQLite, UI and widget tests. |

The main data flow is: checkpoint plus recorded events → replayed observed state → copied
state and profile → complete forecast plan → independent validation → revision-checked
installation → app and widget. Results return through the service into the event ledger.

## Calendar and planning mechanics

- Dates are game dates, never the device date. Day zero is **2009-04-01**;
  `slot = day * 2 + (evening ? 1 : 0)`. Weekdays use ISO Monday=1.
  The pack covers April 7 through January 31, 300 days. Plans include the January 31
  evening **locked finale** as their last row; January 31 daytime is the last free slot.
- `SimState` carries cash and its certainty, estimated hidden stat/affinity points,
  visible ranks, observed rank-up cues, unlock steps, rescue bits, tower visits/floor,
  job counts, episode progress and player-reported rank holds. The same shape is used
  for observed and forecast states, but engine APIs copy their inputs.
- Productive actions are meetings, consuming unlock steps, activities/jobs, Tartarus,
  dating-site notes and Link Episodes. `FREE` and locked rows complete calendar coverage.
  Non-consuming unlock steps appear as **顺便** alongside the main action; they still
  obey money, availability, prerequisite and minimum-gap rules.
- The planner screens 16 base-policy tunings, adds goal-first tunings when relevant,
  and improves promising complete trajectories with fortified rollout. Default options
  allow four candidates and a 2.5-second soft budget. Budget exhaustion keeps a complete
  incumbent tail, rather than truncating the calendar.
- Goals are compared in strict order: **必须**, then **想要**, each by the player's order.
  Target ranks can be below MAX. `Planner.Pool.compareGoals` and strict-priority reruns
  matter; the weighted objective alone is not the whole selection policy.
- Default style **尽量都体验** rewards unlocking and raising more links, with diminishing
  value for later non-goal ranks. **尽量多 MAX** concentrates on completions. Breadth
  planning also runs the MAX style on a second thread and considers its candidates.
  `keepsPromises` compares unlock count, ranks at least 5, rank sum and wasted daytime;
  it also rejects a breadth route that leaves a link at zero when MAX style completes it.
  Breadth does not promise that every individual metric always increases.
- Daytime is scarce for Social Links; evening is preferred for training, jobs and tower
  visits. Jobs address projected spending needs. Unlock payments normally leave a
  ¥2,500 reserve; the fee policy can relax this when waiting would cost a milestone.
  Optional training and dating notes also reserve imminent unlock fees.
- Normal replans favor keeping the next three days stable. This behavior is deliberate
  and differs from **从头重新安排**, which passes no previous plan and uses a 3× budget.
- `PlanValidator` has its own accumulator and transition checks instead of sharing
  `Kernel`/`Simulator` transitions. Keep that independence when changing mechanics.
  It checks contiguous coverage, legality, affordability, caps and forecast consistency.

## Rescues, estimates and reminders

- Since 3.2.0 all **13 missing-person arrival groups (20 people)** feed `RescueBatches`,
  including people without Social Links. Deadline-ordered window coverage delays a trip when
  later arrivals can share it safely, reserves visit capacity and respects block caps/locks.
  This is independent of the selected Social Link goals. The original two rescue bits still
  mean **北村文吉 / 教皇** and **舞子 / 倒悬者**, not whole-group completion.
- `SimState.plannedRescueGroups` and its late mask are transient, copied forecast context;
  they are never serialized as confirmed progress. Actual tower records carry `lastTowerSlot`
  through checkpoints. A recent actual trip within a group's window is treated as rescue
  coverage, following the reminder convention; it does not set either confirmed Social Link
  rescue bit. The player must still confirm those rescues and check the police/Fuuka list.
- The simulation and validator include the deadline evening in each rescue window. Planning
  keeps the 3.1.2 one-day margin whenever an earlier usable evening/capacity exists. Candidate
  selection compares missed groups, then late rescues, before goals. Only-limit-evening plans
  explicitly say that evening has not been checked in-game.
- Wakatsu and Hagakure specials require a once-only discovery meal (`requiresActivity`) plus
  Charm Lv.3 / Courage Lv.3 respectively. Discovery is a real ordinary meal on a special-menu
  evening, with ordinary cost/gain; learning can precede the stat gate. `used[]` and PackLayout's
  ID remapping carry discoveries across saves. Unknown imported discoveries are re-learned
  conservatively. Weekend Wilduck is excluded because Request 11 completion is not tracked.
- Ordinary hangout **+10** is a modeling estimate, not independently verified raw game
  data. Matching Persona and max-Charm multipliers are **1.51**; the model floors per
  meeting. Prefer a player-observed rank-up cue over estimated points.
- Persona mode is NONE / GOALS / ALL, default NONE. Charm bonus and goal margin default
  on; explicit goals get an extra ordinary meeting's points at point-gated ranks.
  An undecided romance route is treated as friendship.
- Dating-site notes are modeled from November 11: an evening, ¥3,000, estimated +10
  affinity, no assumed Persona/Charm multiplication. The five episode tracks are selectable;
  new breadth-style onboarding selects all five, while stored choices are preserved.
- Forecast Tartarus income defaults to zero. The service can supply a nonnegative median
  from at least two exact recorded net gains in the current or preceding block
  (`TowerIncome`). The rules report's blanket statement about no tower income is only
  accurate for the default without usable records.
- `Reminders.attach` adds **今天别错过** information after planning: classroom/exam
  answers, dozing, nurse visits, shopping, requests, missing people and collectibles.
  Display reminders do not consume slots, change forecast stats/cash, or affect `PlanHash`.
  Rescue-group windows are now planning inputs; they are distinct from cosmetic reminder text.
  Request detail includes 25 sourced how-to tips, Request 75 faculty-office visits and 77–81
  outing requirements. Completion/visit counts and request cash effects are not tracked; a
  reminder can describe a consuming action (e.g. Request 11 evening challenge).
  This is distinct from simulated **顺便** unlock steps, whose effects do count.

## Confirmed progress and replanning integrity

- SQLite file **`p3r_v3.db`**, schema **2**: `meta`, `profile`, `checkpoints`, `events`,
  `plan`. Migrations are additive and repeatable; preserve existing player data.
- Observed progress is the latest checkpoint plus non-voided events. Writes are atomic,
  use expected revisions, and deduplicate by command ID and payload fingerprint.
  A retried command must not become another action or tower visit.
- Records capture the actual rank and exact side-step identities at write time.
  Replaying history after changing Persona mode, goals or answers must preserve those
  confirmed results. A tower correction replaces that visit's values without adding a visit;
  cash is either a net change or a total balance, never both.
- `PackLayout` stores entity IDs beside positional arrays and remaps on rule updates.
  Preserve IDs, rule-version stamps and migration behavior when changing pack structure.
  Planner backup schema is **`p3r.backup.v3`**.
- `StateDraft` uses null for untouched fields and `?` for keep-existing input. Unrelated
  edits preserve known hidden estimates. Changed ranks reset their affinity estimate;
  changed visible stat levels use the new level's lower bound. Sync can prefill a forecast
  when the player says they followed it, but requires the player's save to confirm it.
- `ReplanWorker` coalesces work on the service executor, computes outside the lock,
  cancels outdated jobs, validates output, and installs only current state/profile/rules
  revisions. A budget-exhausted ordinary run can get one 2× follow-up.
- Full replan rereads saved state/profile, uses a 7.5-second default budget and no stability
  bias, and does not itself change progress or history. Its ticket/result state is distinct
  from ordinary planning; a current widget can keep recording during it.

## App and widget interaction

- Tabs: **今天 / 日程 / 社群 / 我的**. Setup and later sync preserve drafts and prefill
  values. Result sheets ask only what matters to the action: rank-up/no rank-up, training
  correction, tower floor/cash/rescue, or an alternative action.
- Widget arrows browse individual tasks; **当前** returns to the pending task;
  **完成** is the right-hand primary action. Training/jobs can record directly with undo;
  meetings and tower visits open result sheets. Story/status cards have their own actions.
- Widget writes verify plan ID, state revision, slot and action, with deterministic command
  IDs. All broadcast work goes through `WidgetWorker`/`goAsync`; avoid database access,
  rule loading or RemoteViews construction on the main thread.
- Answers should stay readable at tiny/compact/regular sizes, including classroom answers
  alongside Social Link answers. `DialogueHint`, `CardText` and the glossary encode
  uncertainty/romance wording and compact fallbacks; avoid blindly abbreviating them.
- `OldGuideProgress` only reads `p3r_native_progress_v1` preferences and exports the old
  `p3r_hunterjia_checklist_v1` format. Those ticks are separate from planner progress.
  The old 2.0.0 package used a different signing identity; it is not an in-place upgrade target.

## Rule sources and boundaries

The shipped [rule asset](../android/app/src/main/assets/p3r/p3r_rules.json) is generated
from `rules/sources/` snapshots, their `SOURCES.json` manifest, and `rules/p3r/` evidence.
At this revision it is 317,972 bytes, schema `p3r.rules.v3`, pack version `3.2.0`, runtime
identity **`3.2.0|50f6e869ddd3`**. App and pack version numbers are separate.
`RulesLoader` appends the first 12 SHA-256 hex digits of the actual JSON text.

Coverage: 22 Social Links (three story links), 22 activities, 10 Tartarus blocks,
five Link Episode tracks, two link-specific rescues, 120 static reminder items and
13 missing-person reminder groups. The pack lists 128 source records.

Change builder inputs/logic and regenerate both the asset and `rules/build/REPORT.md`;
the report is not a hand-edited document. Keep Reload-specific provenance and confidence
(`game_data`, `verified`, `single`, `estimate`, `conflict`). Mixed-edition sources only
corroborate a Reload source. A matching file hash establishes identity, not game accuracy.

Model limits to remember: free stat gains are not forecast; not every activity, item
requirement or rescue floor is modeled; 女皇's exam-first-place requirement is described
but not simulated; 审判 ranks 2–10 are dated January 30 as an estimate instead of derived
from floors; some story dates are conservatively locked; no mid-chain holds are encoded
in the pack, although player-reported holds are supported. Some Chinese answers and
multiplier/rounding details still lack direct in-game verification. See the generated
report's known gaps before treating a model assumption as a mechanic.

## Building and checking changes

Dependencies belong inside the repository's `.toolchain/`; outputs go to `dist/` and
`qa_out/`. Use [setup_toolchain.sh](../scripts/setup_toolchain.sh) to provision them when
needed. Release signing material lives in ignored `.signing/`; without it, the release
script falls back to a debug key. Never assume that build can update an installed release.

From the repository root, with the local toolchain already provisioned:

```bash
python3 rules/build/build_p3r_rules.py --check
python3 -m unittest discover -s rules/build -p 'test_*.py'
source .toolchain/env.sh
cd android
./gradlew --offline --no-daemon :app:testDebugUnitTest :app:lintRelease :app:assembleDebug
```

For packaging, [build_release.sh](../scripts/build_release.sh) runs unit tests, release
lint and assembly, checks alignment/signature, and writes the APK plus hash to `dist/`.
[verify_selfcontained.sh](../scripts/verify_selfcontained.sh) is the separate offline
toolchain-isolation check. GitHub CI runs unit tests and a debug build; its workflow does
not currently run the Python builder suite or release lint.

Useful regression entry points:

| Change | Existing tests to consult |
| --- | --- |
| Search, priorities, styles, budget | `PlannerTest`, `PlanRealPackTest`, `PlanBreadthRealPackTest`, `BreadthObjectiveTest`, `PlanPerformanceTest` |
| Mechanics, legality, side steps, rescue margin | `CoreContractTest`, `SimulatorTest`, `PlanValidatorTest`, `SideStepTest`, `RescueMarginTest`, `RescueDeadlineRealPackTest`, `RescueBatchPolicyTest`, `RescueBatchRealPackTest`, `SpecialMenuTest` |
| Recorded progress, migrations, concurrency | `ReplayerRealPackTest`, `PackLayoutTest`, `ProfileChangeReplayTest`, `PlannerServiceRecordTest`, `PlannerServiceFollowUpTest`, `PlannerServiceFullReplanTest` |
| Player results and sync | `EndToEndRealPackTest`, `ResultFlowsRealPackTest`, `ResultRealServiceTest`, `StateWizardTest`, `FullReplanUiTest` |
| Widget behavior and rendering | `PlanWidgetBehaviourTest`, `PlanWidgetRealServiceTest`, `PlanWidgetFadeTest`, `AnswerRowRenderTest`, `ReminderRowRenderTest`, `PlanWidgetRealPackRenderTest` |
| Reviewed dialogue, branch boundaries and compact answers | `ReviewedDialogueHintTest`, `DialogueHintsLoaderTest`, `ReviewedDialogueWidgetTest`, `ConditionalAnswerFitTest`, Python `test_dialogue_guidance.py` |

Real-pack test helpers multiply the planning budget by three when `CI` is set; production
keeps its normal budget. Plan metrics from tests are forecasts under the selected profile,
not promises about every save or measurements on a phone. A focused run writes only its
scenarios to `android/app/build/plan_metrics.md`; do not overwrite the full committed
`docs/PLAN_METRICS.md` with that partial output.

## Checks recorded during the initial orientation (before 3.2.0)

The rules-builder suite passed **30 tests**. An offline Gradle run passed **36 tests**
with no failures, errors or skips: `CoreContractTest` (15), `PlanValidatorTest` (14),
`RescueMarginTest` (5), and two `PlanRealPackTest` scenarios. The latter produced:

| Scenario | Coverage and result |
| --- | --- |
| April 21, 皇帝 必须 plus other links | 572 rows through the January 31 finale; 454 useful actions; 21/22 MAX; 皇帝 MAX June 29. |
| October 22, 皇帝 必须 and 女教皇 想要 plus other links | 204 rows through the finale; 169 useful actions; 7/22 MAX; 皇帝 MAX December 21 and 女教皇 MAX January 29. |

These checks cover rule reproducibility and selected planning invariants. The full Android
suite, release packaging and live-device flows were not rerun for this documentation task.
The existing Today, home-screen widget and tower-result screenshots were inspected.

The 3.2.0 implementation also adds `ActivityPrerequisiteLoaderTest`, source-level menu/request
tests and ledger regressions for checkpoint/restart/rewind of the actual last tower visit.
See the 3.2.0 release notes and current plan metrics for final validation results.


Initial 3.2.0 verification (before the final dialogue completion): all 57 Python tests passed. Android validation ran the full 946-case suite,
which passed its 717 non-widget cases and identified four widget failures. After updating stale
coverage assertions and fixing the tiny-card layout boundary, all 233 widget cases passed,
including four new boundary tests. Thus the latest results cover 950 tests with zero remaining
failures, errors or skips; this total combines the full run and scoped reruns. The renderer has
no consumers outside the widget package. Raw stage reports and the latest-result index live in
`qa_out/dialogue_audit/final_validation.json`. Release lint passed with 176 warnings and zero
errors; debug assembly succeeded. The APK is `app.p3r.planner.debug`, version code 36,
with the exact shipped pack above. Signature/alignment and embedded-rule checks passed.
SHA-256: `d7bfbc0725f125c3812e5229052c15c93153aa2a13dd849032b441cfd513e402`.
This workspace has no release signing key, so that artifact is an independent test install,
not a signed release update. The complete 32-row metrics table records the CI profile's
7.5-second test budget; production remains at 2.5 seconds. No live-device playthrough was performed.


## Dialogue coverage audit before the approved expansion (2026-10-16)

See [the complete audit](analysis/DIALOGUE_COVERAGE_2026-10-16.md) and its
[machine-readable evidence](analysis/dialogue_coverage_2026-10-16.json) before treating a generic
answer hint as a genuine source gap. The audited pack had mapped fragments for 75/171 non-story
RANK2–10 scenes; default friendship displays specific text for 45 and generic fallback for 119.
Pinned original Chinese pages provide explicit-rank records for 93 of the 96 missing scenes,
bringing source-binding potential to 168/171, without certifying full branch/optimal-answer
coverage. The audit found per-fragment deduplication deleting 24 ordered entries across 17
scenes, ordinal-only filtering, blanket high-rank friend suppression, partial question sequences
and best-gain assumptions unsupported by actionable hints. Production dialogue data and
renderer remain unchanged by this audit. Its 342 route/scene combinations were run against
compiled production code; the prior 927-test build does not establish factual completeness.

## Initial approved dialogue expansion (2026-10-16; historical)

The owner approved [this immutable short-wording preview](analysis/DIALOGUE_ADDITION_PREVIEW.md).
The persistent input is `rules/p3r/dialogue_guidance_approved.json`: 115 reviewed scenes,
88 additions and 27 repairs, with pinned original-source hashes and eight excluded candidates.
That stage bound 163/171 manual R2–R10 scenes and supplied 115 typed
`dialogueHints` entries. Common/friend/romance variants are authoritative; missing variants
retain uncertainty instead of reparsing another route's raw fragments. Full details preserve
arbitrary prompts, repeated answers and conditions; compact choices follow the approved preview.
Explicit empty `shortNoteZh` keeps a detail-only condition off the widget. Affinity mechanics,
thresholds and meeting calendars are unchanged by this dialogue expansion.

That stage was not complete best-answer coverage. No usable binding was available for Priestess R2,
Empress R6, Hierophant R9, Fortune R5, Devil R10, Tower R9, Star R3 and Sun R3. Existing
Priestess R8, Hierophant R7, Justice R9 and Strength R7 needed source/wording resolution;
several friendship branches were unverified. Coverage counts are about scene bindings,
not every branch or optimal response. See the [after-change coverage record](analysis/DIALOGUE_COVERAGE_AFTER_2026-10-16.md) for
actual runtime classifications, rather than applying the historical audit counts to this pack.


## Completed dialogue guidance (2026-10-16; current)

The owner authorized completing the remaining choices, using existing friendship/romance
preferences and translating when no sourced Chinese was found, then committing and pushing.
`rules/p3r/dialogue_guidance_completion.json` records 35 scene repairs, 50 rendered source
URLs, 61 route variants and 15 alternative-history sequences. The original 115-scene
approval remains byte-for-byte unchanged. Chinese-guide quotations and editorial translations
have per-choice provenance; the latter are disclosed in details, without lengthening widgets.
No source is relabelled an inspected official game-text dump.

The shipped asset binds all 171 manual R2–R10 scenes, with 135 typed scene entries.
Runtime checks enumerate both profile routes: 156 specific-answer scenes, 14 free-choice
scenes and one story-only instruction per route; generic fallback is zero. All 342 combinations
also pass rank-up, possible-rank-up, certain-no-rank-up and hold checks. Independent review
covers the 109 positive-gain R3–R9 scenes and all new primary/alternative sequences.
See [the final coverage record](analysis/DIALOGUE_COVERAGE_COMPLETED_2026-10-16.md).

Justice's normal friendship guidance keeps the model's four-answer, 50-point R5 witness and
refuses at R9; earlier locked friendship remains a detail alternative. Other early-friend
paths were checked for equal maximum displayed notes; raw script values were not independently
extracted. All non-dialogue/source fields of the final pack equal the prior pack, and three MAX
action fingerprints are unchanged. Profile choices guide the intended path; imported history
can differ, so details retain explicit conditions. This is source-guidance coverage, not a
complete official localization extraction or an in-game replay of every scene.


Final completion validation: 70 Python tests passed. The complete 959-case Android run
passed all 725 non-widget cases and found one SDK 26 matrix failure (three singleton note
wraps). Two compact annotations were shortened without changing full answers or question
sequences. All 236 widget cases then passed, including two SDK variants of the targeted
real-plan regression; dialogue tests and the three updated-identity MAX checks also passed.
The latest-result union is 961 tests, with zero failures, errors or skips, recorded in
`qa_out/dialogue_completion/final_validation.json`. Release lint passed with zero errors
and 176 warnings; debug assembly, APK signature/alignment and exact embedded-asset checks
passed. The final APK is 1,180,956 bytes, SHA-256
`948499d0e334f96600a57934590cbd71f121bed11792bfa8883906285046db43`.
The full 32 benchmark rows matched the committed metrics except for elapsed machine time;
focused golden reruns may replace the generated metrics file with a partial table, so the
committed complete table remains unchanged. No live-device/gameplay verification is claimed.
