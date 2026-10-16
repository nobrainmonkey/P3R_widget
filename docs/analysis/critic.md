# Completeness critique: contradictions, resolved facts, open questions

Historical v2 analysis. File paths and line numbers below refer to the source snapshot examined
at the time; the old handoff files were removed on 2026-10-15 and remain in Git history.
For current implementation requirements, see [the v3 design](../DESIGN_v3.md).

I resolved several gaps by reading project files, the game-data TSVs the sources reader downloaded into `scratchpad/dl_gh/`, and the downloaded Steam threads in `scratchpad/dl_steam/`. No project files were modified. Gradle was not run.

---

## A. Contradictions between reports, and how each resolves

**A1. Plan steps do carry `instance_id`. The UI report is wrong.**
- The UI report says "plan steps carry no instance_id … feature unused and currently unusable". The engine report says steps carry `instance_id`.
- `PlannerEngine.Step.json()` writes `instance_id`, `action_id` and `slot`.
- `GoalPlannerWidget.items()` (`GoalPlannerWidget.java:12`) copies each step with `new JSONObject(step.toString())`, so widget items carry it too.
- `PlannerCoordinator` already passes `expected_plan_revision` and `action_instance_id` into the store payload.
- `PlannerStateStore.java:301` matches `action_instance_id` against `step.instance_id`.
- **Fact:** plan-instance binding works end to end. Only the UI fails to send it. That makes binding the targeted result panels to plan steps cheap.

**A2. English "Current/Done/Apr" on the widget: the UI report and the widget report disagree.**
- The UI report says English is "intentional per the design doc". No handoff document says that; I grepped `P3R_HANDOFF_PROMPT.md`, `docs/handoff/*.md` and `android/*.md`.
- `P3R_HANDOFF_PROMPT.md:103` requires the UI, terminology and app to all be Simplified Chinese using the official ATLUS translation.
- Line 61 names the buttons "Current" and "Done" only as roles and positions.
- Lines 3 and 59 ask to preserve the visual design.
- **Resolution:**
  - Keep the positions (Current left, Done right and primary) and the visual style.
  - Use Chinese labels: 当前 / 完成.
  - Show the date as numerals ("4/21") or 4月21日.
  - Owner decision: whether small English text may stay as decoration.

**A3. Tartarus stage dates.**
- The data and adapters reports use the contract dates (06-14, 07-18, 12-10) and report gaps from Jul 9–17 and Dec 3–9.
- The sources report says game data gives 06-13, 07-09 and 12-03.
- **Verified:** `ActivityDataAsset.tsv` lists these block openings:

  | Opens | Floors |
  |---|---|
  | 4/21 | up to 22 |
  | 5/10 | 23–43 |
  | 6/13 | 44–69 |
  | 7/9 | 70–92 |
  | 8/8 | 93–118 |
  | 9/10 | 119–144 |
  | 10/6 | 145–172 |
  | 11/6 | 173–198 |
  | 12/3 | 199–226 |
  | 1/1 | 227–256 |
  | 1/31 | 257+ |

- **The two sources measure different things.** Game data gives the block's opening date. The contract gives the first night the stage is known to be enterable.
  - The contract's 12-10 is explained by the Tartarus-only closures 12-02 to 12-09 in `global_slot_locks_expansion_candidate.dungeonEntryOnlyRestrictions`.
  - The contract's 07-18 is explained, after 7/9, by the 07-11/12 closures and the 07-14 to 17 exam locks.
- **Recommendation:**
  - Identify the stage, and key per-stage visit caps, from the game-file opening dates. That removes the "contested" stage picker from the UX.
  - Decide entry per night from the closures and locks.
  - Only the 7/9, 7/10 and 7/13 evenings remain ambiguous.

**A4. Lovers 5→6 threshold.**
- The data report has "0 vs 22", and the adapters report has "null → chain stops at 5".
- **Verified:** `CommunityEventDataAsset.tsv`, row 7 (Lovers, Rank4..Rank10) reads 15, 22, **22**, 35, 35, 40, 55.
- **Resolution: 22.** The 0 came from the wiki `Rank_Up_Events_3` anchor, which is likely not Reload data.

**A5. The "Missing aqiu points become 0" bug in data report §11 #1 is not a value bug.**
- The game table confirms Emperor Rank4 = 0 and Aeon Rank4–Rank9 = 0, with Rank10 = 30.
- Only the provenance label needs fixing: upgrade it to "game-data extraction".
- Also confirmed:
  - Rank1–Rank3 = 0 for every link.
  - Fool, Death, Devil, Sun and Judgement are 0 at every rank.
  - So these steps are deterministic rank-ups, `certain:true` in the UX `result_spec`: R1→R3 for every link, Aeon up to R9, Emperor R3→R4, Sun, and Devil after payment.

**A6. Job stat gains and slots.**
- The jobs file and the aqiu-derived reports swap the stats.
- `BMD_ArbeitHelp.tsv` (game help text):
  - Café: "boosts a person's charm", Night, ¥2,500+.
  - Be Blue V (`MSG_ARBEIT_SKILLSHOP`): **"Time: Day"**, ¥3,500+. This settles the day-only versus day-or-night conflict as **day only**.
  - Screen Shot (`MSG_ARBEIT_MOVIE`): "Inspire people to **learn and be courageous**", Day, ¥5,000+. This weakly supports Academics plus **Courage**, matching `jobs_wage_rules_candidate.json` and contradicting aqiu and the sources report (Academics plus Charm).
- The Be Blue V stat pair stays open.

**A7. Engine future: keep or replace `PlannerEngine`.**
- The adapters report says keep `PlannerEngine` and the replay validator. The engine report says replace the search and write an independent validator. The tests report wants an independent `validatePlan` replay.
- **Reconciliation:**
  - Keep `Interval`, `State` and `advance()` as the transition kernel.
  - Replace `plan()`, `score()`, `sort()` and the static per-action `slots` with day-by-day generation from the forecast state.
  - Add an invariant checker that does not call `advance()`. The design doc's replay assertions (line 97) require independence.

**A8. Dialogue choices on plan items: the UX report's source is wrong.**
- The UX report says the widget can show "`known_source_choices` 的中文原文".
- In `NativeManualRules`, `known_source_choices` is an **integer** (`path.known`). `selected_source_options` holds **foreign source ordinals**, which the doc (line 67) and START_HERE forbid showing.
- **The correct source is `rules/p3r/guide_dialogue_reference_map.json`:**
  - 79 references keyed by `entityId` + `targetRank`, each with `displayFragments` (CN guide text verbatim) and a `displayPolicy` (`ordinalTextIsUnverifiedGuideNotation`, `noPromptOrdinalOrPointBinding`).
  - No Java code reads it.
  - Coverage is partial. Examples: Emperor has references only for ranks 2, 3 and 10; Fool and Judgement have none; Magician has 2, 3, 4, 6 and 10.
  - For "皇帝 6→7", look up `targetRank:7`. If none exists, fall back to "按游戏内中文选择，优先能让对方高兴的回答" rather than numbers.

**A9. The UX report's horizon is wrong.**
- The onboarding month row includes 2 and 3, and the progress text says "→ 3月31日".
- The horizon is slot 612, exclusive: Jan 31 is the last day, and the Jan 31 evening is the final operation (lock).
- **Fix:** the date picker offers 4–12 and 1 only. The progress text says "→ 1月31日".

**A10. Size of the startup rule load.**
- The UX report says the form `RulePack` is about 6 MB. The adapters report says about 27 MB.
- Measured on disk: `rules` 6.8 MB, `rules_v03` 6.9 MB, `rules_v04` 9.8 MB (the `RulePack` directory), `rules_v051` 11 MB.
- The adapters report is closer. Either way, the conclusion holds: render result panels and the widget from data precomputed in the plan payload, not by loading packs.

**A11. "Unknown slot" policy.**
- The adapters report says no "unknown = free" policy exists. That is true of the Java code only.
- The unshipped `rules/p3r/full_horizon_forecast_policy.json` already defines one:
  - `availability.weeklyFallback`: "Enabled only in scenario mode … a lack of a positive record is not itself a global hard stop".
  - A four-level priority: observed, then locks and exceptions, then exact O cells, then weekly rule.
  - `dashPolicy`.
  - The `ordinaryMeetingModel` (estimate 10, `ceil(d/10)+1` fillers, rank event in a separate later slot, surplus reset).
  - `resultLabels.briefUiZh` "预估日程 · 已预留余量".
  - Test fixtures.
- This matches the design doc (line 32). Adopt it as the scenario layer.

**A12. Catch-up defaults conflict across four reports.**
- Widget report: each item defaults to "?".
- UX report: values are prefilled with the forecast, marked "预计"; uncertain cells need a choice.
- Sources and adapters reports: everything is ticked, the user unticks.
- Tests report: defaults to 按计划.
- Constraint: `docs/handoff/NATIVE_INTEGRATION.md:38` says a goal widget must route predicted actions through observed-result confirmation rather than silently applying forecasts.
- **Recommended merge** (details in §D):
  - Deterministic forecasts (gate-0 ranks, Sun, Devil, known costs) are prefilled.
  - Estimated rank-ups are shown as a "6 / 7" pair that must be tapped.
  - One explicit "以上都和游戏一致" button counts as confirmation.

**A13. Can widget Done commit directly?**
- UX report: yes, for deterministic actions. Widget report: always open a sheet. Tests report: a product decision.
- **Reconcile with `NATIVE_INTEGRATION.md:38`:** a tap may commit directly when its label states either a visible outcome ("已升到 RANK 7") or the action performed ("完成 · 房间学习").
  - What is recorded is the observed action. Verified base gains (stat +2, the wage floor) go to lower bounds. Estimated gains (filler affinity) go only to the scenario.
  - A bare "Done" on an uncertain social item must not commit. It opens the outcome row or sheet.

**A14. Terminology choices.**
- **Link rank:** "RANK" (UX report and CN guide: 83 guide items mention RANK, e.g. "战车RANK→2") versus "级别" (Steam SC achievement text) versus the current "等级". Recommendation:
  - Use **RANK** in compact UI and the widget, matching the guide and likely the in-game screen.
  - Use 级别 in prose if the owner confirms it.
- **Stats:** use the guide's "LV3 / MAX". No official SC stat-rank names were found; `DT_Hero_Parameter_Name.tsv` has Japanese only.
- **Priority input:** the reports disagree (numbers, drag, stars, or tiers). Recommendation:
  - Use tiers 必须 / 想要 / 顺便, plus tap-to-order within 必须. There is no RecyclerView, so drag is costly.
  - Turn "其余社群也顺带提升" on by default. That satisfies design doc objective 3.

---

## B. Resolved facts (new evidence beyond the reports)

**B1. Maxing every link is barely feasible, and only with the Persona bonus. The legacy guide confirms this.**
- `calendar_execution_extension.json` has 1,352 records over **220 distinct daytime days** and 154 evenings. This confirms the data report's figure of about 220.
- The legacy `assets/data.json` reaches MAX on **all 22 arcana** (Fool 12/31 … Aeon 1/28).
- It tells the player to carry the matching Persona ("确保身上持有“X”面具") in **146 items**.
- So the only known full-max route depends on the ×1.51 bonus (`DT_GeneralCoefficient`: `CMM_POINTBONUS_ARCANA` 1.51, `CMM_POINTBONUS_CHARM` 1.51, exam 1.51 / 1.21).
- The design doc (line 24) forbids silently assuming it.
- **Consequence:** the Persona-carry declaration is the single most consequential input. The UI must ask it explicitly, once, with a default of 否. Without it, honest output for "max everything" is "按优先级取舍", never "impossible".

**B2. The legacy guide is useful to the planner in three ways that no report mentions:**
- It is a feasibility witness for regression tests: a with-Persona scenario should approach its outcome.
- It is a warm start or seed for the search.
- It can suggest prefill values: "沿用固定攻略进度" could propose ranks from completed items matching `X→RANKn`. These must be shown as suggestions that need confirmation, not as observations.

**B3. The rank-up readiness cue very likely exists in the game.**
- Two separate Steam threads, downloaded in `scratchpad/dl_steam/`, report it:
  - Thread `4206994023684392768`: once points meet the threshold, "you'll see the 'rank up' message when you view the city details on the map".
  - Thread `4206993639701168068`: a normal not-ready line, quoted as "it doesent seem like i can rank up", versus a different line, quoted as "it does not matter how many times i spend time with them i wont rank up", for Hierophant at R4 after a school-tree rumour.
- This is the cheapest high-value input. It maps onto the existing field "游戏是否明确提示下次可升级" (`PlayerStateForm.java:52`).
- A "yes" removes the policy's extra safety filler. A "never" message is a different outcome; see C2.
- It still needs the owner to confirm it in the SC game, including the exact wording.

**B4. Rescues decide whether Hierophant and Hanged Man exist at all.**
- `BMD_DisappearHelp.tsv`: Bunkichi Kitamura was reported missing **9/11**; Maiko Oohashi **10/21**.
- aqiu `overworld.md`: "Failing to save Bunkichi will lock the Hierophant SL" and "…Maiko will lock the Hanged SL". The windows are 9/12–10/3 and 10/21–11/2.
- Today's code never writes rescue facts, so both links are unschedulable after those dates (adapters report §3).
- The planner must:
  - reserve at least one Tartarus night inside each window when either link is a goal or a secondary target;
  - count that night against the user's per-stage cap;
  - ask "文吉先生救出了吗？" or "舞子救出了吗？" just in time;
  - leave the link unavailable between the disappearance and the rescue.

**B5. Official zh-Hans names, verified in `DatPlayerNameDataAsset.tsv`:**
- **贝培**. The zh-Hant column is 貝貝, which explains the guide's 贝贝.
- **伊莉莎白**.
- **舞子**, with no surname.
- Apply these fixes as the sources report lists them.

**B6. There is no game-data constant for ordinary hangout points.**
- `DT_GeneralCoefficient.tsv` has only the four multiplier rows.
- +10 stays a labelled estimate, as the doc requires.

**B7. Code facts confirmed:**
- `UnifiedPlannerRules.java:77` sets `strict_requirements=true` on every action.
- `NativeUnlockRules.preparationFormSchema()` exists at line 199, as the UX report assumes.
- `GoalPlannerWidget.java:27` builds the motif key as `"%02d-%02d"`, so the widget report's mismatch with the legacy widget is real.

**B8. Signing.**
- The only keystore on this machine is `/Users/xihehan/P3R_Widget/.toolchain/android-user-home/debug.keystore`, generated today. I searched `/Users/xihehan` to depth 4 and used Spotlight; `~/.android` contains only adbkey files.
- A usable upgrade APK is therefore blocked until the owner provides the original key.
- Without the key, the fallback is a separately named `.dev` app.
- The installed 2.0.0 has no export for the v2 planner, so planner state cannot be migrated: the user re-enters the current state once in the dev app. Legacy guide progress can still move through the guide's JSON export.

---

## C. Open questions that block a full-horizon planner and a polished UI

### P0: decide before implementation

1. **Persona and Charm bonus policy.**
   - Is the Persona declaration global or per link?
   - Does ×1.51 also apply to ordinary hangouts? The source claims +15 with a matching Persona, from the same single source.
   - Charm 6: apply the bonus only once Charm is observed at LV6, or also when it is forecast to reach LV6 (labelled)? The doc forbids only a *silent* assumption.
2. **Ranks that hold until a story event or rumour.**
   - The Steam report says Hierophant R4→R5 is blocked until a school-tree rumour.
   - No rule file has a field for prerequisites in the middle of a rank chain. `manual_link_models.json` has only unlock `all_of`.
   - Without this data, the planner will schedule futile meetings.
   - Minimum mitigation: an outcome "游戏提示暂时无法升级" that freezes that link until the user reports progress, plus a Reload research pass to list such gates.
3. **Rescue scheduling versus caps.** Design is in B4. The floor where each missing person appears is not in the data. Default UI text could say "在本阶段探索到可达最高层附近" (unverified).
4. **Confirm the readiness cue** and its SC wording in the game (B3). It decides whether the main per-meeting input is "地图上显示可升级了吗？".
5. **Cash forecast source.**
   - Tartarus income is intentionally null, so paid training is never affordable in the forecast unless jobs are scheduled.
   - Options:
     - (a) the median of the user's own recorded tower returns for the current stage, labelled "按你的记录估计" (I recommend this, since the data is observed);
     - (b) a one-time user estimate;
     - (c) jobs only.
6. **Scenario defaults for guarded dates:**
   - Dec 24: default "decline all" unless the user has a romance goal.
   - Jan 21: default unknown, ask after Jan 14.
   - The 18 unresolved event contexts (Yakushima 7/20–23, track camp 7/27–8/2, 1/1, 11/4, and others): I recommend treating them as locked in the scenario until disproven.
7. **Replan latency on the phone.**
   - The full horizon covers 612 slots and 22 links.
   - Set a budget, e.g. 1.5 s or less on a low-end phone.
   - Decide whether replans run in `JobService` with "按预期" taps advancing the widget immediately (UX §2.3), or stay synchronous behind a spinner.
8. **Delivery identity:** an owner-supplied key versus the `.dev` side-by-side app (B8).

### P1: correctness and polish

9. **Rank events with no CN guide text** (about 74 of 153): settle the fallback copy.
10. **The CN guide's "（n）" markers.** Are they the ♪ count or the option position? The reference map flags them `ordinalTextIsUnverifiedGuideNotation`. Display them verbatim and never bind them to points.
11. **Holiday invitations** (34 dates in `HolidayScheduleDataAsset`): does accepting count as a meeting with rank or points, and does it consume the daytime slot? This also decides the "今天收到谁的邀约？" prompt.
12. **Remaining data conflicts:** the Be Blue V stat pair; Library +2 or +4; official SC stat-rank names; RANK versus 级别 in the SC game UI.
13. **Free gains** (class answers, exam ranking, summer school +18 Academics): ignore them conservatively, or model them as story auto-gains so stat gates arrive earlier?
14. **Link Episodes and dorm activities:** I recommend offering them only as "做了别的" options, never planning them.
15. **Widget English month:** owner decision (A2).

---

## D. Input and deviation UX, consolidated after the corrections above

**Principle:** the plan supplies the default answer.
- What the user confirms is either the action they performed or a visible result.
- Verified consequences go to lower bounds. Estimates stay in the scenario.
- Effort scales with how surprising the result is: 1 tap when as predicted, 2 for a binary surprise, 3 for doing something else.

1. **Per-item result, from the widget or the app.** Data comes from a `result_spec` precomputed in `plans.payload`, keyed by the existing `instance_id` plus `plan_revision`. Command ids are derivable for idempotency.
   - **Deterministic rank-up** (gate 0, Sun, Devil, Aeon up to R9): one tap "已升到 RANK N+1".
   - **Estimated rank-up:**
     - Equal-size buttons 已升到 N+1 / 没升级.
     - Optional chip "游戏提示可升级？ 是 / 否 / 没看".
     - A third outcome, "提示暂时无法升级", for story holds (C2).
     - "没升级" keeps the rank and adds scenario fillers. It does **not** reset the evidence to unknown, as `socialObservation` does today.
   - **Filler meeting:** 已见面 / 没见到, with the optional readiness chip.
   - **Training or job:** "完成" records the action. The visible-rank question appears only when the forecast interval crosses a threshold. A job fills the balance from the wage floor and is editable. It must no longer route to `setup()` (Home:61).
   - **Tartarus:**
     - A sheet, because RemoteViews cannot take text input.
     - Cash mode "现在余额" (default) or "本次增减", exclusive.
     - Floor stepper clamped to the stage ceiling, optional protagonist level.
     - A "没去成" reason.
     - Corrections reached from the history list, so a correction never adds a visit.
   - Remove the end-date, period and capacity fields from result entry. The next slot is always slot + 1.
2. **Deviations, each a single event:**
   - **做不了:** a reason (剧情 / 对方不在 / 钱不够 / 不想做), which records an availability observation, then "那这个时段做了什么？".
   - **改做别的:** the actions that were legal in that slot, generated from the forecast state.
   - **Skip:** a new `/api/skip` that calls `recordObservedAction` with empty `fact_assignments`.
   - **Undo:** a `void` event plus replay from the checkpoint, generalising `return_correction`. Look the target up in the `events` table, not in `last_return` in SharedPreferences.
   - **Done on a future item:** "做到这里" with "前面 N 项：都完成 / 逐项核对 / 都没做".
3. **Returning after a long gap ("同步现状"):**
   - The date comes first, defaulting to the last record plus the plan.
   - Then a grid of visible values only: cash, stat LV chips, RANK chips for touched links, and stage visit count (asked only if caps are set).
   - Deterministic forecasts are prefilled. Estimated changes show as a "6 / 7" pair that must be tapped.
   - Hidden affinity resets to the conservative estimate unless the readiness cue is reported, so after a gap that is the one high-value question per goal link.
   - Everything else carries over unchanged.
   - Save is one CAS command, then replan, then a one-line change summary.
4. **Onboarding, in this order:**
   - Goals: an arcana grid using `selectorLabelZhHans`, with tiers.
   - The **Persona-carry question**.
   - In-game date, limited to 4–1 (A9).
   - Cash and stats.
   - RANK pad with auto-advance to the next link.
   - Unlock preparation only where relevant.
   - The stage is derived from the date (A3).
5. **Display:**
   - The "预估日程 · 已预留余量" line appears once per plan, not on every widget row.
   - Goal bars show confirmed versus forecast rank.
   - Status text distinguishes 目标未满足, 规则未覆盖 and 计算上限.
   - Dialogue text comes from `guide_dialogue_reference_map.json` only (A8).

Files cited:
- `/Users/xihehan/P3R_Widget/P3R_HANDOFF_PROMPT.md`
- `/Users/xihehan/P3R_Widget/docs/handoff/NATIVE_INTEGRATION.md`
- `/Users/xihehan/P3R_Widget/rules/p3r/full_horizon_forecast_policy.json`
- `/Users/xihehan/P3R_Widget/rules/p3r/guide_dialogue_reference_map.json`
- `/Users/xihehan/P3R_Widget/rules/p3r/calendar_execution_extension.json`
- `/Users/xihehan/P3R_Widget/android/app/src/main/assets/data.json`
- `/private/tmp/claude-501/-Users-xihehan-P3R-Widget/c55d2fa4-7208-4e25-be91-b3e985a53a3e/scratchpad/dl_gh/{CommunityEventDataAsset,ActivityDataAsset,BMD_ArbeitHelp,BMD_DisappearHelp,DatPlayerNameDataAsset,DT_GeneralCoefficient}.tsv`
- `/private/tmp/claude-501/-Users-xihehan-P3R-Widget/c55d2fa4-7208-4e25-be91-b3e985a53a3e/scratchpad/dl_gh/overworld.md`
- `/private/tmp/claude-501/-Users-xihehan-P3R-Widget/c55d2fa4-7208-4e25-be91-b3e985a53a3e/scratchpad/dl_steam/d_4206993639701168068.html`
- `/private/tmp/claude-501/-Users-xihehan-P3R-Widget/c55d2fa4-7208-4e25-be91-b3e985a53a3e/scratchpad/dl_steam/d_4206994023684392768.html`

Web sources consulted:
- [Steam P3R discussion 4206994256763374102](https://steamcommunity.com/app/2161700/discussions/0/4206994256763374102)
- [Steam P3R discussion 4206993639701168068](https://steamcommunity.com/app/2161700/discussions/0/4206993639701168068)
- [Steam P3R discussion 4206994023684392768](https://steamcommunity.com/app/2161700/discussions/0/4206994023684392768)
- [Prima Games Hierophant guide](https://primagames.com/tips/all-hierophant-social-link-answers-in-persona-3-reload-p3r-social-link-guide)
- [rpgsite Hierophant guide](https://www.rpgsite.net/guide/15341-persona-3-reload-bunkichi-mitsuko-hierophant-social-link-choices-unlock-guide)

The search results for the Hierophant R4 hold were inconclusive; it remains open as C2.
