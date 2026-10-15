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
  (the 3.0.x guide entry was removed in 3.1.0; 备份与恢复 exports the progress it left on the phone, §19).
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
   "救出北村文吉了吗？". Not rescued by the deadline → link lost. Both ends of a window count: a visit on the
   limit day's evening still rescues (the game's table gives Start/Limit days, aqiu's tested Reload walkthrough
   rescues on limit days; rules/build/REPORT.md, tartarus "rescue deadline"), so the person is lost from the day
   after the limit. The copy says 「最晚要在10月3日晚上救出」, never 「10月3日前」; the validator rejects a Tartarus
   step whose `rescue_on_visit` claim disagrees with the windows.
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
- **Accepting the result.** With an out-of-date plan, the result is installed as an ordinary job's would be (including the ordinary failure: error card, no automatic retry; its line 「生成安排时出了问题，现在的安排先不变」, `PlannerService.ZH_PLAN_FAILED_KEPT`, says what 「这次没能从头重新安排，现在的安排不变」 says, and the plan is never called a 「版」). With a current plan, the result replaces it when `acceptsFull` holds: better on the goals (`compareGoals`, the strict goal priority that `improves` is now built on), or tied on them and not worse on time (complete, or both ran out of time). The other links do not decide, because dropping the bias is the point. Otherwise the plan stays (`KEPT`) and its follow-up promise ends (`followUpDoneLocked`). A failure keeps a current plan without `planErrorZh` or `failedPlanKey`. An installed full plan is marked `followUp`, so its budget note reads `BUDGET_NOTE_DONE`.
- **Results** (`Snapshot.fullReplanTicket`, `fullReplanDoneTicket`, `fullReplanResult`; immutable holder `PlannerService.Full`, read before the model so a finished ticket comes with its plan):
  - `CHANGED`/`UNCHANGED`: action and 顺便 lines per slot against the installed plan (`sameSteps`);
  - `KEPT`, `FAILED`, `CANCELLED`;
  - null when there is nothing to tell: a backup import voids the request; 「先不算了」 with an out-of-date plan lets an ordinary replan run instead.
  - Tickets count per process. A repeated request while one is pending returns the same ticket. 0 means no state yet, or, after a failed load, the request works as 重试.
- **UI.** Entry 我的 → 安排 → 「从头重新安排」 (after 规划说明; off without state, after the last day and while running), and a quiet button under the 规划设置 footer 「改动会马上生效，安排也会自动更新；想完全不参考现在的安排、从头排一遍：」 (the sheet: 「会重新读取你记下的进度和规划设置，不参考现在的安排，从头排到1月31日。…」, the date in the numeral face like every other date of the app; the plan is never called a 「版」). Both open `FullReplanSheet` 「从头重新安排？」 ([先不了] [从头重新安排]; the running line instead while a request runs).
  - `HomeActivity.requestFullReplan` shows the running state at once (optimistic snapshot), then keeps the ticket (it survives `onSaveInstanceState`) and reloads. The reload also drops optimistic leftovers such as a profile. A screen that sees a request running whose ticket it never got (made again before the answer came, or opened from the launcher after the player backed out while it ran) takes the ticket from the snapshot, so the result is still shown once; the plan the result is counted against survives a configuration change (`onRetainNonConfigurationInstance`), and is otherwise the plan installed when the screen found the request running.
  - 今天 shows 「正在从头重新安排…」 with the busy stripe, 「可以照常记录，记下的结果会一起算进去」 and 「先不算了」; the error card waits.
  - When the done ticket reaches the kept one, `PlanDiff.fullSummary` gives the line once in the message bar and as the status line: 「从头重新安排好了：这一周有 N 处变动(，之后的也顺着调整了)」 / 「…这一周不变，之后的安排顺着调整了」 / 「…要做的事没变，顺便做的事有调整」, 「重新算过了，安排没有变化」, 「重新算过了，没找到更好的，现在的安排不变」, 「这次没能从头重新安排，现在的安排不变」, 「已停下，现在的安排不变」. Changes are counted against the plan shown when the request was made. If that plan is unknown (or a snapshot read in the instant between installing the plan and ending the request already holds the new plan, which a `CHANGED` result rules out as the plan before), the line is 「从头重新安排好了，一直排到1月31日」 (`PlanDiff.FULL_CHANGED_UNCOUNTED`). The last two lines get a neutral mark instead of the check. A record made during the run (the caption invites it) keeps its 「已记录：… 撤销」 bar: a result that ends meanwhile does not replace it and is said by the status line only.
  - Not on the 今天 hero or 规划说明. 「重新安排」/「重试」 still call `requestReplan`. Automatic replans after a profile change keep `previous` (owner decision O1: off).
- **Tests.** `PlannerServiceFullReplanTest`, `ReplanWorkerTest` (selective cancels), `FullReplanUiTest`, `PlanDiffFullSummaryTest`, `widget/PlanViewFullReplanTest`.

## 17. 3.1.0: 当日提醒

### 17.1 Data and engine

- **Pack.** The optional `reminders` section (schema stays `p3r.rules.v3`; packVersion 3.1.0) holds the static lines (`items`: class, exam, tv, request, fragment, trip, feature; final Chinese, sorted by day, slot, id), the 上课犯困 days (`doze`), the 保健室 rule (`nurse`) and the missing people in (missing from, limit) groups (`rescueGroups`). `RulesLoader.parseReminders` validates it with field paths (id pattern, kinds, days, `shortZh` ≤ 16 characters, `answerZh`/`option` only for answers, priority 1..3, `requires`/`firstEligible`, doze days on school days, known `rescueIds`, Reload sources). Counts and sources per kind: REPORT §4.8.
- **Answers.** Dates and option positions come from aqiu, the zh text from the pinned 3DM schedule pages `rules/sources/cn_guides/3dm_p2…p11.html` only; only dates, prices and positions are compared with English sources. The CN guide (`cn_guides/data.json`) is an edited copy of 3DM with English-localisation answers on 9 dates: the builder checks that this drift set is unchanged and never ships its answers. 3DM gives no text for 12/1–12/12 and after 1/13; at 3.1.0 the class answers of 12/7, 12/9, 12/11 and 1/18 were withheld. They now come from 游民星空's authorised copy of the same guide (猎猫人老贾, `cn_guides/gamersky_p1…p10.html`, text and option position, which equals aqiu's Choice N), each with the same zh text on the BWIKI page P3R/课堂问答与考试 (`cn_guides/bwiki_p3r_class.html`, zh text without positions): 臭氧层 (option 1), 鬼道 (2), 孤注一掷 (2; the copy types 孤注—掷, fixed to BWIKI's spelling by `GAMERSKY_TYPO_FIXES`), 喀耳刻 (2). The builder also checks that on the 32 dates both copies answer, 游民星空's answer and position equal 3DM's, and that BWIKI differs from the shipped text only on 9/1 (变成零; both copies write 变为零), zh vs zh only. 36 class and 17 exam answers ship (7/17 MADRID and 12/16 JUNE with conf=estimate). Three obvious 3DM typos are corrected to the CN guide's spelling of the same day (奥义书, 欣快感, 人造人; the 游民星空 copy spells them so too), and 3DM's 11.6 row carries the 11/7 class answer (`CN_3DM_ROW_FIXES`, applied to both copies).
- **Other static lines.** 电视购物: the 32 rows of the game's mail-order table (dates, quantities, prices) with zh item names from 3DM or the CN guide (7/5 has none: 「电视购物：有新商品，3,980 日元」, so the app's line after the chip is 「有新商品，3,980 日元」); the 7/12 and 11/29 request items raise the priority. 伊莉莎白的委托: 9 new-request days (5/10 and 1/1 in the evening, 3DM's evening column: the request system opens that evening, and 1/1's daytime is the shrine visit) and 8 time-limited windows, each shown from 3 days before its end (#97: 「…而且只有在 7月6日当天或更早救出了 6月18日失踪的学生才能完成」, past tense, since it shows from 12/22; the limit evening counts, §10.3). 黄昏的碎片 (4/21 town and dorm, 5/25 roof, Club Escapade on the first evening with Courage Lv2, 8/6 operation map), 屋久岛: request #44 accepted on the evening before (7/19, 3DM 「确保接取支线委托44」; on 7/20 it is too late) and the beach item on 7/20, their short forms 「出发前接伊莉莎白的委托」 and 「海边捡东西（伊莉莎白的委托）」 (the widget row has no chip, so a short form names the request as 伊莉莎白's, like the deadlines' 「伊莉莎白的委托 6月6日截止」), 修学旅行 11/17 request items (the oden is then bought from the student for 5,000 日元, not in the forecast), 真宵堂 on 7/18 evening (the daytime is a story lock).
- **Engine.** `Planner.plan` calls `Reminders.attach(m, start, codes, plan, options.lastTowerSlot)` right after `assemble`. One walk over the plan's codes records each slot's pre-action stat levels and cash. Every line is emitted only at `slot >= plan.fromSlot` and on a calendar day. A static line with `requires` needs the forecast to meet it at its slot; with `firstEligible` it goes to the first open slot of its kind from its day on where the forecast meets it, and it is dropped when its window began before the plan and the start already meets it (the player may have done it). TV lines append 「按预计，这天的现金可能不够。」 when the price is above the forecast cash.
- **Dynamic lines.** 上课犯困: 「上课犯困时别闭眼：学力 +2」 while Academics is below MAX, then 「上课犯困时闭上眼：勇气 +2」, nothing when both are MAX. 保健室: 「放学后先去保健室：勇气 +2，不会消耗时间」 on a school day with a free after-school slot and Courage below MAX, when the evening before is a planned Tartarus visit, a full-moon operation, an evening the story spends in Tartarus (`nurse.storyTowerDays`: 4/20, a prologue lock, so a new game's plan from 4/21 starts with it), or (before the plan) the latest recorded Tartarus evening (`Engine.Options.lastTowerSlot`). 失踪者, per group whose limit is not before the plan: when they go missing 「有 N 名失踪者（包括北村文吉），期限 10月3日」 (N also counts earlier groups with the same limit that the plan has not rescued yet; one line per slot and limit), on the first planned Tartarus evening of the window 「今晚去塔尔塔罗斯时，顺便救出失踪者（N 人）」 (one line per evening), else the day before the limit 「失踪者：7月6日截止，这几天没安排去塔尔塔罗斯」 (the app shows 「7月6日截止，这几天没安排去塔尔塔罗斯」 after the chip; short form 「失踪者 7月6日截止」). The details say 「在塔尔塔罗斯里找到失踪的人就能救出，还能拿到奖励。期限当天晚上去也来得及，过了期限就救不出来了。」 (the limit evening counts, §10.3; the unplanned line's detail says 「…最晚在期限当天晚上去一次塔尔塔罗斯。」) and 「失踪的人在塔尔塔罗斯里，探索时找到就能救出，还能拿到奖励。」, right for one person or many. The details of all three name what is lost: a modelled person's link, and what the group says it locks (`rescueGroups[].locksZh`: the 6/18 group, 「救不出来的话，伊莉莎白的第 97 号委托就做不了。」, aqiu). Groups whose modelled people are already rescued or lost, or who were already missing on the latest recorded Tartarus evening, get no line. Lines that give a stat end their detail with 「这份加成不在安排的预计里，能力值升级后去「同步现状」更新一下，安排会更准。」.
- **Contract.** `plan.reminders` is sorted by slot, priority (highest first), id; ids are the pack's or `doze.MM-DD`, `nurse.MM-DD`, `rescue.MM-DD.start|tower|deadline`. Reminders are not in `PlanHash` (the plan id and steps are the same with and without them) and never change the forecast (§10.9). Cost O(slots + items); the new-game plan JSON grows by about 40 KB.
- **Not shipped (P2):** Mitsuru's results-day reward, shop restocks, URL notes going on sale, the exam first-place threshold, "unlock becomes possible", request #75 and outings #77-81, Persona fusion names.

### 17.2 UI and widget

The reminders are plan data (`Plan.reminders`, final Chinese): shown, never recorded, not in the forecast. Their colour is the gold of the 顺便 side steps (`ReminderViews`).
- **今天.** 「今天别错过」 sits under the hero, before 「接下来」: the observed day's reminders from the observed slot on (an evening drops the day's), one row each with the gold label chip and the line, which has the whole width after the chip (a quoted answer stays whole at 130% font); the chips of one block (今天别错过, 当天提醒) all take the width of the widest, label centred, so every line starts on one column (`ReminderViews.chipColumn`; at 3.1.0 a 保健室 line started about 18px left of a 课堂提问 one), and under it what it gives (「魅力 +2」); a row opens the day sheet of its slot. The chip names the kind, so a line that starts with its label shows without it (「课堂提问｜选「马」（第 2 项）」, `ReminderViews.lineOf`; read out once as well), and a gain the line already says (上课犯困, 保健室: 「上课犯困时别闭眼：学力 +2」) is not repeated (`gainOf`). The widget, which has no chip, shows the whole line. Hidden when there is none. The 接下来 rows carry a gold 「提醒 N」 for the slot's reminders; the row, which TalkBack reads by its own description, adds 「，提醒 N 条」 to it (the marker alone said 「N 条当日提醒」, a word the glossary keeps from players, and was never read).
- **Day sheet.** 「当天提醒」 after the day's 「这天：…」 note: every reminder of the day with its longer text, and 「这条只有一份攻略写过，仅供参考」 under an estimated answer whose longer text does not say so already (the pack's 7/17 「MADRID」 and 12/16 「JUNE」 say 「这条答案只有一份攻略写过，游戏里的写法可能略有不同。」, so no caption repeats it; shown verbatim, decision O4).
- **日程.** A third gold line per day, 「提醒：课堂提问 · 电视购物」 (each label once, in plan order, at most three then 「等」); the legend reads 「…金色的是顺便要做的事和当天提醒。」.
- **Which widget card.** `PlanItems` puts a reminder on the item covering its slot, else (a free slot) on the next item of the same day, else on none (今天 still lists it); reminders before the observed slot are behind. A run of story-locked slots splits where a new day carries a reminder, so each exam day is its own card with its answer; days without reminders still merge.
- **规划说明.** 「课堂提问、上课犯困、保健室、考试名次等带来的能力值不在安排的预计里，回来时用「同步现状」更新就行。」, then 「课堂提问和考试的答案、电视购物、伊莉莎白的委托、黄昏的碎片这些不会消耗时间的事，还有失踪者的期限，会在「今天别错过」和日程详情的「当天提醒」里提醒。」 (`Assumptions.global`; the pack's 【当天提醒】 and 【未计入】 notes say the same, but no screen shows the pack's notes; until the 3.1.0 re-check the page still said 「课堂答题…不计入预测」).
- **Widget rows.** Class and exam answers join the answers row (「课堂：马（第 2 项）」「考试：五月病」, §18). Every other kind uses the one-line reminder row (`widget_reminder`, bell `widget_icon_remind_day/night`): the most important reminder (priority, then plan order) whole, else its short form, else nothing, never 「…」; with more than one it ends 「 · 共 N 条」 (「（共 N 条）」 fused with lines that end in a number or a bracket: 「学力 +2（共 2 条）」; the short form alone is the last resort). The row is 11sp on the regular card and 10.5sp on the compact and tiny ones, like their hint and side rows (spec §1.2.7 says 11sp; `fitReminder` measures at the size drawn, `cueTextSp`). Every reminder is read out (`taskDescription`).

## 18. 3.1.0: 小组件对话选项

- **One source.** `engine/DialogueHint` turns a scene's verbatim guide fragments into one `Answers` (choices in order, every choice free, romance answers to avoid, the 朋友-route caution, conditional) and writes it two ways: the app's 「参考回答：A / B」 (`appLine`, byte-identical to 3.0.2 for a rank-up, checked over every scene of the pack) and the widget's 「答：A / B」 (`widgetLine`). `ui/Dialogue` delegates to it; the meeting's 「怎么做」 (`Labels.meetHow`) carries the same line.
- **Scene rule.** A rank-up shows the scene it ranks up into; a meeting during a hold (a rule hold of that rank-up, or one the player reported: `rank_hold`) or one that certainly does not rank up shows nothing; any other meeting shows the next scene's answers as 「要是升级了，答：…」 (muted on the widget). 3.0.2 showed the current RANK's scene on every meeting, one already played (9 of 41 non-rank-up meetings). The how-to lists the conditional line as a note under the steps, like 「参考回答」.
- **What the widget shows** (`PlanView.itemCard`, rules optional): the step's own line (a meeting's answers; an unlock step's picks, 「答：费洛蒙咖啡 / 红色 / 叶隐盖饭」, from the 「…」 after 选 / 回答 / 依次选 / 依次回答 in its how-to), then the side steps' picks (「顺便选：有话想和你说」; the side row keeps its own text), then the class and exam answers of the card's slots; one labelled line each in `widget_dialogue_choices`, except that a 课堂/考试 answer may share the line before it (「答：好的 / 没那回事 · 课堂：变为零」) or, where the row has no room left for it, sit on the title's line at its right (`widget_title_aside`: 「北村文吉」 … 「课堂：卡巴拉」). The owner wants both kinds on the card without opening the app, the step's own answers and the 课堂/考试 one (row priority below). 朋友 route: 「答：A / B · 别选表白」, or 「别选「我喜欢风花」」 where the guide names the romance answer, or 「答：别选表白的回答」 alone; every choice free: 「答：怎么回答都可以」; nothing known: no row (never the generic hint). The guide's 「（1）」 counts are left out on the widget (decision O3; the app keeps them).
- **Width ladder** (`CardRenderer.answerForms`): the first rung whose choices each fit a line and whose lines fit the budget (two, or one per answers line): labels in full and 「或者」; then 「升级时答：」; then 「或」; then three lines; then a class answer without its 「（第 n 项）」 (the answer names the option, so the position goes before any choice wraps, and stays out of the forms after it); then a 课堂/考试 answer on the line before it, after 「 · 」 (「答：明年的选拔赛呢？ / 加油 · 课堂：印度」, `CardText.shape` with `MERGE_SEPARATOR`: the line breaks before the dot, never after it, and a drawn line that starts there drops the dot), again at 11sp, its choices always whole (「我很荣幸或」/「我不介意 · 课堂：奥义书」 reads worse than the class answer beside the title over whole choices); then choices that wrap inside (short ones stay whole, a 「A或B」 wraps after its 「或」, a position 「（第 2 项）」 never breaks at its spaces, and neither the first two nor the last two characters of a choice split, so 「… / Ｙ」 then 「子是…」 cannot happen); then 11sp with every choice whole; then 11sp with choices that wrap inside. A choice wraps inside only where that takes fewer lines than every choice whole at the same size, or where a choice is wider than the line (`CardRenderer.Ladder.loose`; spec §1.4.3 step 5): at 3.1.0 the only 11sp form wrapped inside, so a card short of height drew 「答：我会帮你的 / 这样的规」/「矩没有意义」 and 「考试：做好了拼命的心理」/「准备」 where the whole choice fits its own line in as many lines (3.1.0 re-check). A label alone on a line gives way to the next rung when that takes fewer lines (1/17 女教皇 at 180x150: 「升级时答：别选表白的回答」, not 「要是升级了，答：」/「别选表白的回答」), and a 12sp form cut to 「等 N 项」 that leaves 「等 N 项」 alone on a line gives way to the 11sp one when that takes fewer lines (`strands`). Shorter forms for a short card follow, first those that keep both kinds (`AnswerForm.both`: the step's own line with a choice in it, or its note where it has none, and every 课堂/考试 answer): an answer to every prompt with each choice of two alternatives written as its first (`AnswerLine.firstAlternatives`, 「我也去看看吧或者我在这里等她」 → 「我也去看看吧」: the guide's 「或者」 joins two equally good answers, so this drops no prompt); then each line cut to its first whole choices plus 「等 N 项」 (N the total), a line only where that saves width (「答：明年的选拔赛呢？ / 加油」 is never cut to 「答：明年的选拔赛呢？ 等 2 项」, which is as wide), so that the 课堂/考试 answer keeps its line or the end of the one before; then the side steps' picks go. Only then forms that lose a kind: lines go, the least needed first (`dropOrder`): the side steps' picks, then a 「要是升级了」 line when a 课堂/考试 answer is there (a meeting that may not rank up gives way to an answer that is certain and missed for good), then the 课堂/考试 answers; each kept line whole before it is cut; then a 朋友-route line's caution alone, the named answer to avoid or else 「别选表白」 (before the title rule below, a slip asked for these as cut forms, which a line without choices cannot be, so they never showed). Last, for a 130dp card with one line left beside its title and no room for both kinds even with the 课堂/考试 answer on the title's line: a 课堂/考试 answer alone and whole rather than a line with no choice in it, else the line that stays to the end with its count only, 「答：共 2 项，点开看看」 (`CardText.countOnly`; 「考试：点开看看」 for one choice, and so 「升级时答：点开看看」 where the count does not fit); the card's tap shows every answer. The forms up to the first cut one keep every line and every choice (`AnswerForm.whole`). `CardRenderer.asideForms` makes the same forms of the row without its first 课堂/考试 answer, which goes on the title's line without its 「（第 n 项）」, at the row's text size (11sp at the least, like the row). No 「…」 inside a choice, no middle choice dropped, the order kept; a note that a line starts with goes there without its 「·」.
- **Row priority.** The answers row is essential and never hidden (spec §1.4.4), and the title is never hidden either: a card that shows 「答：…」 says whom or what it is for (owner review of 3.1.0; until then `CardModel.titleHidden` dropped the whole title on the shortest cards, e.g. 7/10 教皇 at 180x130, which also lost its 「课堂：卡巴拉」 line, and 10/22 战车 at 180x130 at 115% font). When the title would not fit even at its smallest size the rows go in this order: the cue (it already goes before the title drops below a comfortable size), the reminder row, the 人格面具 hint, the side step, the body's padding, the tag/location row (none of these one-line rows is ever cut with 「…」: the side step shows whole, else its short form 「顺便：恶魔社群解锁前的准备」 when its steps prepare one link's unlock, `StepCopy.sideShort`, else 「顺便还有一件事，点开看看」, else no row, `CardRenderer.fitSide`; the 人格面具 reminder whole or its leading whole clauses, else no row, `fitHint`; at 3.1.0 both ended in 「…」 mid-word, 「顺便：把 20,000 日元交给穿西装…」 at 180x150 on SDK 26 and 「顺便：在喷泉前把 20,000 日元交给田中…」 at 250x160 and 115% on SDK 31; the card reads the whole line out either way); then the answers take their whole shorter forms (every line and every choice: 「升级时答：」, 「或」, three lines, no 「（第 n 项）」, a 课堂/考试 answer on the line before it, choices that wrap inside, 11sp); then the title keeps one line, the longest of its forms that fits that line whole at its smallest size (`CardRenderer.oneLineTitle` over `CardModel.shortTitles`, from `StepCopy.shortTitles`: 「教皇 · 北村文吉、光子」, 「北村文吉、光子」, 「北村文吉」, then the arcana 「教皇」; 「结缘网站的字条 · 岳羽由加莉」 → 「岳羽由加莉」 beside the note mark; 「羁绊剧情 · 伊织顺平（第 2 话）」 → 「伊织顺平（第 2 话）」 → 「伊织顺平」; an activity without the place its location names, 「在电玩大巡游游玩「你就是答案」」 → 「游玩「你就是答案」」); then the row gives up the 3dp gap above it. Both kinds of answers stay before any line of them goes (owner, after 3.1.0): next the 课堂/考试 answer moves to the title's line, at its right after an 8dp gap, never cut (`CardModel.answersAside`), and the title keeps the longest of its forms that fits one line beside it (`CardRenderer.titleBeside`: 「北村文吉」 beside 「课堂：卡巴拉」, 「宫本一志」 beside 「课堂：印度」 at 180x130 at 115% font), while the row keeps the step's own answers whole, else in the shorter forms that keep both kinds (the first of two alternatives, 「等 N 项」); then the row's own shorter forms that keep both kinds, with the class answer in it; last the forms that lose a kind (lines left out, a caution alone, a 课堂/考试 answer alone, the count). Titles are measured at the smallest size the layout's autosize draws, which is whole pixels (12sp at 115% is 28px, not 27.6; `CardRenderer.fitsOneLine`), and the narrow card's body padding is 8dp, in line with its buttons (10dp until 3.1.0), which 「答：明年的选拔赛呢？ / 加油」 at 115% font needs beside 「课堂：印度」. The row gives up the 3dp gap above it rather than a choice: a whole form without the gap goes before a cut form, and a cut form keeps the gap where it fits. Story-locked cards keep their short label (「考试期间」). On the shortest cards (130dp high, two lines of text under the plate) that is one line of title and one line of answers. A title that would still end in 「…」 on a taller card gives way to the first of its short forms that is whole. A taller card keeps every line unless the row already fills two (then the side steps' picks may go); the step's own answers and the 课堂/考试 ones always stay. 3.1.0 left the class answer out where the meeting's answers filled two lines (7/10 教皇 「答：我也去看看吧或我在这里等她 / 真担心啊」 at 180x150 and 250x160 at 115% font, 10/19 战车 「答：明年的选拔赛呢？ / 加油」 at 180x150 at 115%), and a 130dp card showed one kind only; now the 16 real cards with both kinds keep both on all 192 of their renders per SDK: at 130dp the class answer shares a short meeting line (「答：走吧！ · 课堂：摆锤」) or sits beside the title (35 of their 64 renders at 130dp per SDK; above 130dp only 7/10 教皇 and 11/7 力量 at 180x150, at 115% font and on SDK 31 at 100% too), at 150dp and more it has a line of its own or the end of the meeting's (「答：明年的选拔赛呢？ / 加油 · 课堂：印度」). In the real-pack matrix the count alone shows on 5 of 1,476 renders per SDK, all 隐者 「Ｙ子是教醉拳的代理师傅？或者Ｙ子是老师？」 at 180x130, whose first alternative alone is wider than the line (3.1.0: 15, which the first alternatives now answer, 「答：我也去看看吧 / 真担心啊」). One-line rows are measured at the pixel size the layout draws them (12sp at 115% is 28px on xhdpi, not 27.6), which a long location at a large font needed.
- **Read out.** The card's description carries the app's full line (「参考回答：…」 / 「要是升级了，答：…」), the picks and every reminder.
- **Checks.** `DialogueHintTest` (scene rule, routes, picks, 3.0.2 parity, the widget's choices equal the engine how-to's on real plans), `AnswerRowRenderTest` (real-pack matrix 180x130…320x240 × font 1.0/1.15 × SDK 26/31 over every card with answers of three plans, 4/21, 10/22 and 7/1, story-locked exam cards included: the row always shows, every card whose step has answers of its own and a 课堂/考试 answer keeps both kinds at every size, in the row or beside the title (asserted, no reported exceptions; renders of those cards at the four shorter sizes under `qa_out/v3/widget/answers/both/`), no taller card leaves a line out, no cut that saves no width, no 「（第」/「项）」 split, no choice's first character alone after 「/」, no choice wrapped inside where it fits a line and every choice whole takes no more lines (`RenderSupport.collectNeedlessChoiceWraps`, in every answers-row check), a 课堂/考试 line outlasts the side picks and 「要是升级了」 lines; row priority; golden renders of 正义 R5, 隐者 R5, 力量 R2, 正义 R2 under `qa_out/v3/widget/answers/`; every one of these renders also shows its title, whole, as the title or one of its short forms, never clipped; the real 7/10 教皇 and 10/19 战车 cards, each with its own line and a class line, at 130dp, 180x150 and 250x160 as `shortest-*`), `CardRendererTest` (one-line title beside answers that need the room, the first alternatives on a 130dp card, the class answer beside the title; a class answer on the line of a short meeting answer with only the meeting's part muted; the longest short form that fits; side and hint rows whole, short or gone at every size, font scale and SDK), `PlanWidgetRenderTest` (no state hides its title; the meeting card at 130dp), `PlanViewTest` (`StepCopy.shortTitles`, `StepCopy.sideShort`), `PlanItemsTest`, `ReminderRowRenderTest`, `RemindersUiTest`.

## 19. 3.1.0: 逐日攻略移除

- **Owner override.** The owner asked to drop the old entry and cut ties with the old project (「能不能把老的entry彻底删了，和老的项目断舍离」; spec `docs/specs/SPEC_3.1.0.md` §1.3). This supersedes the widget half of `P3R_HANDOFF_PROMPT.md:61` (「Existing fixed-guide widget and progress must remain safe when adding adaptive planning」) and §2/§7.1/§8/§9 above for the copy of the 2.x guide that 3.0.x carried. The progress half still holds: the ticks stay on the phone and can be exported at any time (**Progress kept safe** below). The 2.0.0 app (`app.p3r.dayplanner.test`) is a separate package and keeps its own guide and progress. `HANDOFF_COMPLIANCE.md` marks row [61] as met, and its evidence says the old widget itself was removed on the owner's directive (`docs/analysis/audit/overrides.json`).
- **Removed.** `MainActivity` (the WebView page `assets/index.html`), `PlannerStore` (its progress in the prefs `p3r_native_progress_v1` and WebView storage), the 「P3R 逐日攻略」 widget `PlannerWidget` with `res/xml/planner_widget_info.xml`, the picker preview `widget_preview_guide.png` and the strings `widget_description` / `guide_widget_label`, the guide's theme `AppTheme` (`values/styles.xml`, `values-v27/`), the 我的 entry and its icon `ui_ic_guide`, and `GameCalendar.ordinalOfGuideDate`. Tests that only covered the guide went with it (`LegacyGuideChineseTest`, `PlannerIntegrityTest`, `PlannerNavigationTest`, `WidgetDialogueRenderTest`, `WidgetNativeRenderTest`, `WidgetPaletteFadeTest`, `WidgetRemoteViewsTest`, `LegacyWidgetRenderTest`, `LegacyGuideWordsRenderTest`), as did the stale `android/README.md`, `android/README_original_v1_2.md`, `android/NATIVE_WIP_CHECKPOINT_2026-10-12.md`, the Python `prototype/` and `docs/screenshots/legacy_guide.png`.
- **Kept.** The guide's `data.json` and `display_metadata.json` are pinned sources under `rules/sources/cn_guides/` (the rule builder reads `data.json`); the handoff zip, `P3R_HANDOFF_PROMPT.md`, `docs/handoff/`, `docs/analysis/` and `android/SEES_ASSET_SOURCE.txt` stay as records.
- **Moved.** `v3/widget/WidgetUpgradeReceiver` handles `MY_PACKAGE_REPLACED` and only calls `PlanWidget.refreshAll`. The manifest's application theme is `P3R.App` (every activity already sets its own).
- **Coverage kept.** The guide's tests were the only ones on some shared widget code, so they were ported: `FadeGuardTest` (tap throttle, settle generations), `PaletteHostTest` (both palette slots, the settled tree, an attached crossfade drawn frame by frame into `qa_out/v3/widget/fade`), `CardChromeTest` (40 dp targets around 34 dp silhouettes, flat surface, 当前/完成 clear of their glyphs, plate, light shafts, wordmark), `PlanWidgetFadeTest` (a resize cancels a running fade; a burst of taps fades once). `WidgetUpgradeReceiverTest` checks that the broadcast redraws every placed widget and that the manifest declares only the planner's components; `MePageTest` checks the 我的 entries. The real-ViewAnimator test shadow is `v3/widget/RealViewAnimator`.
- **Players.** An old 「P3R 逐日攻略」 widget on the home screen stops working, and the guide itself is gone. Its ticks are not: owner decision O2 (default) keeps the leftovers on the device (prefs and WebView storage), and 备份与恢复 exports them (next point). Release-note copy: 「如果桌面上还有「P3R 逐日攻略」小组件，更新后它会失效，长按把它移除就好；「P3R 每日安排」不受影响。」 and 「3.1.0 去掉了「我的 → 逐日攻略」。它的打勾进度不会被删除，更新后仍然留在手机上，随时可以在「我的 → 备份与恢复」里点「导出旧逐日攻略的打勾进度」导出；这个文件可以导入旧版 2.0.0「P3R」的固定攻略。」 README.md (下载, 「从 3.0.x 更新」) carries the same two points. Until this export existed, the 3.1.0 notes told players to export **before** updating, from the 3.0.x guide's own menu (「打开设置和备份」, then 「导出进度备份」); the published 3.1.0 APK does not have the entry, so `docs/RELEASE_NOTES_3.1.0.md` says it came after 3.1.0. The 3.0.x and 2.0.0 guides share the progress format `p3r_hunterjia_checklist_v1`.
- **Progress kept safe (after 3.1.0).** `ui/OldGuideProgress` reads the prefs `p3r_native_progress_v1` read-only: `getAll()` only, never an editor, so nothing in the app writes or deletes them. They are the guide's own copy: the 3.0.x page mirrored every tick there (`syncState`), and its widget wrote there directly; the WebView storage is not read. When `"completed"` holds at least one task with value `true`, 备份与恢复 (`ui/BackupPage`) ends with a section 「旧版逐日攻略」 and the entry 「导出旧逐日攻略的打勾进度」, subtitle 「3.0.x 逐日攻略里打过勾的 N 项还在手机上，导出后可以在旧版 2.0.0「P3R」里导入」. The prefs are read off the main thread, so the section appears once they are. Without a ticked task the page has no such section. The entry saves through `ACTION_CREATE_DOCUMENT` (`application/json`, default name 「P3R_打卡进度.json」, the old page's download name; request `BackupPage.REQUEST_EXPORT_OLD_GUIDE`, handled in `HomeActivity.onActivityResult`). The prefs are read again when the result arrives, so a recreated activity can still write; the file is opened with "wt" (else "w"). The file is what the old page's `exportProgress()` wrote: `JSON.stringify({format: "p3r_hunterjia_checklist_v1", completed, completedSubsteps, schemaVersion: 2, exportedAt}, null, 2)`, with `completed` from the prefs' `"completed"` (ticked and unticked tasks as stored), `completedSubsteps` from `"substeps"`, and `exportedAt` as `toISOString()` (UTC, milliseconds). Values are written the way that call writes them: array-index keys first, `/` unescaped, controls and lone surrogates escaped, JavaScript numbers. Stored text that is not strict JSON counts as empty: a broken `"completed"` hides the entry, and broken `"substeps"` export as `{}`. Messages: 「打勾进度已导出。」, 「导出失败，请换个位置再试。」, 「没有可以保存文件的应用。」. `OldGuideExportTest` covers: no entry without a ticked task (and looking creates no prefs file); the entry and its count; the save request; the saved bytes equal the old page's output for the same stored text (expected files produced by running that expression in Node), including odd values; the prefs' keys, values and file bytes are unchanged after an export; broken stored text never crashes; a failed write reports it and a cancel writes nothing. Render: `qa_out/v3/ui/home_backup_old_guide.png`.
- **Not in this part.** Guide-only code inside the shared card renderer (`CardText` guide shaping, `CardRenderer.guideTitle`, `CardModel.guideText`, `GuideWords`, the `RenderSupport` legacy helpers, the "legacy" comments in the card layouts) goes with the widget answers rewrite (§18).
- **APK.** 3.0.2 was 1,028,444 B; a release build with only this removal is 839,496 B (the guide page, its two JSON files and the preview PNG were most of it), and it ships no asset besides `assets/p3r/p3r_rules.json`.

## 20. 3.1.0: 尽量都体验

Owner directive (spec §8): plan for a player who wants to see as much of the game as possible, not ten MAX links and the rest ignored.

- **安排风格.** `Profile.style` (JSON `style`: `"breadth"` / `"max"`), default 「尽量都体验」 (`BREADTH`), also for profiles stored without the key: it changes how the planner values links, not a choice the player made. 「尽量多 MAX」 (`MAX`) is the 3.0 objective. 规划设置 shows it as the first card (「先把每个社群都解锁、都往上提，再冲 MAX；时间不够时更均衡。」 / 「集中时间把尽量多的社群升到 MAX。」) and 规划说明 says which style plans (`InfoPages.styleLine`). Goals keep strict priority under both (`Planner.Pool.compareGoals` and `PlanValidator` are unchanged).
- **Objective.** Under 「尽量都体验」 each RANK of a 「其余社群」 link weighs `W_OTHER × BREADTH_RANK[r]` = 4, 2, 1.6, 1.4, 1.25, 0.9, 0.8, 0.7, 0.6, 0.5 for RANK 1…10 and MAX adds two ranks' worth instead of six (`BREADTH_MAX_BONUS_RANKS`), so two unlocks (8) still beat taking a link from RANK 5 to MAX (3.5 + 2), while once every link is open and high, finishing two links beats raising a third from RANK 3 to 8 (2·(0.7 + 0.6 + 0.5 + 2) = 7.6 against 5.05). With one rank's worth (the first 3.1.0 value) that trade was worth 0.55 ranks, less than the margin of one wasted daytime, and a new game with Persona for the goals only (必须 皇帝, 想要 恋爱、女皇) stopped at 19 MAX with three links at RANK 7–8 where 「尽量多 MAX」 reaches 21: MAX scenes are content too. Goals weigh exactly as before up to their target (above it they count as 「其余社群」); only the bonus for reaching a goal earlier is cut to a quarter (`BREADTH_EARLY_SHARE` 0.25): with all of it, finishing a 必须 goal one slot earlier was worth about one RANK of a 其余社群 link, and from 10/22 with 必须 ①星 ②正义 ③命运 the plan gave 21 daytimes to jobs that paid the goals' 结缘网站的字条 (「尽量多 MAX」: 1); with half of it the 「尽量多 MAX」 plan, now always a candidate (next item), won from 10/22 with 必须 皇帝 + 想要 女教皇 by reaching the goals a few days earlier, and the style lost its broader plan there.
- **Never worse than 「尽量多 MAX」** (owner, after the 3.1.0 re-check: a player who wants to see everything must never get a worse plan from 「尽量都体验」 than from 「尽量多 MAX」 on what the style promises, nor lose MAX in a new game when time allows). At 10355be the 「尽量多 MAX」 plans joined a 「尽量都体验」 pool only when its best plan missed a goal (that rule fixed 10/22, 必须 ①女教皇 ②永劫 ③恋爱, where the breadth search alone left 恋爱 at RANK 9). With every goal reached the breadth plan could still be worse on every breadth number (10/22, 必须 ①星 ②正义 ③命运: 18 links unlocked against 19, rank sum 123 against 132, RANK ≥ 5 11 against 13, although the 「尽量都体验」 objective scored the 「尽量多 MAX」 plan 2,150 higher) or waste 11 and 13 more daytimes (two seeded 10/22 goal profiles).
  - `Planner.Pool.startMaxStyleRun` starts the whole 「尽量多 MAX」 planning of the same start (screening, fortified searches and strict-priority runs exactly as a profile with that style; `Heuristics` with `breadth = false`) on a second thread when a 「尽量都体验」 plan starts, with the same deadline and cancel flag and no progress callback. `addMaxStylePlans` waits for it, adds every plan it made to the pool, measured with the 「尽量都体验」 objective, and keeps the plan that style keeps as `maxPlan`. The two runs share only immutable data (the model, the tables, the start state); when no thread can be had the run happens after the breadth run on the same thread.
  - `Pool.choose`: strict goal priority first (`compareGoals`, unchanged, so goals still end where 「尽量多 MAX」 ends them or higher). Among the plans whose goals tie with the best, only those that keep the promises (`keepsPromises`) take part: not beaten by `maxPlan` on links unlocked, links at RANK 5 or above and the rank sum all at once (one of them strictly), fewer links unlocked than `maxPlan` only when the 「尽量都体验」 objective J (before the waste margin) scores them at least as high (next item), and at most `BREADTH_WASTE_SLACK` (2) more wasted daytimes. `maxPlan` keeps them itself, so whenever its goals tie the plan kept keeps every promise and its score (the objective less the waste margin per wasted daytime, what the planner has compared complete plans by since 3.0) is at least `maxPlan`'s, less `SWITCH_MARGIN` at most when it keeps the previous plan's next three days. When a breadth plan reaches a goal 「尽量多 MAX」 misses, strict priority keeps it, and the promises apply among the plans with those goals when one of them keeps them.
  - Why the whole run rather than screening the 「尽量多 MAX」 tunings and fortifying the promising ones: in the re-check's 10/22 goal profiles the best screened tuning (its base policy alone) scored up to 20,000 below the best breadth plan while the best fortified one scored up to 7,700 above it (必须 ①星 ②正义 ③命运: 9,740 below screened, 2,150 above fortified), so the screen does not tell which tunings are worth a search; and the guarantee needs the very plan 「尽量多 MAX」 keeps.
  - **Unlock promise** (3.1.0 re-check). The style says 「先把每个社群都解锁」, yet the score could keep a plan that leaves a link locked which 「尽量多 MAX」 takes to MAX, although J itself scores the 「尽量多 MAX」 plan higher: from 10/22 with 想要 恋爱, 必须 永劫、倒悬者 (random 5 of `goalProfilesKeepTheGoalsAndStayBroad`) 恶魔 stayed at RANK 0 (尽量多 MAX: MAX), 19 links unlocked against 20, J 3,724 lower, kept for 6 wasted daytimes against 18; likewise 必须 皇帝、恋爱 with Persona for the goals (20 against 21, J 1,674 lower) and 必须 女教皇 with every 羁绊剧情 track (20 against 21 although one more MAX). The cause is the waste margin: with goals it is 2.5 slots of the goals' earlier-MAX bonus (`wasteMargin`: 500 per wasted daytime with one 必须 goal, 750 with three), while 「尽量都体验」 keeps a quarter of that bonus in J and an unlock weighs 4 × 300 = 1,200; the daytimes that pay 恶魔's fees and reach 魅力 Lv.4 count as waste. Scaling the margin's goal term by `BREADTH_EARLY_SHARE` would change every breadth plan with goals (the tuning sweep above: 69 more wasted daytimes over 45 starts), so `keepsPromises` takes the narrower rule: a plan that unlocks fewer links than `maxPlan` is kept only when J agrees. A plan with fewer links unlocked and a higher J stays allowed (10/22 必须 隐者、教皇、皇帝: 19 against 20, rank sum 131 against 115, J 3,850 higher). Effect: random 5 now keeps the 「尽量多 MAX」 plan itself (20 unlocked, 恶魔 MAX, 18 wasted daytimes), 皇帝、恋爱 with Persona 21 unlocked, rank sum 138 (尽量多 MAX 135), RANK ≥ 5 15 (12), 24 wasted daytimes (22), 女教皇 with every track 21 unlocked, rank sum 135 (126), 24 wasted daytimes (23), both with 恶魔 at MAX; `PlanRealPackTest`'s 10/22 ①恶魔 ②女皇 ③月亮 21 unlocked (was 20), rank sum 117 (122), RANK ≥ 5 11 (10). `goalProfilesKeepTheGoalsAndStayBroad` asserts per profile that no link 「尽量多 MAX」 takes to MAX stays at RANK 0, and `BreadthObjectiveTest.choiceKeepsThePromisesAgainstTheMaxPlan` checks the rule in `choose`.
  - **The floor is the score, not J.** J alone is lower for the kept plan in a few comparisons where that plan is broader on every count and wastes fewer daytimes (3.1.0 re-check: random 3 and 7, 20 and 15 lower); the planner compares complete plans by the score, so `PlanBreadthRealPackTest.brokenPromise` checks score(「尽量都体验」) ≥ score(「尽量多 MAX」) plus the unlock promise above, not J(「尽量都体验」) ≥ J(「尽量多 MAX」).
  - Cost: the extra run costs what a 「尽量多 MAX」 plan costs, on its own core, so a 「尽量都体验」 plan takes about as long as before on the desktop JVM: new game 0.7–0.95 s (1.4–1.8 s when the two ran one after the other, over `PlanRealPackTest`'s 1.5 s), 10/22 0.15–0.3 s; the CPU work doubles. The slowest start found is a new game with goals and every 羁绊剧情 track (必须 力量 RANK 6, 想要 命运 RANK 4: 1.4–1.5 s, 「尽量多 MAX」 1.1 s; 3.1.0 re-check), held to 2 s by `PlanBreadthRealPackTest.newGameWithGoalsAndEveryEpisodeStaysWithinTheBudget` and never out of the 2.5 s budget there. On a device where one run outlasts the 2.5 s budget both stop at the deadline and the plan is marked out of time as before.
  - The score, not the objective alone: in 7 of the 45 starts compared above (the new games and §8.2 starts, the goal profiles and the random triples) the objective without the waste margin scores the 「尽量多 MAX」 plan higher, mostly because it reaches the goals earlier or opens one more link, paid with 3–17 more wasted daytimes. 10/22 必须 恶魔: 「尽量都体验」 12 MAX, 21 unlocked, rank sum 154, RANK ≥ 5 13, 3 wasted daytimes against 11, 21, 143, 12 and 16, and an objective 15 lower. So `PlanBreadthRealPackTest.brokenPromise` compares scores (`breadthScore`), as the planner does.
  - Checked per profile, with the promises of `PlanBreadthRealPackTest.brokenPromise` (goals, not dominated, waste + 2, score), in `PlanBreadthRealPackTest` (every comparison, the 22 goal profiles of `goalProfilesKeepTheGoalsAndStayBroad`), `PlanRealPackTest.strictGoalPriorityOnRandomMidGameTriples` (12 random 10/22 triples) and `BreadthObjectiveTest` (synthetic year; `choiceKeepsThePromisesAgainstTheMaxPlan` checks `choose` itself). New games also keep the same links unlocked and at most one MAX fewer (`newGameKeepsTheMaxCount`, which now includes the Persona-for-goals case, and `newGameWithGoalsAndEveryEpisodeStaysWithinTheBudget`).
- **Base policy** (`Heuristics`, only under 「尽量都体验」, so 「尽量多 MAX」 plans are byte-identical to 3.0):
  - a link that will not reach its milestone is as urgent as the share of its remaining opportunities its next six meetings need (3.0 treats such meetings as filler);
  - the value of a link that cannot reach MAX counts its reachable ranks by their own weights (`ranksValue`);
  - in the goal-first tunings a required Tartarus visit that is not yet mandatory scores `BREADTH_GOAL_TOWER_KEEP` (4)× its 3.0 value, a constant of its own so that re-tuning the rank curve does not move Tartarus: otherwise an early 其余社群 unlock pushes the visits to the block's last evenings, where they crowd out the goals' 结缘网站的字条 (1/8, 必须 ①正义 ②力量: 力量 RANK 7 instead of 8 without it).
  - The first version also kept a link's stat gates at full value where 3.0 halves them; in the 3.1.0 review sweeps it raised daytime waste with goals (from 10/22 with 必须 隐者、魔术师、月亮: 12 wasted daytimes, 1 without it, 「尽量多 MAX」 2) and it was dropped. These policy terms go beyond spec §8.3's "objective/value table only" and are listed here for the lead's sign-off.
- **Plan id.** `PlanHash` adds the style only for 「尽量都体验」, so a 「尽量多 MAX」 plan keeps its 3.0 id (golden ids in `BreadthObjectiveTest`, synthetic year, and `PlanBreadthRealPackTest`, real pack). The real-pack ids depend on the pack version: they are recorded per pack (`GOLDEN_RULES_VERSION`, `GOLDEN_IDS`, last on `3.1.0|ac37070b36b4`, the pack with the four 游民星空 class answers) and the test fails on another pack, printing the ids to record once the step fingerprints show the plans unchanged (3.1.0 re-check: since the pack rebuild of 10355be the id comparison had been skipped with a message only).
- **羁绊剧情 by default.** A first onboarding (nothing recorded, no profile saved) starts from `Profile.applyNewProfileDefaults`: under 「尽量都体验」 every track of the pack is opted in, so the wizard asks 「羁绊剧情已经看了几话？」 (「这些羁绊剧情会排进安排，不想看的可以在「社群」里取消勾选。…」: nothing was ticked yet). Stored profiles are never rewritten (`StateActivity.startProfile`).
- **Tuning** (real pack, default options). The first curve (1.15 … 0.9 from RANK 6, MAX bonus 2) passed the §8.2 comparisons only because the search did not find the 「尽量多 MAX」 plan: the 3.1.0 review scored both styles' plans with the 「尽量都体验」 objective and the 「尽量多 MAX」 plan scored higher from 10/22 with 必须 皇帝 + 想要 女教皇 (1,812,346 against 1,810,022) and in a new game with every 羁绊剧情 track; the steeper tail above came from sweeps over 43 starts. With the 「尽量多 MAX」 plans always in the pool, a curve that prefers them simply returns them, so the re-check swept, over the 45 starts of `docs/PLAN_METRICS.md`, the goal profiles and the random triples (plus 20 starts replayed from a new-game plan to 6/1, 7/20, 9/7, 11/16 and 12/14 with random goals), the MAX bonus (1, 1.5, 2), the earlier-MAX share (0.1, 0.25, 0.5), a steeper tail (RANK 6–10 0.8…0.4 and 0.7…0.3), the waste margin scaled with the share, and three more fortified breadth searches for short horizons. Kept: the tail above, MAX bonus 2, a quarter of the earlier-MAX bonus, the waste margin and fortified searches as they were. Every §8.2 comparison holds with them and every new game stays within one MAX; 10/22 with 必须 皇帝 + 想要 女教皇 is the delicate one: with a MAX bonus of 1.5, half the earlier-MAX bonus or either steeper tail the plan kept there was the 「尽量多 MAX」 plan itself, which is no broader, and a MAX bonus of 1 leaves the new game with Persona for goals at 19 MAX. A tenth of the earlier-MAX bonus passed too, with nearly the same totals; a quarter keeps more of what the bonus is for (a goal reached earlier leaves room for a slip). Scaling the waste margin with the share passed with 69 more wasted daytimes over the 45 starts. The extra fortified searches gave +7 links at RANK 5 or above over 39 starts and nothing on the replayed ones, so they were left out.

| start | MAX | unlocked | rank sum | RANK ≥ 5 | lowest RANK (opened) | wasted daytime |
|---|---|---|---|---|---|---|
| new game 4/21, no Persona | 21 → 22 | 22 → 22 | 219 → 220 | 22 → 22 | 9 → 10 | 0 → 0 |
| new game 4/21, every 羁绊剧情 track | 21 → 21 | 22 → 22 | 216 → 217 | 22 → 22 | 6 → 7 | 1 → 1 |
| new game 4/21, Persona for goals only, 皇帝 必须 + 恋爱、女皇 想要 | 21 → 20 | 22 → 22 | 213 → 216 | 21 → 22 | 3 → 8 | 1 → 1 |
| mid game 10/22 | 13 → 8 | 20 → 21 | 152 → 151 | 14 → 18 | 0 → 4 | 1 → 1 |
| mid game 10/22, 皇帝 必须 + 女教皇 想要 | 10 → 7 | 21 → 21 | 137 → 137 | 12 → 13 | 1 → 3 | 22 → 21 |
| 1/8 late start (every link but 恶魔 open) | 8 → 5 | 22 → 22 | 135 → 140 | 15 → 16 | 1 → 3 | 0 → 1 |

(「尽量多 MAX」 → 「尽量都体验」; the lowest RANK is over the non-story links either plan unlocks, as `docs/PLAN_METRICS.md` writes it in 「最低 RANK（解锁的）」: from the 10/22 state 女皇 needs Academics MAX from Lv.4, about 40 evenings, which neither style buys, so the plain lowest RANK is 0 under both.) Against 10355be (breadth before → after): the new game with Persona for the goals only went from 19 to 20 MAX (rank sum 212 → 216, lowest RANK 7 → 8), the new game without Persona from one wasted daytime (4/23) to none; from 10/22 without goals the plan trades three links at RANK 5 or above for two more MAX (6 → 8 MAX, RANK ≥ 5 21 → 18). Goal profiles (`goalProfilesKeepTheGoalsAndStayBroad`: the 1/8 pair, the review's seven 10/22 sets and 12 seeded 10/22 profiles): every one keeps the promises (before: 必须 ①星 ②正义 ③命运 dominated, four profiles with 4–13 more wasted daytimes, five with a score below the 「尽量多 MAX」 plan's); over the set 421 links unlocked (before 428, 「尽量多 MAX」 414), rank sum 2,792 (2,771; 2,746), 274 at RANK 5 or above (294; 246), 236 wasted daytimes (280; 280). The 12 random triples of `PlanRealPackTest`: 233 unlocked (235; 228), rank sum 1,559 (1,555; 1,554), RANK ≥ 5 167 (177; 142), 132 wasted daytimes (136; 191). With the unlock promise (3.1.0 re-check) the same 22 profiles: 423 unlocked, rank sum 2,795, 274 at RANK 5 or above, 266 wasted daytimes (with 必须 女教皇 and every 羁绊剧情 track as a 23rd: 444, 2,930, 287, 290; 「尽量多 MAX」 435, 2,872, 257, 303); the random triples 238 unlocked, rank sum 1,568, RANK ≥ 5 163, 180 wasted daytimes: the links that stayed locked are open, paid with the daytimes their fees and stat gates take, still fewer than 「尽量多 MAX」 wastes. Not every profile is broader than its 10355be plan: 10/22 必须 女皇 (seed 7, profile 9) went from rank sum 122 and RANK ≥ 5 14 to 107 and 10, with 19 links unlocked (「尽量多 MAX」: 107, 8 and 18): the search finds a narrower plan under the new weights, although it would score the old one higher. `docs/PLAN_METRICS.md` lists every scenario with both styles' breadth columns.
