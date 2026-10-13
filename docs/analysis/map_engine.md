# Planning engine deep-dive: PlannerEngine, PlannerCoordinator, RulePack, GameDate and PlannerRules

## 0. Summary

- **What the engine is.** `PlannerEngine` is a generic interval-arithmetic state machine with a slot-layered beam search. It contains no game rules. Each action fills exactly one half-day slot. All game knowledge is compiled ahead of time by adapters (`UnifiedPlannerRules` → `NativeManualRules` / `NativeProgressionRules` / `NativeUnlockRules` / `PlannerRules`) into static JSON action lists. Each list is filtered once against the observed state.
- **Why plans are short.** The main cause is coverage: the compiled actions do not include what a full schedule needs. The search budget is a second, real cause.
  1. The adapters compile rank-ups only while readiness is proven. There is no ordinary-hangout (filler meeting) action.
  2. There are no actions at all for links the user did not select.
  3. Future jobs and training are never compiled.
  4. Tower actions have no effects.
  5. The scoring tie-break prefers fewer actions.
  6. The budget counts failed transitions and does not deduplicate states.
- **Measured.** In a synthetic run with the real engine, Emperor at rank 6 gives exactly 1 useful action across Jun 1–Jan 31. With 2 point-independent links plus tower evenings, the budget runs out on Jan 2. With 6 links it runs out by Jul 21.
- **Recommendation.** Keep the interval semantics, the observed/forecast separation, the event-sourced store, the CAS revisions and idempotency, and the honest status words. Rewrite the action generation and the search as a domain-specific, day-by-day forward simulator. Availability should depend on the forecast state, there should be an affinity-accumulation model, and the scoring should include secondary objectives.

---

## 1. Call graph (production path)

- `GoalPlannerHomeActivity:13` creates `new PlannerCoordinator(this,true)`, so `expanded=true` and `UnifiedPlannerRules` is used.
- `GoalPlannerActivity` builds the non-expanded coordinator, which uses `PlannerEngine.plan(pack.rules,…,128,20000)` on the January-only slice. It is registered in the manifest with `exported=false`, but no Java code references it. It is effectively dead (tests only).
- `PlannerCoordinator.replan()` (`PlannerCoordinator.java:25-47`) calls `unified.plan(state,goals,constraints)` → `UnifiedPlannerRules.plan(...,48,60000)` (`UnifiedPlannerRules.java:103`). That calls `build()` (lines 20-101), then `PlannerEngine.plan` (line 111), then `PlannerEngine.validatePlan` (line 112), then post-processing (lines 113-130).
- **`RulePack.java`** loads a manifest, checks its SHA-256, and verifies a hash per file. It is used only for the form catalog (`rules_v04`, Home:13).
- **`PlannerRules.java`** loads the January slice (`planner_v2/rules/`, horizon Jan 2–30). In unified mode it supplies only:
  - the Sun/Devil `visit_*` template (`UnifiedPlannerRules:61-62`)
  - the `catalog`
  - `statRules`
  - the coordinator's `state()` labels and coverage text. These are stale: `coverage` still says "1 月 2–30 日…太阳／恶魔" (`PlannerRules.java:16`), but Home doesn't read them.
- **`GameDate.java`** is the slot axis: `slot=(days since 2009-04-01)*2+(evening?1:0)`. Months 1–3 map to 2010. Apr 21 = slot 40, Jun 1 = 122, Jan 31 evening = 611. The unified horizon is 612, taken from the v04 `calendar_execution_contract.json` window `04-21..01-31`, ordinal 20..305 (`NativeManualRules:55-57`).

---

## 2. State model (`PlannerEngine.State`, lines 53-81)

```
State { run, version, revision(long), slot(int),
        values: TreeMap<String, Interval{Long low, Long high}>   // null = unknown bound
        visits: TreeMap<String stageId, Long count or null(unknown)> }
```

- **Everything is a numeric interval keyed by a dotted field name.** There are no booleans, enums or dates: flags are encoded as `[0,0]` / `[1,1]`.
- **Fields actually produced by the adapters:**
  - `cash`
  - `socialLinks.<slug>.{rank, normal, alreadyUnlocked, ready, readyRank, affinityLowerBound, affinityRank, affinityValid, romanceEligible, relationshipTypeCode}` (`NativeManualRules:85`)
  - `socialLinks.<slug>.affinityExact` (forgotten by unlock actions)
  - `unlock.<slug>.stageCode` (`NativeUnlockRules` `stageField`)
  - `socialStats.<stat>.raw`, plus derived `socialStats.<stat>.rank`. Legacy `stats.*` is removed (`Unified:42`).
- **Visits:** a count per stage. `null` means unknown and is never treated as 0. If a stage has a cap and the count is unknown, `advance` throws `unknown_visit_count` (lines 300-303).
- **Interval arithmetic:** `Interval.plus` (lines 46-49) adds lows and highs separately. Any unknown operand gives unknown. Uncertain gains such as `interval(delta,null)` raise the low bound and make the high bound unknown.
- **Observed vs forecast separation (this part is good):**
  - The search copies its state (`new State(original)`) and never advances `revision`.
  - The observed snapshot lives only in `PlannerStateStore.runs.snapshot`.
  - The forecast is returned as `result.forecast` and stored only inside the plan payload, flagged `observed_state_mutated:false` (`Unified:130`).
  - Evidence and assumptions travel separately: per-action `assumptions` in `action_metadata`, plus `derived_checkpoint_facts` (e.g. `"conservative_nonnegative_floor_not_observed_exact_points"`, `Unified:53`).
- **What is missing from the state:**
  - points toward the next rank, as a separate quantity from `affinityLowerBound`
  - meetings-since-rank
  - inventory (dating-site notes, gifts)
  - protagonist level and highest floor (these live outside the engine state)
  - per-day availability
  - weekday and story flags

  Availability is not state at all. It is baked into each action's static `slots` set at compile time, based on the **observed** state only.

---

## 3. Action model (`Action`, lines 89-129; `advance`, lines 287-336)

- **Shape:** `{id, slots:Set<int>, requirements, maximums, costs, effects, uncertain_effects, assignments, forget_fields, dungeon_visit, target_floor, strict_requirements, verification, source}`.
- **One step = one slot.** Each search layer advances `state.slot++`, so double-booking is structurally impossible. Nothing can span multiple slots, and nothing takes zero slots (buying items, observations).
- **`advance` evaluates in this order:**
  1. `checkContext`: rules version, horizon, fields declared, stage ids for caps, pins inside the horizon. This runs on every transition.
  2. Evidence gate: `verification` must be verified, corroborated or synthetic; synthetic is only allowed if `rules.synthetic`.
  3. `slots.contains(slot)`, and the slot's required action (fixed or pin) must match.
  4. Dungeon checks: exactly one active stage, the `target_floor` ceiling, and the cap against the known count. The visit count is incremented.
  5. Requirements: `minimums = max(requirement, cost)` per field, so a purchase can't be funded by its own reward (tested). If the field's high bound is below the minimum, it throws `requirement`.
     - If the low bound doesn't prove the minimum and the action is strict → `unknown_requirement`.
     - If it is non-strict → the assumption is recorded and the interval is narrowed to `[threshold, high]`.
  6. Maximums: the mirror image of step 5.
  7. Costs subtracted → exact `effects` added → `uncertain_effects` added → `assignments` overwrite → `forget_fields` reset to unknown.
- **`UnifiedPlannerRules:77` forces `strict_requirements=true` on every action.** So in production:
  - the "assumption" path never runs
  - `node.assumptions` is always empty
  - the `-assumptions` score term is always 0

  Every unproven precondition is a hard rejection.
- **Locks are applied by removing slots** from each action's `slots` (`Unified:77`, `progression.isOrdinarySlotLocked` / `isDungeonSlotLocked`). The unified rules set has no `fixed_actions`. The only per-slot "required" actions come from user pins (`constraints.pinned_actions`).
- **`__wait__`** is synthesized for every slot (line 433). It is always available unless a pin or fixed action owns that slot (line 239).

---

## 4. The search, exactly (`plan`, lines 222-285)

```
frontier = [initial]; expanded = 0
for slot in [state.slot, horizon):
   required = requiredAction(slot)   // throws -> if code 'unsupported*' record; BREAK (truncates plan)
   available = actions with slot ∈ action.slots (and == required if any) + __wait__ (if no required)
   for node in frontier:
      for action in available:
         if expanded >= maxExpansions: limited=true; break
         expanded++                              // counts FAILED transitions too
         try advance(...) catch RuleViolation -> classify unsupported/unknown; continue
         child.steps = node.steps + Step(token+":"+slot+":"+id, id, slot)
         child.timing[i] = node.timing[i] + score(child)[i]   (all components but last)
         child.actionCount = node.actionCount + (wait?0:1)
   sort(candidates); if nonempty best = candidates[0]
   if slot+1 == horizon: terminal = candidates (unpruned)
   if limited or candidates empty: break
   pruned |= candidates.size() > beam
   frontier = top beamWidth (48)
```

**Scoring** (`score`, lines 383-396). Priorities are sorted in descending order (larger = more important). For each priority level, two components are added:

- `met` = the number of goals with `low ≥ target`
- `progress` = Σ `min(target, low)`. An unknown low contributes `-10^30`.

A final component is `-assumptions.size()`, which is always 0 in production. The resulting vector is compared lexicographically, so one unit of a higher priority beats anything at a lower priority.

**Comparator** (`sort`, lines 397-408). Ties are broken in this order:

1. score vector, descending
2. `timing` vector (the time-integral of `met` and `progress`), descending. This prefers achieving things earlier.
3. **`actionCount` ascending**, so fewer non-wait actions win
4. action-id strings, lexicographically
5. fewer steps

`score()` is recomputed inside every comparison: O(n log n) BigInteger allocations per layer.

**Pruning** is plain top-48 truncation. There is **no state deduplication** and no dominance check. Nodes with identical states that differ only in *when* a rank-up happened fill the beam. In practice the beam holds a handful of distinct states.

**Final selection** (lines 263-285):

- `guaranteed`: all goals have `low ≥ target` and there are no assumptions. The top one wins, with status `feasible_within_model` or `conditional`; Unified downgrades both to `conditional` (`Unified:114`).
- Otherwise `possible`: no goal has `high < target`. The top one wins with status `conditional`.
- Otherwise the status falls through: `search_limit` (limited) → `unsupported` → `unknown` "Beam pruning discarded alternatives" → `unknown` → `proven_infeasible`, and **`best`** is returned. `best` is the top node of the last layer that was processed. If the loop broke early, the plan is a **prefix** that ends at that slot.

Because rank fields are exact after assignments, a plan that misses its target is never "possible". So an under-covered goal usually ends as `unknown`, with the misleading explanation "Beam pruning…". The engine cannot tell a coverage gap from pruning.

**Replay validator.**

- `result()` (lines 410-414) replays the steps with the **same `advance()`** and throws `AssertionError` if the final state differs.
- Unified replays a second time and compares (`Unified:112-113`, `IllegalStateException`). The legacy coordinator path replays a third time and throws the result away (`PlannerCoordinator:39`).
- Replay enforces slot contiguity (`wrong_slot`), unique instance ids, pins, requirements and costs at lower bounds, and caps.
- It is **not independent**: a bug in `advance` or in the compiled rules (for example a missing lock) passes.
- `AssertionError` is an `Error`. `PlannerCoordinator.withReplan` catches only `Exception` (line 134), so a mismatch would escape to the worker thread.

---

## 5. Why plans stop after a few days (ranked by impact)

**Experiment.** I compiled the unmodified `PlannerEngine.java` against real `org.json` classes extracted from the toolchain's Robolectric android-all jar. It was compiled into the scratchpad, without gradle and without modifying the project. Harnesses: `/private/tmp/claude-501/-Users-xihehan-P3R-Widget/c55d2fa4-7208-4e25-be91-b3e985a53a3e/scratchpad/eng/src/Harness.java` and `Harness2.java`.

| Scenario (beam 48, budget 60000, horizon 612) | Status | Last step | Useful (non-wait) steps |
|---|---|---|---|
| B: Emperor rank 6, ready observed; only `rank_07` compiled (the shape `NativeManualRules` produces) | `unknown` ("Beam pruning…") | Jan 31 | **1** |
| A: Devil 3 + Sun 2 (all ranks compiled, as `Unified:65-70` does) + routine tower action, cap 40, start Jun 1 | `search_limit` | **Jan 2** | 15 (tower **never** chosen) |
| A without the tower action | `conditional` | Jan 31 | 15; 54,661 expansions (close to the limit) |
| 3 chained links + tower, start Apr 21 | `search_limit` | **Sep 18** | 21 |
| 6 chained links + tower, start Apr 21 | `search_limit` | **Jul 21** | 42 |
| 6 chained links + tower, start Jun 1 | `search_limit` | Sep 1 | 42 |

### 5.1 Coverage: only readiness-proven rank prefixes are compiled (main cause)

This is in `NativeManualRules.build`, lines 104-139.

- A rank action is emitted only if `initialReady` (lines 230-244) holds. That requires one of:
  - an observed `ready=1` with `readyRank==rank`
  - a provenance lower bound ≥ the gate
  - a gate of 0
- After each compiled rank-up, the chain continues only if the **minimum event-answer points** (`minimumSourcePath`, the minimum over all safe responses) ≥ the next gate.
- I evaluated `manual_link_models.json` (v04) offline with that logic. From any start rank ≥ 3, every link except Aeon compiles **at most 1 rank-up**, and 0 if readiness was not observed. Priestess, Lovers, Justice and Strength compile 0 at the higher ranks.
- The declared `source_max_base_points` policy helps only in early ranks. Emperor from 6 still gets about 2.
- The forecast can never re-establish readiness, because **there is no ordinary-hangout action**. `rules_v051/ordinary_hangout_rules_candidate.json` explicitly sets `"ordinary_numeric_effect_enabled": false`. No Java code reads `affinity_actions_candidate.json`, `observation_meeting_policy.json`, `conservative_visible_state_defaults.json` or `ordinary_hangout_rules_candidate.json`; the only match in `src` is a single mention in `NativeManualRules.java`.
- `adaptiveReservations` (`Unified:139-157`) adds one "go see if you can rank up" reservation per entity. It has no forecast effect, so nothing after it is planned.

### 5.2 Coverage: no chaining after an unlock in the forecast

- `NativeManualRules.build:93` and `guardedCalendarCandidates:168` skip any entity whose **observed** rank is below 1.
- An unlock action (`NativeUnlockRules.opening`, lines 144-148) can raise the forecast rank 0 → 1, but no rank-up actions exist for that entity.
- So a goal like "Hanged Man to 10" starting from rank 0 yields at most the unlock step.
- Root problem: availability and actions are compiled against the observed state, not the forecast state.

### 5.3 Coverage: no filler and no secondary objective

- `Unified:49-51` keeps only the actions of `selected` entities: the goals plus their prerequisite entities. Unselected links get no actions at all.
- Stat actions are included only if `wantsStats`.
- Even when included, stat and job actions get `eligible` slots only where the slot is **observed** `knownFree` (`NativeProgressionRules:195-214`), which in practice means the current slot. Future routine training and jobs become `conditional_activity_candidates` with `forecast_credit:false`.
- So **forecast cash and stats never grow**. Every later paid or stat-gated action fails `unknown_requirement` under strict mode. This is the "future resource progression is curtailed" problem.

### 5.4 Scoring prefers emptiness

- Score terms exist only for goal fields.
- The `actionCount`-ascending tie-break makes `__wait__` beat any action with no goal effect.
- Dungeon actions have no effects (`NativeProgressionRules:131`: no money, no floor, no EXP). They are therefore **never** chosen unless pinned, which the experiment confirms.
- After the goals are met, the rest of the calendar is all waits. The UI filters waits out (Home:77, `GoalPlannerWidget.items`, line 12), so the user sees two or three items.

### 5.5 Budget exhaustion truncates

- Each layer costs `|frontier| × (|available| + 1)`. **Failed transitions count toward the budget.**
- Sun/Devil compile 10−rank actions that share the same slot list. At each eligible slot, every node tries all of them and all but one fail on `requirement`.
- Tower actions add one attempt per node per evening.
- The search also keeps expanding after every goal is met.
- With the realistic tower context and two or more chained links, the plan becomes a prefix that ends mid-calendar, with status `search_limit` (see the table).

### 5.6 Pins truncate

- A pin whose action id isn't in the rules makes `requiredAction` throw `unsupported_action`, and the loop `break`s (lines 233-235).
- A pin that every frontier node fails leaves `candidates` empty, which also breaks the loop.
- Dungeon action ids include an availability tier (`p3r.tartarus.<stage>.<tier>`, `NativeProgressionRules:126`). When the date arrives, the tier changes from `routine_future_evening` / `source_positive_future_night` to `observed_current_entry`, or the slot is skipped entirely. The pinned id disappears, so the plan is **truncated at that slot**, and to zero steps if the pin is on the current slot.

### 5.7 Smaller issues

- Unknown-low goals contribute `-10^30` to progress.
- Raw stat targets (converted from rank goals at `Unified:81-83`) are summed in the same priority bucket as link ranks (1–10), so stat points dominate numerically.
- Prerequisite helper goals get priority `min-1` and act only as tie-breakers.

---

## 6. Output format consumed by the UI and widget

- **Engine `rawResult`** (lines 416-425):
  - `run_id`, `rules_version`, `state_revision`
  - `steps:[{instance_id: "<requestHash>:<slot>:<actionId>", action_id, slot}]`. Steps are contiguous from the current slot and include every `__wait__`.
  - `forecast`: the **final** state only (`values{field:{low,high}}`, `visits`)
  - `status`, `explanations[]`, `expanded`
  - `plan_id = hash(request token + [slot,action] pairs)`. `requestId` (lines 427-431) hashes the state, rules version, sorted goals and constraints.
- **Unified adds** (lines 116-130):
  - `replay_validated`, `unproven_lower_bound_goals`, `forecast_stat_ranks`, `supported_plan_status`
  - `pin_reservation_status`, `all_pins_honored`, `unhonored_pins`
  - `adaptive_reservations[]`: `{kind, entity, at_rank, slot, date, period, display_title, status:"requires_observation", next_action, …}`
  - `requested_goals`, `planned_goals`, `unsupported_goals`, `goals_needing_observation`, `prerequisite_goals`
  - `diagnostics`, `observation_checkpoints`, `labels{slot:{date,period}}`
  - `action_metadata{id:{display_title, entity, from_rank, to_rank, event_gain, assumptions, …}}`
  - `conditional_activity_candidates`, `dungeon_candidates`, `story_milestones`
  - flags such as `model_scope:"partial_audited_composite"`, `optimality_proven:false`
- **Coordinator** wraps this as `{plan, plan_revision}`.
- **Widget** (`GoalPlannerWidget.items`, lines 10-15) drops waits and merges the non-wait steps with `adaptive_reservations` that have a slot, sorted by slot. Each item is `{instance_id, action_id, slot, title, entity, kind (tower|stats|resources|social, guessed from substrings of the id), reservation}`. The widget shows the plan only if `plan.state_revision == snapshot.revision` (line 20).
- **Home** (`renderPlan`, lines 73-86) does the same projection, plus fixed sections for milestones, the top 3 tower candidates (each with a "预留" button that pins), and the top 3 unconfirmed training/job alternatives.
- **Gaps:**
  - no per-step forecast (expected rank, cash or stats after each step)
  - no expected outcome per item
  - no per-slot alternatives
  - no plan-span or useful-count summary

  So "accept the predicted result" cannot be built on the current payload.

---

## 7. Coordinator and store: revisions, CAS and stale results

- **Ledger** (`PlannerStateStore`): tables `runs(revision, epoch, snapshot, plan_revision)`, `checkpoints(epoch, baseline)`, `events(command_id UNIQUE per run, epoch, kind, payload)`, `preferences`, `plans(plan_revision, state_revision, rules_version, payload)`.
  - Observed state = the checkpoint for the current epoch, plus a replay of that epoch's events (`replay`, lines 205-233).
  - Dungeon corrections *replace* the original return's payload, so a correction never consumes a second visit (lines 210, 223, 250-255).
- **CAS:**
  - Every mutation requires `expected_revision == runs.revision` (`revision()`, line 126), else `StateConflict`. On success the revision is incremented.
  - `save()` (lines 137-140) **deletes the plan row** on every state change.
- **Idempotency:** `existing()` (lines 128-133) returns the prior receipt if the `command_id` was already applied with the same kind and payload, and conflicts if the payload differs. The UI persists one `pending` command (with a UUID) in SharedPreferences under `COMMAND_LOCK` and offers retry (Home:65-67).
- **Plan install** (lines 349-355): CAS on the state revision captured at compute time, plus a rules-version check. `plan_revision` is incremented and the row is replaced.
  - `replan()` reuses the cached row only if the state revision and rules version match (`PlannerCoordinator:29`).
  - If the snapshot's `rules_version` differs from the unified version, the plan is returned **uninstalled**, with `plan_revision:null` (line 32).
- **Stale-result rejection:**
  - (a) the install CAS
  - (b) widget intents carry `plan_revision` and `state_revision`; Home rejects a mismatch with "安排已更新…" (Home:58)
  - (c) `recordObservedAction` supports `expected_plan_revision` plus `action_instance_id` binding (lines 284-303), but **no UI path sends it**: there are 0 occurrences in `GoalPlannerHomeActivity`, `GoalPlannerActivity` and `PlayerStateForm`.
- **Concurrency:**
  - Coordinator methods are `synchronized`. The 60k-transition search runs while holding the lock on the worker thread.
  - Each activity instance creates its own coordinator and SQLite helper, and the widget opens a separate store. The CAS covers this, but `withReplan` turns a `StateConflict` into `replan_pending:true` with a reason. `withReplan` is used for setup, preferences, observed stats/social, and returns. `/api/plan` itself propagates the exception.
- **Constraints that matter for user experience:**
  - An observed action must have `action_slot == observed game_slot` (store line 294). The store does allow `next_game_slot` to jump ahead.
  - Home's result flow sends **any future-dated item to the full setup form** (Home:61, "这是未来时段的安排。先核对并更新…").
  - `/api/baseline` and `/api/setup` start a new epoch, so the history of the old epoch no longer feeds the snapshot. Preservation depends entirely on `PlayerStateForm` pre-filling.

---

## 8. Keep vs rewrite for a full-horizon simulator

**Keep (with minor fixes)**

- **The store and ledger:** event sourcing per epoch, idempotent command ids, CAS revisions, deleting the plan on mutation, installing the plan under CAS, correcting a return without a new visit, legacy isolation.
  - Add plan-instance binding from the UI.
  - Add an event that reports outcome codes.
- **The interval value type and its rules:** unknown ≠ 0, costs come before effects, requirements are checked against the low bound so forecasts can't overspend, assignments and forgets are explicit.
- **The status vocabulary** (never claims infeasibility) and the hashing for request, plan and instance ids. Make `requestId` cheap.
- **`GameDate`** as is. **`RulePack`** hash verification as is.
- **The evidence metadata pipeline:** `action_metadata.assumptions`, `derived_checkpoint_facts`.

**Rewrite**

1. **Generate actions from the forecast state, every day.** Replace the static precompiled `slots` sets and the observed-state-only adapters with a function `available(slot, forecastState)`, so that:
   - a forecast unlock opens that link's calendar
   - weekday routine availability for training, jobs and tower nights comes from evidence, with explicit exceptions (story locks, accepted invitations on Dec 24, Jan 21, Apr 22)
   - each link offers one "meet X" action whose outcome is decided by the affinity model, not N rank actions that mostly fail
2. **Model link progress.** Per link, track `{rank, pointsTowardNext:[lo,hi], meetingsSinceRank}`.
   - A meeting is the same user action whether it turns out to be a rank-up or a filler. Uncertainty affects *when* the rank-up happens, not whether to schedule the meeting.
   - Forecast a rank-up when a documented conservative estimate is reached. For example, filler meetings add a labelled-estimate gain, with the unverified +10 claim discounted.
   - Report `forecast_basis:"estimate"`, kept separate from proven lower bounds.
   - This one change removes cause 5.1 and lets plans run to Jan 31.
3. **Replace the search.** The generic beam has no deduplication and counts failed transitions. Use a domain scheduler instead:
   - Run a deadline- and scarcity-aware greedy pass over the remaining days (links with the fewest remaining eligible slots get priority).
   - Within each day, a lookahead or rollout (a few hundred cheap rollouts, or a beam over distinct state keys with dominance pruning) chooses between competing links.
   - The objective, lexicographic: (1) explicit goals by priority (completion, then earliness, then a slack buffer); (2) number of other links maxed; (3) Σ rank progress on other links; (4) stat ranks needed for gates; (5) cash buffer; and a penalty for reliance on estimates.
   - Always fill the calendar: tower nights within the per-stage caps, jobs when cash is needed, training when a stat gate is coming up.
4. **Make the output a timeline.** Each item should carry `{slot, action, expected_outcome (e.g. "预计 6→7" or "普通约会，好感+估计"), basis: observed|proven|estimate, projected_state_delta, alternatives[2-3]}`. Add a compact per-day forecast snapshot and a summary with `plan_span` and `useful_actions`.
5. **Write a real independent validator.** It should check invariants directly from the evidence tables rather than re-running `advance`: no locked or forbidden dates, one action per slot, no overspend at the low bound, caps respected, gains propagated, observed state not mutated. Throw an `Exception`, not an `AssertionError`.
6. **Pins:** key them by (slot, kind and stage), not by tier-specific ids. Report an unhonourable pin instead of truncating the plan.
7. **Plan stability:** give a small bonus for keeping the previous plan's (slot → action) assignments, so a replan after a small deviation doesn't reshuffle every later date.

---

## 9. Input and deviation handling (the user's question, from the engine and coordinator side)

1. **Make "Done" accept the prediction.** Each plan item carries an `expected_outcome`. Done records an `observed_outcome` event, `{instance_id, outcome_code}`, and the engine derives the fact changes (rank+1, reset points, cash −cost, stat +base). This needs the existing `expected_plan_revision` / `action_instance_id` binding to actually be used.
   - **Social links:** three buttons, "已升到 N+1" / "没升级" / "其他…". "没升级" keeps the rank and raises the points estimate.
   - **Tower:** a floor field and a money field with an explicit net-change / balance toggle.
   - **Training / jobs:** "按预期" or "修正". Only a correction opens a field.
2. **"I did something else":** show the item's precomputed `alternatives`, plus "其他社群…" and "什么都没做". It is one event, no form.
3. **"I skipped some days / I'm back weeks later":** the only required input is the current date. Intermediate slots become unknown, and every other observed value is kept (ranks, stats, cash, bounds). Then offer a short checklist of only the plan items in the skipped window ("这些做了吗？" ✓/✗/升级了?) instead of the full form. The store already allows `next_game_slot` jumps, but Home:61 forces the full setup form for future items; that must change.
4. **One-field corrections:** add per-link and per-stat quick edits from the plan list. Each one is an observed event, not a new epoch, so history and bounds survive.
5. **After every event:** replan quickly from the latest observed state, install under CAS, then show a one-line change note (e.g. "恶魔 11/3 → 11/5"). Use the stability bonus from item 8.7 to keep these changes small.
6. **Display:** show observed values plainly and forecast values as "预计". Explain estimate assumptions once in the detail view, not as badges on every widget line.