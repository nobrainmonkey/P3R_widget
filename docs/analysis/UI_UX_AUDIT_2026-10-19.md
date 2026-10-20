# UI/UX audit, 2026-10-19 (P3R 规划 3.6.0)

- **Date:** 2026-10-19. Status checked against the code at commit 3f64d68 (version 3.6.0).
- **Scope:** every app screen: the app shell and 今天, 日程 with the day sheet, 记录历史 and
  从头重新安排, 社群, 提示, 全书, 我的 with its sub-pages, the state wizard (开始设置 / 同步现状 /
  修改进度) and the quick result sheet. The plan text that the engine and service hand to these
  screens was audited too.
- **Out of scope:** the home-screen widget (the owner is happy with it; `widget/`, its layouts,
  drawables, colours and strings are untouched), the fusion basket logic
  (`compendium/FusionBasket.java`) and the fusion calculator, planning behaviour and storage.
- **Method:** ten screen-group audits ran in parallel: today-shell, schedule-history, links,
  tips, compendium, me-settings, state-wizard, result-sheets, service-copy and visual-kit. Each
  one read the code and the Robolectric renders and wrote, per screen, its purpose, what it shows
  now and a proposal, then a findings list with severity, a PRESERVE list (behaviour that must
  not change), the tests that pin today's strings, and the text it shares with other screens or
  the widget. Together they made 473 findings. The design contract built from them is
  [DESIGN_3.6_UI.md](../DESIGN_3.6_UI.md); each group was then rebuilt against it.
- **Status words below:** *Done* (in 3.6.0, checked in code), *Partly*, *Kept* (left as it was on
  purpose), *Not taken* (a proposal the design did not adopt), *Deferred* (still open, see §5).

## 1. What the owner complained about

1. The UI does not look or move like Persona 3 Reload. It reads as a generic dark settings app:
   robotic.
2. Too much AI voice: self-deprecation (「没找到更好的」「没能」), hedging (「暂时」「可能」「预计」 on
   every line), apologies and explanations of how the planner works.
3. The logic is not smooth: dead ends, extra taps, flows that walk every page to change one value.
4. Clutter: a box around every row, captions under every control, counters nobody acts on, the
   same fact said two or three times.
5. Over-explaining to a player who is not technical (and not slow): teaching obvious controls,
   spelling out navigation paths, legends for colours.
6. Language: Simplified Chinese with ATLUS's official terms. English Persona names only as a
   secondary line so guides can be searched.

## 2. Cross-cutting findings

### 2.1 Voice and copy

Every audit found the same five patterns: planner self-explanation, hedges and apologies,
instructions for self-evident controls, status that changes nothing, and error text in a
system-dialog register. All are now rules in DESIGN_3.6_UI.md §2, and GlossarySourceTest fails on
apologies, some hedges, algorithm talk and the retired names 图鉴 / 合成篮.

| Where | Before | After (3.6.0) |
|---|---|---|
| 社群 tile | 「这次的安排里没排到 MAX，先保证了恋爱」 | 「时间先给了恋爱」; the 社群详情 sheet adds 「想先冲它，就排到恋爱前面」 + 「调整顺序 ›」 |
| 社群 tab | 「点卡片右上角的标签切换：未选 → 想要 → 必须 → 未选。目标按下面的顺序优先安排。」 | removed; only 「想冲 MAX 的社群，点卡片右上角设成目标」 while there are no goals |
| 今天 | 「已重新安排：后面的安排没变」, kept on screen | nothing; a changed week is said once: 「已重新安排：这周 3 处有变」 |
| 今天 welcome | 「告诉我游戏里现在的进度…，我会把…都排好。」 + 「大约需要 2 分钟…」 | 「填好游戏进度和想升的社群，每天做什么就排好了。」 |
| Day sheet | sections 「为什么这样安排」 and 「安排依据」 (「普通见面预计好感 +10（约 ♪♪）。」…) | removed; only an urgent reason, in gold |
| 从头重新安排 | 「普通重排最多 8 秒；深度计算最多 30 秒。」, 「重新算过了，没找到更好的，现在的安排不变」 | 「最多半分钟，期间可以照常记录。」, 「重新安排好了，和现在的一样」 |
| Load failure | 「暂时读不到记录」 + 「点「重试」再试一次，还不行就重新打开应用。」 | 「记录没读出来」 + 「重试一下？不行就重开应用。」 [重试] |
| Saves | 「没能保存，请再试一次。」 / 「安排刚刚更新了，请再确认一次。」 | 「没保存上，再试一次？」 / 「安排刚更新过，再看一眼？」 |
| Result sheet | 「这个时段没能照安排做？」 + 「选一个原因，这个时段就算过去了，后面会重新安排。」 | 「怎么没做成？」 |
| Result sheet | 「完成了，和预计一样」, 「剧情原因暂时升不了」 | 「做完了」, 「剧情卡住了，升不了」 |
| Wizard | 「刚才有新的记录（比如在桌面小组件上点了完成），已经合并进来，你填的内容都还在，请再确认一次。」 | 「刚有新记录，已经合进来了，再看一眼？」 |
| 规划设置 | 「深度修改 / 每次修改后最多计算 12 秒」 | 「仔细重新安排 / 每次更新多等十来秒」, folded under 「更多选项」 |
| 队友特性 | 「宿舍活动占用晚上；救援和社群目标优先。未选特性目标，仍可能为能力值安排活动。」 | 「同一种活动做 3 次解锁特性，两种各 3 次强化特性」 |
| Backup | 「恢复失败：备份内容无法识别。」 | 「这不像是 P3R 规划的备份，换一份试试？」 |

### 2.2 Visual system

- **Boxes everywhere.** Every row, option, setting and skill was its own glass card with a
  repeated cyan corner tick (46 `Ui.card` and 11 `Ui.pressableCard` call sites), so screens read
  as stacks of equal boxes. *Done:* rows sit on the water divided by fading rules, plates lost
  the repeated tick, one hero per screen. *Partly:* the proposed shared helpers (`Ui.plate`,
  `Ui.listRow`, a white `Ui.selectionBand` for the chosen item) were not added; screens build
  their own borderless rows (`SystemList` for 我的 and 规划设置).
- **No page identity.** Only 全书 used `Ui.pageHeader`; the other tabs had a plain 28sp title
  followed by status sentences. *Done:* every tab but 今天 has the header with a Latin kicker
  (CALENDAR, SOCIAL LINK, GUIDE, COMPENDIUM, SYSTEM) and a large watermark cropped by the screen
  edge; the wizard pages have the same kicker treatment.
- **Outline-first controls.** Secondary buttons, unselected chips and about 40 hand-made strokes
  drew wireframe boxes; quiet actions looked like web links. *Partly:* unselected chips lost their
  outline, quiet actions are bold and sink when pressed; secondary buttons keep their cyan outline.
- **Busy water.** The same shafts and caustics behind every page. *Done:* `OceanDrawable.calm`
  behind every page but 今天.
- **Glyphs as icons.** ★☆✓○□ text glyphs, Material-style outlined icons. *Done:* all `ui_ic_*`
  redrawn as solid angular glyphs, tab icons leaning forward; 全书 toggles use vectors with labels.
  *Deferred:* 「完成 ✓」 keeps its text tick.
- **Type.** Two CJK weights and ad-hoc sizes from 10sp to 36sp. *Done:* display titles in the
  heaviest upright weight, rehauled screens keep tags at 13sp. *Deferred:* type tokens, and
  NumeralSpan still uses the italic display face for every digit in small text.
- **Small things.** The WEEKDAY watermark printed over the protagonist and a new moon that read as
  an empty radio button: *Done* (moon always reads as a moon; the widget's S.E.E.S. mark copied to
  `ui_logo_sees`). Sheets were cyan-outlined boxes holding boxes: *Done* (bright top edge only).
  Faint ink below WCAG AA on the bright water: *Deferred*.

### 2.3 Motion

- Before: `Motion.cascade` and `swapIn` ran only on 今天; toggles, chips and segments snapped; ten
  different durations; `RankUpBurst` existed but was never called; `Ui.animationsEnabled()`
  ignored the system's remove-animations setting.
- *Done:* the tab slab slides and content enters from the travel direction; cascades on first
  show of 社群 sub-tabs, 提示, 全书, wizard option lists and 更多选项; the fusion RESULT swaps in;
  RankPad keys flash and the header slides; 目标顺序 rows slide; ToggleRow knob, SegmentedControl
  slab and CheckRow tick animate; sheets drag down to close; a recorded rank-up plays
  `RankUpBurst`; `Ui.animationsEnabled()` also checks `ValueAnimator.areAnimatorsEnabled()`.
- *Deferred:* shared duration tokens and page/sheet helpers, a press animator for every pressable
  view, animated caustics and bubbles, numbers counting up after a record.

### 2.4 Information architecture

- **Tab bar.** The today-shell audit proposed five tabs, with 提示 as a segment of 日程 linked from
  今天别错过. *Not taken:* DESIGN_3.6_UI.md §5 keeps the six tabs 今天 / 日程 / 社群 / 提示 / 全书 /
  我的 (only 图鉴 → 全书) with the same tab keys and widget deep links.
- **Main content buried.** 社群's 22 cards started two screens down under 9 teammate cards and a
  19-row goal list. *Done:* sub-tabs 社群 / 羁绊剧情 / 队友特性; goal strip + 目标顺序 sheet.
- **Ambiguous twins.** 同步现状 and 修改进度 were two equal rows. *Done:* one progress hero on 我的
  with [同步现状] primary and [修改进度] secondary.
- **Walk every page.** 修改进度 started at the date and walked every page; 同步现状 「是」 still
  walked eight pre-filled pages. *Done:* the 「修改哪一项？」 hub, and 基本照着玩了 → 日期 → review.
- **Hidden or modal filters.** 提示 hid its kinds behind a funnel sheet with 应用; 全书 spread
  scope, filter, count and caption over two rows. *Done:* one-tap strips and removable chips.
- **Jumps that lose context.** 加入合成篮 closed every page and opened the basket; back inside the
  result sheet closed the whole sheet. *Done:* stay on the page with 「已加入合体篮」 · 去看看; a panel
  stack where back pops one level.
- **Settings in the wrong place.** The gardening switch lived only in the 同步现状 wizard; 平安夜
  was an expanded card all game long and asked in October; 深度修改 sat second on 规划设置.
  *Done:* 种菜 in the 队友特性 sheet; 平安夜 is a row + sheet, and a wizard page from 12/20; 仔细重新安排
  under 更多选项. 「其余社群也尽量提升」 was on three screens: now one switch in 规划设置 → 其他设置.
- **Records.** 记录历史 was only under 我的 and the result sheet told the player to go there.
  *Done:* a 记录历史 button in the 日程 header and [看记录历史] on the result sheet.

### 2.5 Terminology

| Was | Now | Where |
|---|---|---|
| 图鉴, 图鉴与合成 | 全书, 人格面具全书 | tab, header, 关于, backup text |
| 合成, 特殊合成, 合成篮 | 合体, 特殊合体, 合体篮 | 全书 |
| 火炎 / 光 / 暗 / 回复 (asset also 光明 / 黑暗) | 火焰 / 神圣 / 暗黑 / 恢复, one source (asset `elementNamesZh`) | 全书, 提示 combat |
| 可召唤 / 召唤 / 登录情况 | 已登录 / 未登录 | 全书, 合体篮 |
| 「太阳社群MAX」「完成伊丽莎白委托#77」 | 「太阳社群升到 MAX」「完成伊莉莎白的第 77 号委托」 (composed from the structured unlock) | 全书 合体条件 |
| 「Ｐ５Ｒ　人格面具组合１」 | 「P5R 人格面具组合 1」 | 全书 DLC |
| 「属性」 for 力 魔 耐 速 运 | 「能力值」 | Persona detail |
| 「2–4、6–10层」, 「5层守门：无情冰鸦」 | 「2–4、6–10F」, 「无情冰鸦」 on a 「5F」 plate | 全书, 提示 |
| 「RANK 9 → 10」, the widget's 「答：」 | 「RANK 9 → MAX」, 「回答：」 | 提示 |
| 永劫 「XXI」 | 「XX」 (the pack's card numeral) | 社群, RankPad, 提示 |
| 「不追求」, 「初始设置」 | 「未选」, 「开始设置」 | 队友特性 |
| 「解锁进度」, 「分步前置解锁」, 「前置条件」 | 「解锁前的准备」, 「解锁条件」 | wizard, 提示 |
| 「深度计算」 / 「普通重排」 | 「从头重新安排」, 「仔细重新安排」 | 我的, 规划设置 |
| 「地点没开」 / 「我不想做」 / 「逐项核对」 | 「没开门」 / 「不想做」 / 「一项项对」 | result sheet |

English names are a secondary line wherever a Persona is named in 全书 (list, pickers, recipes,
basket) and in the 羁绊剧情 rewards (「看完可以合体「苏尔特尔」Surt」); bosses carry their English
name in 提示. No official Chinese term was invented: where the data has none, the English stays
(§5).

### 2.6 Text shared with the widget

Step titles, subtitles, why lines, hints, reminder lines, lock reasons and
`StoryDisplay.CONTINUE_LABEL` come from the engine, service and rule pack, and the widget shows
them too. The design's rule (§7) was to compact or filter them in `ui/`: `Dialogue.shown` hides
the generic 「选能让对方高兴的回答（♪ 越多越好）」, `TodayPage.urgentReason` keeps only urgent why lines,
`SlotRows` shows 「可能有剧情（先空出）」 as 「先空出来」, `ReminderViews` drops 「，不会消耗时间」, the day
sheet rewrites the 12/24 note. The later service-copy pass (commit 50abeee) then rewrote
widget-shared engine text where it was self-deprecating or robotic, keeping the formats the widget
parses; no widget code or widget test changed in the rehaul. `CONTINUE_LABEL`, the `DialogueHint`
constants and the widget-shown plan errors were left alone. In the rule pack only the dialogue
notes that leaked Japanese and point values were rewritten (by the build script); its reminder and
day-note text is unchanged. What is still only filtered is listed in §5.

## 3. Findings by screen group

Most important first; severity from the audit in brackets.

### 3.1 App shell and 今天 (39 findings)

- [high] A status line 「已重新安排：后面的安排没变」 stayed after every 完成. *Done:* removed; a
  change to this week is said once in the message bar.
- [high] Three hedges on one rank-up (「≈ RANK 5 → 6」, a 「预计」 badge,
  「「想要」目标，预计这次见面能升到 RANK 6」). *Done:* 「RANK 5 → 6」 + one 「预计」 tag; why lines only
  when urgent, as a gold 「!」 line. *Partly:* urgency is still found by keywords in the why text.
- [high] Planner reasons under every hero (「这个时段没有更划算的事…」, 「手动选择打工…」). *Done* in the
  app, and since 50abeee also at the source.
- [high] 「怎么做 · 3 步 · 点卡片查看」 and 「下一步 · 放学后」 repeated the card and the date plate.
  *Done:* removed; 「详情 ›」 at the top right.
- [high/bug] With no plan the hero spun 「正在根据最新记录重新安排…」 next to an error card. *Done:*
  「安排没排出来」 [重试], or 「正在安排到1月31日…」 while one runs.
- [high] Five to eight same-weight outlined boxes. *Done:* borderless resource band, 接下来 as one
  list with day plates (rest of today and tomorrow, 「全天 · 满月作战」, 「全部日程 ›」), 今天别错过 with a
  gold tag, a widget-like hero.
- [high] Six crowded tabs; proposal: five. *Not taken* (§2.4).
- [medium] Welcome in the AI's first person with a 2-minute hint and three cards; return banner
  said 「上次记录」 twice; full-replan caption and 「先不算了」. *Done* (DESIGN §6.1).
- [medium] The resource card did not open anything. *Done:* the band opens 修改进度. *Deferred:* a
  tap on one cell opening its own page.
- [medium] One message-bar style for success and error. *Done:* RECORD, RANK UP and red ERROR.
- [low] The message bar jumps to the top on 今天 when it would cover the hero. *Deferred.*

### 3.2 日程, day sheet, 记录历史, 从头重新安排 (54 findings)

- [high] Day sheet sections 「为什么这样安排」 and 「安排依据」. *Done:* removed with every instructional
  caption; the sheet mirrors the 今天 hero, with its [做不了…] [完成].
- [high] A legend 「带「≈」的升级是估计…」 and the stats line 「排到 1月31日 · 共 179 件事 · …」. *Done:*
  removed; the list ends with 「1月31日 · 日程结束」.
- [high/bug] A past slot without a record showed a tick, 「暂无记录」 and 「查看这天的记录」, then a dead
  end. *Done:* 「—」, not tappable.
- [high] 「点按查看或修改」 on every recorded line; one bordered card per day. *Done:* timeline with
  day numerals, weekday plates (red on rest days), moons, today as the hero slab, yesterday dimmed.
- [high] Future slots: a run-on caption and a loud primary 「已经玩到这里」. *Done:* one secondary.
- [high] 从头重新安排 asked a player to choose 「深度计算」 or 「从头重新安排」 by seconds. *Done:* one
  [重新安排] that runs the long search, [停下] while it runs, plain results.
- [medium] 记录历史: a recompute caption, undo journal rows next to the undone rows, a caption on
  every row before 同步现状, no retry, no way past 80 rows. *Done:* grouped by day, one
  「10月18日 · 同步现状」 divider, journal rows hidden, 「记录没读出来」 [重试], 「再看更早的」.
- [medium/bug] A rejected tower correction closed the sheet and lost the edits; correcting a
  skipped tower visit was titled 「自由活动」. *Done.*
- [medium] A replan result 「这周有 3 处变动」 could not be inspected. *Deferred:* no 「看看」 jump, no
  marks on changed slots.
- [low] Month chips in two rows, strikethrough on story days. *Done:* 「‹ 10月 ›」 one month at a
  time with swipe, 48dp cells, 「，上次记录」 announced. Sticky month header: *Deferred.*

### 3.3 社群 (44 findings)

- [high/bug] A link the plan raises but that is not a goal read
  「这次的安排里排到 RANK 7，没排到 MAX」, a target the player never set. *Done:* no footer for non-goals.
- [high] Hedged failure lines on most cards. *Done:* one footer at most; the reason and its fix
  in the 社群详情 sheet.
- [high] The 22 cards started about two screens down. *Done:* sub-tabs.
- [high] Each tier tap saved and replanned, passed through 必须 on the way to 未选, and moved the
  card under the finger. *Done:* one save 800ms after the last tap; tiles update in place; the
  goal list became a one-line strip and a sheet.
- [high] The tier instruction paragraph, 「安排不会要求你走恋爱路线」 on six cards, 22 identical
  outlined boxes, 22 × 「安排排好后显示」 before setup. *Done:* tarot tiles (DESIGN §6.3).
- [high] 队友特性: two paragraphs of planner rules, 4–6 lines on each of 9 cards, a gardening dead
  end through 同步现状, 「这里不会替你创建游戏进度」. *Done:* 3×3 tiles, one rule line, 种菜 inline.
- [medium] After a tap the old forecast stayed with no sign of a replan. *Done:* stripe, 40% fade.
- [medium] A changed record threw away the 队友特性 sheet with 「进度或目标已变化，请重新打开后再保存。」.
  *Done:* applied onto the latest data; only a moved count reopens the sheet.
- [medium] 「其余社群也尽量提升」 belongs with the planning options. *Done:* it left the tab and is a
  switch in 规划设置 → 其他设置 (it had briefly lost every home during the merge; fixed in review).
- [low] RankPad: a hint teaching auto-advance, a cryptic 「?」, an instant jump. *Done.*

### 3.4 提示 (64 findings)

- [high/flow] Without a game date the page was empty with no button, though the lookup needs no
  date. *Done:* opens in 全部; 本期 asks 「现在玩到游戏里的哪一天了？」 [设置日期].
- [high/flow] Kinds behind a filter sheet with 重置 / 应用 and two overlapping taxonomies. *Done:*
  one-tap strip + 更多.
- [high/flow] The next rank's answer took about six taps; the recorded rank was never used.
  *Done:* 「下一次 RANK 3 → 4」 with the answer under every link.
- [high] 29 request cards plus batch notices, 7 identical 上课犯困 cards; 全部 was a 460-entry wall;
  search results unsorted. *Done:* grouped rows, a directory of tiles, results by kind.
- [high/bug] Japanese and raw point values in dialogue notes (正义 RANK 9 恋爱, 力量 RANK 8).
  *Done:* rewritten by the rule build script, which now fails on any left; pack regenerated.
- [medium] Each sheet said its type three times; 「查看详情 ›」 / 「查看属性与打法 ›」 on every row;
  「完整问答顺序：」, 「注意：」, the widget's 「答：」. *Done.*
- [medium] Seeing the other route's answers meant changing the profile on 社群. *Done:* [朋友｜恋人]
  preview in the sheet, nothing saved.
- [medium] A search switched to 全部 and never switched back. *Done.*
- [medium] Combat names in two styles (守门 / 守关首领, 5层 / 91F), weaknesses as single characters,
  English names unused. *Done.*
- [medium] Rows rebuilt on every scroll (`convertView` ignored). *Deferred.*
- [low] Persona chips in request sheets; English names for missing people. *Deferred* (§5).

### 3.5 全书 (54 findings)

- [high/terms] 图鉴 / 合成 / 合成篮 and two disagreeing sets of element names. *Done* (§2.5).
- [high/terms] Raw unlock text with no label (「太阳社群MAX」). *Done:* a gold 「合体条件」 tag and the
  condition composed from the structured unlock data, also fixed in the asset.
- [high] Five control rows before the first Persona; DLC as a tool beside the segments. *Done:* DLC
  plate in the header, one search row, filter chips beside the count.
- [high] Two unlabelled look-alike toggles (○ registered, ☆ favourite) on 173 rows and inside the
  pickers. *Done:* ★ and a passive 「已登录」 on rows; labelled toggles on the detail page only.
- [high] 30 boxed recipe cards with up to four tap targets each, 30 white primaries in selector
  mode. *Done:* borderless blocks, one 「＋」 or 「选」.
- [high] The basket's 「目标 1 / 召唤 3 / 未登录 3」 strip and a prominent 清空. *Done:* progress in the
  section headers, a quiet 「清空合体篮」 with 撤销.
- [high/flow] 加入合成篮 closed every page; the empty basket's [去查配方] landed on the calculator.
  *Done:* stays on the page; [挑一个人格面具] opens the target picker.
- [medium] The DLC sheet needed 保存 and had 「仅游戏本体」; a DLC Persona opened the sheet without a
  word. *Done:* applies at once; 「这是 P5R 人格面具组合 1 里的人格面具，买了吗？」 [买了] in place.
- [medium/bug] List rows not recycled; full-width input found nothing. *Done.*
- *Kept:* basket logic, calculator ordering, persistence and the backup schema.

### 3.6 我的 and settings (52 findings)

- [high] 我的: a plain title, the game date as a small grey line, nine boxed rows with eight
  subtitles and three section headers. *Done:* SYSTEM header, progress hero, flat list (DESIGN §6.6).
- [high] About 13 stacked cards and 15 explanatory sentences on 规划设置. *Done:* STYLE / PERSONA /
  OPTIONS / 不再安排 / 更多选项, captions removed.
- [high] 塔尔塔罗斯: a rules lecture, 22 steppers, past blocks as full editable cards. *Done:* defaults
  strip, open block first, compact rows, past blocks folded, a gold warning where 0 drops a rescue.
- [high] 规划说明 「是怎么排的」: 12–14 lines of modelling. *Done:* removed; 「当前设置」 tags instead.
- [medium] 「深度修改」 second on the page; the footer 「按当前进度普通重排或深度计算：」. *Done.*
- [medium/bug] 「恢复安排」 left the row on screen and saved again on each tap. *Done.*
- [medium] Backup: share-only JSON, the warning twice, system-error copy, a confirm on top of the
  paste sheet, a silent failure without a file picker. *Done:* [保存备份文件], clipboard straight to a
  destructive confirm, friendly messages.
- [medium] Destructive actions looked like the happy path. *Done:* `ConfirmDialog.showDestructive`
  for restore; the 记录历史 undo keeps the plain confirm.
- [low] The add-widget fallback 「这台手机的桌面不支持直接添加。请回到桌面…」. *Deferred* (unchanged).

### 3.7 设置向导 (40 findings)

- [high/flow] 开始设置 asked for goals and the 人格面具 before the game state and ran up to 11
  pages, some empty at an early date. *Done:* game state first, pages gated by the date, goals
  last; a 4/18 start is 7 pages.
- [high/flow] 同步现状 「是」 still walked eight pre-filled pages. *Done:* 基本照着玩了 → 日期 → review.
- [high/flow] 修改进度 walked every page to change one number. *Done:* the 「修改哪一项？」 hub.
- [high/bug] After a failed save the button kept saying 「正在保存…」. *Done.*
- [high/bug] 「不确定的都设为未解锁」 wiped recorded ranks in 同步现状 / 修改进度. *Done:* removed.
- [high] 「?」 on a first run, saved as 未解锁 anyway, then listed as 「不确定（先按未解锁安排）」. *Done:*
  no 「?」 on a first run; 「不记得」 later, keeping the record.
- [high/flow] The highest floor could only be held up with [+] (about 7s to 96F). *Done:* tap the
  numeral to type it; −/+ kept.
- [high] The 宿舍活动 page ran about three screens with a lecture. *Done:* a 「做过宿舍活动吗？」 gate
  and one line per activity.
- [medium/bug] Notices stayed pinned above the buttons on every later page. *Done:* message bar.
- [medium] 12月24日 asked in October. *Done:* own page from 12/20 only.
- [medium] Three 'last record' references disagree (the question says the last recorded slot,
  the calendar marks the day of the next one). *Deferred.*

### 3.8 结果面板 (48 findings)

- [high] Persona chip, location, answers and how-to before the buttons, which started at about 78%
  of the sheet height. *Done:* the sheet asks only what happened.
- [high] A settings-style 「游戏提示下次就能升级」 switch card on every social panel. *Done:* a follow-up
  question after 没升级; a compact check on meetings and invitations.
- [high] Up to 11 tap targets; 「没见到」 recorded the same as 对方不在; 改做别的 offered twice. *Done:*
  two answer plates, 「其他结果…」, 「做不了…」.
- [high] Four text sizes in four colours; nothing marked the moment of choice. *Done:* speech plate
  and staggered answer plates under a kind watermark.
- [high] 「这个时段没能照安排做？」 with reasons that could not apply. *Done:* 「怎么没做成？」 with the
  reasons that fit the step.
- [medium] `RankUpBurst` never played. *Done* (also when opened from the widget).
- [medium/flow] Back from 做不了 closed the whole sheet; job cash in a stacked dialog with a second
  primary. *Done:* panel stack; inline keypad.
- [medium] 「这一项已经记录过了」 + a navigation path. *Done:* 「这项已经记过了」 [看记录历史] [好的].
- [medium] TowerPanel: the rescue after optional fields, a disabled segment, previews with 「请」, and a
  correction told to fix itself in 记录历史. *Done.*
- [low] An error banner looked like an info note; a full-screen 「正在保存…」 flash. *Done.*
- [low] The 羁绊剧情 reward on the sheet has no English Persona name. *Deferred.*

### 3.9 Engine and service text (39 findings)

- [high] Rescue why lines ended in policy (「；安排不等到期限当天」, 「合在这次处理」). *Done* at the source:
  「今天是救出北村文吉的最后期限，最晚今晚救出」.
- [high/bug] A 规划说明 note said 「最晚今晚完成救援」 weeks ahead. *Done:* names the date.
- [high] Modelling notes under 需要注意 (×1.51, 「普通见面预计好感 +10（约 ♪♪）」, the goal buffer, the fee
  reserve, note counts). *Done* at the source; 规划说明 filters as well.
- [high] Free slots subtitled 「没有能推进目标的安排」. *Done:* no subtitle.
- [high] Budget notes 「这次没来得及算完，稍后会自动再算一次」. *Partly:* 规划说明 shows its own chip, but
  the engine text is unchanged and the page still recognises it by its wording.
- [medium] Hedges inside fact chips (「RANK 6 · 见面（预计不升级）」, 「预计 +5,000 日元」, 「学力 +2 · 免费」);
  a whole sentence as a note title; a rule lecture on every dorm step. *Done* at the source.
- [medium] 记录历史 lines 「不记得钱了，现金按没变记」, 「多了 2,000 日元」, 「顺便要做的事没做」. *Done:*
  「现金没记」, 「现金 +2,000 日元」, 「顺便的事没做」. *Partly:* a hold still reads 「剧情原因暂时升不了」 (§5).
- [medium] A second labeller (`StepLabels`) with drifting wording. *Partly:* kept, reworded to read
  like `Labels`.
- [medium] UI code parses service text (urgency keywords, budget prefixes). *Deferred:* no
  structured flags yet.

## 4. Preserved contracts

These held through the rehaul (the audits' PRESERVE lists, condensed):

- **Widget boundary.** `widget/`, its layouts, drawables, colours, strings and `p3r_numerals`
  untouched; the class names HomeActivity, ResultActivity and StateActivity (launched by string);
  the extras `EXTRA_TAB` (today, schedule, links, tips, compendium, me), `EXTRA_OPEN_SLOT`, the
  result extras (slot, action, mode, plan id, state revision, correction seq) and
  `EXTRA_STATE_MODE`; `CONTINUE_LABEL`, the `DialogueHint` constants and the widget-shown plan
  errors. The new `EXTRA_OPEN_PAGE` is app-only.
- **Writes.** Optimistic saves with profile and state revision checks: a stale receipt reloads and
  keeps the player's edits (wizard drafts, the 队友特性 sheet), nothing newer is overwritten
  silently; `CommandIds` stay deterministic, so double taps and retries are idempotent; the result
  sheet saves nothing when the plan id, revision or action no longer match.
- **Drafts.** Per-mode wizard drafts survive rotation and process death, resume at their step and
  are cleared on save; an unchanged 同步现状 / 修改进度 saves nothing.
- **Undo.** 「已记录：… 撤销」 for 8s; the result sheet hands its record to 今天 only when opened from
  the app; rank-up summaries 「皇帝 RANK 7」 / 「皇帝 升到 MAX」 drive the RANK UP style; undo in
  记录历史 always confirms.
- **Back order.** Sheet → sub-page → 今天 → leave; inside the result sheet back pops one panel;
  nothing closes while saving.
- **Outcomes.** Outcome shapes and the reason ids (story, absent, closed, money, unwilling, hold,
  other) unchanged; 顺便 defaults to done on result panels and not done on 做不了.
- **Tartarus.** One cash field (balance or change), 不记得钱了 = a change of 0 with the cash unknown,
  corrections never add a visit, the rescue question only in an open window, 想去 ≤ 最多.
- **社群.** Goal cycle and ordering inside a tier; a shortfall names only the goal the plan traded
  it for; story links have no tier; opting into 羁绊剧情 saves the watched count and the profile in
  one call; dorm forecasts never shown as recorded, corrections never grant stats, gardening only
  through its switch.
- **提示** never calls the planner; search aliases (request numbers, floors, ranks, English names)
  and the one-group-per-link and per-Monad-block layout kept.
- **全书.** Basket logic, calculator order, backup JSON, one-tap clear with undo, the workspace
  across tab switches, ingredient recipes one page deep.
- **Settings** save at once and never ask for a manual replan; the current 平安夜 choice is never
  dropped; backup import ids stay idempotent with a confirmation before replacing.
- **Text and accessibility.** Glossary terms, full-width punctuation, en-dash ranges, M/D only on
  date plates, RANK / Lv. / MAX in the numeral face, CJK never italic, decorative Latin hidden from
  TalkBack, 「结缘网站的字条」 never shortened, 48dp targets, the content descriptions tests click,
  and every motion gated by `Ui.animationsEnabled()` so renders stay deterministic.

## 5. Known gaps and follow-ups

Each item was checked in the code at 3f64d68.

1. **「其余社群也尽量提升」 lost its switch during the merge** (the 社群 rehaul moved it to 规划设置, the
   规划设置 rehaul left it on 社群, the wizard dropped it). Fixed in review: it is a switch in
   规划设置 → 其他设置, covered by `PlanStyleSettingsTest.otherLinksIsASettingInTheMainList`.
2. **Missing people named only in English.** 18 of the 20 people in `reminders.rescueGroups` have
   only `nameEn`, so rescue details read 「50F · Ayako Yoshimoto」 (day sheet 当天提醒, 提示). There is
   no official SC name in the data, and none was invented.
3. **Persona names in 提示 and the result sheet.** Request sheets name Personas in Chinese only
   (「猫又＋佛钮司＝塔姆林」) with no chip into 全书; the result sheet's 羁绊剧情 reward has no English
   name (the 社群 tab has it).
4. **No per-cell start page.** Tapping 现金 or a stat on the 今天 resource band opens the
   「修改哪一项？」 hub, not that page.
5. **Shared text still only filtered in `ui/`.** The rule pack keeps 42 reminder details with
   「花的钱不在安排的预计里，买了以后记得在「同步现状」里更新现金。」 (or a variant), 8 with
   「已完成的可以跳过，不必今天全部清空。」, the 1月21日 note 「…所以先空出来」 and the lock reason
   「可能有剧情（先空出）」 (the app shows 「先空出来」, the widget the pack text). The generic answer hint
   is hidden only in the app. Urgency and the 规划说明 budget state are still read from wording, not
   flags. 记录历史 still says 「剧情原因暂时升不了」 where the sheet now says 「剧情卡住了，升不了」.
6. **Copy the guard misses.** GlossarySourceTest does not check 「请」 or 「暂时」, which is how the
   add-widget fallback 「这台手机的桌面不支持直接添加。请回到桌面…」 and the history hold text survived.
7. **提示 performance.** List rows are rebuilt on every `getView`; 全书 got recycling, 提示 did not.
8. **Replan results.** No 「看看」 to jump to the changed days and no marks on changed slots; 日程 has
   no sticky month header or month index.
9. **Wizard dates.** The 同步现状 question names the last recorded slot while the calendar marks the
   day of the next slot and the review's 日期 row starts from it.
10. **Visual kit.** Secondary buttons keep their outline; no shared plate / list-row /
    selection-band helpers or type tokens; NumeralSpan uses the italic face at every size;
    「完成 ✓」 keeps a text tick; the message bar still jumps to the top on 今天; no hint ink token for
    contrast on the bright water; no motion tokens, press animator, moving water or count-up.
11. **Docs.** `docs/screenshots/*.png` predate 3.6.0. DESIGN_3.6_UI.md §3 still gives glass panels
    a cyan corner tick and §6.1 gives [完成] weight 2; the code has no tick on plates and uses
    1 : 1.6 with 「完成 ✓」.
