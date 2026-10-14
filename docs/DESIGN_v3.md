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
  MainActivity, PlannerStore, PlannerWidget, WidgetUpgradeReceiver   legacy fixed guide (kept, restyled labels only)
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
3. **Budget**: soft time budget (default 4 s on device, configurable in tests). When exhausted, follow
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
  次数与上限, 现金余量), 记录历史 (撤销/修改), 备份与恢复, 规划说明 (assumptions & sources), 固定攻略 (legacy
  guide), 添加桌面小组件.
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
- Legacy guide: all legacy tests unchanged; isolation check (planner never touches guide prefs).

## 9. Identity, signing, release
`applicationId` changes to **`app.p3r.planner`** (label "P3R 规划"). Reason: the installed 2.0.0 is
signed with a debug certificate that is not available here; any APK built here cannot update it.
A side-by-side package avoids telling the player to uninstall. Legacy guide progress can be moved via
the guide's export/import; planner state is re-entered once with the new wizard. Release signing key
lives in `.signing/` (git-ignored, never published). Version 3.0.0 (code 30). APK published as a
GitHub release asset.

## 10. Settled decisions (after the critic pass)

1. **Persona bonus** (superseded by Profile.personaMode NONE/GOALS/ALL — players do not carry every matching Persona): a three-way question in onboarding and 规划设置 ("见面时通常带着同阿尔卡那的人格面具吗？" → 基本都带 / 只给目标社群带 / 不带·不一定), default 不带. When on, ×1.51 applies to ordinary meetings and rank-up answers. **Charm bonus**
   (×1.51 at 魅力 Lv6, game data) applies when the forecast reaches Lv6; toggle default on, listed
   in 规划说明. The legacy guide maxes all 22 links only with matching Personas (146 items) — use it
   as the benchmark: with personaBonus=true from a new game the planner should MAX ≥ 18 links.
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
- **Legacy guide.** Text fields of `assets/data.json`, `display_metadata.json` and the copy embedded in `index.html` follow the glossary (「10月13日」, 「3,980 日元」, 「Lv.2」 for stats and 「→RANK2」 for links, 羁绊剧情, no English glosses after Chinese answers; English-class options such as 「Lives」 stay); ids, kinds, dates, keys, order and list lengths are pinned by `LegacyGuideChineseTest` against the 2.x structure. The guide widget keeps goods, answers and counts (「（2）」「（1个）」) whole; `LegacyGuideWordsRenderTest` derives the quoted names and shopping goods from the data itself instead of relying on the word list alone.
- **Wording.** 主角 (not 主人公) in the legacy guide data too; dates in text are 「10月23日 周五」 (the date plate keeps 「10/23」); 结缘网站的字条 always in full; legacy widget and app text are shaped so names and short tags never break across lines (only （…） notes of up to 5 characters are glued, so longer ones can wrap at large font sizes).
- **Aeon meeting days.** The aqiu calendar has Aigis after school Mon–Sat from 1/8; Game8, PC Invasion, Prima and GamesHorizon (Reload) list Mon/Wed/Fri/Sat. By the strictest-claim rule the pack keeps Mon/Wed/Fri/Sat (conflict `avail.aeon.tuethu` in `rules/build/REPORT.md`), found by the independent source check (`docs/analysis/audit/wiki_sources.json`).
- **Measured** (real pack, desktop JVM, default options; full table in `docs/PLAN_METRICS.md`): new game with the arcana Persona for every meeting 22/22 MAX; no Persona 21/22 (恋爱 RANK 9; also 21/22 with all five Link Episode tracks); 皇帝 MUST 21/22 with 皇帝 MAX 6月29日; MUST 皇帝 + WANT 恋爱、女皇 with Persona for goals only 21/22, all three goals MAX; every scenario plans to 1月31日 and passes `PlanValidator`.

## 15. 3.0.2: independent sign-off reviews

- **Sources.** A read-only agent re-verified the Reload-only source policy (`docs/analysis/audit/wiki_sources.json`): all 33 pinned hashes and 40 cited local files match, no P3/FES/P3P data feeds a shipped rule, and 18 facts were checked against the pinned sources and public Reload guides; the one disagreement (Aeon weekdays) was resolved by the strictest-claim rule (§14).
- **Looks.** Two independent design reviews (`design_review_1.json`, `design_review_2.json`) looked at the README gallery, emulator captures and the Robolectric renders. Round-1 nits were fixed: every app text view goes through `ZhTextView` (keep-words-whole shaping, no lone last character, no line ending in 「·」); widget status cards have one live action; chips, names and locations are never cut with 「…」 (lower-priority parts are dropped first); large cards centre their text; pre-S widgets have round corners; the legacy guide widget breaks after clause punctuation and keeps 「<arcana>面具」 whole.
- **Fonts.** The bundled Barlow Condensed files are the upstream ones, pinned by SHA-256 in `PlanWidgetRenderTest`. Adding a 「→」 glyph to the upright files broke the italic numerals on a device (Android takes a family's coverage from its first font), so the system arrow is used as before.

## 16. 3.1.0: 从头重新安排

_Placeholder: WP-B writes this section (spec `docs/specs/SPEC_3.1.0.md` §1.1)._

## 17. 3.1.0: 当日提醒

### 17.1 Data and engine

_Placeholder: WP-C writes this part (spec §1.2.1–§1.2.5; the `Plan.reminders` contract is spec §2)._

### 17.2 UI and widget

_Placeholder: WP-D writes this part (spec §1.2.7)._

## 18. 3.1.0: 小组件对话选项

_Placeholder: WP-D writes this section (spec §1.4)._

## 19. 3.1.0: 逐日攻略移除

_Placeholder: WP-A writes this section (spec §1.3)._

## 20. 3.1.0: 尽量都体验

_Placeholder: WP-E writes this section (spec §8)._
