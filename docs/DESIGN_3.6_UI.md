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
- Glass panel: `ui_glass_top` → `ui_glass_bottom` gradient, **no outline**, two diagonal cuts, no
  corner tick; `SlantedPanelDrawable.cornerTick` marks only the hero, the 必须 tarot tile, edited
  wizard tiles, answer plates and tip tiles.
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
  days, unlock requirement) in gold. Buttons [做不了…] (weight 1, secondary) and [完成 ✓] (weight
  1.6, primary white). Removed: 「下一步 · 放学后」, 「怎么做 · 点卡片查看」, every other whyZh.
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

### 6.3 社群, 社群详情, 目标顺序, 羁绊剧情, 队友特性, RankPad
- Header: `pageHeader("社群", "SOCIAL LINK", 「MAX 15」)`; the count carries the page's only 「预计」
  tag. Before 开始设置 one banner 「先完成开始设置，就能看到每个社群能升到哪」 [开始设置]. While a
  replan runs: BusyStripe under the header, forecast pips / 「→ 9」 / dates at 40%.
- Sub-tabs 「社群」「羁绊剧情」「队友特性」 (sliding slab, last choice kept). Goal strip: 「目标」 +
  plates 「1 皇帝」「2 恋爱」…「+3」 (必须 white, 想要 cyan outline) + 「调整顺序」; no goals:
  「想冲 MAX 的社群，点卡片右上角设成目标」.
- Tarot tiles: the pack's card numeral as a faint watermark (永劫 「XX」), arcana, person name only,
  「恋人」 tag on the romance route, big RANK numeral or 「未解锁」, cyan 「→ 9」 forecast, white
  「MAX」 stamp, 10 pips. 必须 = inverted white plate; 想要 = cyan blade; 未选 = plain glass, 70% when
  not unlocked and not planned. Corner chip 「＋」 / 「想要」 / 「必须」 cycles at once and is saved
  once, 800ms after the last tap; story links show 「剧情自动推进」. One footer at most:
  「11月20日 升到 MAX」, 「时间先给了恋爱」 (goals only), 「救出北村文吉了吗？」 /
  「没救出失踪者，无法继续」.
- 社群详情 (tap a tile): 「RANK 5 → 9」 + pips; one status line with its fix
  (「时间先给了女皇。想先冲它，就排到女皇前面」 + 「调整顺序 ›」 within a tier; a 想要 goal behind a
  必须 one 「时间先给了皇帝。想先冲它，就设成必须，排到皇帝前面」 + 「调整顺序 ›」; no goal ahead of it
  in its tier: only 「这次排到 RANK 9」; 「救出北村文吉了吗？」 +
  [去修改进度]); 目标 未选 | 想要 | 必须; romanceable only 「回答按哪条路线？」 朋友 | 恋人;
  everything saves at once. 目标顺序 sheet: 必须 / 想要 groups, big order numerals, ↑ / ↓ only where
  the move stays in the tier.
- 羁绊剧情: caption 「羁绊剧情不加社群点数」; rows: person, 「1/5」 + pips,
  「看完可以合体「苏尔特尔」Surt」, switch. Opting in mid-game asks 「已经看了几话？」; closing
  without an answer leaves it off.
- 队友特性: 3×3 tiles (pips white recorded / cyan 预计 / hollow; 「解锁」「强化」 tag or
  「已解锁」「已强化」 stamp; 「6月16日起」). Sheet: live stamp,
  「同一种活动做 3 次解锁特性，两种各 3 次强化特性」, 目标 未选 | 解锁 | 强化, 「做过几次？」 0–3 (+
  「预计 2」), 「种菜也排进来」 for gardening teammates, [保存]. A count changed by a new record
  reopens it: 「刚刚有新记录，次数更新了，再看一眼？」.
- RankPad: card numeral, name only, 「2/22」 + a progress bar, key flash and slide on advance, 「?」
  over 「不记得」 (hidden on a first run), [‹ 上一个] [好了].
- Removed: 「这次的安排里没排到 MAX，先保证了…」, 「暂时不安排提升」, 「安排排好后显示」, 「预计」 on
  every card, the tier instruction paragraph, 「全部社群 · 22 个」, the inline goal list, route
  segments and 「安排不会要求你走恋爱路线」 on the cards, 「其余社群也尽量提升」, the 羁绊剧情 date
  windows and sheet caption, the 队友特性 planner paragraphs, 「不追求」, 「保存并重新安排」 /
  「取消」, the RankPad hint.

### 6.4 提示
- Header: `pageHeader("提示", "GUIDE", [本期｜全部])`. No game date: opens in 全部; 本期 asks
  「现在玩到游戏里的哪一天了？」 [设置日期].
- Search with magnifier and ×: searches the whole game, the old scope returns when cleared; results
  grouped by kind, 「找到 12 条」 only while searching;
  「没找到。换个说法试试？比如「风花」「36F」「委托 75」」.
- One-tap strip 「全部 · 社群 · 课堂 · 考试 · 委托 · 战斗 · 其他」; 其他 = a single-tap tag list,
  shown as a removable pill (40dp, 「去掉筛选：失踪者」 for TalkBack).
- 本期: banner (block, floors, dates); 「现在」 / 「接下来」, 「已经过去 · 12」 folded; borderless
  rows with a date plate, kind tag, line, gain, 「10月25日截止」. 「伊莉莎白的委托 · 29 件」 (open
  requests; expired ones stay rows under 已经过去; 「今天截止 2 件」 on a deadline day) and
  repeated days (上课犯困) are one row each. 全部 without a search: directory tiles with counts,
  named like the strip and the search headers: 「社群」「课堂 · 考试」「委托」「战斗」「其他」.
- Social links: numeral, person, 「RANK 3」 + pips, 「下一次 RANK 3 → 4」 + the answer (or
  「还没解锁 · 6月19日起」), rank track 「解锁」「1→2」…「9→MAX」 with played ranks dimmed and the
  next one lit.
- Sheets are titled by the item: scene 「RANK 1 → 2」 + [朋友｜恋人] preview (profile untouched) + one
  choice plate per question 「第 1 问  没问题」; 「解锁女教皇社群」 with a date tag (no 「社群解锁」
  kind tag), conditions and numbered steps; requests as 开放 / 前置 / 怎么做 / 奖励 rows; [知道了].
- 战斗: 「守关首领」「蒙纳多之门与通道」「剧情作战」「特殊挑战」; rows with a floor or date plate, the
  name without the pack prefix, the English name, official element tags (「火焰」「神圣」…) or
  「无弱点」; Monad mini headers 「蒙纳多之门 · 随机出现」 / 「蒙纳多通道 · 91F」. Sheet:
  「弱点与耐性」, one legend line, 「打法」.
- Removed: the filter sheet with 重置 / 应用, the 「N 条」 counter and selection caption,
  「查看详情 ›」, 「查看属性与打法 ›」, 「阵容／阶段：」, 「10 段剧情」, 「+」 / 「−」,
  「RANK 9 → 10」, the 「提示详情」 / 「战斗详情」 titles, 「社群升级剧情 · 随时查阅」,
  「完整问答顺序：」, 「注意：」, the widget's 「答：」, the three legend lines, 「本期」 in the
  whole-game lookup, Japanese left in the dialogue data.

### 6.5 全书, 合体, 合体篮
Basket logic, the calculator, persistence and the workspace behaviour are unchanged.
- Header: `pageHeader("人格面具全书", "COMPENDIUM", DLC plate)` (a dot while a pack is owned);
  segments 「一览」「合体」「合体篮」, the goal count on a numeral plate.
- 一览: one search row (「名字、英文名或技能」, ★, filter), removable filter chips beside 「173 个」;
  borderless recycled rows: Lv. numeral, name, 「Orpheus · 愚者」, a passive 「已登录」, ★; arcana
  order adds group headers. Error 「全书没读出来」 [重试]. 筛选: Lv. 1–99, 等级 / 阿尔卡那,
  「VI 恋爱」 chips, 「也显示没买的 DLC」, [重置] [看结果].
- 「买了哪些 DLC？」: each pack applies at once, [好了]. A DLC Persona asks in place
  「这是 P5R 人格面具组合 1 里的人格面具，买了吗？」 [买了] and continues.
- Detail, titled with the arcana: hero (Latin arcana watermark, name, English, big 「Lv.1」, labelled
  「收藏」 / 「已登录」 toggles, gold 「合体条件」 + 「太阳社群升到 MAX」, DLC tag), [查配方]
  [拿它当素材]; 「洗牌时间」 in F, 「能力值」 bars, one 「弱点与耐性」 band, borderless 「可学技能」
  rows (「初始」 / 「Lv.3」, element tag, English, 「3 SP」), 「神通法」.
- 合体: two material plates (「＋」 「选人格面具」), a RESULT hero that swaps in, [加入合体篮]
  [用它继续合体]; 「这两个合不出人格面具」; 「想合出谁？」 → picker → recipes. Pickers:
  「和 俄耳甫斯 合体」, no toggles, no counter.
- Recipes 「X · 合体配方」: breadcrumb, borderless blocks tagged
  「特殊合体」「素材都已登录」「现在用的」, bilingual ingredient lines that open their own recipes, a
  「＋」 that adds and stays (「已加入合体篮」 · 去看看); 「还有 N 组，接着看」;
  「合不出来，去塔尔塔罗斯的洗牌时间找它」.
- 合体篮: empty 「合体篮是空的」 [挑一个人格面具]; goal hero 「还差 2 步」 / 「已登录」 /
  「配方要重选」 + step bar + ··· (换配方 / 移出合体篮); one banner
  「有配方用到了没买的 DLC，重选一下？」; 「需要的素材」 「已登录 1/3」 with an inline sort and
  「怎么合？」; 「合体顺序」 「完成 0/2」, big step numerals, a menu
  「看 X 的资料」「换个配方」「不合了，直接召唤」; 「X 合出来了！」; 「清空合体篮」 with 撤销.
- Removed: 图鉴 / 合成, 火炎 / 光 / 暗 / 回复, raw unlock text, [全部][收藏], the ○ toggle on every
  row, boxed rows, cells, skills and recipe cards, 「接着合成」, the jump to the basket after an add,
  「仅游戏本体」, the 目标 / 召唤 / 未登录 strip, the sort sheet, 「可召唤」.

### 6.6 我的, 规划设置, 塔尔塔罗斯, 备份与恢复, 规划说明, 关于
- 我的: `pageHeader("我的", "SYSTEM")`; progress hero 「游戏进度」 + DatePlate + 「玩了一阵子？」
  [修改进度] [同步现状] (「还没设置进度」 [开始设置]; 「日程全部结束」). Flat list with a cyan cursor:
  「记录历史」「规划设置」「规划说明」「从头重新安排」「备份与恢复」「添加桌面小组件」「关于」 +
  version. Unusable rows are left out; 从头重新安排 stays while it runs. Removed: subtitles, headers,
  「已记录 78 条」, dimmed rows.
- 规划设置 (saves at once; 「重新安排中…」 while replanning): 「STYLE 安排风格」 tiles
  「尽量都体验 / 每个社群都解锁、都往上提」 and 「尽量多 MAX / 集中冲 MAX」; 「PERSONA 人格面具」
  「见面时带同阿尔卡那的人格面具吗？」 「×1.51」 都带 | 只给目标 | 不带 (+
  「见面前会提醒你带哪个阿尔卡那」); 「OPTIONS 其他设置」 rows 「塔尔塔罗斯」 「每个区块 2 次」 ›,
  「平安夜」 「都婉拒了」 › (sheet 「12月24日接受了谁的邀约？」), 「手上至少留」 「0 日元」 ›, switches
  「其余社群也尽量提升」 and 「安排结缘网站的字条」 「11月11日起 · 每张 3,000 日元」; 「不再安排」 only when non-empty (「恢复」
  removes the row at once); folded 「更多选项」:
  「魅力 MAX 后点数 ×1.51」「目标多留一次见面」「我会照提示选回答」「仔细重新安排」. Removed:
  「深度修改 / 每次修改后最多计算 12 秒」, the 社群点数 / 塔尔塔罗斯 / 平安夜 / 现金 headers, every
  caption, 「不计回答加成」, 「不带/不一定」, 「按当前进度普通重排或深度计算：」.
- 塔尔塔罗斯: defaults strip 「默认每个区块」 想去 / 最多 + 「救失踪者也算一次」; the open block first
  with 「现在」; compact rows (floors, dates, inline 想去, 「最多 4 次 ›」);
  「失踪者：舞子 · 10月21日–11月2日」; gold 「设成 0，舞子就救不出来了」; past blocks folded into
  「已经过了 3 个区块」. Removed: the intro and the per-block rescue sentence.
- 备份与恢复: 「备份」 「进度、设置、全书收藏和合体篮都在里面」 [保存备份文件] [分享备份];
  「从备份恢复」 [选择备份文件] [粘贴文字] (a backup on the clipboard goes straight to the confirm).
  Destructive confirm 「用这份备份替换现在的记录？」 [先不了] [替换]; 「恢复好了！」,
  「这不像是 P3R 规划的备份，换一份试试？」. Removed: the warning said twice, 「…失败，请稍后再试。」.
- 规划说明: hero with the MAX count + 「预计」 and 「从10月22日晚上排到1月31日」, a budget chip
  「后半段还在细算，稍后自动更新」; 「目标」 rows; 「需要注意」 real problems only; 「当前设置」 tags +
  「去规划设置 ›」. Removed: 「是怎么排的」, action / story / free counts, notes that restate settings
  (sources → 关于).
- 关于: 「P3R 规划」 + version band, 「全部在手机上计算，不联网，不收集数据。」, 「资料来源」,
  「全书与合体资料」.
- Controls: ToggleRow flat variant with a sliding knob, SegmentedControl sliding slab, CheckRow drawn
  tick; `ConfirmDialog.showDestructive` (red, 「先不了」) for the restore only.

### 6.7 设置向导 (开始设置 / 同步现状 / 修改进度)
- Frame: X keeps the draft; counter 「03/10」 over the pages this walk shows; Latin kicker + watermark +
  one big question per page, no hints, borderless rows; `Motion.tabIn` between pages, cascades on
  entry only; [上一步] + [下一步] / [开始安排] / [更新安排] / [好的]. Notices slide in the message bar
  (「接着上次填」, 「刚有新记录，已经合进来了，再看一眼？」, errors red); a failed save gives the
  button its label back; 「记录没读出来」 [重试]. Back on 今天: 「已记录：游戏进度」.
- 开始设置: 日期 → 现金 → 个人能力值 → 社群 → only what the date opens (解锁前的准备, 平安夜 from
  12/20, 羁绊剧情, 宿舍活动, 塔尔塔罗斯) → 目标 → 人格面具 → 「准备好了？」; a 4/18 start is 7 pages.
- 同步现状: 「上次记到 10月21日 晚上，之后照着安排玩了吗？」
  「基本照着玩了」「玩了一部分」「没怎么照着玩」; 基本照着玩了 → 日期 → review (rows open their page;
  「还有别的变化？」).
- 修改进度: 「修改哪一项？」 with current values; a row opens one page, [完成] returns, changed rows
  get 「已改」; 「逐项检查」 / 「更新安排」. The 今天 resource band opens it.
- Pages: 「游戏里现在是哪一天？」 (「‹ 10月 ›」, Sundays red, lock reason in the period plate
  「晚上 · 满月作战」); 「现在有多少钱？」 (prefill drawn selected, one 「预计」, 「— 日元」 +
  「不记得」); 「学力、魅力、勇气现在几级？」; 「社群都升到几级了？」 (RANK tiles, 「≈7」, RankPad);
  「解锁准备做到哪一步了？」 (tap step N = 1..N, 「都还没做」); 「12月24日接受了谁的邀约？」 (one
  tap); 「羁绊剧情看到第几话了？」; 「做过宿舍活动吗？」 then 0–3 per activity with 「特性已解锁」 /
  「特性已强化」; 「塔尔塔罗斯爬到几层了？」 (typeable floor 「/117F」, 「期限 10月3日 · 教皇」,
  [救出了] [没救出] or [还没]); 「想把哪些社群升到 MAX？」 (「点一下想要，再点一下必须」, the 19
  manual links, 「清空」); 人格面具 都带 / 只给目标社群带 / 不带.
- Review: 「准备好了？」 hero (DatePlate, 现金 / 学力 / 魅力 / 勇气, 社群, goals, 人格面具, all
  tappable) [开始安排]; 「更新这 7 项？」 (「现金  18,500 → 21,500 日元」, one 「预计」 on forecast
  rows) [更新安排]; 「和上次一样，不用改」 [好的].
- Removed: every hint and footer, 「尽量全部升到 MAX」, 「其余社群也尽量提升」, story links among the
  goals, 「不确定的都设为未解锁」, the first-run 「?」 and 「不确定（先按未解锁安排）」, the gardening
  switch (now in 队友特性), the date summary card and legend, 「将更新 7 项」 +
  「带「预计」的数值请对照游戏核对，保存就算确认。」, 「保存并…」 labels, the pinned gold notice card.

### 6.8 结果面板, TowerPanel, RankUpBurst, SheetChrome
- Sheets: bright top edge only; a grip drags any sheet down to close (96dp or a fling); ResultActivity
  keeps its X.
- Panel: DatePlate (+ 「补记 2/4」), kind tag, 26sp title over a faint Latin word per kind, the
  question on a speech plate with a numeral kicker (「RANK 5 → 6」 + one 「预计」), 顺便 + 「也做了」,
  at most two staggered answer plates, 「其他结果…」 / 「做不了…」. 怎么做 stays folded on unlock /
  note / episode / dorm.
- Questions: 「升到 RANK 6 了吗？」 [升到 RANK 6 了] [没升级] → 「提示下次就能升级了吗？」 [提示了]
  [没有]; 「其他结果…」 → 「游戏里现在是 RANK 几？」 + 「剧情卡住了，升不了」;
  「和山岸风花见面了吗？」 + 「提示了「下次就能升级」」 [见面了] [升到 RANK 5 了]; 「赴约了吗？」;
  「这一步做完了吗？」 (「第 1/3 步」); 「给山岸风花用上了吗？」 [用了] [没用成];
  「看完第 2 话了吗？」; 「做完了吗？」 [做完了] + 「结果不一样…」 (level chips, 「打工后的现金」 with
  an inline keypad); free slots [继续] [做了别的…].
- 做不了: 「怎么没做成？」 + the reasons that fit
  (「剧情占用了」「对方不在」「没开门」「钱不够」「不想做」), 「其实做了别的…」; 「以后还排「X」吗？」
  [只是这次] [以后别排了]; 改做别的 「做了什么？」 + 「都不是，没什么收获」. Back pops one panel; only
  the entry panel closes the sheet.
- 补记: 「前面 3 项也照安排做了吗？」 [都做了] [一项项对] + 「前面都没做」; no question when only
  story time lies in between.
- States: 「还没开始设置」 [开始设置]; 「日程全部结束，辛苦了！」; 「这项已经记过了」 [看记录历史]
  [好的] (opens the app on 记录历史 via `Intents.EXTRA_OPEN_PAGE`); 「记录没读出来」 [关闭] [重试];
  「这条记录刚改过」; 「安排还在排，马上就好」; corrections show 「原来记的是：皇帝 RANK 7」. Errors
  red; 「正在保存…」 after 400ms.
- TowerPanel: 「奇颜之庭 阿尔卡（23–43F）」 + 「第 2 次 · 最多 4 次」; HUD floor numeral (typeable) +
  「顶层 117F」; the rescue next, 「舞子救出来了吗？」; cash: 「不记得了」,
  [出塔后余额 | 这次多了多少] (balance only when the cash before is unknown), inline keypad,
  「进塔前 18,500 → 出塔后 25,050 日元（+6,550）」 once typed; 「记一下主角等级」; [没去成] [保存].
- RankUpBurst: 「RANK UP!!」 + 「皇帝 · RANK 7」 after a recorded rank-up, tap to skip, then the sheet
  closes.
- Removed: the persona chip, location and answers, 「现在是 RANK 5，这次见面预计会升级。」,
  「只加好感，不直接升级。」, 「羁绊剧情不加社群点数。」, the 「游戏提示下次就能升级」 switch card,
  「没见到」, 「这个时段没能照安排做？」 and its caption, 「完成了，和预计一样」,
  「剧情原因暂时升不了」, 「逐项核对」, 「点这里输入」, the tower cash explanations and 「（可选）」,
  the full-screen saving overlay, the full sheet outline.

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
