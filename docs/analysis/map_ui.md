# P3R planner UI, persistence and UX: deep-dive report

## 0. Summary

1. **The UI is built in code and is unattractive.** Every planner screen is built programmatically from framework widgets (`TextView`, `Button`, `EditText`, `Spinner`, `CheckBox`, `AlertDialog`) inside one `ScrollView`/`LinearLayout`. There are no XML layouts (XML is used only for the widgets), no AndroidX and no Material Components. Colors are hard-coded hex values in Java. In the QA renders (`/Users/xihehan/P3R_Widget/qa_out/planner_v2/qa/native_home/*.png`), default grey Material-Light buttons and pale-blue spinners sit on a navy `#051f3c` page. None of this matches the widget's flat cyan, pink Done and italic-numeral style, or the legacy guide's royal-blue `#0642B7` palette.
2. **Result entry is one generic form, which the design doc forbids.** `resultForm()` (`GoalPlannerHomeActivity.java:37-55`) shows one screen with a type spinner (爬塔归来/社群会面/属性训练), editable month/day/phase/capacity fields and three hidden panels. The rank field is never prefilled from the plan. There is no "已升到 7 / 没升级" button and no way to record "skipped". A job result, or any non-current slot, sends the user to the full setup form (`:61`).
3. **Persistence is solid.** It is event-sourced SQLite with CAS revisions, idempotent command IDs, tower-return corrections and replay. The UI layer on top has weak points:
   - deterministic rejections leave a "pending" token that blocks every later submit;
   - the correction pointer lives in SharedPreferences rather than the DB;
   - only tower returns can be corrected;
   - the export API (`/api/export`) has no UI.
4. **There is no navigation model.** `GoalPlannerHomeActivity` swaps the view tree in place. There is no `onBackPressed`, no `onSaveInstanceState`, no view IDs and no `configChanges`. Back exits the app from any sub-screen. Rotation, or a widget tap arriving via `onNewIntent`, throws away an unsaved result form. Only the setup form has a draft.
5. **The plan list on Home cannot be tapped.** Each step is a plain label: date plus title (`:77`). Step detail (`handleWidgetRequest`, `:57-64`) is reachable **only from the widget**.
6. **English and jargon leak into the UI.** Store and engine exception messages are shown raw: "state or preferences changed; reload before submitting", "observed stage conflicts with verified calendar", `DateTimeException` "Invalid date 'FEBRUARY 30'", and `PlannerEngine` explanations such as "Rule coverage is incomplete; this is only a candidate". The dialog shows raw rule IDs (`规则记录：p3r.rule.study_room_night`). Terminology departs from the original CN guide: 爬塔 instead of 探索塔尔塔罗斯, "Persona" instead of 人格面具, 非正常／需修复 instead of 逆位.
7. **Dead code and assets:**
   - `GoalPlannerActivity` (January Sun/Devil only; `exported=false`, never launched, test-only).
   - `assets/planner_v2/index.html` needs a `PlannerV2` JS bridge that does not exist.
   - `PlayerStateForm.answerAssumption` and `Link.affinity` are never added to the view.

---

## 1. How the UI is built

**Manifest** (`android/app/src/main/AndroidManifest.xml`)
- Launcher activity: `.v2.GoalPlannerHomeActivity`. It has no `configChanges` and no `windowSoftInputMode`.
- `.MainActivity` is the legacy WebView guide, `exported=true`, reached only from the planner's "打开原固定攻略与原进度" button.
- `.v2.GoalPlannerActivity` is `exported=false` and has no call sites (only `GoalPlannerActivityTest`).
- Receivers: `GoalPlannerWidget` (label hard-coded as "P3R 目标规划"), legacy `PlannerWidget`, `WidgetUpgradeReceiver`.
- `allowBackup="false"`: planner data is never auto-backed-up.

**Build** (`app/build.gradle`)
- minSdk 26, targetSdk 35. On Android 15 edge-to-edge is enforced.
- The only dependencies are test dependencies (junit, robolectric). Any UI library would have to be vendored to satisfy "all dependencies in this directory".

**Theme** (`res/values/styles.xml`, `values-v27/styles.xml`)
- `AppTheme` parent is `android:style/Theme.Material.Light.NoActionBar`, a light theme. It sets statusBar `#062D91`, navBar `#062676` and windowBackground `#0642B7`.
- The planner paints its root `#051f3c` (`GoalPlannerHomeActivity.java:18`; `PlayerStateForm.java:28`). Status bar, page and window background are therefore three different blues.
- `ScrollView` has no `fillViewport`, so short screens show the window background below the content (white in the Robolectric render `action-assumptions-top.png`).
- Dialogs and spinner pop-ups use the light theme's defaults.

**Widget factories**
- `GoalPlannerHomeActivity`: `label()` at `:19` (white text, padding 8dp), `button()` at `:20` (default framework Button), `number()` at `:21` (EditText with cyan `0xff9bdbff` underline tint), `select()` at `:22` (Spinner, background `0xffc5e8ff`).
- `PlayerStateForm.java:132-138` duplicates the same factories but uses **raw px** padding (`setPadding(20,12,20,24)`, `label` `0,12,0,8`), not dp.
- Text sizes are ad hoc: 26/25/24/23/22/20/18/16/15/14/13 sp.

**Insets**
- `container()` (`:18`) applies only `WindowInsets.Type.systemBars()` padding on SDK ≥ 30. IME insets are not handled, so under edge-to-edge the keyboard can cover bottom inputs.

**Threading**
- A single-thread `ExecutorService worker` (`:8`).
- `busy` disables every control (`setBusy`, `:70`). There is no progress indicator.
- Replanning (beam search) runs synchronously inside every `coordinator.request()` via `withReplan` (`PlannerCoordinator.java:131-135`). Saves therefore block the UI for the whole search.

**Startup**
- `onCreate` builds `new PlannerCoordinator(this,true)`. This loads `PlannerRules` (`planner_v2/rules/`), `UnifiedPlannerRules` and `NativeProgressionRules` (`rules_v051`), plus a second hash-verified `RulePack("planner_v2/rules_v04/", ...)` for form metadata (`:13`).
- The rule-pack directories duplicate a 5.9 MB `calendar_full_candidate.json` four times. This is all redone on every Activity recreation.

**`assets/planner_v2/index.html`**
- A 34 KB light-themed prototype: "P3R · PLANNER FOUNDATION", January Sun/Devil only.
- It calls `PlannerV2.request` and `PlannerV2.openLegacy`. No Java code registers a `PlannerV2` interface (the only `addJavascriptInterface` is `MainActivity.java:69`, "AndroidBridge"), and nothing loads this file. **It is unused.** It can be deleted, or mined for its card/grid CSS ideas.

---

## 2. Screen inventory (GoalPlannerHomeActivity)

### 2.1 Loading (`:12-13`)
Shows "P3R · 目标规划" and "正在读取离线规则与实际状态…". On failure: "规则无法读取：" followed by the raw exception message.

### 2.2 Home, `home()` (`:26-33`)
Content, top to bottom:
- Title and "记录实际结果，后续安排会自动更新。"
- Status, when a snapshot exists:
  - "实际进度 · 10 月 22 日 白天"
  - "现金 1000　最高楼层 100": no ¥ sign, no thousands separator.
  - "当前阶段已进塔 N 次": the visit cap is not shown.
- Buttons:
  - "选择目标并填写当前状态" or "更新实际状态与目标", which opens `setup()`.
  - "记录行动后的实际结果", which opens `resultForm()`.
  - "刷新并重新规划".
- If a `pending` receipt exists:
  - Text: "有一次提交尚未确认。重试会保留原请求，不会重复记账。"
  - "重试原提交" button.
  - "核对后放弃待重试标识" button, with a confirmation dialog.
- `renderPlan()`, described in section 5.
- "添加目标规划小组件", which calls `requestPinAppWidget` or falls back to a help dialog.
- "打开原固定攻略与原进度".

### 2.3 Full setup, `setup()` (`:34-36`) plus `PlayerStateForm`
- Header: "从现在开始规划", then "已带入上次记录。只改变化的项目；草稿自动保留…".
- The form is built in this order: constructor, `loadPreferences`, `setOperationalStageWindows`, `addTrainingAvailability`, `addUnlockPreparation`, `organizeSections`.
- Draft restore happens only if `setup_draft_revision == snapshot.revision` (`:34`).
- Buttons:
  - "确认实际基线与目标" opens a dialog ("确认这些是当前游戏实际状态？旧候选会失效…") and then `submit("/api/setup")`.
  - "返回".

`organizeSections()` (`PlayerStateForm.java:140`) groups views by **matching label text** against four headings. Each heading becomes a cyan button "n · 标题 ▾" (the arrow never flips). **Only section 1 is expanded by default.** The four sections:

**1 · 当前实际状态** (`:29-32`)
- 月份（4–12 月及次年 1–3 月）: EditText.
- 日期: EditText.
- 下一个当前时段: Spinner 白天/晚上. The wording is confusing.
- 该时段还可行动吗？: 不确定/可以/已经用完.
- 当前现金（未知留空）.
- 历史最高楼层（未知留空）.

**2 · 塔与阶段次数** (`:33-37`)
- Stage spinner. Options read "当前解锁阶段不确定", then e.g. "塔尔塔罗斯 2—22F · 已解锁上限 22 层". The redundant ceiling label produces "· 已解锁上限 ? 层" for stage_11.
- 本解锁阶段已经进塔几次（未知留空）.
- 本阶段最多允许进塔几次（留空不设上限）. This applies **only to the currently selected stage**. Switching the spinner refills both fields from the saved `visits_by_stage` / `visit_caps` (`:66`). There is no UI for future stages' caps.
- Checkbox 按正常主线推进，参考已核对的探索夜 (default on).
- Checkbox 也允许预留普通空闲夜晚 (default off).
- Spinner 可选：今晚已在宿舍确认能出发吗？ (不确定／尚未查看 / 可以出发 / 目前不能出发).

**3 · 可见社交属性** (`:38-43`)
- 学力/魅力/勇气 spinners: "不确定, 1 级 … 6 级".
- The game shows named ranks, not numbers, so the player has to translate.
- An unchanged selection keeps the prior raw interval (`initialStatSelections`, `:102`). This is correct.

**4 · 社群目标与当前状态** (`:44-60`). For each of the 22 links:
- Checkbox "<identity label> · 纳入目标", with identity labels from `planner_v2/social_goal_identity_labels.json` → `selectorLabelZhHans`, e.g. "皇帝 · 小田桐秀利 · 男".
- A hidden detail panel, added **above** its own toggle button.
- Button "展开／收起 <arcana> 的状态".
- Detail fields:
  - 目标等级（1–10）, default "10".
  - 优先级（数字越大越优先）, default "0".
  - 当前可见等级（0 未解锁；未知留空）.
  - 关系状态: 不确定/正常/非正常／需修复.
  - 可选：游戏是否明确提示下次可升级.
  - For priestess/empress/lovers/justice/strength/aeon only: 当前关系类型 (朋友/恋人), 是否已确认满足恋爱分支前提, 未来遇到关系分支时的意向 (友情路线/恋爱路线).
  - For not-yet-unlocked links, via `addUnlockPreparation` (`:69-76`): stage-path spinner "已经完成到哪一步", timing spinner, boolean observation spinners (e.g. "当前可归还钢笔并建立社群") and inventory counts (红色钢笔/神秘食品). These are visible only while rank is blank or 0.
- Also in section 4, because it was appended after the section-4 heading:
  - The note "默认采用保守估计，不预设携带 Persona…".
  - The **training availability** toggle "可用训练地点（用于安排属性前置）", with eight location checkboxes (房间学习, 夏卡尔费洛蒙咖啡, 夏卡尔打工, 电玩大巡游, 定食若津/叶隐拉面/怀老鸭 regular meals, 卡拉OK) and "显示常规营业日的待确认训练选项". **This is misgrouped.**

**Behaviors**
- Editing rank resets `ready` and the invisible `affinity` field (`:59`).
- Changing the date resets the current-slot-only observations: capacity, tower entry, preparation timing, and `current.*`/`calendar.*` flags (`watchDateChanges`, `:139`).
- `collect()` (`:90-123`) requires at least one goal ("请至少选择一个社群目标").
- `ready` is silently dropped unless 关系状态 is set to 正常 (`:95`). Since the default is 不确定, a user's "是" is lost.
- The affinity lower bound is hard-coded to `0` whenever rank and normal change (`:97-98`). It is kept when unchanged.

### 2.4 Result form, `resultForm()` (`:37-55`)
Header: "记录实际结果", then "这次行动开始前的记录：<slot>。若已经偏离该日期，请先更新实际状态。"

**Common fields**
- 行动类型: 爬塔归来/社群会面/属性训练.
- 行动结束后的月份 and 行动结束后的日期, prefilled to snapshot slot + 1.
- 结束后的时段, prefilled.
- 结束后这个时段还可行动吗？: 不确定/可以/已经用完.

**Tower panel**
- 爬塔现金填写方式: 净现金变化（可为负数） / 离开后的实际余额. It auto-selects balance when cash is unknown.
- 爬塔实际金额（社群会面不填）.
- 本次爬塔达到的最高楼层（社群会面不填）.
- Checkbox 修正上次爬塔记录（不再增加次数）.
- The "(社群会面不填)" hints are leftovers from when all panels were visible at once.

**Social panel**
- 社群对象（爬塔不填）: **arcana names only** (`names` comes from `rules_v04/social_links.json` `name_zh_cn`, e.g. "愚者", "太阳"). Person and gender are not shown.
- 实际显示的社群等级（0–10）: blank, never prefilled.
- 当前关系状态.
- 是否明确提示下次可升级.

**Stats panel**
- 训练属性（其他行动不填）.
- 训练后实际属性等级（1–6）: typed number.
- 训练后实际现金余额（未知留空）.

**Saving**
- "保存实际结果" builds `/api/return`, `/api/observed_social` or `/api/observed_stats` (`:48-52`).
- "返回".
- When opened from the widget, `resultKind`/`resultEntity` preselect the type and entity (`:54`). That is the only "targeting" there is.

### 2.5 Widget item detail (`handleWidgetRequest`, `:57-64`)
- **Stale check:** if the intent's `EXTRA_STATE` or `EXTRA_REVISION` does not match the current snapshot revision and `plan_revision`, it shows "安排已更新…". It then matches the item by slot, action_id, entity and reservation.
- **Routing:**
  - Done on the current slot opens `resultForm` directly.
  - `kind=resources` (job) opens `setup()` with "请填写打工后的实际余额与当前日期…".
  - A future slot opens `setup()`.
- **Otherwise** it renders the date, title, a hint and the text from `actionDetails()`, with the buttons "记录这个行动的实际结果" and "返回全部安排".

### 2.6 Dialogs
- Setup confirmation.
- Pending-discard confirmation.
- Tower reservation: "预留这个晚上探索？…" / "预留并重排".
- "查看这个备选的条件".
- "查看条件与证据说明", showing `planDetails()` (`:88`). This concatenates **raw** `explanations`/`diagnostics` messages; `PlannerEngine` emits English for these.

---

## 3. Persistence

### 3.1 SQLite database `p3r_goal_planner_v2.db`, version 1 (`PlannerStateStore.java:31-35`)

| Table | Columns | Purpose |
|---|---|---|
| `runs` | `run_id` PK, `revision`, `epoch`, `snapshot` (canonical JSON), `plan_revision` | Current observed state. `run_id` is always `"local"` (`PlannerCoordinator.java:9`). |
| `checkpoints` | (`run_id`, `epoch`) PK, `payload` | Baseline at each setup/re-entry. Replay starts from here. |
| `events` | `seq` autoinc, `run_id`, `command_id`, `epoch`, `kind`, `payload`, **UNIQUE(run_id, command_id)** | Kinds: `initial_setup`, `setup`, `baseline`, `preferences`, `dungeon_return`, `return_correction`, `observed_action`. |
| `preferences` | `run_id` PK, `payload` = `{goals:[{field,target,priority}], constraints:{visit_caps:{stageId:n}, pinned_actions:{slot:actionId}}}` | Validated by `preferencesPayload` (`:310`). |
| `plans` | `run_id` PK, `plan_revision`, `state_revision`, `rules_version`, `payload` | Cached plan. `save()` deletes it on every state change (`:137-140`). |

`onUpgrade` throws "Planner schema migration required…" (`:37-38`). Any schema change therefore needs real migration code.

### 3.2 Snapshot JSON (written by `PlayerStateForm.collect`, `:119-122`)
- Top level: `rules_version`, `game_slot` (`(days since 2009-04-01)*2 + evening`, `GameDate.java`), `cash` (null means unknown), `highest_floor`, `active_stage`, `visits_by_stage{stageId: n|null}`, `facts`, `legacy_guide_backup`, `run_id`/`revision`/`epoch`, `visits_since_baseline`.
- `facts{field:{low,high}}` keys:
  - `socialLinks.<slug>.{rank, alreadyUnlocked, normal, ready, readyRank, affinityLowerBound, affinityRank, affinityValid}`
  - `stats.<id>`, `socialStats.<id>.raw`, `socialStats.<id>.rank`
  - `inventory.red_fountain_pen`, `inventory.odd_morsel`
- `planning_context`:
  - `gameEdition`
  - `socialLinks{slug:{relationshipType, romanceEligible}}`
  - `unlock{slug:{stage, lastPossiblePrepOrdinal, observations{…}}}`
  - `checkpoint{date{ordinal}, userConfirmedCurrentState}`
  - `calendar{slotStates{slot:{remainingCapacity, slotState, knownStoryLock}}, statActionStates{slot:{…}}}`
  - `progression{repeatableActivityGuards, assumeFutureRoutineAvailability}`
  - `tartarus{normalStoryProgressionAssumed, assumeRoutineFutureEntry, entryAvailableObserved, entryObservationDateOrdinal, entryObservationSlot, optionalExplorationUnlockedObserved}`
- `option_policy{relationship_decisions{slug: friendship|romantic}}`. `declared`/`mode` would be written only if the never-displayed `answerAssumption` box were checked (`:61-62, 120`).
- Note: `collect()` **replaces** `progression` wholesale and resets `calendar.slotStates` to only the current slot (`:114-117`).

### 3.3 Revisions / CAS / staleness
- Every command carries `expected_revision`. `revision()` (`:126`) throws `StateConflict` when it does not match.
- `installPlan` (`:349`) checks the state revision and `rules_version`.
- `replan()` (`PlannerCoordinator.java:25-47`) returns the cached plan only if `state_revision` and `rules_version` match.
- Widget intents carry `EXTRA_STATE`/`EXTRA_REVISION`. They are validated in `handleWidgetRequest`. Stale taps cannot commit; this is tested (`staleWidgetTapCannotCommitOrOpenOldResult`).
- The store also supports **plan binding** (`expected_plan_revision` plus `action_instance_id`, `:271-305`). However, plan steps carry no `instance_id` (grep of `PlannerEngine`/`UnifiedPlannerRules` finds none), and the UI never sends one. **This feature is unused and currently unusable.**

### 3.4 Idempotent commands
UI side (`GoalPlannerHomeActivity.submit/execute`, `:65-69`):
- Generates a `command_id` UUID.
- Persists `{path, body}` as `pending` in SharedPreferences `p3r_goal_planner_commands_v2` using `commit()`, then executes.
- On success it removes `pending` only if the command ID still matches.

Store side:
- `existing()` (`:128-136`) returns the original receipt for a duplicate command ID with an identical canonical payload.
- A reused ID with a different payload is rejected.

**UX flaw.** `execute`'s catch block keeps `pending` for **every** exception. That includes deterministic validation failures:
- `InvalidObservation` from store checks such as "stage does not match observed active stage";
- a `StateConflict` from a stale revision.

Every later submit is then refused with "请先处理尚未确认的提交" until the user taps "核对后放弃待重试标识". A retry cannot succeed. Definitive rejections should clear `pending` and show a translated error. Only transport or unknown failures should keep it.

### 3.5 Tower trip recording and corrections
- **`returnPayload`** (`:180-190`) enforces that exactly one of `net_cash` and `absolute_balance` is present.
- **`recordDungeonReturn`** (`:234-266`):
  - requires the stage to equal the observed `active_stage`;
  - requires `visit_slot` to equal the current slot;
  - rejects a second return for the same slot ("slot already has a return", `:259`);
  - checks the floor ceiling.
- **Corrections** are the event kind `return_correction`. They must target a `dungeon_return` in the current epoch and cannot change stage, time or next-slot capacity (`:250-255`).
- **Replay** (`:205-232`) substitutes the corrected payload, so a correction **never adds a visit**. It also re-derives cash from later absolute balances.
- **UI side:** the correction target is `last_return` in **SharedPreferences** (`:67`, written after a successful `/api/return`; cleared on setup/baseline). Consequences:
  - Only the most recent return can be corrected.
  - Losing preferences loses the ability to correct, even though the DB has the event.
  - On the form, the month/day/phase fields are still shown during a correction but ignored (`:50`).
- Social and stat observations **cannot be corrected or undone** at all. The only escape is a full re-entry (`/api/setup` → new epoch).

### 3.6 Other persisted UI state
- `p3r_goal_planner_commands_v2`:
  - `pending`, `last_return`;
  - `setup_draft` = `{schema:"2:E,S3,C,…", values:[…]}`, a positional dump of every input in DOM order (`PlayerStateForm.java:146-147`);
  - `setup_draft_revision`;
  - `setup_open`, which reopens setup after process death.
- Draft caveats:
  - Any change to the form's structure (a new field, a new rule-pack link) invalidates the draft silently, because the schema string must match exactly.
  - Drafts are discarded silently once the snapshot revision changes.
  - **The result form has no draft.**
- `p3r_goal_widget_cursor`: `index_<widgetId>`, `revision_<widgetId>`.
- **Export:** `PlannerStateStore.exportRun()` via `/api/export` has **no button**, and `allowBackup=false`. Users have no way to back up planner progress.

---

## 4. How results are entered today

| Action | Path | Problems |
|---|---|---|
| Social meeting (e.g. Emperor 6→7) | Widget Done → resultForm with type and entity preselected, or Home → "记录行动后的实际结果" → choose type and arcana. User **types** the rank, optionally sets normal/ready, checks the end slot, then saves. | No accept-forecast button. The rank is not prefilled. The arcana-only picker spans all 22 links. `socialObservation` (`PlannerCoordinator.java:95-117`) resets `affinityLowerBound/affinityRank/affinityValid` to **unknown**, so a "met but no rank-up" outcome loses evidence instead of raising the bound. `ready` is ignored unless normal = 正常 (`:108`). |
| Tower | Same form, type 爬塔归来. Cash mode, amount, floor, correction checkbox. | No protagonist-level field. Floor is not prefilled with `highest_floor`. No live "余额将变为 ¥X" preview. Night-tower after an unrecorded daytime action gets attributed to the daytime slot, because `visit_slot` is always the current snapshot slot. |
| Training | Type 属性训练. Stat spinner, typed rank 1–6, optional absolute cash. | No "normal result" confirmation. A rank number is required even when nothing visible changed. |
| Job (打工) | Widget routes to **full setup** (`:61`). | This is the opposite of a targeted flow. |
| Skipped / did something else / no action | No path except full setup. | `recordObservedAction` already accepts empty `fact_assignments`, so a "skip/advance" endpoint is cheap to add. |
| Multiple actions in one day | Must be recorded one slot at a time, in order. | No day log or batch entry. |

---

## 5. How the plan is shown

`renderPlan()` (`:73-86`) produces a flat stack of TextViews:

- **Status line:**
  - `conditional` → "保守安排 · 按实际结果调整"
  - `unknown` → "保守规划 · 部分后续需按实际结果调整"
  - `unsupported` → "部分规则暂未覆盖"
  - `search_limit` → "本轮搜索达到计算上限"
  - anything else is shown as the raw status string.
- "尚未覆盖的目标：…" and `goals_needing_observation` reasons, with arcana-only names via `goalName`.
- **Every** non-`__wait__` step, as "date\ntitle". Titles come from `action_metadata[id].display_title`, e.g. "魔术师 · 2→3"; fallbacks are hard-coded for sun/devil/explore. There is no cap, grouping, today marker or tap target. A full-horizon plan would mean hundreds of labels.
- `adaptive_reservations` with a fixed explanatory paragraph repeated under **each** one.
- "主线目标": the first 3 `story_milestones`.
- "可选爬塔夜": the first 3 `dungeon_candidates`, each as a "预留 <date> 爬塔" button. These pin `constraints.pinned_actions[slot]` via `/api/preferences`. Pins are never pruned once their slot has passed.
- "待确认的训练与打工备选": the first 3 `conditional_activity_candidates`, each with a conditions dialog.
- "清除手动预留，重新安排", if any pins exist.
- "查看条件与证据说明".

There is no goal-progress summary (current rank → projected rank or date for each goal), no money projection, and no per-day view.

---

## 6. Chinese strings and terminology audit

I extracted every CJK literal from the five files (149 in Home, 87 in the form, plus widget and GameDate). The main issues, checked against the original CN guide `assets/data.json` by occurrence count:

| Current | Problem | Suggested |
|---|---|---|
| 爬塔 / 爬塔归来 / 可选爬塔夜 / 预留…爬塔 / 进塔 | Colloquial; the guide never uses 爬塔 (0 occurrences) | 探索塔尔塔罗斯 (45) / 探索 / 塔探索 |
| Persona (in 不计 Persona 等加成, 不预设携带 Persona) | Latin text; the guide uses 人格面具 (33, "Persona" 0) | 人格面具 |
| 非正常／需修复 | The guide says 社群逆位 and 关系会破裂 | 逆位 / 破裂, with official wording to be verified |
| 社群等级 | The guide writes "战车RANK→2" (RANK 85) | RANK / 社群 RANK |
| 1 级 … 6 级 (stats) | The game shows named stat ranks | Use the verified CN rank names; data does not have them yet (`stats_actions.json` ranks carry only numbers) |
| 卡拉OK | Not in the guide | Verify the in-game venue name |
| 基线 / 候选 / 待重试标识 / 提交标识 / 引擎会保留该等级对应的点数区间 / 来源外文选项序号不是中文选项指引 / 规则记录：p3r.rule.* | Engineering jargon and internal IDs | Plain wording; move evidence to a collapsible "依据" block |
| 下一个当前时段 | Ambiguous | 当前时段 |
| 白天 | The guide uses 放学后 (192) on school days and 白天 on holidays | Keep 白天 as the generic label, or show 放学后 on weekdays |
| 现金 1000 | No currency or grouping | ¥1,000 or 1,000 日元 |

Plain English that reaches users:
- Store and coordinator exceptions shown through `notice.setText(e.getMessage())`, for example "Observed rank must be 0–10" (`PlannerCoordinator.java:103`) and "Unknown social link".
- `NumberFormatException` "For input string…" from `Integer.parseInt` on long input.
- `DateTimeException` from `GameDate.slot`.
- `PlannerEngine` explanations in the `planDetails` dialog. The legacy `GoalPlannerActivity.explain()` (`:125-137`) had a translation table; Home lost it.

Identity labels in `social_goal_identity_labels.json` look correct and spoiler-safe, for example "愚者 · 特别课外活动部 · 团体·男女", "教皇 · 北村文吉、光子 · 夫妇·男女", "审判 · 伙伴们 · 团体·男女" and "隐者 · Y子 · 女". Arcana names match the guide (女皇, 倒悬者). These labels are **used only in the goal checkboxes**. The toggle buttons, the result-form picker, Home goal names and reservation titles all use arcana only.

The widget's English "Oct/Jan", "Current" and "Done" are intentional per the design doc.

---

## 7. Gaps against the design doc's "Interaction requirements"

| Requirement | Status | Evidence |
|---|---|---|
| Clear grouped inputs | Partial | Grouping is done by text matching (`organizeSections`). Goals sit in collapsed section 4 next to training locations. 44 rows for the 22 links. |
| Preserve saved values and drafts | Mostly met for setup | Positional draft; none for the result form; lost on rotation. |
| Date, cash, stats, ranks, unlocks, tower, limits, goals and priorities | Present | Caps only for the current stage. Priority is a free integer, default 0 for all. Goals are rank-only. At least one goal is required. |
| Unknown ≠ zero; unrelated saves keep bounds and intervals | Mostly met | Stats are kept (`:102`); affinity is kept if rank and normal are unchanged (`:97-98`). `ready` is dropped unless 正常. A social result resets affinity evidence to unknown. `collect()` replaces `progression`. |
| Arcana + name + gender labels | Partial | Only the goal checkboxes have them. |
| Save atomically, then recalculate; reject stale | Met | `/api/setup` → `reenterWithPreferences` (`:332`) and CAS. |
| Emperor 6→7 panel with 已升到 7 / 没升级 / compact edit | **Not met** | Generic form. |
| Tower: cash and floor, level editable, net XOR balance | Partial | XOR enforced in UI and store; no level field; no balance preview. |
| Training/jobs: relevant result only, simple confirm | **Not met** | Typed rank; jobs go to the full form. |
| Full-state editing separate | Met | |
| Accepting normal predicted results is easy | **Not met** | Nothing is prefilled from the forecast. |
| Each tower trip recorded once; correction does not consume a visit; idempotent across restarts | Met in the store | UI correction is limited to the last return and kept in SharedPreferences. |
| Complex steps in app detail reached by tapping the task | Partial | Works from the widget only; Home steps cannot be tapped. |

---

## 8. Other concrete defects and risks

- **Back button:** with no back handling, Back exits the app from setup, result or detail screens.
- **No saved instance state:** there is no `onSaveInstanceState` and views have no IDs. Rotation or theme change returns to Home and loses result input.
- **Widget taps wipe input:** `onNewIntent` → `refresh()` → `home()` discards an in-progress result form.
- **Errors are easy to miss:** they appear only in `notice` at the top of a long scroll. There is no scroll-to-top, toast or inline field error.
- **Dead fields:** `PlayerStateForm.answerAssumption` and `Link.affinity` are never attached to the view tree. The affinity bound is effectively constant 0.
- **Step titles carry little information:** "魔术师 · 2→3" has no person, location or dialogue choices. One QA render shows "房间学习 · 条件预留" on "10 月 22 日 白天" while its rule ID is `study_room_night`. Either the slot label or the adapter needs checking.
- **Tests are tightly coupled** to private fields (`root`, `busy`, `worker`, `coordinator`, `planning`, and form fields `month/day/cash/floor/used/currentCapacity/links/goal/rank/target/normal/stage`) and to exact Chinese labels (`GoalPlannerHomeActivityTest.java:5-30`). Any UI redesign must rewrite these tests. A presenter/state layer would decouple them.
- **DB on the main thread:** `GoalPlannerWidget.update` opens the DB on the main thread.
- **Asset bloat:** the four rule-pack directories duplicate large JSON files, and `.py`/`.md` files are shipped as assets.

---

## 9. Proposed UX: easier input, and handling deviations

The user asked how input can be made more convenient and what to do each time the player deviates or needs to adjust. Principle: **the plan is the default answer.** Every input should start from "what the plan predicted", and the user only says where reality differed. Observed state stays separate: accepting a prediction is recorded as an explicit user-confirmed observation of the visible result, never as a merged forecast.

### 9.1 Home becomes a "Now" card plus a timeline
- **Top card: the current slot's planned action**, styled like the widget (cyan day / navy night, italic date numerals). It shows the title with the person's name, location, dialogue choices separated by slashes, and the key assumption.
- **Primary buttons depend on the action kind:**

| Action kind | Buttons and fields |
|---|---|
| Social rank event | **已升到 7** (one tap) · **没升级** · **没去成/做了别的…** |
| Ordinary hangout / reservation | **已见面·没提示升级** · **提示可升级** · **已升级到 N** |
| Tower | Two numeric fields prefilled with the current cash and highest floor: "带回金额 / 现在余额" segmented control, live preview "保存后余额 ¥12,340", floor field clamped to the stage ceiling, optional 主角等级. **保存** |
| Training | **按预期完成** (shows "学力 RANK 3 → 可能升级？" with chips 没变/升到 4) · **没做** |
| Job | **已领工资 ¥X** (minimum wage prefilled) · **余额是…** |

- **Below the card:** a goal-progress strip, one chip per goal ("皇帝 6 → MAX · 预计 11/20"), then the timeline grouped by month with sticky date headers and today anchored. Rows are tappable and open a detail bottom sheet with "完成并记录".

### 9.2 Deviation taxonomy, each with one short flow
1. **Did it, result as predicted:** one tap, done.
2. **Did it, result differs:** the same card expands into a compact sheet showing only that action's fields (rank chips 0–10, 逆位 toggle, cash).
3. **Did something else in this slot:** a "我做了别的…" picker. A 22-card arcana grid (arcana · person · gender) for "met someone", plus chips for 训练/打工/探索/休息·剧情. Each choice opens the same targeted sheet.
4. **Skipped / story took the slot:** "跳过此时段". Add a new coordinator endpoint, e.g. `/api/observed_skip`, that calls `recordObservedAction` with `action_id="observed.skip"` and empty `fact_assignments`. Optionally record a "story lock observed" fact.
5. **Played several slots without recording:** a **day log**. "今天 白天 [皇帝 ✓] 晚上 [探索 ¥+3200 · 112F]" lets several results be submitted in order in one go. This also fixes the night-tower attribution problem.
6. **Came back weeks later:** a **"快速同步"** catch-up form instead of the full form. Date stepper first. Then only the volatile fields, each shown as "上次记录 / 计划预计 / 现在": cash, stat chips, a link-rank grid with steppers and a "?" chip for unknown, tower floor and visits. A bulk "以上全部按计划完成" button, then edit the exceptions. Everything not shown is carried over unchanged.
7. **Mistakes:** "撤销上一条记录" for **any** event kind. Generalise `return_correction` into a void/replace event kind in `PlannerStateStore.replay`. Look up the target from the `events` table rather than `last_return` in SharedPreferences.

### 9.3 Controls
- **Date:** stepper "◀ 10月22日 白天 ▶" with a calendar sheet, instead of typed month and day.
- **Ranks:** chip rows 0–10 plus "?".
- **Stats:** segmented control using the in-game rank names (to be verified).
- **Cash:** numeric keypad with grouping.
- **Priorities:** drag-to-reorder of the chosen goals instead of numbers.
- **Option:** "其他社群也尽量推进", on by default, which removes the "must pick one goal" rule.
- **Visit caps:** a table with a global default ("每个阶段最多探索 N 次") plus per-stage overrides, including future stages.
- **Errors:** inline field errors and a snackbar; never a raw exception message.

---

## 10. Making it look good: implementation notes

### 10.1 Visual tokens
Derive these from the established assets:
- legacy guide CSS `:root`: `--bg #0642b7`, `--surface #0b52c6`, `--deep #072d91`, `--navy #062676`, `--cyan #78f1ff`, `--aqua #b0f9ff`, `--muted #b7dafc`, `--line #478ddb`; display font Arial Narrow/Roboto Condensed italic for numerals;
- the widget's day cyan and pink Done shadow.

Then:
- Unify window, status-bar and page colors.
- Switch to a dark parent theme, `Theme.Material.NoActionBar`, so dialogs and spinners match.
- Use `GradientDrawable` or XML shape drawables for cards, chips and primary/secondary buttons.
- Use dp everywhere and an 8dp grid.
- Use one type scale (e.g. 28/20/16/14/12).

### 10.2 Native or WebView

**Option A: keep native.** Add a small in-house UI kit (Card, Chip row, Stepper, Segmented, ListRow, BottomSheet built on `Dialog`), a screen stack with back handling, and state saved in `onSaveInstanceState` or the DB. Separate a presenter/state object from the views so tests assert on state rather than on label text. No new dependencies. Robolectric/Skia render tests keep working.

**Option B: WebView**, like the legacy guide. `PlannerCoordinator.request(path, body)` is already a path-based API ("HTML is presentation only", `PlannerCoordinator.java:7`), so a `@JavascriptInterface` shim is trivial, and CSS makes the target look easy to reach. The costs:
- Robolectric cannot render it.
- Widget deep links need a JS router.
- The handoff advises against UI rewrites without a technical need.

**Recommendation: A**, unless rapid visual iteration matters more than the existing native tests.

### 10.3 Backend changes the UX needs
- Stable `instance_id` on plan steps, plus UI plan binding, so a "已升到 7" tap is bound to a specific step and stale taps are rejected.
- An outcome-typed social result (`rank_up | no_rank_up | reversed | not_met`). It should carry evidence of the confirmed meeting, with provenance, instead of resetting affinity to unknown, and without merging forecast gains into observed state.
- Skip, void and correction for every event kind.
- Structured error codes mapped to Chinese text.
- Separate "rejected" from "unconfirmed" in `execute()`.
- An optional protagonist level on returns.
- Export/import buttons.
- Run replanning off the critical path, with a visible "正在重新规划…" state.

---

## 11. Cleanup candidates
- `v2/GoalPlannerActivity.java` and `GoalPlannerActivityTest.java`: dead January Sun/Devil UI.
- `assets/planner_v2/index.html`: dead; its bridge does not exist.
- `PlayerStateForm.answerAssumption`, `Link.affinity`.
- Asset duplication across `planner_v2/rules*`, and `.py`/`.md` files under assets.