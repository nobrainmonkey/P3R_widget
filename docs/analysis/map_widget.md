# Widget and visual design audit for the P3R planner

## 0. Summary

- **The widget works but doesn't look like P3R.** The logic is careful: stable per-widget PendingIntent identities, revision checks against stale taps, a fade that keeps the old view on screen, and size-aware layouts. The look is a generic rounded card with English "Current/Done" buttons, pink and mauve offset shadows, tiny Unicode dots and line-art motifs too faint to see.
- **The best P3R look in the repo is the legacy WebView guide** (`assets/index.html`). It uses royal blue `#0642b7`, cyan `#78f1ff`, 900-weight condensed italic numerals and skewed tabs. The widget and the native goal-planner screens don't match it.
- **The native goal-planner screens are unstyled.** `GoalPlannerHomeActivity` and `PlayerStateForm` use grey default Material Light buttons on `#051f3c`. Spinners lose their dropdown arrow because `setBackgroundColor(0xffc5e8ff)` replaces it. Month and day are typed into text boxes. Results go into one generic form.
- **This is the user's main input pain point.** The widget's Done button on a goal item sends the user into that generic form, or into the full state editor if the item is in the future (`GoalPlannerHomeActivity.java:61`). Section 8 answers the user's input and deviation question directly.

Real renders already exist: the parallel baseline build wrote Robolectric output to `/Users/xihehan/P3R_Widget/qa_out/` (`p3r_native_final_qa/*.png`, `planner_v2/qa/native_goal_widget/*.png`, `planner_v2/qa/native_home/*.png`). My composites and a rough concept are listed in section 10.

---

## 1. What is live and what is dead

**Live widget resources**
- Layouts: `widget_palette_host.xml` (the host), `widget_final_{day,night}.xml`, `widget_final_compact_{day,night}.xml`, `widget_final_tiny_{day,night}.xml`, and the included `widget_actions_{day,night,compact_day,compact_night}.xml`.
- Drawables: `widget_surface_*`, `widget_day_tile*`, `widget_sees_*`, `widget_sun`, `widget_moon`, `widget_official_p3r_logo` (an inset over `official_p3r_logo_color.png`), `button_spec_return`, `button_spec_check`, `button_spec_flat_*` (a selector, then normal/pressed layer-lists, then back/face, then left/middle/right 9-slice vectors), and 22 `widget_motif_*` (picked at runtime through `getIdentifier`).
- Animations: `widget_palette_in.xml` and `widget_palette_out.xml` only.

**Dead (nothing references them)**
- Layouts: `widget_day.xml`, `widget_night.xml`, `widget_day_compact.xml`, `widget_night_compact.xml`, `widget_scene*.xml` (4 files), `widget_palette_fade.xml`.
- All ten `bg_widget_*` drawables. They are only used by the dead layouts.
- Animations: `widget_art_in`, `widget_art_out`, `widget_fade_in`, `widget_fade_out`. Also dead-layout only.
- `values/widget_brand.xml` (the `widget_wordmark` string).
- `character_day.png` (1.2 MB), `character_night.png` (1.3 MB), `character_day_compact.png` (920 KB). These are about 3.4 MB of unused APK weight. They are good white protagonist silhouettes (an Evoker pose and a reading pose), left over from the paused "scene" design in `android/NATIVE_WIP_CHECKPOINT_2026-10-12.md`.
- `GoalPlannerActivity.java`: an old January-only Sun/Devil activity. It is in the manifest but nothing launches it; only its test does.
- `assets/planner_v2/index.html`: no Java reference.

**Launcher icon:** a flat PNG in each `mipmap-*dpi` folder ("P3R" on blue with a cyan slash). There is no `mipmap-anydpi-v26` adaptive icon and no monochrome layer, so Android 8+ launchers will shrink it inside a white mask.

---

## 2. Palette (exact values)

**Widget surfaces** (`drawable/widget_surface_{day,night}.xml`, 12dp corners)

| Element | Day | Night |
|---|---|---|
| Surface | `#0FB1F5` | `#034572` |
| Primary ink (date, period word, task, progress) | `#1D384A` (5.03:1 on surface) | `#FFFFFF` (10.0:1) |
| ‹ › arrows | `#1D384A` | `#A8E7FF` |
| Day-number tile (parallelogram `M6,2 L32,0 L26,28 L0,30`) | `#B3F3FF`, numeral `#082D67` | `#A8E7FF`, numeral `#082D67` |
| Dialogue / cue / location text | `#1D384A` | `#E5F8FF` / `#D2EFFF` / `#D2E9FF` |
| Dots (XML / code spans) | `#1D384A` | `#D3ECFF`; code sets done/current `0xffa8e7ff`, pending `0xffa1b9d3` (`PlannerWidget.java:140`) |
| Dividers | `#55D4F7FF` (33% `#D4F7FF`) | same |
| S.E.E.S. wordmark | `#1D384A` | `#A8E7FF` |
| Sun / moon icon stroke | `#1D384A` (`widget_sun`) | `#B3F3FF` (`widget_moon`) |
| Motif stroke | `#B5EAFF`, alpha 0.03–0.21 (1.88:1 even at full alpha, so effectively invisible) | `#64A7D9`, alpha 0.03–0.21 |

**Buttons** (`button_spec_flat_*`, 4dp corner radius)
- Current: day face `#E6EBF1`, back `#AA8EAA` (mauve); night face `#C5CFDC`, back `#70536D`. Text `#344057`.
- Done: day face `#FFFFFF`, back `#FF5D91` (pink); night face `#F1EFEA`, back `#B83D6D`. Text `#101723`; disabled `0x99101723`.
- Shape: the back layer is inset left 3, top 3, bottom 7. The face is inset top 7, right 3, bottom 3. So the "shadow" sits 3dp right and 4dp up. When pressed, the back moves to top 5 / bottom 5 / right 1.

**Provenance** (`android/SEES_ASSET_SOURCE.txt`): day `#0FB1F5` was sampled from the official logo PNG; night `#034572` comes from p3re.jp CSS. A side effect: the logo's cyan "RE" bar disappears on the day surface. You can see it in `qa_out/p3r_native_final_qa/t005_04-320x180.png`.

**Legacy WebView app** (`index.html` `:root`)
- Day: `--bg:#0642b7`, `--surface:#0b52c6`, `--deep:#072d91`, `--navy:#062676`, `--cyan:#78f1ff`, `--aqua:#b0f9ff`, `--ink:#fff`, `--muted:#b7dafc`, `--line:#478ddb`.
- Night: `#081c47` / `#103777` / `#81e7ff`.
- Display font stack: 'Arial Narrow', 'Roboto Condensed', at weight 900 italic.

**Native goal UI**
- Root `#051f3c`, white text.
- EditText tint `#9bdbff`; Spinner background `#c5e8ff` (no arrow).
- Section header buttons: tint `#9bdbff`, text `#06263e`.
- `AppTheme` is `Theme.Material.Light.NoActionBar`, so buttons and AlertDialogs render light grey on the dark screen.
- Status bar `#062D91`, nav bar `#062676`, window `#0642B7` (`values/styles.xml`).

---

## 3. Motif drawables

Each motif is a 600×400 or 360×420 vector made of thin outlines and a few translucent fills. In every layout it sits in a full-size `ImageView` with `scaleType=fitEnd`, so it is right- and bottom-aligned. A test checks that it stays uncropped.

How a motif is chosen:
- `motifIndex` (`PlannerWidget.java:91-96`) runs FNV-1a over `"P3R-lineart-v1:"+date`, mod 11.
- **Bug:** the two widgets use different date keys. The legacy widget passes `Day.date`, which is `"4月8日"`. `GoalPlannerWidget.java:27` passes `"04-08"`. So the same game date shows different motifs in the two widgets.

Day and night pairs, from my renders in `scratchpad/viz/sheet1-3.png`:

| Motif | Day | Night |
|---|---|---|
| clock | clock | identical shape |
| arcana | card stack | identical shape |
| glass | shards | identical shape |
| chronicle | headphones, notebook, ticket | Evoker, clock, card |
| window | window frame | window, moon, spire |
| notebook | lined notebook | framed four-point star |
| headphones | headphones | headphones with equaliser bars |
| calendar | calendar grid | clock in a frame |
| moon | sun over horizon | crescent over horizon |
| tower | school block | Tartarus spire with moon |
| evoker | framed square | Evoker pistol |

Only 8 of the 11 are true day/night pairs; the first three differ only in colour. Stroke widths are 1–5 viewport units scaled by about 0.4, so the effective strokes are 0.4–2dp hairlines. **Verdict:** generic clip-art. At these alphas they add almost nothing, especially on the day surface.

---

## 4. Layouts and size buckets

Full layout tree (`widget_final_day.xml`):
- Root `FrameLayout`, 8dp padding.
  - Motif `ImageView` (line 2).
  - Vertical `LinearLayout`:
    - **Header, 32dp tall.** `widget_brand_wrap` is 38dp wide with the logo. A centred cluster holds: `widget_prev` (20dp wide, text "‹"), `widget_date` (English month, sans-serif-condensed bold italic), `widget_day_number` (28×29 tile), `widget_period_icon` (17dp), `widget_period_word` (昼/夜), `widget_next` (20dp).
    - 1dp divider.
    - **`widget_task_area`** (weight 1): `widget_task` (autosize 11–17sp, bold), `widget_dialogue_choices` (12sp), `widget_detail_cue` (12dp tall, 9sp).
    - **23dp footer row:** `widget_location` (autosize 7–9sp) and a 42dp column with `widget_progress` (10sp) and `widget_dots` (7sp).
    - 1dp divider.
    - **`widget_actions_*`**, 40dp tall: `button_spec_current` (weight 4), an 8dp gap, `button_spec_done` (weight 6), forced `layoutDirection=ltr`.
  - `widget_sees_mark` (30×8.5dp, top-right).
- Compact: 6dp padding, 28dp header, 26dp logo, task autosize maximum 14sp, smaller return icon.
- Tiny: same as compact plus a 32dp brand area, 24dp tile, 14dp icon, 22×6.2 SEES mark.

Size thresholds:
- Legacy (`PlannerWidget.java:114-116`): compact if `w<230 || h<175`; tiny if `w<200`. Header sizes at `w<260`: date 17sp, day 19sp, period word 14/16/20sp.
- Goal (`GoalPlannerWidget.java:27`): tiny if `w<=200`; compact if `h<180`. It ignores the width-230 rule and never sets the period word or task text size.
- Legacy on API 31+ builds a `RemoteViews(Map<SizeF,…>)` from `OPTION_APPWIDGET_SIZES` (at most 12 sizes, `:264-270`). Before 31 it guesses a size: portrait uses minW×maxH, landscape maxW×minH (`:247-255`).
- Goal uses only `MIN_WIDTH`/`MIN_HEIGHT`, with no size map, so it under-estimates height in portrait.

Both `xml/*_widget_info.xml` files: minimum 220×160dp, resizable down to 180×160, target 4×2 cells, `updatePeriodMillis=0`, `previewLayout=@layout/widget_final_day` (shows placeholder "May 2 / 查看今天的行动"). The goal widget's info has no `android:description`.

Layout findings:
- **Header has no spare width at default font scale.** `qa_out/.../layout-metrics.txt` reports "Apr needed=47.0 available=47" and "昼 needed=32 available=32" at every width. A system font scale of 1.15 or more will clip. Weekday and moon phase cannot be added without restructuring the header.
- **`setTextViewTextSize(R.id.widget_task,…)` (`:161`) has no effect.** On API 26+, `TextView.setTextSize` does nothing when autosize is on, and every final layout sets `autoSizeTextType="uniform"` on `widget_task`. Only `setMaxLines` takes effect.
- **Touch targets are too small:** ‹ › are 20×32dp, the brand is 38×32. These arrows are the main browsing control.
- **Text is too small:** dots at 5sp when there are more than 6 tasks (`:145`), location at 7–9sp, cue at 9sp.
- **CJK fake italic:** 昼/夜 are bold|italic, which renders as a synthetic oblique on CJK glyphs.

---

## 5. Current, Done and arrow behaviour

### Legacy guide widget (PlannerWidget + PlannerStore)

**Arrows.**
- ‹ sends `ACTION_PREVIOUS` and › sends `ACTION_NEXT_DATE`. The name is misleading: both call `browseViewed(±1)` (`PlannerStore:206-216`).
- Browsing steps through all 691 tasks across 284 days as one flat list, informational tasks included, and stops at either end.
- Each step bumps `widgetActionRevision`.
- The per-widget cursor lives in the `widgetCursor_<id>` pref.

**Current.**
- `ACTION_NEXT_TODO` calls `jumpCurrent`, which calls `firstPendingGlobally`: the earliest unfinished checkable task.

**Done.**
- `ACTION_COMPLETE` calls `toggleViewed(taskId, expectedDone, actionRevision)` (`:235-251`).
- A stale revision, a different shown task or a done-state mismatch does nothing.
- If the task is already done, Done undoes it and the cursor stays.
- Otherwise `completeViewed` marks the task and moves to `nextPendingAfter`: chronological, wrapping once.
- Done is disabled for `kind` free/auto/note/info (`Task.isCheckable`, `:52`), and its text is dimmed (`PlannerWidget:177-180`).

**Taps elsewhere.**
- The task area, date, day number and brand open `MainActivity` (`ACTION_OPEN_TASK` with `gameDate` and `taskId`), which calls `P3RNative.open(date, taskId)` in the WebView.
- `Task.requiresConfirmation` exists but the widget never reads it, so multi-step tasks are completed directly (`MainActivity:133`).

**Risk: a quick double tap can complete two tasks.**
- The first tap completes task A and re-renders with a fresh PendingIntent for task B.
- If the second tap lands after that re-render, B is completed too.
- Fix: a 600–800ms per-widget debounce on COMPLETE in `toggleViewed`.

### Goal widget (GoalPlannerWidget)

**Items.**
- `items(plan)` (`:10-15`) takes `plan.steps` (skipping `__wait__`) and `plan.adaptive_reservations` that have a `slot`, sorted by slot.
- `kind` is inferred from the action ID string: explore/tartarus → tower, `p3r.stat.` → stats, `job_` → resources, otherwise social.
- Titles come from `action_metadata[id].display_title`, for example "魔术师 · 2→3".

**Arrows and Current.**
- The `NAV` broadcast changes `index` by ±1 over the whole plan horizon. Current sends delta 0, which goes to index 0.
- The cursor is stored in `p3r_goal_widget_cursor` as `index_<id>` and `revision_<id>`, and resets when the plan revision changes.

**Done.**
- Opens `GoalPlannerHomeActivity` with `EXTRA_RESULT=true`. Nothing is completed on the widget.
- `handleWidgetRequest` (`Home:57-64`) checks both revisions.
  - If the item's slot equals the snapshot slot, it opens the generic `resultForm()` with the type and person preselected.
  - A future slot or a `resources` item opens `setup()`, the full state form.

**Other differences from the legacy widget.**
- The footer constant "目标规划 · 保守估计" replaces the location on every card.
- The cue "点击查看步骤 · Done 记录实际结果" also appears on every card.
- Dots are a single "◎".
- Progress is `(index+1)/N` across the whole plan, which is meaningless on a calendar.
- Nothing is wired to the brand or the date.
- The exception fallback inflates a bare `widget_final_day` without the host and only wires `widget_root`.
- Visually it is identical to the legacy widget, so a user who adds both cannot tell them apart.

---

## 6. Day/night fade mechanism

**Host layout.** `widget_palette_host.xml` contains:
- a `ViewFlipper` with `widget_palette_day_slot` and `widget_palette_night_slot`, `animateFirstView=false`, in/out set to `widget_palette_in/out` (220ms linear alpha);
- a sibling `widget_palette_static`.

**Normal update** (`hostViews`, `PlannerWidget:221-241`):
1. Rebuild the day slot with forced-day views, the night slot with forced-night views, and the static slot with views for the actual period.
2. Flipper `INVISIBLE`, static `VISIBLE`.

**Transition update** (when the period changes after a broadcast action):
1. Replace only the incoming slot. The outgoing slot keeps its already laid-out child, which is why the code relies on `RemoteViews` reapply with the same layout ID.
2. Call `setDisplayedChild(night?1:0)` so ViewAnimator runs the crossfade.
3. Flipper `VISIBLE`, static `GONE`.

**Guards and settle.**
- `allowPeriodFade` skips the fade if the last action was less than 300ms ago.
- A generation token is stored per widget.
- After `FADE_SETTLE_MS=1000`, a non-animated update is posted through `goAsync()` and a main Handler. It only runs if the generation still matches.
- `onDeleted` and resize invalidate the token.

**Goal widget.**
- Same idea in `GoalPlannerWidget.hostViews` (`:23`), but it adds the same content `RemoteViews` to all three slots.
- Night is decided by `slot%2==1`.
- `LAST_NIGHT` and `LAST_TIME` are static maps; the settle is posted after 1000ms.
- `goAsync` is only taken on the NAV path (released after 1100ms).

**Visual result.** `qa_out/p3r_native_final_qa/fade-frame-1.png` shows both tasks' text double-exposed in the middle of the fade, which is hard to read.

**Fragility.**
- If a launcher fully re-inflates instead of reapplying (layout ID change, host recreation, some OEM launchers), the outgoing slot is empty and the effect becomes a plain fade-in. That is acceptable.
- The goal widget does SQLite I/O and plan JSON parsing on the main thread in `update()`.

---

## 7. Aesthetic assessment

**What already works:**
- The flat cyan/navy split.
- The slanted day-number tile.
- Bold italic condensed English month.
- The S.E.E.S. wordmark.
- The offset-slab idea on the buttons.

**What doesn't:**
1. **Shapes.** 12dp rounded corners plus rounded buttons read as generic Material. P3R uses sharp parallelograms and diagonal cuts.
2. **Accent colours.** Pink `#FF5D91` and mauve `#AA8EAA` slabs read like Persona 5 or neo-brutalism. P3R's world is blue, cyan and white.
3. **Day mode inverts the identity.** It puts dark ink on cyan, while P3R is mostly white on deep blue. White on `#0FB1F5` is only 2.43:1, so large white text there needs a blue plate behind it (white on `#0642B7` is 8.5:1).
4. **The calendar doesn't look like P3R's.** It shows an English "Apr" and a binary 昼/夜. There is no weekday, no moon phase and no real time-of-day, even though `data.json` periods include 放学后, 深夜 and so on.
5. **Small details look rough:** Unicode ◉●◎○ dots, the ⌖ location glyph, 20dp arrows, and synthetic italic CJK.
6. **The logo** is illegible at 38×26dp and loses its cyan bar on the day surface.
7. **Motifs** are too faint and generic to read as art.
8. **"Current"/"Done" are English** in an app the brief says must be entirely Simplified Chinese.
9. **The app family is inconsistent:** the WebView guide (royal blue, polished), the widget (sky cyan and navy) and the native goal UI (unstyled grey Material Light on `#051f3c`) look like three different products.

---

## 8. Recommended visual changes

These keep the hard requirements: cyan day and deep-blue night surfaces, Current on the left, Done on the right, date-area arrows browsing tasks, and the day/night fade.

**V1. Header becomes a slanted "calendar plate."**
- A left-anchored parallelogram vector drawable: royal `#0642B7` on day, cyan `#0FB1F5` or `#62DCFF` on night. It holds:
  - ‹ (40dp hit area, slanted chevron vector);
  - large numerals "4/21", language-neutral like the in-game HUD, replacing "Apr";
  - a weekday chip "周二", computed from the 2009/2010 calendar, same rule as `GameDate.slot`;
  - a moon-phase glyph;
  - the actual time-of-day word (白天/放学后/晚上/深夜 from `Task.period`; 白天/晚上 for goal slots);
  - › (40dp).
- Right side keeps S.E.E.S. and the day progress "3/8".
- **Moon phase:** derive it from the full-moon anchors in `rules_v04/calendar_global_constraints_candidate.json` (`eventKind:"full_moon_story_event"`: 04-09, 05-09, 06-08, 07-07, 08-06, 09-05, 10-04, 11-03, …), using 8 phases. Treat it as decorative and approximate. On full-moon dates show a filled moon plus "满月"; those nights are story-locked, so this is useful information.
- **Font robustness:** set the numerals with `TypedValue.COMPLEX_UNIT_DIP` or draw them as a vector digit set (10 slanted digits × 2 palettes, original art). This removes the clipping at large font scales and avoids Chinese OEM system-font overrides on MIUI, HyperOS, ColorOS and others.
- Use italic only on Latin text and digits.

**V2. Buttons.**
- Parallelogram vectors in a state-list. Keep the 40dp hit area and the 34dp visible shape (tests rely on these numbers).
- **Done (primary):**
  - Day: white face, royal `#0642B7` offset slab, navy `#082D67` label (13.3:1).
  - Night: `#62DCFF` face, white slab, navy label (8.35:1).
- **Current:** a ghost parallelogram with a 1.5dp outline and transparent fill. Day `#082D67`, night `#A8E7FF`.
- Labels: "当前" and "完成" in bold. Small English "CURRENT"/"DONE" can stay as decoration only if the owner wants it.
- Drop all pink and mauve.

**V3. Background.**
- Keep the flat surface. Add two large translucent diagonal slashes bleeding off the right edge (white at 10–14% on day, 7–10% on night), plus an outlined watermark of the day number on the right, like the in-game calendar pages.
- If the owner keeps the motif system: raise alpha to 0.25–0.35, unify strokes at about 2dp in white, make clock/arcana/glass into real pairs, and use the same date key in both widgets.

**V4. Progress and location.**
- Replace the Unicode dots with up to 8 small `ImageView` ticks (filled = done, thick outline = current, thin = pending; set with `setImageViewResource` and `setViewVisibility`). Fall back to text only above 8.
- Location at 10–11sp minimum, with a pin vector instead of ⌖.
- In the goal widget, show "今日 1/2" rather than "1/57".

**V5. Logo and corners.**
- Show the official logo only at widths of 280dp or more: on the blue plate, or tinted white on day so the cyan bar doesn't vanish. Elsewhere, give that width back to the header.
- On API 31+, use `@android:dimen/system_app_widget_background_radius` and `android:id="@android:id/background"`. Otherwise use corners of 6dp or less.

**V6. Fade without the ghosting.**
- Split the host into a background flipper (surface, slashes, motif) with a 260ms crossfade, and a content flipper that plays out-then-in: out is 120ms alpha to 0 with translateX −4%; in starts at 120ms, runs 200ms, alpha 0→1, translateX +6%→0 with decelerate.
- Keep the generation token, the 300ms throttle, the settle update and the static fallback.

**V7. Goal widget identity.**
- Add a small "目标" tag in a corner.
- Title shows Arcana tag + person + "6→7".
- Drop the constant footer and the instruction cue.
- Mark estimated items with a single hollow tick. Don't put warning badges on every item; the brief forbids that.

**V8. App consistency.**
- Move the native goal UI to a dark theme (`Theme.Material.NoActionBar` with custom colours) in the WebView's royal-blue family: bold italic condensed headings, skewed section tabs, parallelogram primary buttons, chips and steppers instead of Spinners and EditTexts.
- Add an adaptive and monochrome launcher icon.
- Give the goal widget a `description` and a real preview layout.

**V9. Clean-up.** Delete the dead layouts, drawables and animations from section 1 and the unused 3.4 MB of character PNGs. The owner may want to keep the PNGs for a later scene layout, so confirm before deleting.

**Tests that will need deliberate updates:**
- `WidgetNativeRenderTest`: 80px button height, 16px gap, label colours `0xff344057` and `0xff101723`, a "uniform solid surface" pixel check at x=12/mid/w−12 (the diagonal slashes will fail it), wordmark position.
- `WidgetPaletteFadeTest`.
- `WidgetRemoteViewsTest`.
- `GoalPlannerWidgetTest`, which asserts the text "选择目标，从当前存档开始".

---

## 9. Making input easier and handling deviations (the user's question)

**Principles**
1. The predicted result is the default and takes one tap to accept.
2. Ask only about what this action could have changed.
3. Never ask for hidden numbers.
4. Every field has a "?" (unknown) state, and unknown is never zero.
5. Every write can be undone and is idempotent; the existing `command_id`, `pending` receipts and CAS revisions already support this.
6. After saving, show what changed in the plan.

### 9a. Done on the widget opens a quick-result sheet, not the app

Build a translucent dialog-style Activity (`excludeFromRecents`, own `taskAffinity`, `singleTask`) launched by Done's PendingIntent. Pass `plan_revision`, `state_revision`, `slot`, `action_id`, `entity`, `from_rank`, `to_rank`; all already exist in `action_metadata`. RemoteViews cannot take text input, so a sheet is the right size for this.

| Item kind | Main tap | Secondary path | Endpoint |
|---|---|---|---|
| Social rank-up (e.g. 皇帝 6→7) | **[已升到 7]** sets `observed_rank=to_rank` | **[没升级]** (`observed_rank=from_rank`); "其他结果…" opens a 0–10 rank stepper, 正常/逆位, 可升级提示 ✓/✗/? | `/api/observed_social` |
| Reservation (rank-ready unknown) | [升级了] / [没升级，加深了关系] | [没去] | same |
| Tartarus | Balance field with a 当前余额/净变化 toggle (balance by default when cash is unknown); floor stepper prefilled with `highest_floor`; optional protagonist level | "修正上次爬塔" as a link (correction, so no extra visit is counted) | `/api/return` |
| Training / meal / café | **[按预期完成]** with no input | "等级变了？" 1–6 chips; balance prefilled with the expected value | `/api/observed_stats` |
| Job | [按预期完成 · 余额≈¥X] | edit balance | an observed-cash path is needed (today it falls into `setup()`) |

Every sheet also has:
- An end-slot stepper "‹ 4/21 晚上 ›", defaulting to slot+1. This replaces the month/day text boxes in `resultForm`.
- **[没做这项 ▾]** with 改做别的事 / 剧情占用 / 时段已过 / 稍后核对. "Skip" needs a small engine endpoint that advances time with no gains; nothing like that exists today.
- After saving: "已记录 · 撤销 (5s)", then close. The widget refreshes and fades to the next item.

**For future goal items, Done should not dump the user into `setup()`.**
- Either show it disabled with the hint "先完成当前时段", or let Current jump to the item at the current slot.
- Also cache a small widget projection when the plan is computed, so the widget no longer opens SQLite on the main thread.

### 9b. "I'm back after weeks": a catch-up wizard (我玩到哪了)

1. **Date stepper** with large numerals, +1 day / +1 week / calendar picker. Default is the date of the last plan item.
2. **"期间做了这些吗？"** A list of the plan items between the old and new slot, each with ✓/✗/?. The default is "?"; never assume they happened.
3. **Confirm only what was touched.** Links and stats touched by ✓ items appear with the forecast value in a distinct "预测" style. One tap confirms; a stepper adjusts. Anything left at "?" keeps its stored interval.
4. **One required number:** 当前现金.
5. **Review screen:** "将更新 5 项" showing old → new values. Save atomically, replan, then show the plan changes (e.g. "4/24 晚上 → 改为 爬塔").

This meets the brief's "only changed fields need editing" without ever writing forecasts into observed state.

### 9c. Full state editor (separate entry point) redesign

**Current problems:**
- Four accordions of EditTexts and arrowless Spinners.
- 22 full-width "展开／收起 X 的状态" buttons (`PlayerStateForm:50`).
- Numeric priority "数字越大越优先".
- Raw-pixel padding.
- Draft saving keyed by view order (`exportDraft` schema `"2:E,S7,…"`), so any UI reorder silently drops the saved draft.

**Proposed:**
- **Summary card at the top:** date and time, cash, tower stage plus visits as a counter, the three stats. Each value can be edited in place.
- **Stats:** 1–6 plus ? segmented chips.
- **Social Links:** a grid of 22 Arcana tiles (numeral, name, person, gender, from `social_goal_identity_labels.json`) with rank pips and a "?" state. Tapping a tile opens a bottom sheet with a rank stepper and only the relevant extra fields: relationship and romance for priestess/empress/lovers/justice/strength/aeon, and preparation for not-yet-unlocked links (as now).
- **Goals:** a ★ toggle on the tile plus a drag-to-reorder "目标顺序" list; target defaults to MAX (10).
- **Provenance:** a "4/18 核对" stamp on each observed field.
- **Sticky bottom bar:** "已修改 3 项 · 保存并重新规划".
- Key drafts by field ID, not view order.

**Plan list.** Show the plan as a date-grouped day/night timeline in the P3R calendar style. Each row: Arcana tag + "6→7" + estimate marker. Tapping a row opens the detail page with "记录结果".

---

## 10. Files to look at

**Real renders** (from the parallel build):
- `/Users/xihehan/P3R_Widget/qa_out/p3r_native_final_qa/t00{5_01,5_04}-*.png`, `t016_03-*.png`, `fade-frame-{0,1,2}.png`, `layout-metrics.txt`
- `/Users/xihehan/P3R_Widget/qa_out/planner_v2/qa/native_goal_widget/card-250-{0,1,2}.png`
- `/Users/xihehan/P3R_Widget/qa_out/planner_v2/qa/native_home/*.png`

**My composites**, in `/private/tmp/claude-501/-Users-xihehan-P3R-Widget/c55d2fa4-7208-4e25-be91-b3e985a53a3e/scratchpad/viz/`:
- `sheet1.png`, `sheet2.png`, `sheet3.png`: all 22 motifs at widget size.
- `real_320x180.png`, `real_goal.png`, `home_sheet.png`, `fade.png`.
- `logos.png`: the logo on both surfaces.
- `chars2.png`: the unused character art.
- `concept.png`: a rough P3R-styled concept of V1–V3; not production art.
- Some composites have a stray white vertical line from my image-joining step. It is not in the actual renders.

The converter and mock scripts are in `scratchpad/tools/`.