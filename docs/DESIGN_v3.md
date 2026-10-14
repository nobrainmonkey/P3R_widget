# P3R 规划 v3 — design (master contract)

Status: authoritative for the v3 rewrite (2026-10-13). Supersedes the v2 "goal planner" internals.
Inputs: `P3R_HANDOFF_PROMPT.md` (product requirements), handoff zip, the eight analysis reports
summarised here. Owner directives that override the handoff: art may be redesigned freely; every
user-visible string must be natural Simplified Chinese with official ATLUS P3R terminology; the plan
must cover **every** daytime/evening slot from the current state to the end of the calendar and be
self-consistent; global optimality is not required; computation is on-device.

## 1. Why a rewrite (one paragraph)

v2 compiled static per-slot action lists from the *observed* state only, had no ordinary-meeting
model, no actions for non-goal links, never credited future jobs/training, gave tower trips no value,
and broke ties toward `__wait__`. Its generic beam (width 48, 60 000 transitions, failed transitions
counted, no dedup) ran out mid-calendar. Measured: Emperor 6→MAX produced **1** useful action over
Jun–Jan; 6 links ran out of budget by Jul 21. The store/CAS/idempotency ideas were good and are kept
conceptually; everything else in `v2/` is replaced.

## 2. Module map and ownership

```
android/app/src/main/java/app/p3r/dayplanner/
  v3/model/     Rules POJOs + RulesLoader (JSON → immutable model)          [rules agent]
  v3/engine/    SimState, Action, Simulator, Heuristics, Planner,
                PlanValidator, Plan, PlanStep, Outcome, PlanJson             [engine agent]
  v3/store/     PlanDb (SQLite), Observed (observed-state record),
                EventLog, Backup (export/import), V2Import                   [store agent]
  v3/service/   PlannerService (facade used by UI + widget), ReplanWorker   [store agent]
  v3/ui/        theme kit + screens (HomeActivity, StateActivity,
                ResultActivity, DetailSheet, etc.)                           [ui agent]
  v3/widget/    PlanWidget provider + rendering                             [widget agent]
android/app/src/main/assets/p3r/p3r_rules.json   single compiled rule pack [rules agent]
rules/build/build_p3r_rules.py                    offline builder           [rules agent]
rules/p3r/…                                       evidence (not shipped)
rules/sources/aqiu_gamedata/*.tsv                 game-data extraction snapshots (not shipped)
```

`v2/` and `assets/planner_v2/` are deleted once v3 is wired (they cost ~34 MB of duplicated assets).
Shared files (`AndroidManifest.xml`, `app/build.gradle`, `res/values/strings.xml`) are edited only by
the integrator; agents list what they need in their report.

## 3. Calendar axis

`day` = days since 2009-04-01 (Apr 7 = 6, Jan 31 2010 = 305). `slot = day*2 + (evening?1:0)`.
Slot 0 of a day is **白天** (on school days the free block is 放学后; UI shows "放学后" on school days,
"白天" otherwise), slot 1 is **晚上**. Plans run from the observed current slot to the last playable
slot (Jan 31 daytime; Jan 31 evening is the finale lock). Weekday: ISO 1=Mon … 7=Sun; never use the
device date.

## 4. Rule pack `assets/p3r/p3r_rules.json` (schema v3)

Single file, UTF-8, < 1 MB. Every rule record carries `conf` ∈ {`game_data` (extracted game tables),
`verified` (≥2 independent Reload sources agree), `single` (one Reload source), `estimate` (policy
choice), `conflict` (strictest claim chosen; alternates in `alt`)} and `src` (list of source ids).

```jsonc
{
  "schema": "p3r.rules.v3",
  "packVersion": "3.0.0",
  "game": "Persona 3 Reload (Xrd777, base game; excludes Episode Aigis/Astrea)",
  "sources": [{"id":"aqiu_gamedata","url":"https://github.com/aqiu384/megaten-database/…","sha256":"…","kind":"game_data"}, …],
  "policy": {
    "ordinaryGain": 10,            // estimate: ♪♪ shown for a normal hangout, 5 pts per ♪ (conf estimate)
    "personaMult": 1.51,           // DT_GeneralCoefficient CMM_POINTBONUS_ARCANA (game_data)
    "charmMult": 1.51,             // CMM_POINTBONUS_CHARM (game_data)
    "rounding": "floor_per_meeting"
  },
  "lockReasons": {"prologue": {"zh":"序章剧情"}, "full_moon": {"zh":"满月作战"}, "exam": {"zh":"期中/期末考试"}, …},
  "stats": [
    {"id":"academics","zh":"学力","thresholds":[0,0,20,55,100,155,230]},   // index = Lv (1..6), [0] unused
    {"id":"charm","zh":"魅力","thresholds":[0,0,15,30,45,70,100]},
    {"id":"courage","zh":"勇气","thresholds":[0,0,15,30,45,60,80]}
  ],
  "days": [   // one entry per day ordinal firstDay..lastDay (contiguous)
    {"d":20,"md":"04-21","wd":2,"type":"school",        // school | sunday | holiday | vacation | exam | story
     "dayLock":null,"eveLock":null,                      // lock reason id or null (null = free unless a rule says otherwise)
     "moon":3,                                           // 0..7 phase, 4 = full moon (decorative)
     "block":0,"tartarus":true,                          // tartarus = entry possible that evening
     "noteZh":null}
  ],
  "links": [  // 22 entries in arcana order 0..20 then Aeon
    {"id":"magician","no":1,"arcanaZh":"魔术师","personZh":"友近健二","genderZh":"男","labelZh":"魔术师 · 友近健二",
     "kind":"manual",                // manual | story
     "slot":"day",                   // day | eve
     "locationZh":"月光馆学园 2楼 教室",
     "avail":[21,23,…],              // day ordinals with an O cell (ordinary meeting possible)
     "invite":[25,…],                // day ordinals with a holiday invitation (D cell) — counts as a meeting
     "threshold":[0,0,0,0,15,15,15,15,22,22,22],   // [r] = points needed while at rank r-1 to reach r
     "eventGain":[0,0,10,20,15,15,15,15,20,15,20], // [r] = best-answer points earned in the rank-r scene (count toward r+1)
     "eventGainRomance":null,        // same shape when a romance route changes scenes
     "romanceable":false,
     "unlock": {"earliestDay":21, "stat":{"courage":6}, "link":{"fortune":1},
                "steps":[{"id":"magician.intro","zh":"与友近健二对话","slot":"day","days":"avail|weekdays:[2,4,5]|fixed:[21]",
                          "cost":0,"minGapDays":0,"establishes":true}],
                "conf":"verified","src":["aqiu","atwiki453"]},
     "missing": {"fromDay":164,"toDay":185,"rescueId":"bunkichi"},   // optional: link unavailable; lost if not rescued by toDay
     "storyRanks": null,             // story links: [{"day":17,"rank":1},…]
     "noteZh":null, "conf":"game_data", "src":[…]}
  ],
  "activities": [   // stats, jobs and other slot-consuming non-link actions
    {"id":"study_room","zh":"在宿舍房间学习","kind":"stat","slots":["eve"],"weekdays":[1,2,3,4,5,6,7],
     "dayTypes":["school","sunday","holiday","vacation"],"fromDay":20,"toDay":305,
     "cost":0,"gain":{"academics":2},"requires":{},"locationZh":"宿舍 · 自己房间","conf":"verified","src":[…]},
    {"id":"job_chagall","zh":"咖啡店「夏卡尔」打工","kind":"job","slots":["eve"],"weekdays":[1,2,3],
     "pay":[[1,2500],[5,3000],[15,3500],[30,5000]],   // [minShiftNumber, minimum pay] tiers (conservative, non-busy)
     "gain":{"charm":1,"courage":1}, …}
  ],
  "tartarus": {
    "blocks":[{"id":"thebel","zh":"世俗之庭 台伯勒","floorsZh":"2–22F","fromDay":20,"toDay":38,"top":22}, …],
    "rescues":[{"id":"bunkichi","zh":"北村文吉","fromDay":164,"toDay":185,"link":"hierophant"}, …]
  },
  "invitesMeaning": "Holiday invitation (D cell): the link calls you on that day; accepting uses the daytime slot and counts as a normal meeting/rank-up.",
  "notes": [ … free-form audit notes, Chinese terminology decisions … ]
}
```

Conflict policy (from research): Devil needs Hermit **R4** + Charm Lv4; Tower needs Courage **Lv4**;
Empress Mon/Fri cells are unconfirmed → use Tue/Wed/Thu/Sat + pre-exam week 12/7–12/12; tower block
starts 06-13 and 12-03 (game data); Lovers invitations 08-03/08-09 exist (game data); Sun earliest
08-09. Unknown slot state = **free** unless a lock or rule says otherwise (handoff: absence of an
annotation is not proof of unavailability); links are only met on their O/D cells.

Official zh-Hans terms (game string tables + Steam SC achievements): 社群, 级别 (UI uses "RANK" as the
guide does), 个人能力值 (学力/魅力/勇气), 塔尔塔罗斯, 打工, 失踪者, 人格面具, 女皇, 倒悬者, 月亮, 贝培,
伊莉莎白, Ｙ子, 虎狼丸, 严户台商店街, 月光馆学园. Stat level names: show "Lv.N" (no verified SC names).

## 5. Planning model

### 5.1 State (`v3.engine.SimState`, all arrays, cheap copy)

| field | meaning | observed or estimate |
|---|---|---|
| `slot` | next slot to act in | observed |
| `cash`, `cashExact` | yen; exact when typed by user or derived only from exact values | observed/derived |
| `statPts[3]` | hidden points, **lower-bound estimate** (≥ threshold of visible Lv) | estimate |
| `rank[22]` | visible rank 0–10 | observed (forecast in plan) |
| `pts[22]` | estimated points toward next rank | estimate |
| `ready[22]` | game showed the rank-up cue (next meeting ranks up) | observed |
| `unlockStep[22]`, `lastStepDay[22]` | progress through `unlock.steps` | observed |
| `rescued` bitset, `lost` bitset | missing-person outcomes | observed |
| `towerVisits[blocks]`, `floor` | visits per block, highest floor | observed |
| `jobShifts[jobs]` | shift counter for pay tiers | observed |

### 5.2 Actions and transitions (`Simulator`)

Exactly one action per free slot. Types: `MEET(link)`, `UNLOCK_STEP(link, step)`, `ACTIVITY(id)`,
`TARTARUS(block)`, `FREE` (only when nothing legal), plus non-actions `LOCKED(reason)` emitted for
locked slots. Legality (all must hold): slot not locked; slot kind matches; date inside the rule's
day set; prerequisites (stat Lv computed from `statPts`, link ranks) hold; `cash ≥ cost` (+ profile
cash buffer for optional spends); tower cap not exceeded; link not missing/lost; rank < 10.

Meeting (manual link, rank r ≥ 1, mult m = (persona?1.51:1)·(charmBonus && charmLv==6 ? 1.51:1)):
- `need = threshold[r+1] + (isGoal && goalBuffer && threshold[r+1] > 0 ? ordinaryGain : 0)`
- if `ready[L]` or `pts ≥ need` → **rank-up**: r+1, `pts = floor(eventGain[r+1]·(answerPolicy==BEST?1:0)·m)`, `ready=false`.
  Certain (not an estimate) when `threshold[r+1]==0` or `ready[L]`.
- else **ordinary meeting**: `pts += floor(ordinaryGain·m)` (estimate).
Invitations behave like meetings on their D dates. Story links change rank by date only.
Unlock steps follow `unlock.steps` in order with `minGapDays`; the `establishes` step sets rank 1.
Steps with `consumesSlot=false` are **side steps** (Devil payments, Justice talks, Hanged Man gift,
Sun ask/pen, Tower/Moon rumours): before the slot's action, every wanted link (goal, or every link
with 其余社群) whose next step is non-consuming and legal in this slot (slot kind, day rule, fromDay,
earliest day, stat/link requirements, `minGapDays`, fee after reserving the action's own fee) does
that one step — at most one step per link per slot, none on locked slots. A non-consuming chain with
`minGapDays` 0 and the slot kind of its consuming step folds into that step's action (Moon rumour +
opening the same day). Plan steps list them in `side`/`sideZh` ("顺便：…"); outcomes apply them unless
`sideDone` is false.
Activities add `gain` to `statPts`, subtract `cost`; jobs add the tier pay for `jobShifts+1` and
increment the shift counter. Tartarus increments `towerVisits[block]`; no cash/floor is forecast;
a planned visit inside a rescue window marks that rescue planned (estimate) and the result sheet asks
whether it happened.

### 5.3 Objective (lexicographic, scalarised)

`J = Σ_links w(L)·(rank_L + 6·[rank_L=10]) + Σ_goals w(L)·0.002·(lastSlot − slotReachedMax)
     + 0.5·Σ stat Lv + 0.0001·cash − 10⁶·(unmet required tower visits) `
with `w(MUST, order i) = 100 000 − 100 i`, `w(WANT, i) = 8 000 − 10 i`, `w(other) = 300` when
"其余社群也尽量提升" is on (default) else 0. A MUST link at MAX outweighs everything below it.

### 5.4 Search: fortified rollout over a greedy base policy (on-device)

1. **Base policy π (greedy)**: at each slot choose the legal action maximising heuristic `H`:
   - `MEET L`: `w(L)·(rankUp ? 1 : 0.55)·(0.4 + min(2, need_L / max(1, remain_L(slot))))`, where
     `need_L` = meetings to reach the target rank under the model (O(10) loop) and `remain_L(slot)`
     = static count of L's remaining O/D slots (precomputed suffix sums). Scarce links go first.
   - `UNLOCK_STEP`: same as the link's first rank-up × 1.2 (unlocking early opens future meetings).
   - stat `ACTIVITY`: Σ over not-yet-met stat gates of wanted links `w(L)·0.9·min(1, gain/deficit)·(1+gateUrgency)`
     + charm-6 multiplier value when enabled + `0.2·gain` base.
   - `job`: `min(pay, projectedDeficit)/800 · wMoney + 0.3·statGain + 0.25` (never worse than idle).
   - `TARTARUS`: required visits remaining in block / remaining open evenings in block × 50 000;
     rescue windows × 200 000 near the deadline.
   - `FREE`: 0.
   - Stability: +2% for the action the previous plan had in the same slot within the next 3 days.
2. **Fortified rollout**: walk slots in order; at each slot with ≥2 candidates take the top-K (K=4,
   at most one per category: best meeting, second meeting, best stat/job, tartarus) by `H`; for each,
   apply it and roll π to the end, score `J`. Keep the incumbent trajectory and switch only when a
   candidate's rollout beats it (monotone improvement over π); a daytime slot in which a wanted link
   could be met goes to training/a job only for a clear gain (half a RANK, more for goals).
   Deterministic tie-breaks by index.
   **Portfolio**: π has knobs (stat/money/fee weights, patience, how early gated links must open,
   evening share for training, admitted daytime demand). Every plan screens 16 tunings with π alone
   (≈1 ms each on desktop) and fortifies the 3 best; the best `J` wins (stability preference below).
   Implementation notes (2026-10-13, engine tune): H treats daytime as the overbooked resource —
   stat gates are scheduled earliest-deadline-first against explicit evening capacity (open evenings
   minus required Tartarus and Devil/Tower meetings) and earn daytime value only for trainings
   evenings provably cannot fit before a hard deadline; link-rank prerequisites (Justice ← Emperor 1,
   Tower ← Strength 4, …) carry part of the gated link's value; money is a list of near-term needs
   (fees about to open, a small training float), so jobs stop once they are covered; admission
   control keeps the densest links "planned to MAX" when a slot kind is overbooked.
3. **Budget**: soft time budget (default 2.5 s on device, configurable in tests). When exhausted, follow
   the incumbent for the remaining slots — the plan still covers the full calendar.
4. **Validation**: `PlanValidator` re-derives everything with its own independent accumulator and
   checks: one action per slot, contiguous slots, no action on a locked slot, link availability,
   prerequisites, no overspending (cash never < 0), tower caps, rank monotone & ≤10, predicted ranks
   match recomputation, observed state untouched. Any violation → plan rejected, error surfaced.

Expected scale: ≤ 600 slots × K 4 × ≤ 600 rollout steps × ~15 actions ≈ 20 M heuristic evaluations,
≈1–3 s on a mid-range phone; runs on a background thread with progress.

### 5.5 Plan output (`Plan`, stored as JSON)

`{planId, stateRevision, profileRevision, rulesVersion, fromSlot, toSlot, createdMillis,
  steps:[{slot, type, target, step, titleZh, subtitleZh, locationZh, kind, predicted:{rankFrom,
  rankTo, rankUp, certain, cashDelta, statDelta[3]}, estimate, assumptions:[ids],
  side:["unlock:devil:0"], sideZh:["顺便：…"]}],   // side/sideZh omitted when empty; cashDelta includes side fees
  summary:{endRanks[22], maxed[], goalMaxSlot{link:slot}, usefulActions, lastUsefulSlot,
  freeSlots, notesZh[]}}` — one step per slot (locked slots included as `LOCKED`).

## 6. Persistence (`v3.store`)

SQLite `p3r_v3.db`:
- `meta(key PRIMARY KEY, value)`: schema version, `state_revision`, `profile_revision`.
- `profile(id=1, json)`: goals (tier + order), target ranks, options (other links, persona bonus,
  charm bonus, goal buffer, answer policy, tower visits per block, tower cap, cash buffer, romance
  route per link, excluded activities).
- `checkpoints(id, created, json)`: full observed state written by onboarding / 同步现状 / full edit.
- `events(seq, command_id UNIQUE, checkpoint_id, kind, json, voided, created)`: outcomes after the
  checkpoint. Kinds: `outcome` (slot, action key, outcome code, extras incl. `sideDone:false` when the
  step's side steps were not done), `tower` (slot, block, floor,
  cashMode `net|balance`, amount, level?), `tower_fix` (target seq, new values — never adds a visit),
  `skip` (slot, reason), `void` (target seq).
- `plan(id=1, state_rev, profile_rev, rules_version, json)`.

Observed state = latest checkpoint + replay of non-voided events. Every mutation runs in one
transaction, bumps `state_revision`, requires the caller's expected revision (CAS) and is idempotent
by `command_id` (same id + same payload → original receipt; different payload → rejected).
Plans are installed only if their `stateRevision/profileRevision` are still current (stale results
discarded). Forecasts never write into checkpoints/events. Backup = JSON export of profile +
checkpoints + events; import replaces atomically after confirmation. On first launch, a v2 database
(`p3r_goal_planner_v2.db`) in the same package is imported best-effort into a checkpoint.

## 7. Interaction design (Chinese UI)

Principle: **确认而不是录入** — every field is prefilled (last observed value or plan forecast marked
"预计"); the player only changes what differs. Effort is proportional to surprise.

### 7.1 Screens
- **今天 (HomeActivity tab 1)**: date plate (大号日期 + 周X + 月相 + 放学后/白天/晚上), resource strip
  (现金 · 学力/魅力/勇气 Lv · 塔尔塔罗斯 区块与次数), hero card for the next slot (title "皇帝 · 小田桐秀利",
  "RANK 6 → 7", location, short why-line), buttons **[做不了…]** (left; its sheet offers the reasons and
  「改做别的…」) **[完成 ✓]** (right).
  Below: rest of today + next 3 days; goal progress chips ("皇帝 6→MAX · 预计 11/20"). A return banner
  appears when the last record is >8 h old: "上次记录：5月2日 晚上 · 游戏进度变了吗？ [没变] [同步现状]".
- **日程 (tab 2)**: whole remaining calendar, month headers, one row per day with 白天/晚上 lines, icons
  per kind, locked slots grey ("满月作战"), estimated rank-ups prefixed "≈". Tap → detail sheet with
  "记录结果" and "这个时段改为…".
- **社群 (tab 3)**: 22 arcana cards (罗马数字 + 阿尔卡那 + 人物 · 性别), current RANK (observed) and projected
  end RANK, goal tier chip cycling 「—」→「想要」→「必须」, order for 必须/想要 via ↑↓, MAX date or reason
  ("时间不够：优先级低于 恋爱"). Story links show "剧情自动推进".
- **我的 (tab 4)**: 同步现状 / 完整修改状态, 规划设置 (人格面具加成, 魅力加成, 目标留余量, 回答方式, 每区块进塔
  次数与上限, 现金余量), 记录历史 (撤销/修改), 备份与恢复, 规划说明 (assumptions & sources), 添加桌面小组件
  (the 3.0.x guide entry was removed in 3.1.0, §19).
- **State wizard / 同步现状 (StateActivity)**: one screen per step, prefilled: 日期 (月份标签 + 日历格,
  白天/晚上) → 现金 (keypad, "不确定") → 个人能力值 (Lv 1–6 segmented ×3) → 社群 RANK (grid; RankPad with
  未建立 1–9 MAX ? and auto-advance) → 解锁进度 (only relevant links) → 塔尔塔罗斯 (区块 auto from date,
  最高层, 本区块已去次数) → 目标 (on first run) → 确认 diff ("将更新 5 项"). For returns: "这段时间基本按计划
  了吗？ 是 / 一部分 / 否" prefills from forecast/last observed.
- **Quick result sheet (ResultActivity, translucent, from widget or app)** by action kind:
  - social rank event: "皇帝 · RANK 6 → 7？" **[已升到 RANK 7]** [没升级] [其他结果…] (+ optional
    "游戏提示下次可升级" toggle; 其他 = rank stepper / 没见到).
  - ordinary meeting (predicted no rank-up): **[完成会面]** [升级了！→ RANK N+1] [没见到].
  - unlock step: **[已完成]** [没完成].
  - training / meal / job: **[按预期完成]** (shows "学力 +2"/"+2,500 日元") [结果不同…] → Lv chips / balance.
  - Tartarus: 最高到达层 stepper (prefilled), 现金 segmented **现在余额 | 本次增减** (one field only, live
    preview "进塔前 8,200 → 出塔后 14,750 日元"), optional 主角等级, rescue question when relevant, [没去成].
  - all: [做不了] → reasons (剧情占用了 / 对方不在 / 地点没开 / 钱不够 / 我不想做) and [改做别的] → list
    of legal actions for that slot (computed by the engine) → their own outcome panel.
  After saving: undo snackbar "已记录：皇帝 RANK 7 · 撤销"; replan in background; a one-line change
  summary "已重新规划：覆盖至 1月31日 · 调整 3 处".
- **Catch-up**: Done on a future item → "前面还有 3 项没记录：[都按计划完成了] [逐项核对] [从这里开始]".

### 7.2 Widget
Keep: Current (left) / Done (right), date-area arrows browse items, Current returns to the pending
item, day (cyan `#0FB1F5`) / night (deep blue `#034572`) palettes with the existing ViewFlipper fade.
Change: Chinese labels **当前 / 完成**, slanted P3R calendar plate (numerals "10/22", 周四, moon phase,
白天/晚上), parallelogram buttons (no pink/mauve), item title "皇帝 · 小田桐秀利" + "RANK 6→7" tag +
location line, progress "今天 1/2". Done: deterministic items (training, job, story ack) commit
directly with a "✓ 已记录 · 再点撤销" state; link meetings / Tartarus open the quick result sheet.
Stale PendingIntents (plan id/revision mismatch) never commit. Rendering reads a cached widget
projection (no rule loading on the main thread). Current is dimmed on the pending item (like ‹);
status cards (planning, error, finished) show one button that opens the app (打开应用 / 查看日程)
instead of a dead row, and a story-locked item one that goes on to the next item (下一项; 查看日程 when
the plan ends with it). Chips and names are never cut with "…": the location shortens or goes first,
and the one-line note keeps its leading whole clauses or goes. A title that wraps at 「 · 」 wraps there
without the dot (「结缘网站的字条」/「山岸风花」).

### 7.3 Visual language
Dark royal/navy app theme (P3R menu blues), cyan accent `#3CE4FF`, white ink; sharp parallelograms
and diagonal cuts, bold condensed italic Latin numerals (bundled OFL font), no fake-italic CJK,
8 dp grid, 40 dp+ touch targets, adaptive launcher icon. Character art may be reused as decoration.

## 8. Testing & acceptance
- Engine: synthetic fixtures + real pack; replay invariants on every produced plan; early-game
  (Apr 21, new game, goal 皇帝 MAX + others) and mid-game (Oct 22, Magician 2, Sun 7, ¥1,000)
  cases must have useful actions through the last two weeks of January; report plan span, useful
  action count, maxed links; no FREE slot while a legal productive action exists; deterministic.
- Deviations: skipped, no-rank-up, early rank-up, swap, weeks-later sync, low money, tower cap 0/1,
  story lock, stale replan rejection, idempotent retry, tower correction adds no visit.
- UI: Robolectric renders of every screen/sheet at 360×780 and widget sizes, day/night.
- Legacy guide (3.0.x): its tests and the planner/guide isolation check went with the guide in 3.1.0
  (§19); the shared widget pieces they covered (fade host, fade throttle, card chrome) have their own tests.

## 9. Identity, signing, release
`applicationId` changes to **`app.p3r.planner`** (label "P3R 规划"). Reason: the installed 2.0.0 is
signed with a debug certificate that is not available here; any APK built here cannot update it.
A side-by-side package avoids telling the player to uninstall. The 2.0.0 guide and its progress stay in
the old app (3.0.x carried a copy of the guide until 3.1.0, §19); planner state is re-entered once with
the new wizard. Release signing key lives in `.signing/` (git-ignored, never published). Version 3.0.0
(code 30). APK published as a GitHub release asset.

## 10. Settled decisions (after the critic pass)

1. **Persona bonus** (superseded by Profile.personaMode NONE/GOALS/ALL — players do not carry every matching Persona): a three-way question in onboarding and 规划设置 ("见面时通常带着同阿尔卡那的人格面具吗？" → 基本都带 / 只给目标社群带 / 不带·不一定), default 不带. When on, ×1.51 applies to ordinary meetings and rank-up answers. **Charm bonus**
   (×1.51 at 魅力 Lv6, game data) applies when the forecast reaches Lv6; toggle default on, listed
   in 规划说明. The CN guide (pinned as `rules/sources/cn_guides/data.json`) maxes all 22 links only
   with matching Personas (146 items) — use it as the benchmark: with personaBonus=true from a new
   game the planner should MAX ≥ 18 links.
2. **Mid-chain holds** (rank-ups blocked by story/rumour, e.g. Hierophant R4→R5, Moon R9 reversal):
   encoded in the pack as `links[].holds:[{rank, untilDay|untilFlag, zh}]`; meetings during a hold give
   ordinary points only. Result sheets offer "游戏提示暂时无法升级" which freezes that link until the
   player reports progress (outcome NOT_DONE with reason `hold`).
3. **Rescues**: Hierophant (文吉 9/12–10/3) and Hanged Man (舞子 10/21–11/2) — the link is unavailable
   inside the window; the planner reserves ≥1 Tartarus evening inside the window (counts toward the
   block cap) whenever the link is a goal or "others" is on and rank < 10; the tower sheet asks
   "救出北村文吉了吗？". Not rescued by the deadline → link lost.
4. **Cash**: jobs are the only forecast income by default. `Engine.Options.towerIncomePerVisit`
   (default 0) may be set by the service to the median net gain of the player's own recorded tower
   returns in the current/previous block (labelled "按你的记录估计").
5. **Guarded dates**: Dec 24 free (all invitations declined) by default; Jan 21 daytime locked
   (conservative); unresolved story contexts (Yakushima 7/20–7/23, 1/1, 11/4 …) locked until
   disproven; the rules builder records each decision in `notes`.
6. **Replan**: in-process single-thread executor, default budget 2.5 s on device; a result that
   matches the prediction leaves the current plan valid, so the widget advances immediately.
7. **Dialogue text** comes only from `rules/p3r/guide_dialogue_reference_map.json` (CN guide
   verbatim) as `links[].dialogue:{"<targetRank>": ["…"]}`; otherwise the fallback copy
   "选能让对方高兴的回答（♪越多越好）". Never show foreign option ordinals.
8. **Holiday invitations** (D cells) consume the daytime slot and count as a meeting.
9. **Free story gains** (class answers, exams, summer school) are not forecast; the player syncs stats.
10. **Link Episodes** (羁绊剧情) are opt-in tracks in the pack's `episodes` section (Profile.episodeTracks); the dating-site note (结缘网站的字条) is `datingNote` (evening, ¥3,000, +10 estimate, from 11/11, repeatable). Dorm activities are never planned. `days[].tartarus` is also false on nights the aqiu calendar marks 'Everyone' (no party member available) and on the GameFAQs closure dates.
11. Date pickers offer months 4–12 and 1 only; the horizon ends Jan 31 (daytime).
12. **Engine option** `towerIncomePerVisit` and `previous plan` stability bonus are part of
    `Engine.Options`/`Engine.plan` (engine module adds the field).

## 11. Persistence schema 2 (round 2)

1. `checkpoints` and `events` gain a nullable `rules_version` column, added by migrations that can safely run twice.
2. Checkpoint JSON carries a `layout` object (ids of links, blocks, activities, rescues, episodes and unlock steps per link) and is remapped by id on load, so a rule-pack update never reinterprets stored arrays by position; records that no longer fit are counted and surfaced as `Snapshot.noticeZh` (suggesting 同步现状).
3. Outcome payloads may carry `stepId`; tower payloads may carry `sideDone:false`.
4. Backup checkpoints and events may carry `rulesVersion`.
5. Replay applies each row at its own slot, skips locked slots only after the last row, and moves time only through `Engine.advanceTo` (same day rollover as the forecast).
6. Rescue inference at checkpoint time: if a rescue window ended before the checkpoint date, the player did not answer, and the linked Social Link's rank ≥ 1, it counts as rescued; only an explicit 没救出 sets `rescueFailed`.

## 12. Round 2 engine notes

- Objective adds 2,500 per Link Episode watched in an opted-in track plus 4,000 for finishing the track; dating-site notes affect the objective only through ranks and cash.
- Persona bonus is per link: `Profile.personaFor(link)` (modes NONE / GOALS / ALL); a meeting computed with it carries `hintZh` 「记得带上「X」阿尔卡那的人格面具」.
- Daytime-waste margin is one RANK of a non-goal link; the 16 plan variants are compared by objective minus that margin per wasted daytime slot; candidate types include the best dating-site note and the next Link Episode.
- 改做别的 comes from `Engine.alternatives` (labelled like plan steps, ordered by the objective after each action).
- Measured on the real pack (desktop JVM, default options, 0.55–0.70 s) at the end of round 2: new game Persona ALL 22/22 MAX; no Persona 22/22 (21/22 with notes off); 皇帝 MUST 21/22 with 皇帝 MAX 6/29; mid game 10/22 12/22; all five Link Episode tracks complete when opted in. Current numbers: §14 and `docs/PLAN_METRICS.md`.

## 13. Record integrity (final review)

- Outcome and tower payloads carry the exact side steps done (`side` keys + `sideStepIds`) and, for outcomes, `rankAfter`, worked out at record time (from the UI's `sideKeys`, else the installed plan step, else the engine) and excluded from the duplicate-command hash. Replay applies exactly those; only older rows recompute.
- AS_PREDICTED outcomes that carry `rank` for MEET/UNLOCK_STEP reconcile to that observed rank, so a later profile change (goal tiers, Persona mode, answers, charm bonus) never rewrites confirmed ranks or history.
- Opting into a 羁绊剧情 track mid-game asks 「已经看了几话？」 and stores it in a checkpoint; 修改进度 / 同步现状 have a 羁绊剧情 page.
- After a failed load, 重试 re-opens the rule pack and database (`requestReplan` → `retryOpen`).

## 14. 3.0.1: strict goal priority and the remaining audit gaps

- **Strict goal priority.** Goal weights stay spaced by priority (§5.3), and on top of that the plan kept is compared goal by goal in priority order (`Planner.Pool.compareGoals`: reached beats missed, a missed goal's higher end rank beats a lower one, only then the objective). With two or more goals, when the best plan misses goal *k* while a later goal is reached, the planner re-plans with goals 1…*k* only (the rest count as 「其余社群」) and screens the 「goal first」 tunings (`GOAL_FIRST`), so an earlier goal is never traded for a later one. `Summary.tradedFor[link]` records, for a goal the kept plan misses, the goal the planner kept instead (−1 when no compared plan reached it); the Links page names only that goal in 「没排到 MAX（先保证了…）」.
- **Hard fee reserve.** `Kernel.FEE_RESERVE` = 2,500 日元 (one café shift): an unlock fee is paid leaving at least that much unless waiting would cost the link its milestone (`feeAffordable`); while a fee is due within 14 days and affordable, paid training and 结缘网站的字条 leave the fee plus the reserve; on a fee day they leave the reserve (`optionalHold`). The 规划说明 sheet states this (`Labels.FEE_NOTE`).
- **Honest follow-up.** A plan whose time budget ran out says 「稍后会自动再算一次」 only while the service's one follow-up computation is still to come; that computation sets `Plan.followUp` (JSON `followUp`), and the note then reads 「后半段是粗略安排；记下一个结果后会重新安排」 (`Plan.budgetNoteZh`). The UI reads the flag directly. The follow-up replaces the installed plan only by the same strict goal priority (`PlannerService.improves`: goals in order, each by end rank up to its target; then a complete plan beats one that ran out of time).
- **Meeting how-to.** 「怎么做」 of a meeting is: when/where/whom, the guide's 「参考回答：…」 (engine `DialogueHint`, same filtering as the UI's `Dialogue`), side steps, and the link's own note as 「注意：…」. The UI shows 「参考回答」 and 「注意」 lines as marked notes under the numbered steps (not counted as steps) and drops its separate 参考回答 row when the how-to already has it.
- **Legacy guide** (removed in 3.1.0, §19). Text fields of `assets/data.json`, `display_metadata.json` and the copy embedded in `index.html` followed the glossary (「10月13日」, 「3,980 日元」, 「Lv.2」 for stats and 「→RANK2」 for links, 羁绊剧情, no English glosses after Chinese answers; English-class options such as 「Lives」 stay); ids, kinds, dates, keys, order and list lengths were pinned by `LegacyGuideChineseTest` against the 2.x structure. The guide widget kept goods, answers and counts (「（2）」「（1个）」) whole; `LegacyGuideWordsRenderTest` derived the quoted names and shopping goods from the data itself instead of relying on the word list alone. The two JSON files now live only as pinned sources under `rules/sources/cn_guides/`.
- **Wording.** 主角 (not 主人公), also in the 3.0.x guide data; dates in text are 「10月23日 周五」 (the date plate keeps 「10/23」); 结缘网站的字条 always in full; widget and app text are shaped so names and short tags never break across lines (only （…） notes of up to 5 characters are glued, so longer ones can wrap at large font sizes).
- **Aeon meeting days.** The aqiu calendar has Aigis after school Mon–Sat from 1/8; Game8, PC Invasion, Prima and GamesHorizon (Reload) list Mon/Wed/Fri/Sat. By the strictest-claim rule the pack keeps Mon/Wed/Fri/Sat (conflict `avail.aeon.tuethu` in `rules/build/REPORT.md`), found by the independent source check (`docs/analysis/audit/wiki_sources.json`).
- **Measured** (real pack, desktop JVM, default options; full table in `docs/PLAN_METRICS.md`): new game with the arcana Persona for every meeting 22/22 MAX; no Persona 21/22 (恋爱 RANK 9; also 21/22 with all five Link Episode tracks); 皇帝 MUST 21/22 with 皇帝 MAX 6月29日; MUST 皇帝 + WANT 恋爱、女皇 with Persona for goals only 21/22, all three goals MAX; every scenario plans to 1月31日 and passes `PlanValidator`.

## 15. 3.0.2: independent sign-off reviews

- **Sources.** A read-only agent re-verified the Reload-only source policy (`docs/analysis/audit/wiki_sources.json`): all 33 pinned hashes and 40 cited local files match, no P3/FES/P3P data feeds a shipped rule, and 18 facts were checked against the pinned sources and public Reload guides; the one disagreement (Aeon weekdays) was resolved by the strictest-claim rule (§14).
- **Looks.** Two independent design reviews (`design_review_1.json`, `design_review_2.json`) looked at the README gallery, emulator captures and the Robolectric renders. Round-1 nits were fixed: every app text view goes through `ZhTextView` (keep-words-whole shaping, no lone last character, no line ending in 「·」); widget status cards have one live action; chips, names and locations are never cut with 「…」 (lower-priority parts are dropped first); large cards centre their text; pre-S widgets have round corners; the 3.0.x guide widget (removed in 3.1.0, §19) broke after clause punctuation and kept 「<arcana>面具」 whole.
- **Fonts.** The bundled Barlow Condensed files are the upstream ones, pinned by SHA-256 in `PlanWidgetRenderTest`. Adding a 「→」 glyph to the upright files broke the italic numerals on a device (Android takes a family's coverage from its first font), so the system arrow is used as before.

## 16. 3.1.0: 从头重新安排

- **Why.** Every automatic replan passes the installed plan as `previous`, which biases the next three days towards it (the base policy's +2 %, `Planner.STABILITY_MARGIN`, `SWITCH_MARGIN`), and `requestReplan` does nothing while the plan is current. After several setting changes the plan can therefore look held back by its own history. 「从头重新安排」 is the player's way to drop that bias. With budget to spare, planning is deterministic, so the run only differs where the bias or the budget mattered.
- **Service** (`PlannerApi.requestFullReplan` / `cancelFullReplan`, `PlannerService`). A request first re-reads the view from the store under the lock (`LedgerView.load`; the rule pack stays as loaded). It then queues a full job (`ReplanWorker.Job.full`, with a ticket): `previous = null`, three times the budget (`fullReplanBudget`: 7.5 s on device) and the usual 4 candidates (they are in the plan id). It also sets `options.followUp` and `followUpKey`, so no automatic 2× run follows. When the full job installs no plan for those revisions (stopped with 「先不算了」, or failed, while the installed plan is out of date) it gives the key back (`releaseFollowUpLocked`): the ordinary job that computes those revisions instead (the replan after 「先不算了」, or 重试) keeps its one 2× follow-up if it runs out of time, so the note 「这次没来得及算完，稍后会自动再算一次」 never stands without a run behind it.
  - A pending full job takes precedence over a follow-up or an ordinary job (`nextJob`). A request cancels the other computation in flight (`cancelPartial`). 「先不算了」 cancels only a full one (`cancelFull`).
  - It is not a write: no revision moves, no command id, nothing in 记录历史, and `planCurrent` stays true throughout. So the widget keeps recording with one tap, and `Snapshot.planning` stays false. `Snapshot.fullReplanning` says a request is queued or computing.
  - A record during the run cancels the computation for the old records (`cancelOutdated`). The request stays pending, and the next job is again a full one for the new revisions.
- **Accepting the result.** With an out-of-date plan, the result is installed as an ordinary job's would be (including the ordinary failure: error card, no automatic retry). With a current plan, the result replaces it when `acceptsFull` holds: better on the goals (`compareGoals`, the strict goal priority that `improves` is now built on), or tied on them and not worse on time (complete, or both ran out of time). The other links do not decide, because dropping the bias is the point. Otherwise the plan stays (`KEPT`) and its follow-up promise ends (`followUpDoneLocked`). A failure keeps a current plan without `planErrorZh` or `failedPlanKey`. An installed full plan is marked `followUp`, so its budget note reads `BUDGET_NOTE_DONE`.
- **Results** (`Snapshot.fullReplanTicket`, `fullReplanDoneTicket`, `fullReplanResult`; immutable holder `PlannerService.Full`, read before the model so a finished ticket comes with its plan):
  - `CHANGED`/`UNCHANGED`: action and 顺便 lines per slot against the installed plan (`sameSteps`);
  - `KEPT`, `FAILED`, `CANCELLED`;
  - null when there is nothing to tell: a backup import voids the request; 「先不算了」 with an out-of-date plan lets an ordinary replan run instead.
  - Tickets count per process. A repeated request while one is pending returns the same ticket. 0 means no state yet, or, after a failed load, the request works as 重试.
- **UI.** Entry 我的 → 安排 → 「从头重新安排」 (after 规划说明; off without state, after the last day and while running), and a quiet button under the 规划设置 footer 「改动会马上生效，安排也会自动更新；想完全不参考现在的安排、从头排一遍：」 (the sheet: 「会重新读取你记下的进度和规划设置，不参考现在的安排，从头排到1月31日。…」; the plan is never called a 「版」). Both open `FullReplanSheet` 「从头重新安排？」 ([先不了] [从头重新安排]; the running line instead while a request runs).
  - `HomeActivity.requestFullReplan` shows the running state at once (optimistic snapshot), then keeps the ticket (it survives `onSaveInstanceState`) and reloads. The reload also drops optimistic leftovers such as a profile. A screen that sees a request running whose ticket it never got (made again before the answer came, or opened from the launcher after the player backed out while it ran) takes the ticket from the snapshot, so the result is still shown once; the plan the result is counted against survives a configuration change (`onRetainNonConfigurationInstance`), and is otherwise the plan installed when the screen found the request running.
  - 今天 shows 「正在从头重新安排…」 with the busy stripe, 「可以照常记录，记下的结果会一起算进去」 and 「先不算了」; the error card waits.
  - When the done ticket reaches the kept one, `PlanDiff.fullSummary` gives the line once in the message bar and as the status line: 「从头重新安排好了：这一周有 N 处变动(，之后的也顺着调整了)」 / 「…这一周不变，之后的安排顺着调整了」 / 「…要做的事没变，顺便做的事有调整」, 「重新算过了，安排没有变化」, 「重新算过了，没找到更好的，现在的安排不变」, 「这次没能从头重新安排，现在的安排不变」, 「已停下，现在的安排不变」. Changes are counted against the plan shown when the request was made. If that plan is unknown (or a snapshot read in the instant between installing the plan and ending the request already holds the new plan, which a `CHANGED` result rules out as the plan before), the line is 「从头重新安排好了，一直排到1月31日」 (`PlanDiff.FULL_CHANGED_UNCOUNTED`). The last two lines get a neutral mark instead of the check. A record made during the run (the caption invites it) keeps its 「已记录：… 撤销」 bar: a result that ends meanwhile does not replace it and is said by the status line only.
  - Not on the 今天 hero or 规划说明. 「重新安排」/「重试」 still call `requestReplan`. Automatic replans after a profile change keep `previous` (owner decision O1: off).
- **Tests.** `PlannerServiceFullReplanTest`, `ReplanWorkerTest` (selective cancels), `FullReplanUiTest`, `PlanDiffFullSummaryTest`, `widget/PlanViewFullReplanTest`.

## 17. 3.1.0: 当日提醒

### 17.1 Data and engine

- **Pack.** The optional `reminders` section (schema stays `p3r.rules.v3`; packVersion 3.1.0) holds the static lines (`items`: class, exam, tv, request, fragment, trip, feature; final Chinese, sorted by day, slot, id), the 上课犯困 days (`doze`), the 保健室 rule (`nurse`) and the missing people in (missing from, limit) groups (`rescueGroups`). `RulesLoader.parseReminders` validates it with field paths (id pattern, kinds, days, `shortZh` ≤ 16 characters, `answerZh`/`option` only for answers, priority 1..3, `requires`/`firstEligible`, doze days on school days, known `rescueIds`, Reload sources). Counts and sources per kind: REPORT §4.8.
- **Answers.** Dates and option positions come from aqiu, the zh text from the pinned 3DM schedule pages `rules/sources/cn_guides/3dm_p2…p11.html` only; only dates, prices and positions are compared with English sources. The CN guide (`cn_guides/data.json`) is an edited copy of 3DM with English-localisation answers on 9 dates: the builder checks that this drift set is unchanged and never ships its answers. 3DM gives no text for 12/1–12/12 and after 1/13, so the class answers of 12/7, 12/9, 12/11 and 1/18 are withheld (32 class, 17 exam answers ship; 7/17 MADRID and 12/16 JUNE with conf=estimate). Three obvious 3DM typos are corrected to the CN guide's spelling of the same day (奥义书, 欣快感, 人造人), and 3DM's 11.6 row carries the 11/7 class answer (`CN_3DM_ROW_FIXES`).
- **Other static lines.** 电视购物: the 32 rows of the game's mail-order table (dates, quantities, prices) with zh item names from 3DM or the CN guide (7/5 has none: 「电视购物有新商品」); the 7/12 and 11/29 request items raise the priority. 伊莉莎白的委托: 9 new-request days (5/10 and 1/1 in the evening, 3DM's evening column: the request system opens that evening, and 1/1's daytime is the shrine visit) and 8 time-limited windows, each shown from 3 days before its end (#97: 「…而且只有在 7月6日前救出了 6月18日失踪的学生才能完成」, past tense, since it shows from 12/22). 黄昏的碎片 (4/21 town and dorm, 5/25 roof, Club Escapade on the first evening with Courage Lv2, 8/6 operation map), 屋久岛: request #44 accepted on the evening before (7/19, 3DM 「确保接取支线委托44」; on 7/20 it is too late) and the beach item on 7/20, 修学旅行 11/17 request items (the oden is then bought from the student for 5,000 日元, not in the forecast), 真宵堂 on 7/18 evening (the daytime is a story lock).
- **Engine.** `Planner.plan` calls `Reminders.attach(m, start, codes, plan, options.lastTowerSlot)` right after `assemble`. One walk over the plan's codes records each slot's pre-action stat levels and cash. Every line is emitted only at `slot >= plan.fromSlot` and on a calendar day. A static line with `requires` needs the forecast to meet it at its slot; with `firstEligible` it goes to the first open slot of its kind from its day on where the forecast meets it, and it is dropped when its window began before the plan and the start already meets it (the player may have done it). TV lines append 「按预计，这天的现金可能不够。」 when the price is above the forecast cash.
- **Dynamic lines.** 上课犯困: 「上课犯困时别闭眼：学力 +2」 while Academics is below MAX, then 「上课犯困时闭上眼：勇气 +2」, nothing when both are MAX. 保健室: 「放学后先去保健室：勇气 +2，不会消耗时间」 on a school day with a free after-school slot and Courage below MAX, when the evening before is a planned Tartarus visit, a full-moon operation, an evening the story spends in Tartarus (`nurse.storyTowerDays`: 4/20, a prologue lock, so a new game's plan from 4/21 starts with it), or (before the plan) the latest recorded Tartarus evening (`Engine.Options.lastTowerSlot`). 失踪者, per group whose limit is not before the plan: when they go missing 「有 N 名失踪者（包括北村文吉），期限 10月3日」 (N also counts earlier groups with the same limit that the plan has not rescued yet; one line per slot and limit), on the first planned Tartarus evening of the window 「今晚去塔尔塔罗斯时，顺便救出失踪者（N 人）」 (one line per evening), else the day before the limit 「失踪者 7月6日截止：这几天没安排去塔尔塔罗斯」. The details of all three name what is lost: a modelled person's link, and what the group says it locks (`rescueGroups[].locksZh`: the 6/18 group, 「救不出来的话，伊莉莎白的第 97 号委托就做不了。」, aqiu). Groups whose modelled people are already rescued or lost, or who were already missing on the latest recorded Tartarus evening, get no line. Lines that give a stat end their detail with 「这份加成不在安排的预计里，能力值升级后去「同步现状」更新一下，安排会更准。」.
- **Contract.** `plan.reminders` is sorted by slot, priority (highest first), id; ids are the pack's or `doze.MM-DD`, `nurse.MM-DD`, `rescue.MM-DD.start|tower|deadline`. Reminders are not in `PlanHash` (the plan id and steps are the same with and without them) and never change the forecast (§10.9). Cost O(slots + items); the new-game plan JSON grows by about 40 KB.
- **Not shipped (P2):** Mitsuru's results-day reward, shop restocks, URL notes going on sale, the exam first-place threshold, "unlock becomes possible", request #75 and outings #77-81, Persona fusion names.

### 17.2 UI and widget

The reminders are plan data (`Plan.reminders`, final Chinese): shown, never recorded, not in the forecast. Their colour is the gold of the 顺便 side steps (`ReminderViews`).
- **今天.** 「今天别错过」 sits under the hero, before 「接下来」: the observed day's reminders from the observed slot on (an evening drops the day's), one row each with the gold label chip and the line, which has the whole width after the chip (a quoted answer stays whole at 130% font), and under it what it gives (「魅力 +2」); a row opens the day sheet of its slot. The chip names the kind, so a line that starts with its label shows without it (「课堂提问｜选「马」（第 2 项）」, `ReminderViews.lineOf`; read out once as well), and a gain the line already says (上课犯困, 保健室: 「上课犯困时别闭眼：学力 +2」) is not repeated (`gainOf`). The widget, which has no chip, shows the whole line. Hidden when there is none. The 接下来 rows carry a gold 「提醒 N」 for the slot's reminders.
- **Day sheet.** 「当天提醒」 after the day's 「这天：…」 note: every reminder of the day with its longer text, and 「这条只有一份攻略写过，仅供参考」 under an estimated answer whose longer text does not say so already (the pack's 7/17 「MADRID」 and 12/16 「JUNE」 say 「这条答案只有一份攻略写过，游戏里的写法可能略有不同。」, so no caption repeats it; shown verbatim, decision O4).
- **日程.** A third gold line per day, 「提醒：课堂提问 · 电视购物」 (each label once, in plan order, at most three then 「等」); the legend reads 「…金色的是顺便要做的事和当天提醒。」.
- **Which widget card.** `PlanItems` puts a reminder on the item covering its slot, else (a free slot) on the next item of the same day, else on none (今天 still lists it); reminders before the observed slot are behind. A run of story-locked slots splits where a new day carries a reminder, so each exam day is its own card with its answer; days without reminders still merge.
- **Widget rows.** Class and exam answers join the answers row (「课堂：马（第 2 项）」「考试：五月病」, §18). Every other kind uses the one-line reminder row (`widget_reminder`, bell `widget_icon_remind_day/night`): the most important reminder (priority, then plan order) whole, else its short form, else nothing, never 「…」; with more than one it ends 「 · 共 N 条」 (「（共 N 条）」 fused with lines that end in a number or a bracket: 「学力 +2（共 2 条）」; the short form alone is the last resort). The row is 11sp on the regular card and 10.5sp on the compact and tiny ones, like their hint and side rows (spec §1.2.7 says 11sp; `fitReminder` measures at the size drawn, `cueTextSp`). Every reminder is read out (`taskDescription`).

## 18. 3.1.0: 小组件对话选项

- **One source.** `engine/DialogueHint` turns a scene's verbatim guide fragments into one `Answers` (choices in order, every choice free, romance answers to avoid, the 朋友-route caution, conditional) and writes it two ways: the app's 「参考回答：A / B」 (`appLine`, byte-identical to 3.0.2 for a rank-up, checked over every scene of the pack) and the widget's 「答：A / B」 (`widgetLine`). `ui/Dialogue` delegates to it; the meeting's 「怎么做」 (`Labels.meetHow`) carries the same line.
- **Scene rule.** A rank-up shows the scene it ranks up into; a meeting during a hold (a rule hold of that rank-up, or one the player reported: `rank_hold`) or one that certainly does not rank up shows nothing; any other meeting shows the next scene's answers as 「要是升级了，答：…」 (muted on the widget). 3.0.2 showed the current RANK's scene on every meeting, one already played (9 of 41 non-rank-up meetings). The how-to lists the conditional line as a note under the steps, like 「参考回答」.
- **What the widget shows** (`PlanView.itemCard`, rules optional): the step's own line (a meeting's answers; an unlock step's picks, 「答：费洛蒙咖啡 / 红色 / 叶隐盖饭」, from the 「…」 after 选 / 回答 / 依次选 / 依次回答 in its how-to), then the side steps' picks (「顺便选：有话想和你说」; the side row keeps its own text), then the class and exam answers of the card's slots; one labelled line each in `widget_dialogue_choices`. 朋友 route: 「答：A / B · 别选表白」, or 「别选「我喜欢风花」」 where the guide names the romance answer, or 「答：别选表白的回答」 alone; every choice free: 「答：怎么回答都可以」; nothing known: no row (never the generic hint). The guide's 「（1）」 counts are left out on the widget (decision O3; the app keeps them).
- **Width ladder** (`CardRenderer.answerForms`): the first rung whose choices each fit a line and whose lines fit the budget (two, or one per answers line): labels in full and 「或者」; then 「升级时答：」; then 「或」; then three lines; then a class answer without its 「（第 n 项）」 (the answer names the option, so the position goes before any choice wraps, and stays out of the forms after it); then choices that wrap inside (short ones stay whole, a 「A或B」 wraps after its 「或」, a position 「（第 2 项）」 never breaks at its spaces, and neither the first two nor the last two characters of a choice split, so 「… / Ｙ」 then 「子是…」 cannot happen); then 11sp. Shorter forms for a short card follow: each line cut to its first whole choices plus 「等 N 项」 (N the total), a line only where that saves width (「答：明年的选拔赛呢？ / 加油」 is never cut to 「答：明年的选拔赛呢？ 等 2 项」, which is as wide); then lines go, the least needed first (`dropOrder`): the side steps' picks, then a 「要是升级了」 line when a 课堂/考试 answer is there (a meeting that may not rank up gives way to an answer that is certain and missed for good), then the 课堂/考试 answers; each kept line whole before it is cut; last a 朋友-route line's caution alone, the named answer to avoid or else 「别选表白」. No 「…」 inside a choice, no middle choice dropped, the order kept; a note that a line starts with goes there without its 「·」.
- **Row priority.** The answers row is essential and never hidden (spec §1.4.4). When the title would not fit even at its smallest size the rows go in this order: the cue (it already goes before the title drops below a comfortable size), the reminder row, the 人格面具 hint, the side step, the body's padding, the tag/location row, then the answers' shorter forms. On the shortest cards (130dp high, two lines of text under the plate) the row then gives up the gap above it, and where not even its shortest form fits beside a one-line title the title goes instead (`CardModel.titleHidden`; the card's description still names the step) and the row takes the first form that fits alone: a first choice wider than the card (「我也去看看吧或者我在这里等她」) needs two lines. A taller card keeps every line unless the row already fills two, or a 「要是升级了」 line gave way to a class answer; with a certain own line that fills two lines a class answer can still go (7/9 教皇 「答：我也去看看吧或我在这里等她 / 真担心啊」 with a class answer at 180x150: 2–4 renders per SDK, reported by `AnswerRowRenderTest`). One-line rows are measured at the pixel size the layout draws them (12sp at 115% is 28px on xhdpi, not 27.6), which a long location at a large font needed.
- **Read out.** The card's description carries the app's full line (「参考回答：…」 / 「要是升级了，答：…」), the picks and every reminder.
- **Checks.** `DialogueHintTest` (scene rule, routes, picks, 3.0.2 parity, the widget's choices equal the engine how-to's on real plans), `AnswerRowRenderTest` (real-pack matrix 180x130…320x240 × font 1.0/1.15 × SDK 26/31 over every card with answers of three plans, 4/21, 10/22 and 7/1, story-locked exam cards included: the row always shows, no cut that saves no width, no 「（第」/「项）」 split, no choice's first character alone after 「/」, a 课堂/考试 line outlasts the side picks and 「要是升级了」 lines; row priority; golden renders of 正义 R5, 隐者 R5, 力量 R2, 正义 R2 under `qa_out/v3/widget/answers/`), `PlanItemsTest`, `ReminderRowRenderTest`, `RemindersUiTest`.

## 19. 3.1.0: 逐日攻略移除

- **Owner override.** The owner asked to drop the old entry and cut ties with the old project (「能不能把老的entry彻底删了，和老的项目断舍离」; spec `docs/specs/SPEC_3.1.0.md` §1.3). This supersedes `P3R_HANDOFF_PROMPT.md:61` (「Existing fixed-guide widget and progress must remain safe when adding adaptive planning」) and §2/§7.1/§8/§9 above for the copy of the 2.x guide that 3.0.x carried. The 2.0.0 app (`app.p3r.dayplanner.test`) is a separate package and keeps its own guide and progress; `HANDOFF_COMPLIANCE.md` marks row [61] as superseded (`docs/analysis/audit/overrides.json`).
- **Removed.** `MainActivity` (the WebView page `assets/index.html`), `PlannerStore` (its progress in the prefs `p3r_native_progress_v1` and WebView storage), the 「P3R 逐日攻略」 widget `PlannerWidget` with `res/xml/planner_widget_info.xml`, the picker preview `widget_preview_guide.png` and the strings `widget_description` / `guide_widget_label`, the guide's theme `AppTheme` (`values/styles.xml`, `values-v27/`), the 我的 entry and its icon `ui_ic_guide`, and `GameCalendar.ordinalOfGuideDate`. Tests that only covered the guide went with it (`LegacyGuideChineseTest`, `PlannerIntegrityTest`, `PlannerNavigationTest`, `WidgetDialogueRenderTest`, `WidgetNativeRenderTest`, `WidgetPaletteFadeTest`, `WidgetRemoteViewsTest`, `LegacyWidgetRenderTest`, `LegacyGuideWordsRenderTest`), as did the stale `android/README.md`, `android/README_original_v1_2.md`, `android/NATIVE_WIP_CHECKPOINT_2026-10-12.md`, the Python `prototype/` and `docs/screenshots/legacy_guide.png`.
- **Kept.** The guide's `data.json` and `display_metadata.json` are pinned sources under `rules/sources/cn_guides/` (the rule builder reads `data.json`); the handoff zip, `P3R_HANDOFF_PROMPT.md`, `docs/handoff/`, `docs/analysis/` and `android/SEES_ASSET_SOURCE.txt` stay as records.
- **Moved.** `v3/widget/WidgetUpgradeReceiver` handles `MY_PACKAGE_REPLACED` and only calls `PlanWidget.refreshAll`. The manifest's application theme is `P3R.App` (every activity already sets its own).
- **Coverage kept.** The guide's tests were the only ones on some shared widget code, so they were ported: `FadeGuardTest` (tap throttle, settle generations), `PaletteHostTest` (both palette slots, the settled tree, an attached crossfade drawn frame by frame into `qa_out/v3/widget/fade`), `CardChromeTest` (40 dp targets around 34 dp silhouettes, flat surface, 当前/完成 clear of their glyphs, plate, light shafts, wordmark), `PlanWidgetFadeTest` (a resize cancels a running fade; a burst of taps fades once). `WidgetUpgradeReceiverTest` checks that the broadcast redraws every placed widget and that the manifest declares only the planner's components; `MePageTest` checks the 我的 entries. The real-ViewAnimator test shadow is `v3/widget/RealViewAnimator`.
- **Players.** An old 「P3R 逐日攻略」 widget on the home screen stops working, and the guide's progress can no longer be read. Release-note copy (spec §1.3 item 10, the export line made concrete): 「如果桌面上还有「P3R 逐日攻略」小组件，更新后它会失效，长按把它移除就好；「P3R 每日安排」不受影响。」 and 「3.1.0 去掉了「我的 → 逐日攻略」，它的打勾进度更新后就看不到了。想保留的话，更新前先打开逐日攻略，点右上角的菜单按钮打开「设置」，再点「导出进度备份」；这个文件可以导入旧版 2.0.0「P3R」的固定攻略。」 (the 3.0.x guide's menu button is 「打开设置和备份」, its button 「导出进度备份」; the 3.0.x and 2.0.0 guides share the progress format `p3r_hunterjia_checklist_v1`). README.md (下载) carries the same two points for players who update from 3.0.x. Owner decision O2 (default): the leftovers on the device (prefs and WebView storage) are not deleted; nothing in the app reads or writes them.
- **Not in this part.** Guide-only code inside the shared card renderer (`CardText` guide shaping, `CardRenderer.guideTitle`, `CardModel.guideText`, `GuideWords`, the `RenderSupport` legacy helpers, the "legacy" comments in the card layouts) goes with the widget answers rewrite (§18).
- **APK.** 3.0.2 was 1,028,444 B; a release build with only this removal is 839,496 B (the guide page, its two JSON files and the preview PNG were most of it), and it ships no asset besides `assets/p3r/p3r_rules.json`.

## 20. 3.1.0: 尽量都体验

Owner directive (spec §8): plan for a player who wants to see as much of the game as possible, not ten MAX links and the rest ignored.

- **安排风格.** `Profile.style` (JSON `style`: `"breadth"` / `"max"`), default 「尽量都体验」 (`BREADTH`), also for profiles stored without the key: it changes how the planner values links, not a choice the player made. 「尽量多 MAX」 (`MAX`) is the 3.0 objective. 规划设置 shows it as the first card (「先把每个社群都解锁、都往上提，再冲 MAX；时间不够时更均衡。」 / 「集中时间把尽量多的社群升到 MAX。」) and 规划说明 says which style plans (`InfoPages.styleLine`). Goals keep strict priority under both (`Planner.Pool.compareGoals` and `PlanValidator` are unchanged).
- **Objective.** Under 「尽量都体验」 each RANK of a 「其余社群」 link weighs `W_OTHER × BREADTH_RANK[r]` = 4, 2, 1.6, 1.4, 1.25, 0.9, 0.8, 0.7, 0.6, 0.5 for RANK 1…10 and MAX adds one rank's worth instead of six, so two unlocks (8) beat taking a link from RANK 5 to MAX (4.5). Goals weigh exactly as before up to their target (above it they count as 「其余社群」); only the bonus for reaching a goal earlier is halved (`BREADTH_EARLY_SHARE`): with all of it, finishing a 必须 goal one slot earlier was worth about one RANK of a 其余社群 link, and from 10/22 with 必须 ①星 ②正义 ③命运 the plan gave 21 daytimes to jobs that paid the goals' 结缘网站的字条 (「尽量多 MAX」: 1).
- **Goals never end below 「尽量多 MAX」** (spec §8.1). The breadth policy shares the days out differently, and its search alone can miss a goal the 3.0 policy reaches (3.1.0 review: 10/22, 必须 ①女教皇 ②永劫 ③恋爱 left 恋爱 at RANK 9 while 「尽量多 MAX」 reached all three, with a note that blamed 永劫). So when the best 「尽量都体验」 plan misses a goal, `Planner.Pool.addMaxStylePlans` runs the whole 「尽量多 MAX」 planning (the same screening, fortified searches and strict-priority runs, `Heuristics` with `breadth = false`) inside the same time budget and adds every plan to the pool, measured with the 「尽量都体验」 objective: `compareGoals` sees every plan 3.0 makes, and among plans whose goals tie the breadth objective decides. When every goal is reached nothing more runs (no plan can do better on the goals), so the common case costs nothing; when the extra run is cut short the plan is marked as out of time and gets the usual 2× follow-up. Checked in `PlanRealPackTest` (12 random 10/22 triples against their 「尽量多 MAX」 plans) and `PlanBreadthRealPackTest.goalProfilesKeepTheGoalsAndStayBroad` (22 goal profiles).
- **Base policy** (`Heuristics`, only under 「尽量都体验」, so 「尽量多 MAX」 plans are byte-identical to 3.0):
  - a link that will not reach its milestone is as urgent as the share of its remaining opportunities its next six meetings need (3.0 treats such meetings as filler);
  - the value of a link that cannot reach MAX counts its reachable ranks by their own weights (`ranksValue`);
  - in the goal-first tunings a required Tartarus visit that is not yet mandatory scores `BREADTH_GOAL_TOWER_KEEP` (4)× its 3.0 value, a constant of its own so that re-tuning the rank curve does not move Tartarus: otherwise an early 其余社群 unlock pushes the visits to the block's last evenings, where they crowd out the goals' 结缘网站的字条 (1/8, 必须 ①正义 ②力量: 力量 RANK 7 instead of 8 without it).
  - The first version also kept a link's stat gates at full value where 3.0 halves them; in the 3.1.0 review sweeps it raised daytime waste with goals (from 10/22 with 必须 隐者、魔术师、月亮: 12 wasted daytimes, 1 without it, 「尽量多 MAX」 2) and it was dropped. These policy terms go beyond spec §8.3's "objective/value table only" and are listed here for the lead's sign-off.
- **Plan id.** `PlanHash` adds the style only for 「尽量都体验」, so a 「尽量多 MAX」 plan keeps its 3.0 id (golden ids in `BreadthObjectiveTest`, synthetic year, and `PlanBreadthRealPackTest`, real pack, recorded at the WP-0 commit).
- **羁绊剧情 by default.** A first onboarding (nothing recorded, no profile saved) starts from `Profile.applyNewProfileDefaults`: under 「尽量都体验」 every track of the pack is opted in, so the wizard asks 「羁绊剧情已经看了几话？」 (「这些羁绊剧情会排进安排，不想看的可以在「社群」里取消勾选。…」: nothing was ticked yet). Stored profiles are never rewritten (`StateActivity.startProfile`).
- **Tuning** (real pack, default options). The first curve (1.15 … 0.9 from RANK 6, MAX bonus 2) passed the §8.2 comparisons only because the search did not find the 「尽量多 MAX」 plan: the 3.1.0 review scored both styles' plans with the 「尽量都体验」 objective and the 「尽量多 MAX」 plan scored higher from 10/22 with 必须 皇帝 + 想要 女教皇 (1,812,346 against 1,810,022) and in a new game with every 羁绊剧情 track. Sweeps over 43 starts (the §8.2 ones, the 1/8 pair, the 12 random triples, 24 seeded goal profiles) of the tail (RANK 6–10 from 1.0…0.6 down to 0.8…0.4), the MAX bonus (0.5–2), the share of the goals' earlier-MAX bonus (0.25–1) and each policy term kept the values above: the only set where the objective prefers the breadth plan in every §8.2 scenario (`PlanBreadthRealPackTest.both` asserts it) while every §8.2 comparison holds and daytime waste with goals is at or below 「尽量多 MAX」 over the set.

| start | MAX | unlocked | rank sum | RANK ≥ 5 | lowest RANK (opened) |
|---|---|---|---|---|---|
| new game 4/21, no Persona | 21 → 22 | 22 → 22 | 219 → 220 | 22 → 22 | 9 → 10 |
| new game 4/21, every 羁绊剧情 track | 21 → 21 | 22 → 22 | 216 → 217 | 22 → 22 | 6 → 7 |
| mid game 10/22 | 13 → 6 | 20 → 21 | 152 → 151 | 14 → 21 | 0 → 5 |
| mid game 10/22, 皇帝 必须 + 女教皇 想要 | 10 → 7 | 21 → 21 | 137 → 135 | 12 → 13 | 1 → 3 |
| 1/8 late start (every link but 恶魔 open) | 8 → 4 | 22 → 22 | 135 → 141 | 15 → 18 | 1 → 3 |

(「尽量多 MAX」 → 「尽量都体验」; the lowest RANK is over the non-story links either plan unlocks, as `docs/PLAN_METRICS.md` writes it in 「最低 RANK（解锁的）」: from the 10/22 state 女皇 needs Academics MAX from Lv.4, about 40 evenings, which neither style buys, so the plain lowest RANK is 0 under both.) Goal profiles (`goalProfilesKeepTheGoalsAndStayBroad`: the 1/8 pair, the review's seven 10/22 sets and 12 seeded 10/22 profiles): no goal ends lower than under 「尽量多 MAX」 in any of the 22 (nor in the 12 random triples of `PlanRealPackTest`, where a goal sometimes ends higher); over the set 428 links unlocked against 414, 294 at RANK 5 or above against 246, 280 wasted daytimes against 280. Not every profile is broader: 必须 ①星 ②正义 ③命运 stays below 「尽量多 MAX」 on every breadth number (18 unlocked against 19, rank sum 123 against 132, RANK ≥ 5 11 against 13, the 「尽量多 MAX」 plan scores higher under the breadth objective too, so the search simply misses it), two more unlock one link fewer, and two waste 11 and 13 more daytimes (bounds: one link, 15 daytimes). `docs/PLAN_METRICS.md` lists every scenario with both styles' breadth columns.
