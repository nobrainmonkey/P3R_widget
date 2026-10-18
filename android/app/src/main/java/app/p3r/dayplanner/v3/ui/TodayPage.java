package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
 * 今天 tab: date plate, return banner, replanning status, resource strip, the hero card for the
 * next slot (with its 人格面具 reminder, "顺便" side steps and [做不了…] [完成 ✓]), the rest
 * of today plus the next 3 days, and goal progress chips. Once Jan 31 is recorded the hero card
 * becomes a closing summary.
 */
final class TodayPage {
    private static final int LOOKAHEAD_DAYS = 3;

    private final HomeHost host;
    private final Context c;
    private final FrameLayout root;
    private final ScrollView scroll;
    private final LinearLayout column;
    private int bottomInset;
    /** The hero card's button row (null when the hero has none); the undo bar keeps clear of it. */
    private View heroButtons;

    TodayPage(HomeHost host) {
        this.host = host;
        this.c = host.activity();
        root = new FrameLayout(c);
        ImageView art = new ImageView(c);
        art.setImageResource(R.drawable.ui_art_protagonist);
        art.setAlpha(0.2f);
        art.setScaleType(ImageView.ScaleType.FIT_END);
        art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams artParams = new FrameLayout.LayoutParams(Ui.dp(c, 210), Ui.dp(c, 300),
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

    /** The hero card's [做不了…] [完成 ✓] row, or null. */
    View heroButtons() {
        return heroButtons;
    }

    void setBottomInset(int px) {
        bottomInset = px;
        scroll.setPadding(0, 0, 0, px);
    }

    /** Rebuilds the page for {@code d}, keeping the scroll position. */
    void bind(UiData d) {
        int y = scroll.getScrollY();
        column.removeAllViews();
        heroButtons = null;
        if (!d.hasState()) {
            buildWelcome();
            return;
        }
        SimState obs = d.observed();
        Rules rules = d.rules;
        boolean finished = Slots.finished(rules, obs) && d.snap.storyDisplay == null;
        DatePlate plate = new DatePlate(c, true);
        plate.bind(rules, finished ? rules.lastSlot() : d.displaySlot());
        LinearLayout.LayoutParams plateParams = Ui.matchWrap();
        plateParams.topMargin = Ui.dp(c, 8);
        column.addView(plate, plateParams);

        if (d.returnBanner) {
            column.addView(returnBanner(d), spaced(16));
        }
        View status = statusLine(d);
        if (status != null) {
            column.addView(status, spaced(12));
        }
        column.addView(resources(d), spaced(16));
        column.addView(finished ? finishedCard(d) : hero(d), spaced(20));
        // 今天别错过: the day's 当日提醒 still to come, under the hero.
        View reminders = finished ? null : ReminderViews.todayBlock(host, d);
        if (reminders != null) {
            column.addView(reminders, spaced(20));
        }
        View upcoming = upcoming(d);
        if (upcoming != null) {
            column.addView(upcoming, spaced(24));
        }
        View goals = finished ? null : goals(d);
        if (goals != null) {
            column.addView(goals, spaced(20));
        }
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private LinearLayout.LayoutParams spaced(int topDp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, topDp);
        return p;
    }

    // ------------------------------------------------------------------ welcome (no state yet)

    private void buildWelcome() {
        column.addView(Ui.gap(c, 96));
        TextView title = Ui.text(c, "从现在的存档开始", 28, Ui.col(c, R.color.p3r_white), true);
        column.addView(title);
        TextView body = Ui.body(c, "告诉我游戏里现在的进度和你想要的社群，我会把到1月31日为止的每个"
            + "白天和晚上都排好。之后每做完一件事点一下「完成」，后面的安排会自动跟上。");
        body.setLineSpacing(0f, 1.3f);
        column.addView(body, spaced(12));
        TextView start = Ui.primary(c, "开始设置");
        start.setOnClickListener(v -> host.openState("onboarding"));
        column.addView(start, spaced(28));
        TextView hint = Ui.muted(c, "大约需要 2 分钟，所有内容之后都能修改。");
        column.addView(hint, spaced(12));
        String[][] steps = {
            {"1", "选目标", "想把哪些社群升到 MAX，分「想要」和「必须」。"},
            {"2", "对照游戏填进度", "日期、现金、个人能力值和社群 RANK，点选就行。"},
            {"3", "照着做，点「完成」", "结果和预计不同时说一声，后面的安排会自动调整。"},
        };
        for (String[] s : steps) {
            column.addView(welcomeStep(s[0], s[1], s[2]), spaced(12));
        }
    }

    private View welcomeStep(String number, String title, String body) {
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.TOP);
        row.setBackground(Ui.card(c));
        Ui.pad(row, 16, 12, 16, 12);
        TextView n = Ui.numeral(c, number, 30, Ui.col(c, R.color.p3r_cyan_400));
        n.setMinWidth(Ui.dp(c, 32));
        row.addView(n, Ui.wrap());
        LinearLayout texts = Ui.column(c);
        texts.addView(Ui.text(c, title, 16, Ui.col(c, R.color.p3r_white), true));
        texts.addView(Ui.muted(c, body));
        LinearLayout.LayoutParams tp = Ui.weight(1);
        tp.leftMargin = Ui.dp(c, 8);
        row.addView(texts, tp);
        return row;
    }

    // ------------------------------------------------------------------ banners and status

    private View returnBanner(UiData d) {
        LinearLayout card = Ui.column(c);
        SlantedPanelDrawable bg = Ui.card(c).accent(Ui.col(c, R.color.ui_gold), Ui.dp(c, 4));
        card.setBackground(bg);
        Ui.pad(card, 18, 14, 14, 12);
        String when = Zh.lastRecorded(d.rules, d.currentSlot());
        TextView text = Ui.mixed(c, "上次记录：" + when + " · 游戏进度变了吗？", 15,
            Ui.col(c, R.color.p3r_white), true);
        card.addView(text);
        TextView age = Ui.caption(c, "距离上次记录已经 " + Zh.age(d.nowMillis, d.snap.lastRecordMillis).replace("前", ""));
        card.addView(age, spaced(2));
        LinearLayout buttons = Ui.row(c);
        TextView same = Ui.secondary(c, "没变");
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

    private View statusLine(UiData d) {
        if (d.snap.fullReplanning) {
            // 从头重新安排 (the plan may still be current): recording goes on as usual meanwhile.
            LinearLayout box = Ui.column(c);
            box.addView(Ui.text(c, FullReplanSheet.RUNNING, 14, Ui.col(c, R.color.p3r_cyan_300), true));
            box.addView(new BusyStripe(c), spaced(6));
            box.addView(Ui.caption(c, "可以照常记录，记下的结果会一起算进去"), spaced(6));
            TextView stop = Ui.quiet(c, "先不算了");
            stop.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            stop.setOnClickListener(v -> host.cancelFullReplan());
            box.addView(stop, Ui.wrap());
            return box;
        }
        if (d.snap.planErrorZh != null && !d.snap.planning && !d.snap.fullReplanning) {
            LinearLayout card = Ui.row(c);
            card.setBackground(Ui.card(c).accent(Ui.col(c, R.color.p3r_danger), Ui.dp(c, 4)));
            Ui.pad(card, 18, 10, 8, 10);
            TextView text = Ui.text(c, "这次没能排好：" + d.snap.planErrorZh, 14, Ui.col(c, R.color.p3r_white), false);
            card.addView(text, Ui.weight(1));
            TextView retry = Ui.quiet(c, "重试");
            retry.setOnClickListener(v -> host.requestReplan());
            card.addView(retry, Ui.wrap());
            return card;
        }
        if (d.snap.planning || (!d.snap.planCurrent && d.plan() != null)) {
            LinearLayout box = Ui.column(c);
            TextView text = Ui.text(c, d.snap.planning ? "正在重新安排…" : "安排还没跟上最新记录",
                14, Ui.col(c, R.color.p3r_cyan_300), true);
            box.addView(text);
            if (d.snap.planning) {
                box.addView(new BusyStripe(c), spaced(6));
            } else {
                TextView again = Ui.quiet(c, "重新安排");
                again.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                again.setOnClickListener(v -> host.requestReplan());
                box.addView(again, Ui.wrap());
            }
            return box;
        }
        if (d.changeSummary != null) {
            LinearLayout row = Ui.row(c);
            // A 从头重新安排 that failed or was stopped redid nothing: no check mark for it.
            boolean neutral = PlanDiff.neutral(d.changeSummary);
            row.addView(Ui.icon(c, neutral ? R.drawable.ui_ic_info : R.drawable.ui_ic_check,
                Ui.col(c, neutral ? R.color.p3r_ink_muted : R.color.p3r_good), 18));
            TextView text = Ui.mixed(c, d.changeSummary, 14, Ui.col(c, R.color.p3r_ice_100), false);
            LinearLayout.LayoutParams p = Ui.weight(1);
            p.leftMargin = Ui.dp(c, 8);
            row.addView(text, p);
            return row;
        }
        return null;
    }

    // ------------------------------------------------------------------ resources

    private View resources(UiData d) {
        SimState obs = d.observed();
        Rules rules = d.rules;
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.card(c));
        Ui.pad(card, 16, 12, 16, 12);
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.TOP);
        if (obs.cashExact || obs.cash > 0) {
            String cash = (obs.cashExact ? "" : "≥") + Zh.number(obs.cash);
            row.addView(cell("现金", cash, "日元"), Ui.weight(1.6f));
        } else {
            row.addView(cell("现金", null, "不确定"), Ui.weight(1.6f));
        }
        for (int i = 0; i < Math.min(3, rules.stats.length); i++) {
            int lv = rules.stats[i].levelFor(obs.statPts[i]);
            row.addView(cell(rules.stats[i].zh, Zh.lv(lv), null), Ui.weight(1));
        }
        card.addView(row);
        View line = Ui.divider(c);
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            Math.max(1, Ui.dp(c, 1)));
        lineParams.topMargin = Ui.dp(c, 10);
        lineParams.bottomMargin = Ui.dp(c, 8);
        card.addView(line, lineParams);
        card.addView(towerLine(d));
        card.setContentDescription(null);
        return card;
    }

    /** A resource cell; a null {@code value} shows {@code unit} as plain text ("不确定"). */
    private View cell(String label, String value, String unit) {
        LinearLayout cell = Ui.column(c);
        TextView caption = Ui.text(c, label, 12, Ui.col(c, R.color.p3r_ink_muted), false);
        cell.addView(caption);
        LinearLayout valueRow = Ui.row(c);
        valueRow.setGravity(Gravity.BOTTOM);
        if (value == null) {
            valueRow.addView(Ui.text(c, unit, 16, Ui.col(c, R.color.p3r_ink_muted), true), Ui.wrap());
            cell.addView(valueRow);
            cell.setContentDescription(label + " " + unit);
            return cell;
        }
        TextView number = Ui.numeral(c, value, 24, Ui.col(c, R.color.p3r_white));
        valueRow.addView(number, Ui.wrap());
        if (unit != null) {
            TextView u = Ui.text(c, unit, 11, Ui.col(c, R.color.p3r_cyan_300), true);
            LinearLayout.LayoutParams up = Ui.wrap();
            up.leftMargin = Ui.dp(c, 3);
            up.bottomMargin = Ui.dp(c, 2);
            valueRow.addView(u, up);
        }
        cell.addView(valueRow);
        cell.setContentDescription(label + " " + value + (unit == null ? "" : unit));
        return cell;
    }

    private View towerLine(UiData d) {
        SimState obs = d.observed();
        Rules.Block block = d.rules.blockOn(Rules.dayOf(obs.slot));
        Profile profile = d.profile();
        LinearLayout row = Ui.row(c);
        row.addView(Ui.icon(c, R.drawable.ui_ic_tower, Ui.col(c, R.color.p3r_cyan_400), 18));
        String text;
        if (block == null) {
            text = "塔尔塔罗斯 · 现在没有能探索的区块";
        } else {
            int visits = block.idx < obs.towerVisits.length ? obs.towerVisits[block.idx] : 0;
            text = "塔尔塔罗斯 · " + block.zh + "\n本区块去过 " + visits + " 次，上限 " + profile.capFor(block.id) + " 次";
        }
        TextView label = Ui.mixed(c, text, 13, Ui.col(c, R.color.p3r_ice_100), false);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams p = Ui.weight(1);
        p.leftMargin = Ui.dp(c, 8);
        row.addView(label, p);
        if (obs.floor > 0) {
            // Labelled like the cells above it ("现金", "学力"): "最高" over "96F".
            LinearLayout floor = Ui.column(c);
            floor.setGravity(Gravity.END);
            floor.addView(Ui.text(c, FLOOR_LABEL, 12, Ui.col(c, R.color.p3r_ink_muted), false), Ui.wrap());
            floor.addView(Ui.numeral(c, obs.floor + "F", 16, Ui.col(c, R.color.p3r_cyan_300)), Ui.wrap());
            floor.setContentDescription(FLOOR_LABEL + " " + obs.floor + "F");
            LinearLayout.LayoutParams fp = Ui.wrap();
            fp.leftMargin = Ui.dp(c, 8);
            row.addView(floor, fp);
        }
        return row;
    }

    /**
     * Caption over the highest floor reached in the stats strip; two characters like the other
     * captions, so the tower line beside it still fits at a large font.
     */
    static final String FLOOR_LABEL = "最高";

    // ------------------------------------------------------------------ hero

    private View hero(UiData d) {
        Plan.Step step = d.current();
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.heroCard(c));
        Ui.pad(card, 22, 16, 18, 18);
        if (step == null) {
            TextView text = Ui.headline(c, d.plan() == null ? "正在排到1月31日的安排…" :"正在根据最新记录重新安排…");
            card.addView(text);
            TextView sub = Ui.muted(c, "一般几秒钟就好，可以先去游戏里看看。");
            card.addView(sub, spaced(6));
            card.addView(new BusyStripe(c), spaced(14));
            return card;
        }
        StepKind kind = StepKind.of(step);
        LinearLayout top = Ui.row(c);
        top.addView(kindTag(kind));
        View spacer = new View(c);
        top.addView(spacer, Ui.weight(1));
        TextView next = Ui.text(c, "下一步 · " + Zh.period(d.rules, step.slot), 13,
            Ui.col(c, R.color.p3r_cyan_200), true);
        top.addView(next, Ui.wrap());
        card.addView(top);

        TextView title = Ui.mixed(c, SlotRows.title(d.rules, step), 24, Ui.col(c, R.color.p3r_white), true);
        card.addView(title, spaced(10));
        String subtitle = SlotRows.subtitle(step);
        if (kind == StepKind.LOCKED) {
            String note = lockedNote(step);
            if (d.snap.storyDisplay != null && d.snap.storyDisplay.cursorSlot == step.slot) {
                note = note.replace("点「继续」", "点「" + StoryDisplay.CONTINUE_LABEL + "」");
            }
            card.addView(Ui.mixed(c, note, 14, Ui.col(c, R.color.p3r_ink_muted), false), spaced(4));
        } else if (!subtitle.isEmpty()) {
            LinearLayout subRow = Ui.row(c);
            TextView sub = Ui.mixed(c, subtitle, 20, Ui.col(c, R.color.p3r_cyan_400), true);
            subRow.addView(sub, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (step.estimate) {
                TextView est = Ui.text(c, "预计", 11, Ui.col(c, R.color.p3r_cyan_200), true);
                est.setBackground(new SlantedPanelDrawable().stroke(Ui.col(c, R.color.p3r_cyan_200), Ui.dp(c, 1))
                    .skew(Ui.dp(c, 4)));
                Ui.pad(est, 7, 1, 7, 1);
                LinearLayout.LayoutParams ep = Ui.wrap();
                ep.leftMargin = Ui.dp(c, 10);
                subRow.addView(est, ep);
            }
            card.addView(subRow, spaced(2));
        }
        if (StepViews.hasHint(step) && kind != StepKind.LOCKED) {
            card.addView(StepViews.hintChip(c, step.hintZh), spaced(10));
        }
        if (step.locationZh != null && !step.locationZh.isEmpty()) {
            LinearLayout loc = Ui.row(c);
            loc.addView(Ui.icon(c, R.drawable.ui_ic_pin, Ui.col(c, R.color.p3r_ink_muted), 16));
            TextView where = Ui.text(c, step.locationZh, 14, Ui.col(c, R.color.p3r_ice_100), false);
            LinearLayout.LayoutParams wp = Ui.weight(1);
            wp.leftMargin = Ui.dp(c, 6);
            loc.addView(where, wp);
            card.addView(loc, spaced(10));
        }
        // The scene's answers; a meeting that may not rank up gets the next scene's, muted.
        String dialogue = Dialogue.line(d.rules, d.profile(), step);
        if (dialogue != null) {
            int ink = Dialogue.conditional(dialogue) ? R.color.p3r_ink_muted : R.color.p3r_ice_100;
            TextView options = Ui.text(c, dialogue, 14, Ui.col(c, ink), false);
            card.addView(options, spaced(6));
        }
        if (StepViews.hasSide(step)) {
            card.addView(StepViews.sideBlock(c, step, 14), spaced(8));
        }
        if (step.whyZh != null && !step.whyZh.isEmpty()) {
            TextView why = Ui.mixed(c, step.whyZh, 13, Ui.col(c, R.color.p3r_ink_muted), false);
            card.addView(why, spaced(8));
        }
        if (StepViews.hasHowTo(step)) {
            TextView how = Ui.mixed(c, StepViews.howToLabel(step.detailZh) + " · 点卡片查看", 13,
                Ui.col(c, R.color.p3r_cyan_200), true);
            card.addView(how, spaced(6));
        }
        heroButtons = heroButtons(step, kind);
        card.addView(heroButtons, spaced(18));
        card.setClickable(true);
        card.setOnClickListener(v -> host.openDetail(step.slot));
        return card;
    }

    /**
     * The hero line under a story slot: the step's own note when it has one (a reserved slot such
     * as 1月21日 explains why it is kept free), else the plain "不能自由行动".
     */
    static String lockedNote(Plan.Step step) {
        String note = Zh.clause(step.subtitleZh);
        if (note.isEmpty()) {
            note = "这段时间不能自由行动";
        }
        return note + "。游戏里过完后点「继续」。";
    }

    /** Shown instead of the hero once every slot up to Jan 31 is recorded. */
    private View finishedCard(UiData d) {
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.heroCard(c));
        Ui.pad(card, 22, 18, 18, 20);
        card.addView(Ui.text(c, "日程已经全部结束", 24, Ui.col(c, R.color.p3r_white), true));
        card.addView(Ui.muted(c, "1月31日之前的时段都记录完了，辛苦了！"), spaced(6));
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
        String line = "升到 MAX 的社群：" + maxed + " 个";
        if (goals > 0) {
            line += " · 目标 " + goalsMaxed + "/" + goals + " 个";
        }
        card.addView(Ui.mixed(c, line, 17, Ui.col(c, R.color.p3r_cyan_400), true), spaced(12));
        return card;
    }

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
        cannot.setTextSize(15);
        cannot.setMaxLines(2);
        cannot.setContentDescription("做不了，选原因或改做别的");
        Ui.pad(cannot, 10, 8, 10, 8);
        cannot.setOnClickListener(v -> host.openResult(step.slot, step.action, Intents.MODE_CANNOT));
        row.addView(cannot, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        TextView done = Ui.primary(c, "完成 ✓");
        done.setContentDescription("完成，记录结果");
        done.setOnClickListener(v -> {
            if (StepKind.oneTap(step)) {
                host.commitDirect(step);
            } else {
                host.openResult(step.slot, step.action, Intents.MODE_RESULT);
            }
        });
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        doneParams.leftMargin = Ui.dp(c, 14);
        row.addView(done, doneParams);
        return row;
    }

    /** The hero's secondary button (as 「其他结果…」「改做别的…」: "…" opens more choices). */
    static final String CANNOT_LABEL = "做不了…";

    private View kindTag(StepKind kind) {
        LinearLayout tag = Ui.row(c);
        tag.addView(Ui.icon(c, kind.iconRes, Ui.col(c, R.color.p3r_navy_950), 14));
        TextView label = Ui.text(c, kind.tagZh, 12, Ui.col(c, R.color.p3r_navy_950), true);
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.leftMargin = Ui.dp(c, 4);
        tag.addView(label, lp);
        tag.setBackground(new SlantedPanelDrawable().fill(Ui.col(c, R.color.p3r_cyan_400)).skew(Ui.dp(c, 5)));
        Ui.pad(tag, 9, 3, 11, 3);
        return tag;
    }

    // ------------------------------------------------------------------ upcoming

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
        LinearLayout dayCard = null;
        for (int slot = current.slot + 1; slot <= lastSlot; slot++) {
            Plan.Step step = plan.at(slot);
            if (step == null) {
                continue;
            }
            int day = Rules.dayOf(slot);
            if (day != shownDay) {
                shownDay = day;
                dayCard = Ui.column(c);
                dayCard.setBackground(Ui.card(c));
                Ui.pad(dayCard, 14, 8, 12, 6);
                String head = day == firstDay ? "今天" : Zh.dayAndWeekday(day);
                TextView dayLabel = Ui.mixed(c, head, 13, Ui.col(c, R.color.p3r_cyan_300), true);
                dayCard.addView(dayLabel);
                box.addView(dayCard, spaced(8));
            }
            LinearLayout line = SlotRows.line(c, d.rules, step, SlotRows.State.FUTURE, true);
            int reminders = ReminderViews.countAt(plan, slot);
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
            line.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_faint)));
            final int target = slot;
            line.setOnClickListener(v -> host.openDetail(target));
            dayCard.addView(line);
        }
        return box.getChildCount() > 1 ? box : null;
    }

    // ------------------------------------------------------------------ goals

    private View goals(UiData d) {
        Profile profile = d.profile();
        List<Integer> order = LinkStatus.goalOrder(profile);
        if (order.isEmpty()) {
            return null;
        }
        LinearLayout box = Ui.column(c);
        box.addView(Ui.sectionHeader(c, "目标进度", null));
        FlowLayout chips = new FlowLayout(c, 8, 8);
        for (int link : order) {
            LinkStatus status = LinkStatus.of(d.rules, profile, d.observed(), d.plan(), link);
            TextView chip = Ui.chip(c, status.chipZh, false);
            chip.setTextSize(13);
            if (profile.tier[link] == Profile.Tier.MUST) {
                SlantedPanelDrawable bg = new SlantedPanelDrawable().skew(Ui.dp(c, 6))
                    .fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 140))
                    .stroke(Ui.col(c, R.color.p3r_white), Ui.dp(c, 1.5f));
                chip.setBackground(bg);
            }
            chip.setOnClickListener(v -> host.selectTab(HomeActivity.TAB_LINKS));
            chips.addView(chip);
        }
        box.addView(chips, spaced(4));
        return box;
    }
}
