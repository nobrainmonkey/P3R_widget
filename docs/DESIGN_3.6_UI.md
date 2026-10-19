# P3R 规划 3.6 — UI/UX rehaul (design contract)

This section-style contract supersedes the visual and copy parts of [DESIGN_v3.md](DESIGN_v3.md) §7,
§26 and the older quoted UI strings. Planning, storage, the home-screen widget and the fusion
basket logic are unchanged. The audit behind it is
[analysis/UI_UX_AUDIT_2026-10-19.md](analysis/UI_UX_AUDIT_2026-10-19.md).

## 1. What the owner asked for

- Look and move like Persona 3 Reload, not like a generic dark settings app.
- Remove the AI voice: self-deprecation, hedging, apologies and explanations of how the planner
  thinks. The player is not technical and not slow: show the answer, the action and the real game
  condition, nothing about the model behind it.
- Less clutter, smoother flows, fewer taps.
- Simplified Chinese with ATLUS's official terms; Persona names keep the English name as a
  secondary line so guides can be searched.
- Keep: the widget (untouched), the fusion basket logic (compendium/FusionBasket.java), all
  planning behaviour, records, undo, backup, deep links.

## 2. Voice and copy rules (all app screens)

1. Talk like a friend at your shoulder: short clauses, questions where natural
   (「升到 RANK 7 了吗？」「之后又玩了吗？」). No 「我」 for the app, no 「请」, no 「您」.
2. Never apologise or hedge: no 「暂时」「可能」「不一定」「仅供参考」「以游戏为准」「没能」
   「没找到更好的」「这是估计值」. A forecast gets **one** small 「预计」 badge (or the 「≈」 prefix in
   compact rows) and nothing else.
3. Never explain the planner: no 「为什么这样安排」, 「安排依据」, 「这一步用到的估计」, scoring,
   budgets in seconds, 「普通重排」, 「按…计算」. Keep only reasons that change what the player
   does (a deadline, the last chance, a person only around on some days, an unlock requirement).
4. Never teach an obvious control: no 「点卡片查看」「点「重试」再试一次」「点按查看或修改」, no
   legends, no captions under self-explanatory toggles. A setting gets at most one short line
   (about 16 characters) when its title alone could be misread.
5. Errors say what happened and offer the fix as a button: 「记录没读出来」 + [重试];
   「没保存上，再试一次？」; 「安排刚更新过，再看一眼？」.
6. Status that changes nothing is not shown (「后面的安排没变」 never appears).
7. Glossary terms stay (docs/GLOSSARY_zh.md), plus §8 below. Full-width punctuation; numbers with
   units; RANK / Lv. / MAX in the numeral face (Ui.prepare does this).

## 3. Visual system (the "sea of the heart")

P3R's menus are the protagonist's inner sea: lit royal water near the surface, abyss below,
caustics, bubbles, giant cropped Latin words, slanted plates, and selection that cuts through.

### Tokens (res/values/ui_colors.xml, p3r_colors.xml)
- Page water: `OceanDrawable.page(context)` — `ui_sea_surface` #123C9C at the top → navy 800 →
  navy 950, royal glow top-right, light shafts, caustic ripples near the surface.
- Glass panel: `ui_glass_top` → `ui_glass_bottom` gradient, **no outline**, two diagonal cuts and a
  short cyan corner tick (`SlantedPanelDrawable.cornerTick`).
- Hero: royal → navy gradient, cyan blade on the left, light slash (`lightSlash`), white corner tick.
- Cyan `p3r_cyan_400` #3CE4FF is the selection colour; white is the action colour (primary buttons,
  like the widget's 完成); gold `ui_gold` only for 「顺便」 and 「今天别错过」 attention; red
  `p3r_danger` only for errors; `ui_holiday` red for Sunday/holiday weekday plates (as in-game).

### Type
- CJK: system face, upright, never italic. Page title 32sp bold (tab pages), sub-page title 24sp,
  `Ui.title` 24sp, `Ui.headline` 18sp, body 15sp, `Ui.muted` 14sp, `Ui.caption` 13sp. Nothing
  under 12sp; chips and badges 13sp.
- Latin and numerals: Barlow Condensed ExtraBold Italic through `Ui.numeral` / `Ui.latin` /
  NumeralSpan. Big numerals are graphics (date 68dp, ranks, levels, counts).
- Decorative Latin words only (never the only carrier of meaning): the tab kicker and watermark
  (`Ui.pageHeader`), WEEKDAY/WEEKEND behind the date plate, 「RANK UP」.

### Components (ui/Ui.java and friends)
| Piece | Use |
|---|---|
| `Ui.pageHeader(ctx, 「社群」, "SOCIAL LINK", trailing)` | Top of every tab except 今天 (its DatePlate is its header). Kickers: 日程 CALENDAR, 社群 SOCIAL LINK, 提示 GUIDE, 全书 COMPENDIUM, 我的 SYSTEM. |
| `Ui.subPageBar` | Every pushed sub-page (HomeActivity.pushPage). |
| `Ui.card` | Default glass panel. Use sparingly: group content, not every row. |
| `Ui.pressableCard` | Tappable row/panel. |
| `Ui.band` | Flat slanted strip for summaries and list groups. |
| `Ui.heroCard` | The one most important thing on a screen. |
| `Ui.primary` | White parallelogram, navy ink, cyan slab — one per region. |
| `Ui.secondary` / `Ui.quiet` | Cyan-outline / text actions. |
| `Ui.chip` / `SegmentedControl` | Choices; selected is cyan with a white slab. |
| `Ui.tag(ctx, label, fill, ink)` | Small slanted label (kind, tier, 「预计」, 「今天别错过」). |
| `Ui.sectionHeader` | Cyan blade + title + fading rule. |
| `Ui.fadingRule` | Hairline that fades right; use between list items instead of boxes. |
| `DatePlate` | Calendar HUD; large one shows the WEEKDAY/WEEKEND watermark; rest days red. |
| `UndoBar.show(..., Style)` | RECORD / RANK_UP (「RANK UP」 badge) / ERROR (red slab, 「!」). `HomeHost.showError`. |
| `RankUpBurst.play(parent, "RANK UP!!", caption, done)` | Full-screen flourish after a recorded rank-up. |
| `Motion.enter / cascade / tabIn / swapIn / pressable` | The motion language (§4). |

Layout rules: lists are rows separated by space or a fading rule, not a box per row. At most one
outlined element per screen region. Asymmetric compositions are fine; keep the 16dp gutter.

## 4. Motion

All motion is skipped when `Ui.animationsEnabled()` is false (render tests).
- Tab switch: the tab-bar slab slides (220ms, SETTLE); content slides 24dp in the direction of
  travel with a fade (`Motion.tabIn`).
- First show of a list / page: rows cascade in from the right, 32ms apart, 280ms, at most 10 rows
  (`Motion.cascade`). Never on every rebind — only on first show, tab entry or a real change.
- Hero step changes (after 完成): the new hero swaps in from the right with a slight tilt
  (`Motion.swapIn`).
- Buttons sink to 96% while pressed (`Motion.pressable`, built into primary/secondary).
- Message bar slides in from the left; rank-ups get the 「RANK UP」 badge; a recorded rank-up plays
  `RankUpBurst` (about 1.3s, tap to skip) on the result sheet before it closes.
- Stillness between motions is part of the style: no looping animation on text.

## 5. Information architecture

Tabs stay: 今天 / 日程 / 社群 / 提示 / 全书 / 我的 (「图鉴」 is renamed 「全书」). Tab keys and widget
deep links are unchanged (`today`, `schedule`, `links`, `tips`, `compendium`, `me`).

## 6. Screen contracts

Each screen keeps its behaviour and tests' semantic contracts (see the audit's PRESERVE lists);
copy and layout change as below.

### 6.1 今天
- Header: DatePlate (WEEKDAY/WEEKEND watermark, red rest-day plate, moon). Ordinary replans show only
  a BusyStripe under it — no text.
- Resource band: one borderless `Ui.band`: 现金 (「290+」 when only a lower bound is known, 「—」 when
  unknown) / 学力 / 魅力 / 勇气 in big numerals; one compact tower row 「提齐亚 · 去过 1/4 次」 with
  「最高 96F」, hidden when no block is open. The band is pressable → 修改进度 (`openState("edit")`).
- Hero (widget-like): kind tag (slanted cyan, icon) + 「详情 ›」 at the top right; 26sp title; the
  rank line 「RANK 5 → 6」 + one 「预计」 tag (no 「≈」); gold persona chip; location; real answers
  only (「回答：…」, muted 「要是升级了，答：…」; the generic fallback is hidden; friend route shows
  「只想做朋友？别选表白的回答」); 顺便 lines; an urgent reason only (deadline, last chance, scarce
  days, unlock requirement) in gold. Buttons [做不了…] (weight 1, secondary) and [完成] (weight 2,
  primary white with check). Removed: 「下一步 · 放学后」, 「怎么做 · 点卡片查看」, every other whyZh.
- 今天别错过: one card with a gold tag on its edge and 「全部提示 ›」; lines drop a trailing
  「，不会消耗时间」.
- 接下来: rest of today + tomorrow, one borderless list with white slanted day plates; locks of a day
  merge into one 「全天 · 满月作战」 row; ends with 「全部日程 ›」.
- Goals: only goals in trouble, as gold lines (「皇帝 · 没排到 MAX ›」); all fine → 「目标都排到了 ›」.
- Status: return banner 「之后又玩了吗？」 / 「上次记到 6月5日 晚上 · 3 天前」 [没有] [同步现状];
  error banner 「新安排没排出来，先按原来的走」 [重试]; full replan running 「正在从头重新安排…」 +
  stripe + [停下]. The persistent change summary line is removed; changes of this week are said
  once in the message bar.
- Welcome: title 「从现在的存档开始」, one line 「填好游戏进度和想升的社群，每天做什么就排好了。」,
  [开始设置]. No 2-minute hint, no three explanation cards.
- Finished: big numeral of MAX links + 「目标 5/5」, title 「日程全部结束，辛苦了！」.

### 6.2 日程, day sheet, 记录历史, 从头重新安排
- 日程: `pageHeader("日程", "CALENDAR", 记录历史 icon)`; no stats line, no legend. Day rows are a
  timeline (no box per day): DatePlate-style day numeral + weekday plate (+ moon on full moons),
  period as a small slanted tag, one line for identical all-day locks, gold reminder chips. Today is
  the hero slab; yesterday dimmed; a past slot without a record shows 「—」 and is inert; recorded
  lines have no 「点按查看或修改」. 「回到今天」 only shows when today is off-screen.
- Day sheet: DatePlate → day note as one line → step block (kind tag, title, subtitle, persona,
  location, real answers, 顺便, 怎么做 · N 步) → 当天提醒 → actions identical to the 今天 hero
  ([做不了…] [完成]); future slots get one secondary 「已经玩到这里」 without a caption. Removed:
  为什么这样安排, 安排依据, all instructional captions, 怎么做 on story slots.
- 记录历史: grouped by game day, rows = period tag + result + 「3 分钟前」 + compact 修改/撤销; void
  journal rows hidden; one divider 「10月18日 · 同步现状」 instead of per-row captions; no
  explanation caption; 「再看更早的」 when the limit is hit; errors get [重试].
- 从头重新安排: one question 「从头重新安排？」, one line 「最多半分钟，期间可以照常记录。」,
  [先不了] [重新安排] (runs the deep search). Running → [停下]. Results:
  「重新安排好了：这周 3 处有变」, 「…，之后也有调整」, 「重新安排好了：这周不变，之后有调整」,
  「重新安排好了：顺便的事有调整」, 「重新安排好了，和现在的一样」, 「这次没排好，安排先不变」,
  「已停下，安排没变」; ordinary: 「已重新安排：这周 3 处有变」 or nothing.

(§6.3–6.8 are filled in from the remaining audits: 社群, 提示, 全书, 我的/设置, 设置向导, 结果面板.)

## 7. Out of scope

- `widget/` package, widget layouts/drawables/colours, widget strings.
- Engine/service/rule-pack text that the widget also shows (engine/Labels, DialogueHint,
  Reminders, StoryDisplay.CONTINUE_LABEL, p3r_rules.json): compact or filter it in `ui/` instead.
- Fusion basket logic (compendium/FusionBasket.java) and the fusion calculator.

## 8. Terminology changes (official SC)

| Was | Now | Evidence |
|---|---|---|
| 图鉴 (tab), 图鉴与合成 | 全书 (tab), 人格面具全书 (title) | Official SC site: 「登录至“人格面具全书”」 |
| 合成 (fusion), 特殊合成, 选择合成目标 | 合体, 特殊合体, 选合体目标 | Official SC site 「人格面具合体」; Steam SC 「进行了２身合体」 |
| 合成篮 | 合体篮 (the app's own feature) | No in-game equivalent |
| 火炎 / 光 / 暗 / 光明 / 黑暗 / 回复 | 火焰 / 神圣 / 暗黑 / 恢复 | Game data DatAttrNameDataAsset zh-Hans |
| 羁绊剧场 | 羁绊剧情 | Glossary |
