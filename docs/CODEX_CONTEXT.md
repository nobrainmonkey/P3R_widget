# P3R planner repository context

Reference for continuing the owner's existing project. Updated for the 3.6.1 calendar cold-start
fix and the 3.6.0 P3R interface, available-action calendars and earlier teammate characteristics.
This is an orientation snapshot; check newer code and owner decisions before implementing changes.

## Product and current baseline

- **P3R 规划** is a native, offline Android app and home-screen widget for the base game
  Persona 3 Reload. Episode Aigis/Astrea is outside its ruleset.
- The player enters visible game progress and chooses ordered Social Link goals. The app
  simulates and schedules the remaining calendar through January 31, then adapts when the
  player records a different result, changes goals, or returns later and syncs progress.
- Useful, consistent plans matter more than global optimality. An unmet goal is not proof
  that the goal is impossible. Forecasts and confirmed progress must remain separate.
- Current app source: **3.6.1**, version code **44**, application ID **`app.p3r.planner`**;
  debug builds add `.debug`. Java package/Android namespace remains `app.p3r.dayplanner`.
  Java 17, Android Gradle Plugin 8.7.3, Gradle 8.9, minimum API 26, compile/target API 35.
- The owner released 3.4.2 at `52e9b9d`; 3.5.0 builds on those changes. See
  [release signing](RELEASE_SIGNING.md) for the verified current public APK and CI evidence.
  Retain the 3.3.0 signing certificate for in-place updates.
- Public `v3.5.1` points to `4acf72109d4e8efb4b45910b49c397e8b2ef9b2b`. Full CI
  `39319649477` passed (1,294 tests, one skipped); publisher `39321609609` succeeded.
  The public APK and checksum were downloaded and compared with the signed local files.
- Public `v3.6.0` points to `73cab56c4d20f6c8d2a92e2f9b2c8c8db3a8f531`.
  Main CI `39501279106` and prebuilt publisher `39508093429` succeeded. Release ID
  `418942942` has the verified APK and checksum; public downloads byte-match local files.
  APK SHA-256 is `3cd3c4c6cb7b5c93b23c869f6687318526b80a53bbe2f65b77f64e9bc3824eb0`.
  GitHub's four signing secrets remain unset; retain the existing local/private backup key
  and use the documented verified-prebuilt path. Pushing main or a tag alone is not publication.
- Public latest `v3.6.1` points to `b82c9956693d404e98ef04d5ee78756f00ba0ddd`.
  Main CI `39692532769` and prebuilt publisher `39694028957` succeeded. Release ID
  `420154931` contains the 1,218,760-byte APK and 88-byte checksum; both public downloads
  byte-match the audited local files. APK SHA-256 is
  `fcfebfc84f7426607d3b0b442f76d35201ffd4d0342b88e7325bcd72a6b61d8b`.
  Local validation passed 504 UI tests, release lint and the signed build. Four startup
  regressions use Android's real immediate scroll-listener callback, which Robolectric's
  default shadow omits. `SchedulePage` now registers listeners after all its views exist,
  so reopening with saved grid preferences preserves progress and calendar choices.
  Rules remain `3.6.0` and scheduling policy remains 5; this patch does not force replanning.
  Retain the `apk-preview/v3.6.1` branch at `593873958660f11a5350071bf43b6d5d2c0ec2d4`:
  its verified provisional APK link was already sent to the owner.
- Player-facing language is natural Simplified Chinese using Reload terminology. Preserve
  the established navy/cyan P3R presentation, slanted panels, condensed numerals, day/night
  widget palettes and fade, readable answers, and action-specific result sheets.
- Owner preference (October 18): game tips should give information directly: answers,
  locations, steps, rewards, conditions and deadlines. Remove repeated disclaimers and
  internal modeling explanations. Keep provenance/uncertainty analysis in developer evidence;
  retain concrete conditions that affect play, such as receiving an SMS, accepting a request
  first or spending an evening. Missing Chinese text may be translated, with official SC
  wording preferred wherever available.
- Stat timing is a tradeoff, not a preference for later MAX dates. The owner explicitly
  clarified that early MAX is not inherently wrong. Compare actual exam/unlock benefits,
  future free gains, cash, competing activities and calendar slack. A later MAX date alone
  does not establish an improvement; current exam rewards/results are not fully modeled.
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
| [3.3.0 change notes](RELEASE_NOTES_3.3.0.md) | Period tips, dated group study, dorm characteristics, gardening preparation and widget sizes. |
| [3.4.0 change notes](RELEASE_NOTES_3.4.0.md) | Dated training needs, automatic summer classes, conditional invitations, story display and tips search. |
| [3.4.1 change notes](RELEASE_NOTES_3.4.1.md) | Goal-prefix coverage, joint prerequisites, shared school calendars, useful dating notes and manual deep search. |
| [3.5.0 change notes](RELEASE_NOTES_3.5.0.md) | Compendium, fusion basket, previous-day corrections, rescue floors and prerequisite candidates. |
| [3.5.1 change notes](RELEASE_NOTES_3.5.1.md) | Icon controls, acquisition floors, grouped social/Monad lookups, period scoping and jump to today. |
| [3.6.0 change notes](RELEASE_NOTES_3.6.0.md) | P3R interface overhaul, available actions, list/month-grid calendars and selected teammate trait timing. |
| [3.6.1 change notes](RELEASE_NOTES_3.6.1.md) | Reopening after choosing the grid or activity calendar, with saved progress and view choices preserved. |
| [3.6 UI contract](DESIGN_3.6_UI.md) | Current visual system, short player-facing copy, transitions and calendar interactions. |
| [3.1.0 specification](specs/SPEC_3.1.0.md) | Full replan, reminders, legacy removal, widget answers and breadth planning. |
| [Chinese glossary](GLOSSARY_zh.md) | Exact UI terms, sentence style and deadline wording. |
| [Rules report](../rules/build/REPORT.md) | Generated source decisions, confidence, coverage and modeling gaps. |
| [Plan metrics](PLAN_METRICS.md) | Recorded benchmark scenarios and tradeoffs between planning styles. |
| [Autumn nine-goal comparison](DEEP_SEARCH_AUTUMN_BENCHMARK_3.4.1.md) | Primary mid-save deep-search evidence: September/October female links plus Sun MUST, full-week records/deviations, all 40 outcomes and scoring tradeoffs. |
| [Mid-save deep-search comparison](DEEP_SEARCH_BENCHMARK_3.4.1.md) | Ordinary, deep and incremental results after onboarding mid-game, recording actions and skipping an action. |
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
| [compendium](../android/app/src/main/java/app/p3r/dayplanner/v3/compendium/) | `Compendium` loads the offline catalogue; `FusionCalculator` handles normal/same-arcana/fixed special fusion with three owned DLC packs; `CompendiumPrefs` stores favorites, registrations, DLC and the fusion basket separately from scheduling assumptions. |
| [store](../android/app/src/main/java/app/p3r/dayplanner/v3/store/) | `PlanDb`, `Ledger`, `Replayer`, `PackLayout`, `Backup`: SQLite records, replay, rule-update remapping and backup format. |
| [service](../android/app/src/main/java/app/p3r/dayplanner/v3/service/) | `PlannerApi`/`PlannerService`: shared boundary for app and widget. `CommandCheck` validates writes; `ReplanWorker` computes and validates plans in the background; `PeriodTips` collects the current block period's reminder catalogue independently of the plan. |
| [ui](../android/app/src/main/java/app/p3r/dayplanner/v3/ui/) | `HomeActivity` and six tab pages, including `TipsPage` and `CompendiumPage`; `StateActivity`/`StateDraft` for setup and sync; `ResultActivity`/`Outcomes`/`TowerPanel` for results; `FullReplanSheet`/`PlanDiff` for fresh planning. Native Android Views, largely built in Java. |
| [widget](../android/app/src/main/java/app/p3r/dayplanner/v3/widget/) | `PlanWidget`, `PlanView`, `PlanItems`, `CardRenderer`, `CardText`, `WidgetWorker`: RemoteViews, task browsing, result actions, sizing and background work. `PaletteHost`/`FadeGuard` handle palette transitions. |
| [rules builder](../rules/build/build_p3r_rules.py) | Python standard-library compiler for the shipped JSON asset and its generated report. |
| [tests](../android/app/src/test/java/app/p3r/dayplanner/v3/) | JVM engine/model tests and Robolectric service, SQLite, UI and widget tests. |

The main data flow is: checkpoint plus recorded events → replayed observed state → copied
state and profile → complete forecast plan → independent validation → revision-checked
installation → app and widget. Results return through the service into the event ledger.

3.6.0 adds a separate read-only availability flow: `engine/Availability` shares the compiled
calendar checks, `service/AvailableActions` supplies short Chinese entries, and `UiData.availability`
feeds 今天可做 and the 活动日历 source in 日程. These opportunities follow recorded progression
and dated opening conditions independently of the chosen plan, goals or remaining stat utility.
`ScheduleMonthGrid` supplies the month presentation; source tabs and list/grid controls stay distinct.
Today cash uses its own full-width row through seven digits. The current UI baseline is
`DESIGN_3.6_UI.md`; preserve its motion, slanted panels, royal-blue ocean and white primary actions.

3.5.0 adds the independent bottom 「图鉴」 tab as the sole main entry. `CompendiumPage`
has internal 图鉴 / 合成 / 合成篮 navigation; search, quiet scope/filter tools and independent
等级/阿尔卡那 sorting mode plus ascending/descending direction stay distinct from those tabs.
It keeps favorites, details and fusion navigation together, using filling sub-pages with their
own lists rather than nesting a list inside the ordinary sub-page ScrollView. The 194 summonable
Personas (173 base + 21 DLC), official SC/English names, skills, chart and recipes are in
`assets/p3r_compendium.json`; provenance and regeneration are in [PERSONA_DATA_SOURCES.md](PERSONA_DATA_SOURCES.md).
No portraits are shipped; Episode Aigis is excluded. DLC ownership changes fusion ladders, not
just list visibility. Favorites do not change the planner's assumed carried Arcana. These choices
live in SQLite meta and round-trip through the normal backup; old backups keep current choices.
The owner's continuous-fusion flow adds a persistent basket (multiple unique targets and selected
ingredient recipes) plus a separate registered/summonable-Persona checklist. The owner explicitly
clarified this means registered in the Compendium, not currently carried inventory. Chosen recipes
expand a dependency tree and show ALL leaf summon quantities with registration status and
prerequisite-first crafting steps on the same page; this does not change the validated fusion calculator. An explicit
「记为已合成」 registers the result and never removes registrations of materials. Explicit chosen
recipes remain selected even when their result is registered; collapsing a branch to a summon is
a separate choice. Registrations do not change the calendar's Persona bonus assumptions.
Browsing keeps one transient page above the existing workspace, supports direct
ingredient recipe lookup and 「接着合成」, and preserves search/filter/list state. Sheets and page
transitions cancel superseded animations so a previous dismissal cannot hide the next picker.

3.5.1 adds exact Shuffle Time acquisition ranges for 111 base-game Personas, from the upstream
floor/encounter/minor-arcana/Persona-card tables; existing fusion fields and formulas stay unchanged.
Persona details use aligned star and circle/check slanted controls; filters use icon-only funnels.
Tips group social links into expandable opening/current-RANK selectors and Monad fights into
their tower blocks and floor ranges. A displayed/search RANK 1 means the scene from 1 to 2;
stored dialogue scene IDs still mean the target rank, and explicit 「升到 RANK 2」 remains supported.
Permanent pickups and undated requests belong to their opening period, while actual deadline
windows can overlap periods. All entries remain available under 「全部」. The calendar's floating
arrow uses the game cursor, and keeps the previous-day correction entry point.

Scheduling policy is now **5**: older forecasts are recomputed without changing records.
NEW_DIVERSE reserves one unlock/prerequisite candidate within K (K≥2), including when an
ordinary unlock and a goal-serving prerequisite meeting coexist. Goal priority follows unmet
rank prerequisites recursively, stops when the required rank is met or parent established,
and does not make prerequisites implicit MAX goals. Rescue groups now carry all20 people and
their floors, shared by planned-visit reminders and period tips; IDs21–27 are cats, excluded.
Selected teammate traits also retain a rollout candidate (K≥3, preserving the gateway slot),
gain finite earlier-completion value, and add a trait-focused search tuning under the same
rescue/goal comparison. Track milestone dates in rollout-local arrays, never persisted progress.

## Calendar and planning mechanics

- Dates are game dates, never the device date. Day zero is **2009-04-01**;
  `slot = day * 2 + (evening ? 1 : 0)`. Weekdays use ISO Monday=1.
  The pack covers April 7 through January 31, 300 days. Plans include the January 31
  evening **locked finale** as their last row; January 31 daytime is the last free slot.
- `SimState` carries cash and its certainty, estimated hidden stat/affinity points,
  visible ranks, observed rank-up cues, unlock steps, rescue bits, tower visits/floor,
  job counts, episode progress, dorm project counts in `used[]` and player-reported rank holds. The same shape is used
  for observed and forecast states, but engine APIs copy their inputs.
- Productive actions are meetings, consuming unlock steps, activities/jobs, Tartarus,
  dating-site notes and Link Episodes. `FREE` and locked rows complete calendar coverage.
  Non-consuming unlock steps appear as **顺便** alongside the main action; they still
  obey money, availability, prerequisite and minimum-gap rules.
- The planner screens 16 base-policy tunings, adds goal-first tunings when relevant,
  and improves promising complete trajectories with fortified rollout. Default options
  allow four candidates and a 4-second soft budget. Automatic follow-up and full replan
  budgets are capped at 8 seconds. Persistent optional **规划设置 → 深度修改** gives
  automatic replans after progress records/settings changes 12 seconds, with no subsequent
  8-second follow-up. Manual **我的 → 从头重新安排 → 深度计算** uses 30 seconds,
  more action candidates and additional strategy variants. Both 12/30-second modes first
  run the ordinary 4-second search inside their shared total budget, admit its route, then
  use the remaining time for eight-candidate/all-tuning expanded search. Rescue and ordered
  goals retain that ordinary route's priority; secondary metrics follow the current style.
  Budget exhaustion keeps a complete incumbent tail,
  rather than truncating the calendar. The owner explicitly authorized this modestly
  longer computation on October 18; earlier 2.5/5/7.5-second figures are historical.
- Goals are compared in strict order: **必须**, then **想要**, each by the player's order.
  Target ranks can be below MAX. `Planner.Pool.compareGoals` and strict-priority reruns
  matter; the weighted objective alone is not the whole selection policy.
- **3.4.1 前置准备**：三维和解锁资金按 MUST/WANT 档位共同规划；同档看实际开放日期、
  缺口、剩余训练窗口和后续免费收益，不继承目标序号。多个 MUST 的属性需求同时计入，
  不等前一个社群 MAX 才准备下一个。直接见面、解锁行动和最终目标比较仍按目标顺序。
  白天训练必须由该门槛自身的晚间容量缺口支持。
- **3.4.1 上学日与结缘**：从真实 O 日历识别校内社群，共用的可见面上学日单独计算容量；
  校外社群先用其实际假期机会。结缘按实际省下的白天见面和有限累积好感计分，只有
  后续仍能使用好感的见面才安排。「尽量都体验」的其余社群保留自身剩余剧情的稀缺度，
  只有显式目标可使用共同上学日压力提高紧迫度；共享资源仍通过原有平方根竞争项计分。
- **已解锁目标的结缘策略**：至少两个目标、仍有目标未完成、未完成目标均已解锁且字条
  可用时，在同一预算内额外比较 `orderedNotes` 路线，按目标顺序分配晚间好感，保留
  后续目标可达的部分等级；默认属性准备仍按档位与日期共同安排。
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
- Since 3.3.0, 14 dated exam-study sessions compete with paid training using their actual
  free cost and Academics +4/+5 gain. `fixedDays` narrows availability under the existing
  story locks; rare dated gains are excluded from ordinary repeatable-training capacity
  estimates. The four whole-team +5 records retain conflict confidence because a separate
  guide claims +6; the model selects the lower sourced value.
- Eighteen dorm projects cover nine teammates, two projects each. The 社群 tab offers
  「不追求 / 解锁 / 强化」 per teammate, default NONE, including when loading an old profile.
  UNLOCK needs either project at three; UPGRADE needs both at three. H and J value finite
  progress/milestones only for the selected target; remaining dates affect urgency.
  Without an outstanding selected target, a dorm action needs useful stat or affinity gain to be
  planned; a zero-stat project cannot occupy an evening just to pursue an unselected trait.
  Achieving UNLOCK does not implicitly select UPGRADE. When choosing an action solely
  for UNLOCK, concentrate on the feasible project with the most existing progress rather
  than scattering completions across both. Existing actual project counts
  determine whether the target is already achieved. Dorm actions have their own rollout
  category. Rescue comparison and ordered Social Link goals retain priority. Selected
  characteristic targets compete with other links, training and jobs in the weighted
  objective, so their opportunity cost may reduce non-goal progress. Dorm forecasts award no
  episode rank or inventory. Since 3.4, the eight dorm projects for Yukari, Fuuka, Mitsuru
  and Aigis add a raw +1 lower bound within an established link's next-rank gap, without
  multipliers; ready/unopened/MAX links have no deficit. This continues after completion
  three. Its score uses a wanted link's actual meeting savings and finite progress value.
  New activity events store the stable link ID and applied delta in `OutcomeRecord` outside
  the original command, excluded from dedup fingerprints. Replay replaces the current pack's
  predicted affinity with this snapshot; legacy records without it add zero. Stat/cash/rank
  history is unchanged. See `dorm_affinity_evidence.json` and the engine/service/replay tests.
  All dorm activities consume an evening.
- Four gardening projects require `Profile.planDormGardening`, default false. The player's
  explicit opt-in promises crops will be planted first; crop/seed inventory and preparation
  costs are not modeled. It is a forecast preference, not a reason to erase past records.
- Normal replans favor keeping the next three days stable. **从头重新安排** removes this
  stability bias and uses a 3× budget capped at 8 seconds. Its manual **深度计算** option
  uses 30 seconds. Incremental replans also independently validate and replay the previous
  route's remaining suffix from the current observed state before admitting it as a quality
  baseline under the current goals and style promises. A higher raw score cannot override
  BREADTH coverage requirements (`chooseWithFloor` checks `keepsPromises`). A suitable
  legal suffix carries earlier deep-search gains forward; changed actual progress
  rejects an illegal suffix and replans from the new state. The route is a comparison
  baseline, not a fixed schedule; manual deep search gives its actions no stability bonus.
- `PlanValidator` has its own accumulator and transition checks instead of sharing
  `Kernel`/`Simulator` transitions. Keep that independence when changing mechanics.
  It checks contiguous coverage, legality, affordability, caps and forecast consistency.
- Since 3.4.0, stat urgency has no constant early reward. Future free activities reserve
  training capacity without granting points; activity usefulness depends on the forecast's
  actual date. Preserve catch-up when those dates are excluded or unavailable.
- `Rules.Day.automaticStatGain` applies at that day's daytime-to-evening transition.
  Only the six compulsory August 10–15 summer classes are currently sourced (+3 Academics
  each, +18 total). Kernel and the independent validator both apply them. A checkpoint
  entered after a class already includes the player's observed gains; never backfill it.
  Conditional classroom answers, awake/sleep choices, nurse visits and exam rank rewards
  remain reminders and must not be assumed to have happened.
- Surplus cash has no objective value. Terminal cash reward is capped at the explicit cash
  buffer; spare tower visits have finite utility under the visit cap and rescue policy.
  Manual legality does not imply that an activity must occupy an otherwise free slot.
  Valuable dating notes for any wanted link receive a short-term budget of at most three,
  bounded by real affinity gaps and remaining uses. A ready link can reserve the next rank's
  sourced gap when actual meeting/note opportunities remain. This is a purchase budget, not
  a protected unlock fee: spending it on notes must not cancel the note's own value.

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
- Holiday D cells are conditional SMS candidates, not ordinary meeting availability.
  Automatic plans, unlock calendars and remaining-meeting counts use O cells only.
  `invite:<id>` records a received invitation manually, never ranks up by itself and does
  not invent unknown affinity. The result may confirm readiness for the next ordinary
  meeting. Validators and future catch-up explicitly reject unconfirmed INVITE forecasts.
  Previously confirmed `meet:` records retain their explicit observed rank.
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
- The 3.3.0 提示 tab uses `PeriodTips` to collect all stored tips for the current Tartarus
  opening period, from this block's opening to before the next opening (final period to
  January 31, with a separate pre-opening period before the first block). It uses the
  observed game date and raw rules, retaining past dates within the period and explicit
  windows overlapping it from earlier periods. Ordinary old
  events without an end are not accumulated; `firstEligible` without a deadline remains
  conditional through the calendar end. No-deadline outing instructions and Request 75
  preparation persist across periods. It includes collected requests/preparation,
  all period-matching sourced classroom/exam answers (36/17 in the complete pack, no
  withheld class dates), shopping, dozing, the nurse trigger and rescue groups. It also
  reads manual/story unlock earliest dates and requirements/steps, all five episode
  tracks' 24 part calendars with full-window deadlines/how-to/available rewards,
  dating-note first availability, group study dates and a dorm-activity schedule/progress
  summary. Existing `Day.noteZh` facts (results, holidays, openings, white-flower story)
  and consecutive calendar days locked for the same reason and day/evening mask are also
  shown; range labels distinguish occupied dates from request deadlines. Exam ranges include
  days without manual answers, alongside summer classes, trips and full-moon operations.
  Calendar rows introduce no new assumed rewards. The catalogue is independent of profile
  opt-ins and chosen goals; an earliest opening does not imply prerequisites are met or an
  earlier episode was completed.
  A current plan may refine dynamic advice but is optional; a missing/stale plan never
  hides the raw period.
  Dates elapsed do not mean completed. Browsing the catalogue writes no progress and
  changes no cash, stats, plan identity or request-completion count.
- `TipsCatalogue` adds all 171 manual-link R2–R10 scenes and the whole-game reminder
  catalogue for read-only search. It is rebuilt from current rules/profile, including
  friendship/romance choices, and requires no plan/current rank. Short answers retain full
  ordered questions/conditions in details. `TipsPage` offers period/all scope and content
  filters in a single sheet, including the actual category tags used on cards. The main
  page retains period/all, search and one result counter; no duplicate summary/tutorial.
  Applying a filter preserves the selected scope; starting a query switches to all-game scope.
- The independent request catalogue contains all numbered base-game requests 1–101.
  Match bare/labelled numbers exactly and index titles/aliases. Requests 77–81 require total
  completed-request difficulty stars 25/60/100/150/185, with a three-day interval after the
  previous outing for 78–81. The old English table's 15/30/45/60/80 request counts were route
  approximations, not the actual gate. Request 53 also needs outing 78; catalogue evidence
  records the cross-source corrections and all source-row fingerprints.
- Combat references include every sourced guard-floor encounter, Monad groups, full-moon
  operations and final fights. Preserve structured enemy/phase rows and the nine raw element
  affinities. `v` doubles damage without a weakness/down flag; `*` blocks secondary effects,
  not elemental damage. Keep them distinct from true weakness, null, repel and drain. Cards
  show the roster/phase names and weaknesses; details use a nine-attribute matrix and short
  tactics. The owner explicitly rejected a standard affinity legend; only unusual `v/*/z`
  mechanics need footnotes. April 9 is an awakening story entry with no invented enemy data.
- Replayer still normalizes canonical progress to the next actionable slot. `StoryDisplay`
  overlays the skipped locked interval in Today, Schedule and widget with per-day reasons
  and static exam answers. A separate prefs cursor acknowledges display only; it must never
  create ledger actions, add points, replan or change state/profile revisions. Its source
  key distinguishes checkpoint/effective observed events and the skipped interval so undo/sync
  invalidates old acknowledgements; replan, profile-only changes and historical tower-money
  corrections preserve them.
- Forecast policy version is 5. Old cached forecasts are recomputed without changing
  confirmed records, even if rule pack and revisions happen to match.

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
- The 宿舍活动 setup/sync page records introduced projects at 0–3 completions and offers
  the gardening preparation toggle. Counts beyond three display as three; old unknown
  counts start at zero. Corrections change existing `used[]` without awarding past stats
  again. Forecast-prefilled counts carry 预计 labels until the player confirms the sync.
  Existing ledger completion/undo, checkpoint layout-ID remapping and backup schema carry
  these counts; no database migration or separate observed trait-awakening bit is added.
- The 社群 tab allows checking the two actual project counts when choosing each
  teammate's characteristic goal. Predicted counts are not actual history. Goal choices
  are the `Profile.dormTargets` JSON map keyed by stable teammate ID (for example
  `dorm_yukari`); absent entries mean `DormTarget.NONE`.
  Profile copying, backup/restore and plan hashing include this map, with no rule-pack
  or database-schema change. Changing the goal never erases completed projects.
- `ReplanWorker` coalesces work on the service executor, computes outside the lock,
  cancels outdated jobs, validates output, and installs only current state/profile/rules
  revisions. A budget-exhausted 4-second ordinary run can get one 2× follow-up. With
  persistent **深度修改**, progress/settings replans use 12 seconds and do not queue that
  8-second follow-up. The setting does not replace manual 8/30-second full-replan choices.
- Plan-cache compatibility also checks the scheduling policy. `Plan.CURRENT_POLICY_VERSION`
  is 5 and new plans default to `policyVersion=5`; legacy JSON without this field loads
  as version 0. `PlannerService.isCurrent` checks this version plus the existing revisions
  and rules identity. Upgrading recalculates older scheduling policies once without
  changing actual records or the rule pack. A saved policy-version-5 plan remains reusable
  on restart when its normal currentness checks pass.
- Full replan rereads saved state/profile, uses an 8-second default budget and no stability
  bias, and does not itself change progress or history. Its ticket/result state is distinct
  from ordinary planning; a current widget can keep recording during it. Manual deep mode
  uses 30 seconds and independently validates/replays a same-state current plan before
  comparing it under current goals and style promises. Ordinary and deep full replans share
  the cancellable worker.
- **3.4.1 对比验收**：比较必须覆盖中途存档、正常完成后的连续重排，以及没升级、改做
  别的行动、同步不同属性或现金等实际偏离；分别检查 4 秒、12 秒修改和 30 秒手动计算。
  记录目标最终等级、解锁数、总等级、白天占用、花费、耗时和独立校验结果；不能只用
  新游戏结果说明中途重排质量。深度模式须实际执行并纳入普通搜索路线，检验救援及
  按序目标不退步；MAX 数和 R5 覆盖仍按当前风格取舍。
- **2026-10-18 benchmark correction**: January fixtures primarily test tail recovery; use
  September/October progressed checkpoints with all eight female manual links plus Sun
  set to MUST, including unopened Empress/Aeon, for broader deep-search claims. Distinguish
  automatic 4/12-second updates from manual 8/30-second replans. Include a full week of
  records, legal alternative actions, uncertain failed rank-ups and cash corrections;
  preserve affinity, Persona choice, dorm history, story ranks and rescue/tower history.
  Compare ordered end ranks first and show MAX/R5/rank-sum/date tradeoffs separately.

## App and widget interaction

- Tabs: **今天 / 日程 / 社群 / 提示 / 图鉴 / 我的**. Setup and later sync preserve drafts and prefill
  values. Result sheets ask only what matters to the action: rank-up/no rank-up, training
  correction, tower floor/cash/rescue, or an alternative action.
- 日程保留前一天；已记录的结果通过 `OUTCOME_FIX` 更正原记录并重放后续记录，不重复记一次行动。
- Widget arrows browse individual tasks; **当前** returns to the pending task;
  **完成** is the right-hand primary action. Training/jobs can record directly with undo;
  meetings and tower visits open result sheets. Story/status cards have their own actions.
- Widget writes verify plan ID, state revision, slot and action, with deterministic command
  IDs. All broadcast work goes through `WidgetWorker`/`goAsync`; avoid database access,
  rule loading or RemoteViews construction on the main thread.
- The 提示 summary shows the block, floors, period dates, count and game progress. Its
  three sections are 当前要留意 / 接下来别错过 / 本期已过日期; every card opens its complete
  提示详情 sheet. Opening/deadline windows retain their original dates across periods;
  ongoing conditional tips are labelled 满足条件时 rather than claiming they can be done now.
  The new tab key is `tips`; existing keys such as `me` retain their meaning.
- Widgets target **5×2**, with **4×2** as the owner's minimum. Provider metadata targets
  5×2 cells at minWidth 320dp/minHeight 140dp and allows minimum resize to 250×140dp;
  launchers determine actual cell sizes. Current regression boundaries are 250×140dp
  minimum and 320×160, 360×180, 400×180dp nominal, at normal and 115% font scaling.
  Historic smaller-card matrices below describe earlier validation, not a new 3.3.0
  support promise. Android 13+ CJK fallback line spacing and whole-pixel title autosize
  are measured consistently with TextView; compact friendship notes keep full details.
- Answers should stay readable at supported widget sizes, including classroom answers
  alongside Social Link answers. `DialogueHint`, `CardText` and the glossary encode
  uncertainty/romance wording and compact fallbacks; avoid blindly abbreviating them.
- `OldGuideProgress` only reads `p3r_native_progress_v1` preferences and exports the old
  `p3r_hunterjia_checklist_v1` format. Those ticks are separate from planner progress.
  The old 2.0.0 package used a different signing identity; it is not an in-place upgrade target.

## Rule sources and boundaries

The shipped [rule asset](../android/app/src/main/assets/p3r/p3r_rules.json) is generated
from `rules/sources/` snapshots, their `SOURCES.json` manifest, and `rules/p3r/` evidence.
The current source pack uses schema `p3r.rules.v3`, pack version `3.6.0`, and is
458,931 bytes with SHA-256 `0cc64369290dc349d260c3e2b281af2b5c23b170c142e015e529b301f2c07078`.
App and pack version numbers are separate.
`RulesLoader` appends the first 12 SHA-256 hex digits of the actual JSON text.

Coverage: 22 Social Links (three story links), 54 activities (the previous 22, 14 study
sessions and 18 dorm projects), 10 Tartarus blocks,
five Link Episode tracks, two link-specific rescues, 120 static reminder items and
13 missing-person reminder groups, all 101 Elizabeth request guides and 97 combat guides.
The pack lists 139 source records. Concrete Reload routes restore five Aeon and three
Empress O dates that older generic weekday filters deleted; existing story locks and
source absences remain. See `late_link_availability_evidence.json`.

Change builder inputs/logic and regenerate both the asset and `rules/build/REPORT.md`;
the report is not a hand-edited document. Keep Reload-specific provenance and confidence
(`game_data`, `verified`, `single`, `estimate`, `conflict`). Mixed-edition sources only
corroborate a Reload source. A matching file hash establishes identity, not game accuracy.

Model limits to remember: conditional gains from class answers and exams are not
forecast; compulsory summer classes award +3 Academics on each of August 10–15.
Scheduled study/dorm activity gains are forecast; not every activity, item
requirement or rescue floor is modeled; 女皇's exam-first-place requirement is described
but not simulated; 审判 ranks 2–10 are dated January 30 as an estimate instead of derived
from floors; some story dates are conservatively locked; no mid-chain holds are encoded
in the pack, although player-reported holds are supported. Some Chinese answers and
multiplier/rounding details still lack direct in-game verification. See the generated
report's known gaps before treating a model assumption as a mechanic.

## Building and checking changes

Dependencies belong inside the repository's `.toolchain/`; outputs go to `dist/` and
`qa_out/`. Use [setup_toolchain.sh](../scripts/setup_toolchain.sh) to provision them when
needed. Release signing material lives in ignored `.signing/`; the release script requires it. Direct Gradle release assembly still has a development
debug-key fallback, but publishing must verify the configured certificate. See RELEASE_SIGNING.md.

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
| Dated study/dorm activities, characteristic scoring and prepared gardening | `DormActivityTest`, `DormActivityLoaderTest`, Python `test_dorm_activities.py` and `test_group_study_evidence.py` |
| Dorm progress correction, save compatibility and restoration | `DormProgressUiTest`, `DormProgressBackupRealPackTest` |
| Recorded progress, migrations, concurrency | `ReplayerRealPackTest`, `PackLayoutTest`, `ProfileChangeReplayTest`, `PlannerServiceRecordTest`, `PlannerServiceFollowUpTest`, `PlannerServiceFullReplanTest` |
| Player results and sync | `EndToEndRealPackTest`, `ResultFlowsRealPackTest`, `ResultRealServiceTest`, `StateWizardTest`, `FullReplanUiTest` |
| Widget behavior and rendering | `PlanWidgetBehaviourTest`, `PlanWidgetRealServiceTest`, `PlanWidgetFadeTest`, `AnswerRowRenderTest`, `ReminderRowRenderTest`, `PlanWidgetRealPackRenderTest` |
| Period tips, overlapping windows and navigation without a current plan | `PeriodTipsTest`, `TipsPageTest` |
| Dorm widget milestones and current size boundaries | `DormWidgetRenderTest` and the focused friendship/classroom cases in `AnswerRowRenderTest` |
| Reviewed dialogue, branch boundaries and compact answers | `ReviewedDialogueHintTest`, `DialogueHintsLoaderTest`, `ReviewedDialogueWidgetTest`, `ConditionalAnswerFitTest`, Python `test_dialogue_guidance.py` |

Real-pack quality tests use an explicit 8-second search budget on both local and CI hosts;
the default performance test uses the actual 4-second production budget. Plan metrics from tests are forecasts under the selected profile,
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
with the then-current 3.2.0 pack. Signature/alignment and embedded-rule checks passed.
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


3.2.0 publication uses a new formal RSA4096 signing identity after the owner confirmed that
the old Mac and its key were unavailable and explicitly accepted an incompatible update.
Certificate SHA-256: `7123294095e29e1458eb01a3f3331a025e7dd1a5d7877b25c56fe0d8291beb2f`.
The formal APK is 905,984 bytes, SHA-256
`9512cb85c6b78c30c8fb25067952172a27aebeed1242b632c92a7ebd6a9431d9`.
Old-signature 3.x installs must export a backup before uninstalling; current backup format is
compatible and preserves profile/checkpoints/events. The new private key and private backup
were kept outside Git. Original commit ccbe7e8 records a local
`dist/P3R-signing-backup-2026-10-16.zip`, intended for the owner's private retention;
there is no verified durable attachment or successful download record. Do not describe it
as already supplied to the owner. Never upload it with the APK. The signed-release workflow requires four
ANDROID_* secrets and checks this new identity; configure those from the private backup for
future tag-triggered builds. CI on main remains a test/debug build and does not itself publish.

## Historical 3.3.0 delivery boundary (2026-10-17)

The owner requested higher priority for group study and dorm activities, then a separate
period-wide 提示 tab and a 4×2 minimum / 5×2 nominal widget. After reviewing the opportunity
cost of automatic characteristic scoring, the owner requested explicit goals in the 社群
tab: nine teammates with NONE / UNLOCK / UPGRADE choices, default NONE. The implementation
adds dated activity rules, opt-in finite characteristic scoring, progress entry, compatible
record storage, preparation-gated gardening and a plan-independent period catalogue. It does not turn story
Persona awakenings into a planner-controlled mechanic. Source evidence is in
`rules/p3r/group_study_evidence.json` and
`rules/p3r/dorm_activities_evidence.json`. The rule pack identity is
`3.3.0|30aef29ce462` (SHA-256
`30aef29ce4629746b0895c83f81226cfcea3737d2e026c7c2a7e558415a7e79e`).

Previous-round validation (before the explicit characteristic-goal change): all 83 Python
tests and the generated-pack freshness check passed. An initial
full Android regression followed by fixes and focused reruns has 1,009 distinct latest
passing results, with no remaining failures or skips. The final feature batch passed 56
cases covering the period catalogue, all answers in the real period, navigation and
4×2/5×2 dorm/dialogue cards on API 26/34. The final calendar-slot and club-entry refinements
passed all 19 catalogue/UI cases again. Results are retained locally in
`qa_out/dorm_validation/final_validation.json`; this is a latest-result union, not a claim
that a single 1,009-case invocation ran. The newly added API 34 matrix for unsupported tiny
widgets was removed at the owner's request and excluded; older existing tests remain.
All 32 real-pack quality benchmarks and the updated MAX golden checks passed in that round.
The table in `PLAN_METRICS.md` has since been regenerated for explicit goals as described below.
Do not replace it with a focused run's partial generated table. Ordinary 360dp phone screenshots were inspected. No live-device
or gameplay verification is claimed.
Debug assembly and the exact embedded-rule comparison passed; APK metadata is 3.3.0/37,
application ID `app.p3r.planner.debug`. Release lint passed with zero errors and 176 warnings.

Explicit-goal validation: a 786-case non-widget regression plus focused fixes/reruns yields
789 distinct latest passing results, with no failures or skips. The initial four failures
were two reviewed golden identities/routes, an UNLOCK policy that split pure-trait effort
between projects (fixed without relaxing the three-use assertion), and the stale Lovers
RANK 5 compact-note assertion that also caused GitHub run 39015205410 to fail. The latter
now checks the actual friend/apology answers, while the detailed full sequence is retained.
Results are in `qa_out/trait_goals/validation.json`. At that stage no widget size matrices
were rerun. New policy-cache restart, goal JSON/copy/hash, backup/replay and six ordinary
360dp UI cases passed; actual record edits leave stats and unrelated state intact.

All 32 real-pack benchmark rows were regenerated with default NONE goals and the existing
quality bounds unchanged. Three synthetic MAX action fingerprints remain unchanged; only
their plan identities changed with the explicit-goal hash namespace. Three real-pack MAX
fingerprints/ids were reviewed and refreshed for the new default behavior. Ten additional
independently validated same-start comparisons record NONE, one teammate's unlock/upgrade,
and all-team upgrades with/without gardening, both with and without all Link Episode tracks.
These demonstrate opportunity costs rather than promise identical counts for other saves.
Keep the rules identity above unless actual source rules change. Final debug assembly and exact
embedded-rule checks passed (3.3.0/37, `app.p3r.planner.debug`); release lint has zero errors and
176 warnings. The six goal UI cases passed again after the formatted-string lint cleanup.

The owner requested commit/push, a green CI and a formal release. Run 39016487230 found
all four ANDROID_* secrets absent; the 3.2.0 private key and locally documented backup
could not be recovered. The owner then confirmed they decisively have no backup and
explicitly authorized using a new key if recovery fails. This supersedes the earlier
instruction to retain 3.2.0 permanently and pause publication without it. Do not treat
that historical instruction as a current release block or ask again about the lost Mac.

A new 3.3.0 RSA4096 key was generated on 2026-10-17. Its certificate SHA-256 is
`83d4395bcac9e7b33b20f3bd2b1327c856e56a6f0bd4816c52a42550a2b653ba`.
Current private files are `.signing/p3r-release-2026-10-17.p12`, its ignored properties,
`dist/P3R-signing-backup-2026-10-17.zip` and
`dist/P3R-signing-recovery-2026-10-17.json`. The release-signing agent uploaded the
ZIP and recovery JSON on the owner's private recovery page:
`https://chatgpt.com/space/page_bd2a4b411600819194ee6d5425c8e2b3`.
Owner/share settings were checked (owner is the user, no direct or inherited shares,
no Space/workspace). Both attachment references were inserted and read back; the recovery
JSON matches the local SHA-256
`ce7d637c0c96eab9d6812fe9d591c6b4624e0b2ca4f56b7d8f4cb4898cbbdc8b`,
and restoration was verified. ZIP SHA-256 is
`0621d8c40dd6d59296cdb968ae80054ee4396cb6559b7816aeefd99b88df998c`.
For future recovery, read the private recovery JSON and restore the key/config entirely
inside a functions isolate; never print key bytes, passwords or the JSON body. Neither
private file belongs in Git or a public Release. Verify the public certificate before
building, and retain this same 3.3.0 key for subsequent versions.

The new key cannot update 3.2.0 or older installations in place. Users must export a
backup outside the app, uninstall, install 3.3.0 and restore. Stable 3.2.0's tag, APK and
historical certificate/hash stay intact. The final 3.3.0 CI and public release succeeded;
the exact source, runs and verified artifact are recorded below.

CI follow-up: run 39016480054 passed 1,028 of 1,029 cases; its remaining failure was the
full answer-row render matrix. Align that matrix with the supported 250×140 minimum and
320×160 / 360×180 / 400×180 nominal sizes (plus taller 320×200 / 320×240 cards), rather
than optimizing unsupported 180dp-wide or 130dp-high cards. The supported matrix exposed
a real API 31 / 115% font failure at 250×140: Priestess RANK 8's friendship annotation
displaced the scene answers beside a classroom answer. Its compact note is now
「已走友情」; full choices, sequence, branch conditions and alternative history are unchanged.
This cosmetic source change explains the new pack identity above. Three reviewed MAX
action fingerprints are unchanged; their plan ids are refreshed for the new pack identity.
The deterministic scene regression now includes conditional Priestess RANK 8, API 31 and
the four supported sizes. Android CI preserves JUnit XML/HTML reports and prints full
failure messages, so a future render failure identifies its size and content directly.

Final local follow-up: 19 focused Android cases pass across the new scene, full supported
answer matrix, side-pick/golden renders, reviewed dialogue and refreshed MAX identities.
All 83 Python tests and the generated-pack freshness check pass. Debug assembly embeds the
exact new pack, with metadata still 3.3.0/37 and `app.p3r.planner.debug`. The official local
packaging script previously enforced the sole 3.2.0 signer and rejected a real debug-signed
APK before copying to `dist`. The authorized key change updates that check to the new
3.3.0 certificate; formal build and publication are being handled separately from debug CI.

CI run 39017962543 subsequently passed 1,029 of 1,030 cases, including all widget tests.
The remaining `BreadthObjectiveTest` failure used the production 2.5-second search budget
and exhausted it under CI load. Quality tests now use a 60-second budget so they measure
planning quality rather than host scheduling; the production budget remains unchanged.
All ten focused `BreadthObjectiveTest` quality cases now pass locally. Release lint has
zero errors and 176 warnings, and a signed release candidate was assembled and verified locally.
Checks covered the new certificate, alignment, non-debug package `app.p3r.planner`, version
code 37 and exact embedded pack (identity prefix `30aef29ce462`). The final APK was rebuilt
after commit to embed the final source identity. The 1,029/1,030 run remains a historical
failed run; the later full green CI and completed publication supersede its status.

Final delivery: source commit `f2be4042515540def723ac5f419c19c817faa284` passed the complete
Android CI run [39019148832](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39019148832)
(8 minutes 21 seconds). Do not infer an exact test count from the successful log.
The source Signed Android release run 39019718556 stopped because the four signing
secrets remain absent. The final package instead came from the locally signed, verified
release build, using the current 3.3.0 key, and was published by a prebuilt-APK workflow.
Its first publisher run 39019719742 created a draft but tag lookup returned 404; recovery
looked up the existing release by ID and reused it without overwriting assets. Final
publisher run [39019952354](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39019952354),
helper commit `437ec5c971b1d255d8ef14094f75aae0ee713a86`, succeeded.

Public release [v3.3.0](https://github.com/nobrainmonkey/P3R_widget/releases/tag/v3.3.0)
(ID 415691640) was published at `2026-10-17T09:08:14Z`, confirmed latest, not draft and
not prerelease. It has exactly two assets: `P3R-Planner-3.3.0.apk` (931,500 bytes) and
its `.sha256` file (88 bytes). Final APK SHA-256 is
`4c35c908d5f17598a023eed0dabf9f855b00e2079c9d83c4488f30a99389bb03`;
certificate is the new `83d439…653ba` identity and the embedded rules remain
`3.3.0|30aef29ce462`. Private signing material is absent from the public release.
Subsequent documentation-only commits do not require rebuilding this published APK.

## 3.4.0 delivery record (2026-10-18)

Source `b8d5f52110fa5b289e2714e1a71a3e6b8394ec5d` passed complete Android CI
[39118246698](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39118246698).
The official local release script passed all **1,160 tests in 116 suites**, with zero
failures, errors or skips, plus release lint and signed assembly. Python rules checks
passed **110 tests** and the generated pack freshness check. No live-device run was performed.

The owner authorized longer background computation: ordinary plans use a 4-second soft
search budget; follow-up and full replans are capped at 8 seconds. Real-pack quality tests
use 8 seconds and retain their goal, rescue, validator and complete-search assertions.
All 32 base metric scenarios were refreshed; results match the preceding complete-search
baseline apart from timings. The 10 trait scenarios retain their earlier 7.5-second capture.

[v3.4.0](https://github.com/nobrainmonkey/P3R_widget/releases/tag/v3.4.0), release ID
416381441, was published at `2026-10-18T10:25:20Z` and independently confirmed latest,
not draft and not prerelease. Publisher run
[39119147338](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39119147338)
succeeded from helper commit `9ada9fc1326a97d9bfb095d058c61c87494a183e`.
The tag resolves to the source above. Assets are `P3R-Planner-3.4.0.apk` (982,956 bytes)
and its 88-byte checksum file. APK SHA-256 is
`aa0342eb209fb2d2720f62b60f291f2e1f62be9350ff5c8b29608f6677deec26`.
The certificate remains `83d439…653ba`; the embedded rule bytes match source and pack
`3.4.0|06843d87d8ea`. Both public asset digests match the verified local files.
Version code is 38, package `app.p3r.planner`, non-debuggable. It can update 3.3.0 in place.
Signing secrets remain absent from Actions; the verified prebuilt publication path was
used again. No private signing material was committed or published. Later documentation
changes do not require rebuilding this APK.

## Current 3.4.1 delivery boundary (2026-10-18)

Source `51e0ae1ff0ed4d6e85f97281cd042f508330ab60` passed complete Android CI
[39157582456](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39157582456),
including the full unit-test suite and debug assembly. Local verification also passed
the 493-test core/UI batch, the final 42 focused tests, the final 23 real-pack tests,
release lint and signed assembly. These local batches overlap; do not sum their counts.
The committed mid-save benchmark generated 16 validated plans in eight comparisons:
six matched and two improved the current BREADTH objective, with no lower rescue or
ordered-goal result. See [the report](DEEP_SEARCH_BENCHMARK_3.4.1.md) for tradeoffs.

[v3.4.1](https://github.com/nobrainmonkey/P3R_widget/releases/tag/v3.4.1), release ID
416657125, was published at `2026-10-18T19:32:33Z` and independently confirmed latest,
not draft and not prerelease. Publisher run
[39159213883](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39159213883)
succeeded from helper commit `d8f9d4d84bbb71f417ffb675d5be7628111b6fde`.
The tag resolves to the source above. Assets are `P3R-Planner-3.4.1.apk` (989,324 bytes)
and its 88-byte checksum file. APK SHA-256 is
`446d240187cb889256f4db3e18f0efa55a408dc6a404988a9f9c62685e22c524`.
Both public asset digests match the verified local files. Version code is 39, package
`app.p3r.planner`, non-debuggable, zipaligned and signed with the unchanged
`83d439…653ba` certificate; it can update 3.3.0/3.4.0 in place. Embedded rule bytes
match source and remain `3.4.0|06843d87d8ea`; scheduling policy is 3.
Signing secrets remain absent from Actions, so the verified prebuilt path was used.
No private signing material was committed or published. Later documentation-only
commits do not require rebuilding this APK.

### Autumn benchmark follow-up (2026-10-18)

The [new report](DEEP_SEARCH_AUTUMN_BENCHMARK_3.4.1.md) and
[captured metrics](benchmarks/autumn-deep-3.4.1.json) cover five constructed September/
October checkpoints, 40 plans and 20 comparisons, without changing the released engine.
Every plan completes all nine MUST goals. Fifteen deep candidates score higher and five
tie; no pair adds a completed MUST target over ordinary search. The earlier-goal bonus
accounts for many gains: excluding only that bonus from the same scored routes leaves
five higher, five equal and ten lower results. Do not describe 15/20 as uniformly better
Social Link progress. All plans are complete and independent validation errors, missed
rescues and unmet tower visits are zero; 86 advisory FREE-slot warnings remain recorded.

Ordinary continuation can redistribute non-goal progress under the BREADTH acceptance
rules even when the old deep suffix is legal: the balanced September expected-week case
changes 19 MAX/R5+21/minimum2 into 18 MAX/R5+22/minimum5, retaining all nine MUST MAX.
The 12-second update keeps the former route. This ordinary change is a style tradeoff,
not budget exhaustion, and is not evidence that all earlier deep secondary gains persist.
The benchmark launcher now defaults to autumn; `--legacy` reproduces the first16 plans.
