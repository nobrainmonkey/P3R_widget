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

/**
 * 今天 tab: date plate, return banner, replanning status, resource strip, the hero card for the
 * next slot (with its 人格面具 reminder, "顺便" side steps and [做不了/改做别的] [完成 ✓]), the rest
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

    /** The hero card's [做不了/改做别的] [完成 ✓] row, or null. */
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
        boolean finished = Slots.finished(rules, obs);
        DatePlate plate = new DatePlate(c, true);
        plate.bind(rules, finished ? rules.lastSlot() : obs.slot);
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
        if (d.snap.planErrorZh != null && !d.snap.planning) {
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
            row.addView(Ui.icon(c, R.drawable.ui_ic_check, Ui.col(c, R.color.p3r_good), 18));
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
            TextView floor = Ui.numeral(c, obs.floor + "F", 16, Ui.col(c, R.color.p3r_cyan_300));
            LinearLayout.LayoutParams fp = Ui.wrap();
            fp.leftMargin = Ui.dp(c, 8);
            row.addView(floor, fp);
        }
        return row;
    }

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
            card.addView(Ui.mixed(c, lockedNote(step), 14, Ui.col(c, R.color.p3r_ink_muted), false), spaced(4));
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
        String dialogue = Dialogue.line(d.rules, d.profile(), step);
        if (dialogue != null) {
            TextView options = Ui.text(c, dialogue, 14, Ui.col(c, R.color.p3r_ice_100), false);
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
            TextView go = Ui.primary(c, "继续");
            go.setContentDescription("剧情结束，继续到下一个时段");
            go.setOnClickListener(v -> host.commitDirect(step));
            row.addView(go, Ui.weight(1));
            return row;
        }
        boolean large = c.getResources().getConfiguration().fontScale > 1.15f;
        TextView cannot = Ui.secondary(c, large ? "做不了/\n改做别的" : "做不了/改做别的");
        cannot.setTextSize(15);
        cannot.setMaxLines(2);
        cannot.setContentDescription("做不了或改做别的");
        Ui.pad(cannot, 10, 8, 10, 8);
        cannot.setOnClickListener(v -> host.openResult(step.slot, step.action, Intents.MODE_CANNOT));
        row.addView(cannot, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.3f));
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
        Plan plan = d.plan();
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
                String head = day == firstDay ? "今天" : Zh.shortDate(day) + " " + Zh.weekdayOf(day);
                TextView dayLabel = Ui.mixed(c, head, 13, Ui.col(c, R.color.p3r_cyan_300), true);
                dayCard.addView(dayLabel);
                box.addView(dayCard, spaced(8));
            }
            LinearLayout line = SlotRows.line(c, d.rules, step, SlotRows.State.FUTURE, true);
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
