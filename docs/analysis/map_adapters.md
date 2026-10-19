# P3R planner rule adapters: deep dive

## 0. Summary

1. **Four rule packs feed the runtime, but only one is needed.** The live classes read `planner_v2/rules/` (v0.2.0), `rules_v04/` (v0.4.0) and `rules_v051/` (v0.5.1). `rules_v03/` is loaded only by `NativeExpandedRules`, which only tests use. `planner_v2/index.html` is not referenced anywhere. The JSON files the code actually consumes from `rules/` and `rules_v04/` are byte-identical to the copies in `rules_v051/`. About 34.5 MB of rule assets could become about 11 MB or less.
2. **The adapters are built so that nothing can be forecast beyond a short readiness-proven prefix.** That is by design, and it is the main reason plans stop after a few days. There are 7 independent reasons:
   - ordinary hangouts are never modeled;
   - the default answer policy gives a 0-point lower bound;
   - no rank-up chain is compiled after an unlock;
   - stat and job gains are never credited on future dates;
   - the final unlock step needs today-only `current.*` flags;
   - actions are compiled only for goal links and their prerequisites;
   - the UI never sets several guard inputs (rescue, Christmas, Jan 21, the Moon rank-9 opt-in, unlock envelopes).
3. **Status of the defects listed in the handoff:**

   | Defect | Status |
   |---|---|
   | Dec 24 invitation | Fixed for training and jobs, with residual gaps |
   | Jan 21 | Fixed for training and jobs, with residual gaps |
   | Apr 22 lock | Fixed in one place and patched globally by a post-filter in Unified |
   | Preparation steps dated today | Only partly fixed. Devil, Justice and Hanged Man openings are still "today or never" in forecasts |
   | Short plans | Not fixed |

4. **Recommendation:** replace all six adapter classes with one compiled pack (built offline from `rules/p3r/*` by Python) and one generic Java interpreter that creates actions per day from the simulated state. Section 9 has the schema. The unshipped `rules/p3r/full_horizon_forecast_policy.json` already describes most of the forecasting policy the new model needs.

---

## 1. What is loaded at runtime

| Class (v2/) | Asset root | Manifest check | Files actually used | Where it is created at runtime |
|---|---|---|---|---|
| `PlannerRules` | `planner_v2/rules/` (`PlannerRules.java:18`), pack 0.2.0 | **Manifest hash not pinned.** It recomputes each listed file's SHA against the manifest (`:21-26`). The manifest SHA (`fea261…`) appears only in the version string (`:28`). | `social_links.json`, `stats_actions.json`, `calendar_january_slice.json`, `playable_actions.json`, `tower_stages.json` | `PlannerCoordinator.java:15` (always). It supplies the Sun/Devil `visit_*` template, January labels, the catalog, statRules and the obsolete `coverage` text (`:16`), which is still returned by `PlannerCoordinator.state()` at `:21-22` |
| `NativeManualRules` | `rules_v04/` (`:29`) | Pinned `5c6c35…` (`:30,49`). Hashes **and fully parses all 31 files** (`:53`), including the 5.9 MB `calendar_full_candidate.json` | `calendar_execution_contract`, `calendar_global_constraints_candidate`, `calendar_execution_extension`, `manual_link_models`. `execution_registry` is loaded but `registryMode()` (`:357`) is never called | `UnifiedPlannerRules:15` |
| `RulePack` (generic) | `rules_v04/` | Same pinned hash. Hashes, parses and **keeps the text of all 31 files in memory** (`RulePack.java:7-8`) | `social_links.json`, `stats_actions.json` only | `GoalPlannerHomeActivity.java:13`, for form labels |
| `NativeProgressionRules` | `rules_v051/` (`:15`) | Pinned `4928f5…` (`:16,28`). Hashes only the 11 named files (`:31-32`). `tartarus_entry_availability_supplement.json` is **outside the manifest** and pinned separately as `e5a0f5…` (`:20,33`) | stage_identification, stats_actions, stat_activity_expansion, stat_lower_bound_contract (hashed only), calendar_global_constraints, calendar_execution_contract, global_slot_locks_expansion, story_link_transitions, jobs_wage_rules, calendar_january_slice, playable_actions (hashed only) | Twice: `UnifiedPlannerRules:15` and `PlannerCoordinator:15` |
| `NativeUnlockRules` | `rules_v051/` (`:21`) | Same pinned v051 hash. Hashes 8 named files (`:37`) | simple_unlock, multistep_unlock, calendar_execution_extension, calendar_execution_contract, calendar_global_constraints, global_slot_locks_expansion, stats_actions, manual_link_models | Twice: `UnifiedPlannerRules:15` and `GoalPlannerHomeActivity:34` (form schema) |
| `NativeExpandedRules` | `rules_v03/` (`:29`) | Pinned `11d096…` | magician/chariot base models | **Only in `NativeExpandedRulesTest`. Dead at runtime.** |
| `UnifiedPlannerRules` | Composes the classes above | — | — | `PlannerCoordinator(ctx, true)` from `GoalPlannerHomeActivity:13`. `GoalPlannerActivity` uses `PlannerCoordinator(this)` (expanded=false, January-only path), but nothing in `main/` launches it (manifest `exported=false`, test-only) |

**Hash verification** proves packaging identity only. The checks are: the pinned manifest SHA, `gameEdition=="P3R-base"`, `packVersion`, the filename regex `[a-zA-Z0-9_.-]+\.json`, each file's SHA, and the JSON root type. The v051 manifest also lists `.md` files (`ADAPTER_CONTRACT_v05.md`), so `RulePack` cannot point at v051 unless its regex changes.

**Dead weight and cost**
- 78 duplicate asset copies by hash. Every JSON file the live code reads from `rules/` and `rules_v04/` has an identical copy in `rules_v051/`, including `manual_link_models.json` (1.2 MB) and `calendar_execution_extension.json` (735 KB).
- `calendar_full_candidate.json` (5.9 MB × 4 directories) is never consumed. It is hashed 3 times and parsed twice into an org.json tree on every launch, by `NativeManualRules:53` and `RulePack:8`.
- Also dead: all of `rules_v03/`, `planner_v2/index.html`, and the `.py`/`.md` files in the asset directories. v051 ships 27 manifest-listed files that no adapter reads, for example `ordinary_hangout_rules_candidate`, `affinity_actions_candidate`, `observation_meeting_policy`, `guide_dialogue_reference_map` (478 KB), `romance_base_models_candidate` (537 KB) and `other_manual_link_base_models_candidate` (488 KB).
- Each `GoalPlannerHomeActivity` launch creates 6 adapter instances and hashes and parses about 27 MB or more. That is a real risk of startup latency and running out of memory on low-end phones.
- **Version coupling:** `Unified.version = "p3r-unified-v2-a6|"+january|manual|progression|unlocks` (`:16`). Any change to a pack directory or hash changes it. `NativeManualRules.checkpointStatus` (`:180`) then returns `rules_version_mismatch`, and the user has to press "确认实际基线与目标" again. `PlayerStateForm.collect` (`:119`) re-stamps the version and keeps the fields, so data survives, but a migration should re-stamp silently.

---

## 2. The composition pipeline (`UnifiedPlannerRules.build`, `:20-101`)

1. `validateContext` (`:159-164`) sets `checkpoint.consistencyValidated` from the form's `userConfirmedCurrentState`. It flags a conflict if a link's rank is above 0 before the link's `earliest_month_day_claim`.
2. The three sub-adapters are called on copies of the observed state, with their own `rules_version` patched in (`:23-26`).
3. **Selected entities** are the goal links plus their recursive unlock dependencies. Dependencies are only expanded when the goal link's observed rank is exactly 0 (`:31-35`). Dependencies become extra engine goals at priority `min(priority)-1`.
4. Manual-link actions are kept only if `meta.entity ∈ selected` or the action is pinned (`:49`). **Links that are not goals get no actions at all**, so the engine cannot do any secondary optimization.
5. Unlock actions are added only when the unlock adapter's status is `conditional` (`:51`).
6. Sun/Devil: for each selected Sun/Devil goal with a `conditional` guarded calendar, one action per rank from the current rank to 10 is cloned from the January `p3r.action.visit_*` template. Each uses all eligible slots (`:56-71`).
7. Tartarus candidates come from `progression.dungeonCandidates` (`:72-74`).
8. Stat actions, the job, and "routine" candidates are added **only if `wantsStats`**, meaning a socialStats goal or dependency exists (`:46, :75-76`).
9. **Global lock post-filter** (`:77`): every action's slots are filtered through `progression.isOrdinarySlotLocked`, or `isDungeonSlotLocked` for Tartarus. This one line is what applies the v051 locks (exams, full moons, trips, Apr 22) to the v04 manual-link actions and the v051 unlock actions.
10. Goals are classified as unsupported, needing observation, supported, or already met (`:79-92`). Fool, Death and Judgement goals are **unsupported**, which turns the whole plan's status into `unknown` (`:121`).
11. `plan()` (`:104-131`) runs `PlannerEngine.plan` with beam width 48 and 60,000 attempted transitions, replays the result, and adds `adaptiveReservations` (`:139-157`). For each goal stopped at an observation checkpoint, that adds one zero-cost "前往会面，确认是否可升阶" slot.

---

## 3. How availability is derived

**Social-link meetings**
- Source: `calendar_execution_extension.json.records`, 1,352 source-positive dates ("O" cells from the wiki calendar matrix, which the code calls exact-date O cells) for 19 entities. Records exist only for daytime links except Devil and Tower, which are evening. Weekday distributions match `social_links.json.availability.iso_weekdays`. The only exceptions are explained ones: Hermit on holidays, and Empress on Mondays and Fridays in Nov–Dec.
- `NativeManualRules.evaluateDate` (`:190-228`) applies, in order:
  - hard locks: `explicitActionLockClaims` (12 entries) plus Aug 16;
  - the current slot's capacity;
  - `knownStoryLock` (null adds assumption `A.calendar_context_complete_enough`);
  - `entityDateStates…knownUnavailable`;
  - `permanentlyUnavailable`;
  - the rescue guard;
  - special guards for Christmas and Jan 21.
- **No weekly fallback exists.** Availability means the record exists.

**Global locks** (v051 `global_slot_locks_expansion_candidate.json`, read by Progression and Unlock):
- `retainedLockClaims` (14): Apr 10–17, Apr 19, the Oct 5 / Nov 5 / Dec 3 daytimes, Aug 16 and **Apr 22 daytime**.
- `newWholeSlotLockClaims`, 16 groups covering 84 slots:
  - prologue: Apr 7–9;
  - exams: May 18–22, Jul 14–17, Oct 13–16, Dec 14–18;
  - summer classes: Aug 10–14;
  - typhoon and illness: Sep 18–20;
  - Kyoto trip: Nov 17–19;
  - work experience: Nov 24–26;
  - 7 full moons.
- `dungeonEntryOnlyRestrictions`: 20 nights when Tartarus is closed.
- The source summary leaves 473 of 602 slots **unknown**, and 18 `unresolvedEventContexts` (Yakushima Jul 20–22, the Jul 27–Aug 2 track training, Jan 1, and others) are not locked.

**School and exam rules**
- School links' exam and pre-exam blackouts, holidays and vacations exist only implicitly, as missing records. `social_links.json.global_guards.school_blackout` has `exact_blackout_dates: null`.
- Exams appear only as global locks. There is no exam-score model and no charm or academics exam bonus.

**Special guards** (none of these flags are ever written by the UI. A search of the `main/` Java sources finds no writer for `storyFlags`, `rescuedAtOrdinal`, `personAvailabilityConfirmedForDate` or `assume_complete_events`):
- **Christmas:** 6 records carry `christmas_all_invitations_declined`. Unknown means not eligible.
- **Jan 21:** 10 records carry `jan21_conditional_story_event_resolved`, including the **evening** Tower record, although the source claim covers daytime only.
- **Rescue:** 133 records (Hierophant from Sep 12 onward, Hanged Man from Oct 21 onward) carry `person_confirmed_available_after_rescue_window`. **Hierophant (96 records) and Hanged Man (37 records) can therefore never be scheduled after their disappearance dates.**
- **Aeon on Jan 25:** already excluded in the data (no record exists).

---

## 4. What gets compiled for each of the 22 links

Unless noted, ranks 2–10 come from `manual_link_models.json`, which has 153 thresholds and 168 event branches.

| Link | Kind | Meeting calendar | Unlock path (`NativeUnlockRules`) | Rank-up model / romance |
|---|---|---|---|---|
| Fool 愚者 | Story | — | — | Informational milestones only (`storyGoalMilestones`, Progression `:60-87`), 9 transitions. Goal is unsupported |
| Magician 魔术师 | Manual | 63 days, Tue/Thu/Fri | Forced intro on Apr 22 (not an action) | Gates 0,0,15,15,15,15,22,22,22 |
| Priestess 女教皇 | Manual | 51 | Simple: courage 6 and Fortune ≥1 | Romance branches at ranks 8–10 |
| Empress 女皇 | Manual | 30 (from Nov 21) | Simple, with `unresolvedExtraCondition`. Needs the policy `empress_wiki_first_gate`, which the UI never sets | Romance at 9–10 |
| Emperor 皇帝 | Manual | 54 | Simple, Apr 27 | — |
| Hierophant 教皇 | Manual | 185, but **effectively 89** because of the rescue guard | Simple, Apr 25 | — |
| Lovers 恋爱 | Manual | 50 | Simple: charm 6 | **Rank-6 gate is conflicting (null)**. Romance at 9–10 |
| Chariot 战车 | Manual | 80 | Simple, Apr 23 | — |
| Justice 正义 | Manual | 60 | Multistep: conversations 1–3 cost 0 slots, so they are **checkpoints only**. Conversation 4 needs the today-only `current.*` flag and `date_strictly_after` | Romance at 9–10 |
| Hermit 隐者 | Manual | 46 | Simple: `inventory.onlineGameAvailable` | — |
| Fortune 命运 | Manual | 41 | Simple, Jun 17 | — |
| Strength 力量 | Manual | 43 | Multistep but `embedded_post_chariot`, so **never an action** | Romance or rejected romance at 8–10 |
| Hanged Man 倒悬者 | Manual | 89, **effectively 52** because of the rescue guard | Multistep: items (Weird Takoyaki, Mad Bull), then `return_and_play` with the today-only flag | — |
| Death 死神 | Story | — | — | Milestones only, 6 transitions |
| Temperance 节制 | Manual | 56 | Simple: Hierophant ≥3 and academics 2 | — |
| Devil 恶魔 | Fixed +1 per visit | 61 evenings | Multistep: ¥20,000 / ¥10,000 / ¥10,000. Payments 1–2 cost 0 slots, so they are checkpoints. Payment 3 is today-only | Chain to rank 10 cloned in Unified `:56-71` |
| Tower 塔 | Manual | 126 evenings | Multistep: hearing the rumor has `slotCost=null`, so it is a checkpoint. The final step needs the courage-gate observation or the opt-in `tower_courage4` | — |
| Star 星 | Manual | 61 | Simple: courage 4 | — |
| Moon 月亮 | Manual | 215 | Multistep: rumor (checkpoint), then quiz plus Odd Morsel, today-only | **Rank 9 always stops** (`NativeManualRules:275`), because `assume_complete_events` is never set |
| Sun 太阳 | Fixed +1 per visit | 23 Sundays | Multistep pen chain: two `slotCost=null` checkpoints, then today-only | Chain to rank 10 via Unified |
| Judgement 审判 | Story / Tartarus | — | — | Milestones only (floor flags 227–255) |
| Aeon 永劫 | Manual | 18 (Jan 8–30) | Simple, Jan 8 | Gates are 0 up to rank 9, then 30 for rank 10. Romance branch at 9 |

**A rank-0 link never gets rank-up actions.** `NativeManualRules.build:93` skips ranks below 1, and the Sun/Devil branch in Unified requires guarded status `conditional`, which needs rank ≥1 (`:60`). The unlock action assigns rank 1, but no ranks 2–10 follow it in the same plan.

---

## 5. Rank-up points model and why chains stall

The model for manual links (`NativeManualRules:104-139`, `:268-326`):
- An action for rank r→r+1 compiles only while `ready` is proven. Initially, ready means the user observed it, or `affinityLowerBound ≥ gate`, or gate == 0 (`initialReady`, `:230-244`).
- After an event, the affinity lower bound is **replaced** by that event's answer gain (`replaces_prior_rank_knowledge`; no carryover). The next link in the chain compiles only if that gain is at least the next gate.
- With the default policy (the form box is unchecked), each prompt takes the **minimum** over all safe options (`minimumSourcePath`, `:308-326`). That minimum is usually 0.
- With "source_max_base_points" (form checkbox, `PlayerStateForm:120`), the best answer path is used.
- An option that sets the relationship type requires an explicit `relationship_decisions` value. Otherwise the path returns null and the chain stops.
- Bonuses (×1.51 matching Arcana, Charm 6, exam ×1.51/1.21) are disabled (`bonus_lookup_executable=false`).
- **No ordinary non-rank-up hangout action exists.**

Chain length starting from rank 1, simulated from the shipped data:

| Link | Default (minimum) policy reaches | Max-answer policy reaches |
|---|---|---|
| Magician | 3 | 7 |
| Chariot | 3 | 3 |
| Priestess | 3 | 4 |
| Empress | 3 | 4 |
| Lovers | 3 | 3 |
| Justice | 3 | 3 |
| Strength | 3 | 3 |
| Emperor | 4 | 8 |
| Hierophant | 3 | 5 |
| Hermit | 3 | 4 |
| Fortune | 3 | 8 |
| Hanged Man | 3 | 3 |
| Temperance | 3 | 5 |
| Tower | 3 | 3 |
| Star | 3 | 3 |
| Moon | 3 | 5 |
| Aeon | 8 (romance branch) | 8 |

A midgame link with an unknown readiness state and a nonzero gate gets **zero** actions: only an observation checkpoint plus one adaptive reservation.

---

## 6. Training, money, Tartarus and story links

**Stats** (`NativeProgressionRules.build`, `:165-232`)
- Sources: 5 base actions (`stats_actions.json`) and 12 expansion actions (11 repeatable plus a one-use burger challenge that is skipped). Thresholds: academics `[0,20,55,100,155,230]`, charm `[0,15,30,45,70,100]`, courage `[0,15,30,45,60,80]`.
- Visible rank becomes a raw interval (`deriveStatFacts`). Gains are credited as base lower bounds.
- **No future date is ever credited.** For any `slot > start`, either `knownFree` is false (`slotStates` is written only for the current slot, `PlayerStateForm:116`) or `exactDateActionAvailable` is not true. So `availabilityUnknown=true` (`:204`) and the slot becomes a `conditional_activity_candidates` entry with `forecast_credit:false` (`:215-219`).
- In practice, stat forecasts cover only the current slot.

**Money**
- Cash is an interval; unknown cash becomes a lower bound of 0.
- The only income is `p3r.action.job_chagall_minimum`: ≥¥2,500, Mon–Wed evenings, stat gains deliberately dropped (`:39-46`). It has the same future-date problem, so **income is never forecast**.
- Be Blue V and the cinema job have wage tiers in `jobs_wage_rules_candidate.json` but are disabled.
- Costs apply to stat actions and to the final unlock step. The Devil payment steps cost 0 slots and are never compiled, so their money is never simulated.
- Job and stat actions reach the engine only when `wantsStats` is true.

**Tartarus** (`dungeonCandidates`, `:89-133`)
- Evening candidates from Apr 21 to Jan 30 require all of: a validated checkpoint; `context.tartarus` present; `normalStoryProgressionAssumed` (form default true); and either one of the 16 positive-witness nights plus 2 January allowlist nights, or `assumeRoutineFutureEntry` (default false).
- They also require known `visits_by_stage` and known caps; unknown is never treated as 0.
- The action has no money, floor or EXP effect. It only increments the stage visit count, and `PlannerEngine.advance` enforces caps.
- Because the score ignores Tartarus, it is scheduled only when the user pins it ("预留这个晚上探索", `GoalPlannerHomeActivity:87`).
- Stage windows come from `stage_identification_contract.json`. There are gaps from Jul 9 to Jul 18 and from Dec 3 to Dec 10, and stage 11 is unbounded. Identifying the stage from the date never grants permission to explore.

**Story links:** informational milestones only. No automatic rank transitions are compiled, even though `story_link_transitions_candidate.json` has 25 transitions.

---

## 7. Status of the defects named in the design doc

| Defect | Status and where |
|---|---|
| Training/job credited on **Dec 24** despite an accepted invitation | **Fixed for training and jobs.** `NativeProgressionRules.specialSlotGuard` (`:236-241`) returns −1 (blocked) when declined==false, 0 (unknown, never credited) when unknown, and 1 when declined==true; used at `:192-193, :204`. Test: `christmasKnownEventBlocksEvenExplicitOrdinaryActivityClaims`. Manual and unlock calendars check the record-level guard (`:219-221`, Unlock `:157`). **Residual:** (a) `dungeonCandidates:105` skips only when the guard is −1, so with unknown invitation state a Dec 24 evening Tartarus candidate can still appear if routine entry is assumed; (b) no UI input exists for the flag, so Dec 24 can never be used |
| **Jan 21** conditional | **Fixed for the daytime slot** (`:239`): needs a Boolean `junpeiWhiteFlowersGiven` plus a known non-locked slot. **Residual:** the data puts the guard on the *evening* Tower record but no evening guard exists for stats or Tartarus, which is inconsistent. No UI input exists, so Jan 21 daytime is never credited |
| **Apr 22** mandatory daytime lock omitted | **Fixed in Progression** (`:49-51` loads `retainedLockClaims`). Test: `retainedForcedIntroductionBlocksApr22DaytimeOnly`. `NativeUnlockRules:41-44` and `NativeManualRules:66-69` still omit it; Unified's post-filter (`:77`) covers them. It is a latent bug if either class is used on its own |
| Preparation steps dated today block Devil/Justice/Hanged Man | **Partly fixed.** `PlayerStateForm:112` sets `lastPossiblePrepOrdinal=today−1` when "准备在今天之前完成" is chosen, but the default is still today. `compileGuard` `date_strictly_after` (`:138`) therefore blocks today. Separately, `observations()` (`:162`) drops `current.*` flags for any future slot, so the final step can never be scheduled on a future date. The forecast outcome for these openings is "today with the right timing answer, or never" |
| Short plans | **Not fixed.** See §5 and §6 |
| Per-action assumptions displayed | Outside the adapters. Metadata `assumptions` are populated |

**Other adapter defects found**
- **Hierophant and Hanged Man** cannot be scheduled after Sep 12 and Oct 21 because the UI never writes rescue facts (§3).
- **Moon rank 9:** always blocked (`:275`); no UI opt-in exists.
- **Lovers rank 6:** the threshold is null (conflict), so the chain stops at 5 under any policy.
- **Story-link goals:** selecting Fool, Death or Judgement degrades the whole plan status to `unknown` (`:86, :121`).
- **Strength unlock:** embedded, so never an action.
- **Tower, Sun and Moon:** their rumor or pen steps have `slotCost=null`, so they are checkpoints only.
- **Leftover January copy:** `PlannerCoordinator.state()` still returns January-only `coverage`, `labels` and `first_slot` (`:21-22`).
- **No "unknown = free" scenario policy:** this contradicts the design doc's statement that a missing annotated date does not prove that no future activity can be planned.

---

## 8. Data quality and what is missing for a full-calendar plan

**Good enough to build on**
- The 1,352-date meeting calendar for 19 links (weekday-consistent, exception-aware).
- Per-option answer points and thresholds for all 17 manual links (cross-sourced, provenance kept).
- Stat thresholds and base gains.
- Locks for exams, full moons, trips, Aug 16, Apr 22 and the prologue.
- Tartarus stage windows and ceilings.
- Story transition dates.
- Unlock prerequisites.
- Chinese names and identity labels.

**Missing or unusable**

1. **Ordinary hangout gain.** Only one Reload source reports a base of +10 (`ordinary_affinity_numeric_followup.json`, status low-to-medium). It is required for filler meetings. `full_horizon_forecast_policy.json` gives a usable estimate: `ceil(deficit/10)+1` fillers, labeled as an estimate.
2. **A scenario availability policy.** 473 slots are unknown, and 18 unresolved event contexts (Yakushima, track training, Jan 1, Nov 4, Dec 31 evening, and others) need an explicit lock-or-free decision.
3. **Unlock-interaction schedules.** When can Devil payments, Justice conversations, the Tanaka/Bebe steps and the Moon quiz happen on *future* dates? This exists only as evidence witnesses, not as weekday rules. Minimum gaps between steps (`date_strictly_after`) need a "distinct later day" semantic.
4. **Story-event state:**
   - Christmas acceptance (with whom; the "accepted" claim consumes both slots);
   - Jan 21 flowers;
   - rescue deadlines and completion for Hierophant (Sep 12) and Hanged Man (Oct 21), tied to Tartarus progress;
   - automatic transitions for Fool, Death and Judgement.
5. **Holiday and Sunday invitations:** 75 "D" cells are listed in the source research; the data is unused.
6. **Lovers rank-6 threshold conflict, Moon rank-9 temporary reversal, reversal repair procedure.**
7. **Romance and branch flags:** where `romance_eligible` gets set, and a default route policy.
8. **Money sources:**
   - exact Chagall tiers;
   - Be Blue V and cinema (unlock dates conflict: Jul 22 vs Jul 23);
   - job stat gains (+1 vs +2 conflict);
   - a Tartarus income estimate (currently intentionally null).
9. **Other stat sources:** not modeled (for example in-class answers or exam-result effects). This needs verification.
10. **Bonus multipliers and rounding:** optional; keep them off for conservative forecasts.
11. **Chinese option binding:** only 79 guide references exist; foreign option ordinals must not be shown as Chinese answer numbers.

---

## 9. Recommendation: one data-driven rules model

The six classes repeat loaders, lock sets, date maths and guard logic. They disagree with each other: different lock sets, different Christmas behavior for dungeons, and NativeManualRules alone checks `beforeIntroduction`. They also fix action slot lists at compile time, which is exactly why multi-step progress cannot be simulated.

Replace them with:

- **An offline builder** (Python, under `rules/p3r/`) that merges the evidence files into **one** `p3r_rules.json`, about 1–2 MB, with a manifest. Raw matrices and research files are not shipped.
- **One Java `RuleSet` interpreter.** Each day, it creates the legal actions from the *simulated* state; it does not precompile slot lists. The engine then covers chains after an unlock, filler meetings, secondary links and money in the same pass.

The pack needs these top-level sections.

**`meta`**
- `packVersion`, `gameEdition`, `builtFrom: [{file, sha256}]`, `sources[]`.
- `policyDefaults`: unknown slot = free unless locked; ordinary gain 10 plus a 1-meeting buffer; bonuses 0; answer policy = best non-reversing; romance per user preference.

**`calendar.days[]`** (Apr 7–Jan 31)
- `ordinal`, `mmdd`, `weekday`, `isHoliday`, `isSchoolDay`, `examWindow`, `towerStage`, `tartarusOpen`.
- `slots.{daytime,evening}`: `{state: free|locked|conditional, lockReason, guardId?, evidence[]}`.

**`guards{}`**
- Examples: `christmas_choice`, `jan21_flowers`, `rescue_hierophant`, `rescue_hanged_man`, `romance_eligible.<link>`.
- Each has: `type` (enum or bool), `scenarioDefault`, `askWhen` (for example "planner wants slot ≥ Dec 20"), `questionZh`, `optionsZh[]`, `affects[]`.

**`links[22]`**
- `id`, `arcanaNo`, `nameZh`, `characterLabelZh`, `gender`, `kind: manual|fixed_increment|story_auto`.
- `availability: {slot, weekdays[], holidayPolicy, monthOverrides[], exactDates[]?, excludeDates[], schoolLink}`.
- `unlock: {steps: [{id, fromStage, toStage, slotCost, minDayGapAfterPrev, schedule (weekday/slot or exactDates), yen, items[], guards[], outcomeSchema}], autoDate?}`.
- `ranks[2..10]: {threshold: {value|null, status}, event: {prompts graph, optimalGain, minGain, branchEffects}, entryGuards[]}`.
- `ordinaryMeeting: {gainEstimate, status: "estimate"}`.
- `autoTransitions[]` for story links: `{date|flag, toRank}`.

**`activities[]`** (training, food, jobs, dating-site note)
- `id`, `labelZh`, `slots`, `weekdays`, `availableFrom/To`, `yenCost`, `statGain{}`, `moneyGain: {min, tiers?}`, `affinityGain?`, `guards[]`, `repeatable`, `outcomeSchema`.

**`tartarus`**
- `stages[]` (windows, ceilings, gaps), `closures[]`, `visitOutcomeSchema` (net yen **or** balance; highest floor; optional protagonist level), `incomeEstimate: null|{...}`.

**`outcomeSchemas{}`** (one per action type)
- Fields to ask after the action, each with `defaultFromForecast: true` and `zhLabel`.
- Example for a rank-up: `{choices: ["已升到 N+1", "没升级", "其他结果"]}`.

**Evidence on every rule:** `{confidence: verified|corroborated|single_source|estimate|conflict, sources[]}`, so the UI can show assumptions in app detail without putting a badge on every widget line.

**Engine state the interpreter needs:** ranks, `affinityEstimate` intervals kept apart from verified lower bounds, readiness, raw stat intervals, the cash interval, inventory, unlock stage codes, guard values, visits per stage. Forecast state stays in the simulation only; the observed ledger is never changed by it.

---

## 10. UX: how input and deviations should work, and what the rules model must provide

The current adapters put the input burden in the wrong place: per-slot flags valid only today (`current.*`, `calendar.exactDateActionAvailable`), training checkboxes, preparation timing, romance spinners, and Tartarus entry observations. The new model should make the UI ask only for **visible** facts, and only **when they matter**.

1. **Checkpoint form prefilled from the forecast.**
   - When the user returns, the date picker defaults to the next planned day. Every field (link ranks 0–10 as chips, stat ranks 1–6, cash, Tartarus floor and visits) is **prefilled with the forecast value for that date**, marked "预测值".
   - The user only fixes what differs. Unknown remains a distinct chip, never 0.
   - On save, the result becomes observed state; untouched fields keep their earlier observed precision.
2. **Just-in-time guard questions.** Each `guards.askWhen` creates a question only when the planner wants a slot that depends on it within the next few days. Examples: "圣诞节：已接受谁的邀请 / 全部婉拒 / 还没到" around Dec 20; "是否已救出 (教皇)" after Sep 12. These are never part of the main form.
3. **One-tap results driven by `outcomeSchema`.**
   - Widget "Done" means the predicted outcome happened.
   - Tapping a task opens the targeted panel. For example, Emperor 6→7 shows "已升到 7 / 没升级 / 其他".
   - "没升级" records readiness false at that rank; the model adds filler meetings and replans. Nothing else is asked.
   - Tartarus asks for net yen **or** current balance (one radio choice) plus the highest floor, keyed by an idempotent step ID.
4. **Deviations:**
   - **Skipped:** "这个时段没做": the slot counts as spent with no effect. Replan.
   - **Substituted:** "做了别的": a quick list of the actions that were legal in that slot (other links available that day, training, job). Picking one logs it with its predicted outcome.
   - **Long gap:** "从 10/3 到今天大致按计划了吗？ 是 / 部分 / 否".
     - "是" accepts all intermediate steps as an event batch, then shows the prefilled checkpoint form.
     - "部分" shows a compact day list where the user unticks what did not happen.
     - "否" goes straight to the checkpoint form.
5. **Always replan from the latest confirmed checkpoint plus the logged events.** Pins and goals are preserved. Stale results are rejected by revision, as `installPlan` already does.

These flows require the single model in §9, because answering "what was legal in that slot" and "what is the forecast value on date X" means generating actions from simulated state each day.

---

## 11. Migration notes

- The `applicationId` and the SQLite store are unaffected. Only `rules_version` changes, so auto-rebase on first load by re-stamping, instead of showing "规则已更新" and requiring a manual re-save.
- Keep `PlannerEngine` (generic) and the replay validator. Delete `NativeExpandedRules`, `rules_v03/`, `planner_v2/index.html` and the duplicate pack directories after the builder produces the unified pack.
- Keep the evidence (`rules/p3r/`, snapshots) out of the APK.
- `GoalPlannerActivity` and the January `PlannerRules` path can be removed once nothing depends on the Sun/Devil template, which the new pack can carry as `kind: fixed_increment`.