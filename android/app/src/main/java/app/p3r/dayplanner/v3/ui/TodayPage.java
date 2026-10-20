package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.Intents;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.StoryDisplay;

/**
 * 今天 tab, after the game's calendar HUD: the date plate, at most one slim banner (return,
 * error or 从头重新安排), the borderless resource band (tap → 修改进度), the hero card for the next
 * slot (kind tag, title, the rank line with one 「预计」 tag, 人格面具 reminder, location, real
 * answers, 顺便, an urgent reason only, [做不了…] [完成]), 今天别错过, 今天可做, the rest of today
 * and tomorrow, and the goals that are in trouble. Once Jan 31 is recorded the hero becomes a closing card.
 */
final class TodayPage {
    /** 接下来 covers the rest of today and this many more days. */
    private static final int LOOKAHEAD_DAYS = 1;

    private final HomeHost host;
    private final Context c;
    private final FrameLayout root;
    private final ScrollView scroll;
    private final LinearLayout column;
    private int bottomInset;
    /** The hero card's button row (null when the hero has none); the undo bar keeps clear of it. */
    private View heroButtons;
    /** Slot the hero showed last time (-1 before the first bind): a new one slides in. */
    private int shownSlot = -1;
    private boolean shownOnce;
    private final AvailableActionViews.State availableState = new AvailableActionViews.State();

    TodayPage(HomeHost host) {
        this.host = host;
        this.c = host.activity();
        root = new FrameLayout(c);
        ImageView art = new ImageView(c);
        art.setImageResource(R.drawable.ui_art_protagonist);
        art.setAlpha(0.3f);
        art.setScaleType(ImageView.ScaleType.FIT_END);
        art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams artParams = new FrameLayout.LayoutParams(Ui.dp(c, 220), Ui.dp(c, 300),
            Gravity.TOP | Gravity.END);
        root.addView(art, artParams);
        scroll = new ScrollView(c);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        column = Ui.column(c);
        Ui.pad(column, Ui.GUTTER, 8, Ui.GUTTER, 24);
        scroll.addView(column, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
    }

    View view() {
        return root;
    }

    /** The hero card's [做不了…] [完成] row, or null. */
    View heroButtons() {
        return heroButtons;
    }

    void setBottomInset(int px) {
        bottomInset = px;
        scroll.setPadding(0, 0, 0, px);
    }

    /**
     * Rebuilds the page for {@code d}. A new next step scrolls back to the top and slides its hero
     * in; the first show cascades the page in; any other rebind keeps the scroll position.
     */
    void bind(UiData d) {
        int y = scroll.getScrollY();
        column.removeAllViews();
        heroButtons = null;
        if (!d.hasState()) {
            buildWelcome();
            shownSlot = -1;
            if (!shownOnce) {
                Motion.cascade(column);
            }
            shownOnce = true;
            return;
        }
        SimState obs = d.observed();
        Rules rules = d.rules;
        boolean finished = Slots.finished(rules, obs) && d.snap.storyDisplay == null;
        int slot = finished ? rules.lastSlot() : d.displaySlot();
        DatePlate plate = new DatePlate(c, true);
        plate.bind(rules, slot);
        LinearLayout.LayoutParams plateParams = Ui.matchWrap();
        plateParams.topMargin = Ui.dp(c, 8);
        column.addView(plate, plateParams);
        // An ordinary replan: just the stripe under the date, no words. It sits in the gap above the
        // next block and takes no room (its negative bottom margin), so nothing below moves when it
        // comes and goes; the first plan has the hero's own wait.
        if (d.snap.planning && !d.snap.fullReplanning && (finished || d.current() != null)) {
            LinearLayout.LayoutParams stripeParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(c, BusyStripe.HEIGHT_DP));
            stripeParams.topMargin = Ui.dp(c, 6);
            stripeParams.bottomMargin = -(stripeParams.topMargin + stripeParams.height);
            column.addView(new BusyStripe(c), stripeParams);
        }

        if (d.returnBanner) {
            column.addView(returnBanner(d), spaced(14));
        }
        View status = statusBanner(d);
        if (status != null) {
            column.addView(status, spaced(14));
        }
        column.addView(resources(d), spaced(16));
        View hero = finished ? finishedCard(d) : hero(d);
        column.addView(hero, spaced(18));
        // 今天别错过: the day's 当日提醒 still to come, under the hero.
        View reminders = finished ? null : ReminderViews.todayBlock(host, d);
        if (reminders != null) {
            column.addView(reminders, spaced(18));
        }
        View available = finished ? null : AvailableActionViews.today(host, d, availableState);
        if (available != null) {
            column.addView(available, spaced(22));
        }
        View upcoming = upcoming(d);
        if (upcoming != null) {
            column.addView(upcoming, spaced(22));
        }
        View goals = finished ? null : goals(d);
        if (goals != null) {
            column.addView(goals, spaced(18));
        }
        boolean moved = shownSlot >= 0 && shownSlot != slot;
        if (moved) {
            scroll.scrollTo(0, 0);
            Motion.swapIn(hero);
        } else if (!shownOnce) {
            Motion.cascade(column);
        } else {
            scroll.post(() -> scroll.scrollTo(0, y));
        }
        shownSlot = slot;
        shownOnce = true;
    }

    private LinearLayout.LayoutParams spaced(int topDp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, topDp);
        return p;
    }

    // ------------------------------------------------------------------ welcome (no state yet)

    private void buildWelcome() {
        column.addView(Ui.gap(c, 120));
        // The serif S.E.E.S. mark of the widget, as on the armbands.
        ImageView mark = new ImageView(c);
        mark.setImageResource(R.drawable.ui_logo_sees);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        column.addView(mark, new LinearLayout.LayoutParams(Ui.dp(c, 120), Ui.dp(c, 34)));
        TextView title = Ui.text(c, "从现在的存档开始", 32, Ui.col(c, R.color.p3r_white), true);
        title.setShadowLayer(0.01f, Ui.dp(c, 2), Ui.dp(c, 2), Ui.alpha(Ui.col(c, R.color.p3r_royal_500), 200));
        column.addView(title, spaced(6));
        TextView body = Ui.body(c, "填好游戏进度和想升的社群，每天做什么就排好了。");
        body.setLineSpacing(0f, 1.3f);
        column.addView(body, spaced(12));
        TextView start = Ui.primary(c, "开始设置");
        start.setOnClickListener(v -> host.openState("onboarding"));
        column.addView(start, spaced(32));
    }

    // ------------------------------------------------------------------ banners

    /** 「之后又玩了吗？」 when the last record is old: [没有] keeps going, [同步现状] catches up. */
    private View returnBanner(UiData d) {
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.card(c).accent(Ui.col(c, R.color.ui_gold), Ui.dp(c, 4)));
        Ui.pad(card, 18, 14, 14, 12);
        TextView text = Ui.text(c, "之后又玩了吗？", 17, Ui.col(c, R.color.p3r_white), true);
        card.addView(text);
        String when = Zh.lastRecorded(d.rules, d.currentSlot());
        String age = Zh.age(d.nowMillis, d.snap.lastRecordMillis);
        TextView sub = Ui.mixed(c, "上次记到 " + when + " · " + age, 13, Ui.col(c, R.color.p3r_ink_muted), false);
        card.addView(sub, spaced(2));
        LinearLayout buttons = Ui.row(c);
        TextView same = Ui.secondary(c, "没有");
        same.setOnClickListener(v -> host.dismissReturnBanner());
        buttons.addView(same, Ui.weight(1));
        TextView sync = Ui.primary(c, "同步现状");
        sync.setOnClickListener(v -> host.openState("sync"));
        LinearLayout.LayoutParams syncParams = Ui.weight(1.3f);
        syncParams.leftMargin = Ui.dp(c, 12);
        buttons.addView(sync, syncParams);
        card.addView(buttons, spaced(12));
        return card;
    }

    /** 从头重新安排 running, or a replan that failed while the old plan stays; null otherwise. */
    private View statusBanner(UiData d) {
        if (d.snap.fullReplanning) {
            // 从头重新安排 (the plan may still be current): recording goes on as usual meanwhile.
            LinearLayout box = Ui.column(c);
            LinearLayout row = Ui.row(c);
            row.addView(Ui.text(c, FullReplanSheet.runningText(d), 15, Ui.col(c, R.color.p3r_cyan_300), true),
                Ui.weight(1));
            TextView stop = Ui.quiet(c, STOP_LABEL);
            stop.setOnClickListener(v -> host.cancelFullReplan());
            row.addView(stop, Ui.wrap());
            box.addView(row);
            box.addView(new BusyStripe(c), spaced(4));
            return box;
        }
        if (d.snap.planErrorZh != null && !d.snap.planning && d.plan() != null) {
            LinearLayout card = Ui.row(c);
            card.setBackground(Ui.card(c).accent(Ui.col(c, R.color.p3r_danger), Ui.dp(c, 4)));
            Ui.pad(card, 18, 8, 8, 8);
            TextView text = Ui.text(c, PLAN_FAILED, 14, Ui.col(c, R.color.p3r_white), true);
            card.addView(text, Ui.weight(1));
            TextView retry = Ui.quiet(c, "重试");
            retry.setOnClickListener(v -> host.requestReplan());
            card.addView(retry, Ui.wrap());
            return card;
        }
        return null;
    }

    /** Stops a running 从头重新安排. */
    static final String STOP_LABEL = "停下";
    /** A replan failed and the plan before it stays. */
    static final String PLAN_FAILED = "新安排没排出来，先按原来的走";

    // ------------------------------------------------------------------ resources

    /**
     * The resource band: 现金 and the three 个人能力值 in big numerals, the open tower block below;
     * tapping it opens 修改进度.
     */
    private View resources(UiData d) {
        SimState obs = d.observed();
        Rules rules = d.rules;
        LinearLayout band = Ui.column(c);
        band.setBackground(Ui.band(c).pressedFill(Ui.col(c, R.color.ui_cyan_faint)));
        Ui.pad(band, 18, 12, 16, 12);
        // Cash has its own full-width line: seven digits with separators must never fight the
        // three ability cells for space, including at the player's larger system font size.
        LinearLayout cashRow = Ui.row(c);
        if (obs.cashExact || obs.cash > 0) {
            String cash = Zh.number(obs.cash) + (obs.cashExact ? "" : "+");
            cashRow.addView(cashCell(cash, "日元"), Ui.weight(1));
        } else {
            cashRow.addView(cashCell("—", null), Ui.weight(1));
        }
        band.addView(cashRow);
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.TOP);
        for (int i = 0; i < Math.min(3, rules.stats.length); i++) {
            int lv = rules.stats[i].levelFor(obs.statPts[i]);
            row.addView(cell(rules.stats[i].zh, Zh.lv(lv), null), Ui.weight(1));
        }
        band.addView(row, spaced(6));
        View tower = towerLine(d);
        if (tower != null) {
            View line = Ui.fadingRule(c);
            LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, Ui.dp(c, 1)));
            lineParams.topMargin = Ui.dp(c, 10);
            lineParams.bottomMargin = Ui.dp(c, 8);
            band.addView(line, lineParams);
            band.addView(tower);
        }
        band.setClickable(true);
        band.setFocusable(true);
        band.setOnClickListener(v -> host.openState("edit"));
        // TalkBack reads the cells, then what a double tap does.
        band.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View v, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(v, info);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,
                    EDIT_LABEL));
            }
        });
        return band;
    }

    /** What tapping the resource band does. */
    static final String EDIT_LABEL = "修改进度";

    /** Full-width balance line; the autosize only matters at unusually large system font sizes. */
    private View cashCell(String value, String unit) {
        LinearLayout cell = Ui.row(c);
        cell.addView(Ui.text(c, "现金", 13, Ui.col(c, R.color.p3r_ink_muted), false), Ui.wrap());
        TextView number = Ui.numeral(c, value, 30, Ui.col(c, R.color.p3r_white));
        number.setMaxLines(1);
        number.setHorizontallyScrolling(false);
        number.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        number.setAutoSizeTextTypeUniformWithConfiguration(20, 30, 1, TypedValue.COMPLEX_UNIT_SP);
        // The italic face's last glyph extends slightly beyond its advance width.
        number.setPadding(Ui.dp(c, 8), Ui.dp(c, 2), Ui.dp(c, 5), Ui.dp(c, 2));
        number.setTag("today-cash-number");
        cell.addView(number, Ui.weight(1));
        if (unit != null) {
            TextView suffix = Ui.text(c, unit, 12, Ui.col(c, R.color.p3r_cyan_300), true);
            LinearLayout.LayoutParams up = Ui.wrap();
            up.leftMargin = Ui.dp(c, 2);
            cell.addView(suffix, up);
        }
        cell.setContentDescription("现金 " + ("—".equals(value) ? CashPad.UNKNOWN_LABEL
            : value + (unit == null ? "" : unit)));
        return cell;
    }

    /** A resource cell: caption over a big numeral (and a small unit). */
    private View cell(String label, String value, String unit) {
        LinearLayout cell = Ui.column(c);
        TextView caption = Ui.text(c, label, 13, Ui.col(c, R.color.p3r_ink_muted), false);
        cell.addView(caption);
        LinearLayout valueRow = Ui.row(c);
        valueRow.setGravity(Gravity.BOTTOM);
        TextView number = Ui.numeral(c, value, 26, Ui.col(c, R.color.p3r_white));
        valueRow.addView(number, Ui.wrap());
        if (unit != null) {
            TextView u = Ui.text(c, unit, 12, Ui.col(c, R.color.p3r_cyan_300), true);
            LinearLayout.LayoutParams up = Ui.wrap();
            up.leftMargin = Ui.dp(c, 3);
            up.bottomMargin = Ui.dp(c, 2);
            valueRow.addView(u, up);
        }
        cell.addView(valueRow);
        // 「—」 is the wizard's 不记得 (as StateSteps.resourceCell says it).
        cell.setContentDescription(label + " " + ("—".equals(value) ? CashPad.UNKNOWN_LABEL
            : value + (unit == null ? "" : unit)));
        return cell;
    }

    /** 「提齐亚 · 去过 1/4 次」 with 「最高 96F」; null when no block is open. */
    private View towerLine(UiData d) {
        SimState obs = d.observed();
        Rules.Block block = d.rules.blockOn(Rules.dayOf(obs.slot));
        if (block == null) {
            return null;
        }
        Profile profile = d.profile();
        LinearLayout row = Ui.row(c);
        row.addView(Ui.icon(c, R.drawable.ui_ic_tower, Ui.col(c, R.color.p3r_cyan_400), 18));
        int visits = block.idx < obs.towerVisits.length ? obs.towerVisits[block.idx] : 0;
        String text = towerText(block.zh, visits, profile.capFor(block.id));
        TextView label = Ui.mixed(c, text, 14, Ui.col(c, R.color.p3r_ice_100), false);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams p = Ui.weight(1);
        p.leftMargin = Ui.dp(c, 8);
        row.addView(label, p);
        if (obs.floor > 0) {
            LinearLayout floor = Ui.row(c);
            floor.setGravity(Gravity.BOTTOM);
            floor.addView(Ui.text(c, FLOOR_LABEL, 13, Ui.col(c, R.color.p3r_ink_muted), false), Ui.wrap());
            TextView number = Ui.numeral(c, obs.floor + "F", 18, Ui.col(c, R.color.p3r_cyan_300));
            LinearLayout.LayoutParams np = Ui.wrap();
            np.leftMargin = Ui.dp(c, 4);
            floor.addView(number, np);
            floor.setContentDescription(FLOOR_LABEL + " " + obs.floor + "F");
            LinearLayout.LayoutParams fp = Ui.wrap();
            fp.leftMargin = Ui.dp(c, 8);
            row.addView(floor, fp);
        }
        return row;
    }

    /** The tower line beside the tower icon: 「提齐亚 · 去过 1/4 次」 (block, visits this block / cap). */
    static String towerText(String blockZh, int visits, int cap) {
        return blockZh + " · 去过 " + visits + "/" + cap + " 次";
    }

    /** Caption beside the highest floor reached ("最高 96F"). */
    static final String FLOOR_LABEL = "最高";

    // ------------------------------------------------------------------ hero

    private View hero(UiData d) {
        Plan.Step step = d.current();
        LinearLayout card = Ui.column(c);
        Ui.pad(card, 22, 16, 18, 18);
        if (step == null) {
            card.setBackground(Ui.heroCard(c));
            boolean failed = d.snap.planErrorZh != null && !d.snap.planning && !d.snap.fullReplanning;
            if (failed) {
                card.addView(Ui.headline(c, "安排没排出来"));
                TextView retry = Ui.primary(c, "重试");
                retry.setOnClickListener(v -> host.requestReplan());
                card.addView(retry, spaced(14));
            } else {
                card.addView(Ui.headline(c, "正在安排到1月31日…"));
                card.addView(new BusyStripe(c), spaced(14));
            }
            return card;
        }
        StepKind kind = StepKind.of(step);
        card.setBackground(heroBackground(step));
        LinearLayout top = Ui.row(c);
        top.addView(kindTag(c, kind));
        View spacer = new View(c);
        top.addView(spacer, Ui.weight(1));
        if (kind != StepKind.LOCKED) {
            TextView more = Ui.text(c, "详情 ›", 14, Ui.col(c, R.color.p3r_cyan_200), true);
            more.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            top.addView(more, Ui.wrap());
        }
        card.addView(top);

        TextView title = Ui.mixed(c, SlotRows.title(d.rules, step), 26, Ui.col(c, R.color.p3r_white), true);
        card.addView(title, spaced(10));
        String subtitle = heroSubtitle(step, kind);
        if (kind == StepKind.LOCKED) {
            card.addView(Ui.mixed(c, lockedNote(step), 15, Ui.col(c, R.color.p3r_ink_muted), false), spaced(4));
        } else if (!subtitle.isEmpty()) {
            LinearLayout subRow = Ui.row(c);
            TextView sub = Ui.mixed(c, subtitle, 21, Ui.col(c, R.color.p3r_cyan_400), true);
            subRow.addView(sub, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (step.estimate) {
                TextView est = Ui.tag(c, "预计", Ui.alpha(Ui.col(c, R.color.p3r_cyan_200), 40),
                    Ui.col(c, R.color.p3r_cyan_200));
                LinearLayout.LayoutParams ep = Ui.wrap();
                ep.leftMargin = Ui.dp(c, 10);
                subRow.addView(est, ep);
            }
            card.addView(subRow, spaced(2));
        }
        if (StepViews.hasHint(step) && kind != StepKind.LOCKED) {
            card.addView(StepViews.hintChip(c, step.hintZh), spaced(12));
        }
        if (step.locationZh != null && !step.locationZh.isEmpty()) {
            LinearLayout loc = Ui.row(c);
            loc.addView(Ui.icon(c, R.drawable.ui_ic_pin, Ui.col(c, R.color.p3r_cyan_300), 16));
            TextView where = Ui.text(c, step.locationZh, 15, Ui.col(c, R.color.p3r_ice_100), false);
            LinearLayout.LayoutParams wp = Ui.weight(1);
            wp.leftMargin = Ui.dp(c, 6);
            loc.addView(where, wp);
            card.addView(loc, spaced(12));
        }
        // The scene's answers; a meeting that may not rank up gets the next scene's, muted.
        String dialogue = Dialogue.shown(Dialogue.line(d.rules, d.profile(), step));
        if (dialogue != null) {
            int ink = Dialogue.conditional(dialogue) ? R.color.p3r_ink_muted : R.color.p3r_ice_100;
            TextView options = Ui.text(c, dialogue, 15, Ui.col(c, ink), false);
            card.addView(options, spaced(8));
        }
        if (StepViews.hasSide(step)) {
            card.addView(StepViews.sideBlock(c, step, 14), spaced(8));
        }
        String reason = urgentReason(step.whyZh);
        if (reason != null) {
            LinearLayout urgent = Ui.row(c);
            urgent.setGravity(Gravity.TOP);
            TextView mark = Ui.numeral(c, "!", 16, Ui.col(c, R.color.ui_gold));
            urgent.addView(mark, Ui.wrap());
            TextView why = Ui.mixed(c, reason, 14, Ui.col(c, R.color.ui_gold), false);
            LinearLayout.LayoutParams rp = Ui.weight(1);
            rp.leftMargin = Ui.dp(c, 8);
            urgent.addView(why, rp);
            card.addView(urgent, spaced(10));
        }
        heroButtons = heroButtons(step, kind);
        card.addView(heroButtons, spaced(18));
        card.setClickable(true);
        card.setOnClickListener(v -> host.openDetail(step.slot));
        return card;
    }

    /** The hero panel; a rank-up carries its target rank as a huge faint numeral, like the widget's day. */
    private android.graphics.drawable.Drawable heroBackground(Plan.Step step) {
        SlantedPanelDrawable panel = Ui.heroCard(c);
        if (step.predicted == null || !step.predicted.rankUp || step.predicted.rankTo <= 0) {
            return panel;
        }
        String mark = step.predicted.rankTo >= 10 ? "MAX" : String.valueOf(step.predicted.rankTo);
        WatermarkDrawable number = new WatermarkDrawable(mark, Ui.numerals(c),
            Ui.alpha(Ui.col(c, R.color.p3r_cyan_300), 40), Ui.dp(c, mark.length() > 1 ? 120 : 190),
            Ui.dp(c, 2), 0f, mark.length() > 1 ? 0.5f : 0.68f);
        return new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{panel, number});
    }

    /**
     * The hero's second line: the engine's subtitle without the 「≈」 (the hero has its 「预计」 tag)
     * and without the 「（预计不升级）」 of an ordinary meeting; nothing for a free slot.
     */
    static String heroSubtitle(Plan.Step step, StepKind kind) {
        if (kind == StepKind.FREE) {
            return "";
        }
        String sub = step.subtitleZh == null ? "" : step.subtitleZh.trim();
        if (sub.startsWith("≈")) {
            sub = sub.substring(1).trim();
        }
        return sub.replace("见面（预计不升级）", "见面");
    }

    /**
     * The one reason worth a line on the hero: a deadline or a last chance, someone who is only
     * around on some days, a stat an unlock needs, or a story hold (「要等剧情推进才能升级…」,
     * 「6月20日前升不了级…」); null for every reason that only explains the plan.
     */
    static String urgentReason(String why) {
        if (why == null || why.trim().isEmpty()) {
            return null;
        }
        String text = why.trim();
        boolean urgent = text.contains("最后") || text.contains("期限") || text.contains("只在")
            || text.contains("机会不多") || text.contains("日子不多") || text.contains("晚上不多")
            || (text.contains("需要") && text.contains("达到")) || text.contains("暂时无法升级")
            || text.contains("要等剧情") || text.contains("升不了级") || text.contains("短信后赴约");
        return urgent ? text : null;
    }

    /**
     * The hero line under a story slot: the step's own note when it has one (a reserved slot such
     * as 1月21日 explains why it is kept free), else 「这段时间不能自由行动」.
     */
    static String lockedNote(Plan.Step step) {
        String note = Zh.clause(step.subtitleZh);
        return note.isEmpty() ? "这段时间不能自由行动" : note;
    }

    /** Shown instead of the hero once every slot up to Jan 31 is recorded. */
    private View finishedCard(UiData d) {
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.heroCard(c));
        Ui.pad(card, 22, 18, 18, 20);
        card.addView(Ui.text(c, FINISHED_TITLE, 24, Ui.col(c, R.color.p3r_white), true));
        SimState obs = d.observed();
        Profile profile = d.profile();
        int maxed = 0;
        int goals = 0;
        int goalsMaxed = 0;
        for (int i = 0; i < Math.min(obs.rank.length, d.rules.links.length); i++) {
            boolean max = obs.rank[i] >= 10;
            if (max) {
                maxed++;
            }
            if (profile.isGoal(i)) {
                goals++;
                if (max) {
                    goalsMaxed++;
                }
            }
        }
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.BOTTOM);
        row.addView(Ui.numeral(c, String.valueOf(maxed), 72, Ui.col(c, R.color.p3r_cyan_400)), Ui.wrap());
        LinearLayout labels = Ui.column(c);
        labels.addView(Ui.mixed(c, "MAX 社群", 17, Ui.col(c, R.color.p3r_white), true));
        if (goals > 0) {
            labels.addView(Ui.mixed(c, "目标 " + goalsMaxed + "/" + goals, 15, Ui.col(c, R.color.p3r_cyan_200), true));
        }
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.leftMargin = Ui.dp(c, 12);
        lp.bottomMargin = Ui.dp(c, 10);
        row.addView(labels, lp);
        row.setContentDescription("升到 MAX 的社群 " + maxed + " 个" + (goals > 0 ? "，目标 " + goalsMaxed + "/" + goals : ""));
        card.addView(row, spaced(8));
        return card;
    }

    /** Title of the closing card after Jan 31. */
    static final String FINISHED_TITLE = "日程全部结束，辛苦了！";

    private View heroButtons(Plan.Step step, StepKind kind) {
        LinearLayout row = Ui.row(c);
        row.setBaselineAligned(false);
        if (kind == StepKind.LOCKED) {
            boolean pendingStory = host.data().snap.storyDisplay != null
                    && host.data().snap.storyDisplay.cursorSlot == step.slot;
            TextView go = Ui.primary(c, pendingStory ? StoryDisplay.CONTINUE_LABEL : "继续");
            go.setContentDescription("剧情结束，继续到下一个时段");
            go.setOnClickListener(v -> host.commitDirect(step));
            row.addView(go, Ui.weight(1));
            return row;
        }
        // One short label; the sheet it opens offers the reasons and 「改做别的…」.
        TextView cannot = Ui.secondary(c, CANNOT_LABEL);
        // One line at any size: on a narrow phone with large text the label shrinks a little
        // instead of breaking 「做不了…」 in two.
        cannot.setMaxLines(1);
        cannot.setAutoSizeTextTypeUniformWithConfiguration(12, 15, 1, TypedValue.COMPLEX_UNIT_SP);
        cannot.setContentDescription("做不了，选原因或改做别的");
        Ui.pad(cannot, 10, 8, 10, 8);
        cannot.setOnClickListener(v -> host.openResult(step.slot, step.action, Intents.MODE_CANNOT));
        row.addView(cannot, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        TextView done = Ui.primary(c, DONE_LABEL);
        done.setContentDescription("完成，记录结果");
        done.setOnClickListener(v -> {
            if (StepKind.oneTap(step)) {
                host.commitDirect(step);
            } else {
                host.openResult(step.slot, step.action, Intents.MODE_RESULT);
            }
        });
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.6f);
        doneParams.leftMargin = Ui.dp(c, 14);
        row.addView(done, doneParams);
        return row;
    }

    /** The hero's secondary button (as 「其他结果…」「改做别的…」: "…" opens more choices). */
    static final String CANNOT_LABEL = "做不了…";
    /** The hero's primary button, as on the widget. */
    static final String DONE_LABEL = "完成 ✓";

    /** The slanted cyan kind tag of a step: icon + 「社群」. */
    static View kindTag(Context c, StepKind kind) {
        LinearLayout tag = Ui.row(c);
        tag.addView(Ui.icon(c, kind.iconRes, Ui.col(c, R.color.p3r_navy_950), 15));
        TextView label = Ui.text(c, kind == StepKind.NOTE ? "社群" : kind.tagZh, 13, Ui.col(c, R.color.p3r_navy_950), true);
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.leftMargin = Ui.dp(c, 4);
        tag.addView(label, lp);
        tag.setBackground(new SlantedPanelDrawable().fill(Ui.col(c, R.color.p3r_cyan_400)).skew(Ui.dp(c, 5)));
        Ui.pad(tag, 10, 3, 12, 3);
        return tag;
    }

    // ------------------------------------------------------------------ upcoming

    /**
     * 接下来: the rest of today and tomorrow as one list under white slanted day plates; a day's
     * identical story locks are one 「全天」 row. Ends with 「全部日程 ›」.
     */
    private View upcoming(UiData d) {
        Plan plan = d.displayPlan();
        Plan.Step current = d.current();
        if (plan == null || current == null) {
            return null;
        }
        int firstDay = Rules.dayOf(current.slot);
        int lastSlot = Math.min(plan.toSlot, Rules.slot(firstDay + LOOKAHEAD_DAYS, true));
        LinearLayout box = Ui.column(c);
        box.addView(Ui.sectionHeader(c, "接下来", null));
        int shownDay = -1;
        int rows = 0;
        for (int slot = current.slot + 1; slot <= lastSlot; slot++) {
            Plan.Step step = plan.at(slot);
            if (step == null) {
                continue;
            }
            int day = Rules.dayOf(slot);
            if (day != shownDay) {
                shownDay = day;
                String head = day == firstDay ? "今天" : Zh.dayAndWeekday(day);
                TextView plate = Ui.tag(c, Ui.latin(c, head), Ui.col(c, R.color.p3r_white), Ui.col(c, R.color.p3r_navy_950));
                LinearLayout.LayoutParams pp = Ui.wrap();
                pp.topMargin = Ui.dp(c, rows == 0 ? 6 : 14);
                box.addView(plate, pp);
            }
            boolean allDay = false;
            if (!Rules.isEvening(slot) && slot + 1 <= lastSlot && StepKind.of(step) == StepKind.LOCKED) {
                Plan.Step evening = plan.at(slot + 1);
                allDay = evening != null && StepKind.of(evening) == StepKind.LOCKED
                    && SlotRows.title(d.rules, evening).equals(SlotRows.title(d.rules, step));
            }
            SlotRows.Line line = SlotRows.line(c, d.rules, step, SlotRows.State.FUTURE, true);
            if (allDay) {
                line.setPeriod("全天");
            }
            int reminders = ReminderViews.countAt(plan, slot) + (allDay ? ReminderViews.countAt(plan, slot + 1) : 0);
            TextView marker = ReminderViews.marker(c, reminders);
            if (marker != null) {
                LinearLayout.LayoutParams mp = Ui.wrap();
                mp.leftMargin = Ui.dp(c, 6);
                line.addView(marker, mp);
                // The row reads out its own description, not its children's: the count goes into it.
                CharSequence spoken = line.getContentDescription();
                line.setContentDescription((spoken == null ? "" : spoken + "，") + ReminderViews.spokenCount(reminders));
            }
            line.setClickable(true);
            line.setFocusable(true);
            line.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_faint))
                .skew(Ui.dp(c, 6)));
            final int target = slot;
            line.setOnClickListener(v -> host.openDetail(target));
            box.addView(line);
            rows++;
            if (allDay) {
                slot++;
            }
        }
        if (rows == 0) {
            return null;
        }
        TextView all = Ui.quiet(c, "全部日程 ›");
        all.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        all.setOnClickListener(v -> host.selectTab(HomeActivity.TAB_SCHEDULE));
        box.addView(all, Ui.matchWrap());
        return box;
    }

    // ------------------------------------------------------------------ goals

    /**
     * The goals the plan does not reach, as gold lines that open the link's 社群详情; one quiet line
     * (to 社群) when every goal is on track; null without goals.
     */
    private View goals(UiData d) {
        Profile profile = d.profile();
        List<Integer> order = LinkStatus.goalOrder(profile);
        if (order.isEmpty() || d.plan() == null) {
            return null;
        }
        LinearLayout box = Ui.column(c);
        box.addView(Ui.sectionHeader(c, "目标", null));
        for (int link : order) {
            LinkStatus status = LinkStatus.of(d.rules, profile, d.observed(), d.plan(), link);
            String trouble = troubleOf(d.rules, status);
            if (trouble == null) {
                continue;
            }
            TextView line = Ui.mixed(c, trouble + " ›", 15, Ui.col(c, R.color.ui_gold), true);
            line.setMinHeight(Ui.dp(c, 44));
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setClickable(true);
            line.setFocusable(true);
            // Straight to that link's 社群详情, where the fix is.
            final int target = link;
            line.setOnClickListener(v -> host.openLink(target));
            box.addView(line, Ui.matchWrap());
        }
        if (box.getChildCount() == 1) {
            box.removeAllViews();
            TextView ok = Ui.quiet(c, GOALS_OK);
            ok.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            ok.setOnClickListener(v -> host.selectTab(HomeActivity.TAB_LINKS));
            box.addView(ok, Ui.matchWrap());
        }
        return box;
    }

    /** Every goal is reached by the plan. */
    static final String GOALS_OK = "目标都排到了 ›";

    /**
     * 「皇帝 · 没排到 MAX」 / 「女教皇 · 无法继续」 for a goal in trouble, else null; a goal already
     * reached is never in trouble (a rescue missed afterwards changes nothing).
     */
    static String troubleOf(Rules rules, LinkStatus s) {
        String name = rules.links[s.link].arcanaZh;
        if (s.observed >= s.target) {
            return null;
        }
        if (s.lost) {
            return name + " · 无法继续";
        }
        if (s.story || s.targetSlot >= 0 || s.projected >= s.target) {
            return null;
        }
        return name + " · 没排到 " + (s.target >= 10 ? "MAX" : Zh.rank(s.target));
    }
}
