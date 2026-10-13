package app.p3r.dayplanner.v3.ui;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * Builders for the state wizard's pages. Every page is prefilled from {@link StateDraft}; edits go
 * straight into the draft and are persisted through {@link Callbacks#changed()}.
 */
final class StateSteps {
    /** Wizard pages. */
    enum Page { GOALS, PERSONA, SYNC_INTRO, DATE, CASH, STATS, LINKS, UNLOCK, TOWER, CONFIRM }

    /** What a page needs from the wizard. */
    interface Callbacks {
        /** The draft changed (persist it). */
        void changed();

        /** Go to the next page. */
        void next();

        /** Re-render the current page. */
        void refresh();
    }

    private final Activity c;
    private final StateDraft draft;
    private final Callbacks callbacks;

    StateSteps(Activity activity, StateDraft draft, Callbacks callbacks) {
        this.c = activity;
        this.draft = draft;
        this.callbacks = callbacks;
    }

    /** Pages for the draft's mode; 解锁进度 only when some link needs it. */
    static List<Page> pagesFor(StateDraft draft) {
        List<Page> pages = new ArrayList<>();
        switch (draft.mode) {
            case ONBOARDING:
                pages.add(Page.GOALS);
                pages.add(Page.PERSONA);
                break;
            case SYNC:
                pages.add(Page.SYNC_INTRO);
                break;
            default:
                break;
        }
        pages.add(Page.DATE);
        pages.add(Page.CASH);
        pages.add(Page.STATS);
        pages.add(Page.LINKS);
        if (!draft.unlockLinks().isEmpty()) {
            pages.add(Page.UNLOCK);
        }
        pages.add(Page.TOWER);
        pages.add(Page.CONFIRM);
        return pages;
    }

    /** Short title for the top bar. */
    static String titleOf(Page page) {
        switch (page) {
            case GOALS:
                return "目标";
            case PERSONA:
                return "人格面具";
            case SYNC_INTRO:
                return "这段时间";
            case DATE:
                return "日期";
            case CASH:
                return "现金";
            case STATS:
                return "个人能力值";
            case LINKS:
                return "社群 RANK";
            case UNLOCK:
                return "解锁进度";
            case TOWER:
                return "塔尔塔罗斯";
            default:
                return "确认";
        }
    }

    View build(Page page) {
        switch (page) {
            case GOALS:
                return goals();
            case PERSONA:
                return persona();
            case SYNC_INTRO:
                return syncIntro();
            case DATE:
                return date();
            case CASH:
                return cash();
            case STATS:
                return stats();
            case LINKS:
                return links();
            case UNLOCK:
                return unlock();
            case TOWER:
                return tower();
            default:
                return confirm();
        }
    }

    // ------------------------------------------------------------------ helpers

    private LinearLayout page(String question, String hint) {
        LinearLayout column = Ui.column(c);
        TextView q = Ui.mixed(c, question, 24, Ui.col(c, R.color.p3r_white), true);
        q.setLineSpacing(0f, 1.15f);
        column.addView(q);
        if (hint != null) {
            TextView h = Ui.mixed(c, hint, 14, Ui.col(c, R.color.p3r_ink_muted), false);
            h.setLineSpacing(0f, 1.25f);
            column.addView(h, top(6));
        }
        return column;
    }

    private LinearLayout.LayoutParams top(int dp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, dp);
        return p;
    }

    private TextView forecastTag() {
        TextView tag = Ui.text(c, "预计", 11, Ui.col(c, R.color.p3r_cyan_200), true);
        tag.setBackground(new SlantedPanelDrawable().stroke(Ui.col(c, R.color.p3r_cyan_200), Ui.dp(c, 1)).skew(Ui.dp(c, 4)));
        Ui.pad(tag, 7, 1, 7, 1);
        return tag;
    }

    /** Big tappable option card (title + description), selected = cyan outline. */
    private LinearLayout option(String title, String description, boolean selected, Runnable onTap) {
        LinearLayout card = Ui.column(c);
        SlantedPanelDrawable bg = Ui.pressableCard(c);
        if (selected) {
            bg.stroke(Ui.col(c, R.color.p3r_cyan_400), Ui.dp(c, 2)).fill(Ui.col(c, R.color.ui_card_raised))
                .accent(Ui.col(c, R.color.p3r_cyan_400), Ui.dp(c, 5));
        }
        card.setBackground(bg);
        card.setMinimumHeight(Ui.dp(c, 64));
        Ui.pad(card, 20, 14, 16, 14);
        card.addView(Ui.text(c, title, 18, Ui.col(c, R.color.p3r_white), true));
        if (description != null) {
            TextView d = Ui.text(c, description, 13, Ui.col(c, R.color.p3r_ink_muted), false);
            card.addView(d, top(2));
        }
        card.setClickable(true);
        card.setFocusable(true);
        card.setSelected(selected);
        card.setContentDescription(title + (description == null ? "" : "，" + description) + (selected ? "，已选" : ""));
        card.setOnClickListener(v -> onTap.run());
        return card;
    }

    // ------------------------------------------------------------------ 目标

    private View goals() {
        LinearLayout column = page("想把哪些社群练到 MAX？",
            "点一下是「想要」，再点一下是「必须」。不选也可以，规划会尽量提升所有社群。");
        LinearLayout presets = Ui.row(c);
        TextView all = Ui.chip(c, "尽量全部 MAX", false);
        all.setOnClickListener(v -> {
            for (Rules.Link link : draft.rules.links) {
                boolean manual = link.kind == Rules.LinkKind.MANUAL;
                Goals.setTier(draft.profile, link.idx, manual ? Profile.Tier.WANT : Profile.Tier.NONE);
            }
            callbacks.changed();
            callbacks.refresh();
        });
        presets.addView(all, Ui.wrap());
        TextView clear = Ui.chip(c, "清空", false);
        clear.setOnClickListener(v -> {
            for (int i = 0; i < Profile.LINKS; i++) {
                Goals.setTier(draft.profile, i, Profile.Tier.NONE);
            }
            callbacks.changed();
            callbacks.refresh();
        });
        LinearLayout.LayoutParams cp = Ui.wrap();
        cp.leftMargin = Ui.dp(c, 8);
        presets.addView(clear, cp);
        column.addView(presets, top(16));

        LinearLayout row = null;
        for (Rules.Link link : draft.rules.links) {
            if (link.idx % 2 == 0) {
                row = Ui.row(c);
                row.setBaselineAligned(false);
                column.addView(row, top(link.idx == 0 ? 14 : 8));
            }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Ui.dp(c, 76), 1);
            if (link.idx % 2 == 1) {
                p.leftMargin = Ui.dp(c, 8);
            }
            row.addView(goalCard(link), p);
        }
        ToggleRow others = new ToggleRow(c, "其余社群也尽量提升", "排完目标后，空出来的时段用来提升其他社群。",
            draft.profile.otherLinks);
        others.setListener(on -> {
            draft.profile.otherLinks = on;
            callbacks.changed();
        });
        column.addView(others, top(16));
        return column;
    }

    private View goalCard(Rules.Link link) {
        Profile.Tier tier = draft.profile.tier[link.idx];
        LinearLayout card = Ui.column(c);
        SlantedPanelDrawable bg = Ui.pressableCard(c);
        if (tier == Profile.Tier.MUST) {
            bg.stroke(Ui.col(c, R.color.p3r_white), Ui.dp(c, 2)).fill(Ui.col(c, R.color.ui_card_raised));
        } else if (tier == Profile.Tier.WANT) {
            bg.stroke(Ui.col(c, R.color.p3r_cyan_400), Ui.dp(c, 1.5f));
        }
        card.setBackground(bg);
        Ui.pad(card, 12, 8, 8, 8);
        LinearLayout top = Ui.row(c);
        top.addView(Ui.numeral(c, Zh.roman(link.arcanaNo), 17, Ui.col(c, R.color.p3r_cyan_400)), Ui.wrap());
        TextView name = Ui.text(c, link.arcanaZh, 16, Ui.col(c, R.color.p3r_white), true);
        LinearLayout.LayoutParams np = Ui.weight(1);
        np.leftMargin = Ui.dp(c, 6);
        top.addView(name, np);
        if (tier != Profile.Tier.NONE) {
            TextView badge = Ui.text(c, Goals.label(tier), 11, tier == Profile.Tier.MUST
                ? Ui.col(c, R.color.p3r_navy_950) : Ui.col(c, R.color.p3r_cyan_400), true);
            SlantedPanelDrawable b = new SlantedPanelDrawable().skew(Ui.dp(c, 4));
            if (tier == Profile.Tier.MUST) {
                b.fill(Ui.col(c, R.color.p3r_white));
            } else {
                b.stroke(Ui.col(c, R.color.p3r_cyan_400), Ui.dp(c, 1));
            }
            badge.setBackground(b);
            Ui.pad(badge, 6, 1, 6, 1);
            top.addView(badge, Ui.wrap());
        }
        card.addView(top);
        TextView person = Ui.text(c, link.personZh + " · " + link.genderZh, 12, Ui.col(c, R.color.p3r_ink_muted), false);
        person.setSingleLine(true);
        person.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(person, top(2));
        if (link.kind == Rules.LinkKind.STORY) {
            card.addView(Ui.text(c, "剧情自动推进", 11, Ui.col(c, R.color.p3r_ink_faint), false));
        }
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(link.labelZh + "，" + (tier == Profile.Tier.NONE ? "不是目标" : Goals.label(tier))
            + "，点按切换");
        card.setOnClickListener(v -> {
            draft.cycleTier(link.idx);
            callbacks.changed();
            callbacks.refresh();
        });
        return card;
    }

    // ------------------------------------------------------------------ 人格面具

    private View persona() {
        LinearLayout column = page("约会时通常带着同阿尔卡那的人格面具吗？",
            "带着和社群同阿尔卡那的人格面具时，社群点数会多一些（×1.51），升级更快。拿不准就选「不一定」，计划会更保守。");
        boolean on = draft.profile.personaBonus;
        column.addView(option("通常带着", "按 ×1.51 计算社群点数", on, () -> {
            draft.profile.personaBonus = true;
            callbacks.changed();
            callbacks.next();
        }), top(20));
        column.addView(option("不一定 / 不带", "按保守估计（推荐）", !on, () -> {
            draft.profile.personaBonus = false;
            callbacks.changed();
            callbacks.next();
        }), top(10));
        column.addView(Ui.muted(c, "之后可以在「我的 → 规划设置」里随时修改。"), top(16));
        return column;
    }

    // ------------------------------------------------------------------ 同步：这段时间

    private View syncIntro() {
        String last = Zh.lastRecorded(draft.rules, draft.base.slot);
        LinearLayout column = page("这段时间基本按计划了吗？", "上次记录：" + last + "。选一个，后面的数值会帮你预先填好。");
        StateDraft.SyncAnswer answer = draft.sync();
        column.addView(option("是", "按计划推算现在的状态，你只需核对", answer == StateDraft.SyncAnswer.YES,
            () -> answer(StateDraft.SyncAnswer.YES)), top(20));
        column.addView(option("一部分", "从上次记录开始，旁边会提示计划的预计值", answer == StateDraft.SyncAnswer.PARTLY,
            () -> answer(StateDraft.SyncAnswer.PARTLY)), top(10));
        column.addView(option("否", "按上次记录填写，你改动不一样的地方", answer == StateDraft.SyncAnswer.NO,
            () -> answer(StateDraft.SyncAnswer.NO)), top(10));
        return column;
    }

    private void answer(StateDraft.SyncAnswer value) {
        draft.setSync(value);
        callbacks.changed();
        callbacks.next();
    }

    // ------------------------------------------------------------------ 日期

    private View date() {
        LinearLayout column = page("游戏里现在是几月几日？", draft.mode == StateDraft.Mode.ONBOARDING
            ? "灰色划掉的日子整天被剧情占用。"
            : "上次记录：" + Zh.lastRecorded(draft.rules, draft.base.slot) + "。灰色划掉的日子整天被剧情占用。");
        int day = Rules.dayOf(draft.slot());
        int marked = draft.observed == null ? -1 : Rules.dayOf(draft.observed.slot);
        CalendarPicker picker = new CalendarPicker(c, draft.rules, day, marked);
        picker.setListener(d -> {
            boolean evening = Rules.isEvening(draft.slot());
            int slot = Rules.slot(d, evening);
            if (draft.rules.lockOf(slot) != null) {
                slot = Rules.slot(d, !evening);
            }
            draft.setSlot(slot);
            callbacks.changed();
            callbacks.refresh();
        });
        column.addView(picker, top(16));

        String dayLabel = Zh.period(draft.rules, Rules.slot(day, false));
        SegmentedControl period = new SegmentedControl(c, Arrays.asList(dayLabel, "晚上"));
        period.setSelected(Rules.isEvening(draft.slot()) ? 1 : 0);
        period.setSegmentEnabled(0, draft.rules.lockOf(Rules.slot(day, false)) == null);
        period.setSegmentEnabled(1, draft.rules.lockOf(Rules.slot(day, true)) == null);
        period.setListener(i -> {
            draft.setSlot(Rules.slot(day, i == 1));
            callbacks.changed();
            callbacks.refresh();
        });
        column.addView(period, top(16));
        TextView explain = Ui.muted(c, "「" + dayLabel + "」是回宿舍之前的自由时间，「晚上」是回到宿舍之后。");
        column.addView(explain, top(6));
        LinearLayout summary = Ui.row(c);
        summary.setBackground(Ui.card(c).accent(Ui.col(c, R.color.p3r_cyan_400), Ui.dp(c, 4)));
        Ui.pad(summary, 18, 12, 14, 12);
        TextView chosen = Ui.mixed(c, Zh.date(day) + " " + Zh.weekdayOf(day) + " · " + Zh.period(draft.rules, draft.slot()),
            17, Ui.col(c, R.color.p3r_white), true);
        summary.addView(chosen, Ui.weight(1));
        column.addView(summary, top(14));
        String lock = draft.rules.lockOf(Rules.slot(day, !Rules.isEvening(draft.slot())));
        if (lock != null && draft.rules.lockReasonsZh.get(lock) != null) {
            String other = Rules.isEvening(draft.slot()) ? dayLabel : "晚上";
            column.addView(Ui.muted(c, "这天" + other + "是「" + draft.rules.lockReasonsZh.get(lock) + "」。"), top(6));
        }
        return column;
    }

    // ------------------------------------------------------------------ 现金

    private View cash() {
        String hint;
        if (draft.isForecastCash()) {
            hint = "按计划推算大约是 " + Zh.yen(draft.cash()) + "，和游戏里一样就直接下一步。";
        } else if (draft.observed != null && draft.observed.cashExact) {
            hint = "上次记录：" + Zh.yen(draft.observed.cash) + "。和游戏里一样就直接下一步。";
        } else {
            hint = "游戏菜单里能看到。不确定也没关系，规划会按保守的数字算。";
        }
        LinearLayout column = page("现在有多少钱？", hint);
        CashPad.Options options = new CashPad.Options();
        options.initial = draft.cashUnknown() ? null : draft.cash();
        View pad = CashPad.inline(c, options, amount -> {
            if (amount == null) {
                draft.setCashUnknown();
            } else {
                draft.setCash(amount);
            }
            callbacks.changed();
        });
        column.addView(pad, top(12));
        return column;
    }

    // ------------------------------------------------------------------ 个人能力值

    private View stats() {
        LinearLayout column = page("个人能力值", "对照游戏里的个人能力值画面，选现在的等级。");
        SimState forecast = draft.sync() == StateDraft.SyncAnswer.PARTLY ? draft.forecast() : null;
        for (Rules.Stat stat : draft.rules.stats) {
            if (stat.idx >= 3) {
                continue;
            }
            LinearLayout card = Ui.column(c);
            card.setBackground(Ui.card(c));
            Ui.pad(card, 16, 12, 16, 14);
            LinearLayout head = Ui.row(c);
            head.addView(Ui.text(c, stat.zh, 18, Ui.col(c, R.color.p3r_white), true), Ui.wrap());
            TextView lv = Ui.numeral(c, Zh.lv(draft.statLv(stat.idx)), 20, Ui.col(c, R.color.p3r_cyan_400));
            LinearLayout.LayoutParams lp = Ui.wrap();
            lp.leftMargin = Ui.dp(c, 10);
            head.addView(lv, lp);
            head.addView(new View(c), Ui.weight(1));
            if (draft.isForecastStat(stat.idx)) {
                head.addView(forecastTag(), Ui.wrap());
            } else if (forecast != null) {
                int f = stat.levelFor(forecast.statPts[stat.idx]);
                if (f != draft.statLv(stat.idx)) {
                    head.addView(hintChip("预计 " + Zh.lv(f), () -> {
                        draft.setStatLv(stat.idx, f);
                        callbacks.changed();
                        callbacks.refresh();
                    }), Ui.wrap());
                }
            }
            card.addView(head);
            List<String> labels = Arrays.asList("1", "2", "3", "4", "5", "6");
            SegmentedControl seg = new SegmentedControl(c, labels);
            seg.setSelected(draft.statLv(stat.idx) - 1);
            for (int i = 0; i < 6; i++) {
                seg.segment(i).setContentDescription(stat.zh + " " + Zh.lv(i + 1));
            }
            seg.setListener(i -> {
                draft.setStatLv(stat.idx, i + 1);
                callbacks.changed();
                callbacks.refresh();
            });
            card.addView(seg, top(10));
            column.addView(card, top(stat.idx == 0 ? 20 : 10));
        }
        return column;
    }

    private TextView hintChip(String label, Runnable onTap) {
        TextView chip = Ui.text(c, Ui.latin(c, label), 12, Ui.col(c, R.color.p3r_cyan_200), true);
        chip.setBackground(new SlantedPanelDrawable().stroke(Ui.col(c, R.color.p3r_cyan_200), Ui.dp(c, 1))
            .pressedFill(Ui.col(c, R.color.ui_cyan_soft)).skew(Ui.dp(c, 4)));
        Ui.pad(chip, 8, 6, 8, 6);
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setContentDescription(label + "，点按采用");
        chip.setOnClickListener(v -> onTap.run());
        return chip;
    }

    // ------------------------------------------------------------------ 社群 RANK

    private View links() {
        LinearLayout column = page("社群 RANK", "点一格打开 RANK 小键盘，选完会自动跳到下一个。选「?」会保留原来的记录。");
        boolean anyUnknown = false;
        for (Rules.Link link : draft.rules.links) {
            anyUnknown |= draft.rank(link.idx) == StateDraft.UNKNOWN;
        }
        if (anyUnknown) {
            TextView rest = Ui.chip(c, "不确定的都设为未建立", false);
            rest.setTextSize(13);
            rest.setOnClickListener(v -> {
                for (Rules.Link link : draft.rules.links) {
                    if (draft.rank(link.idx) == StateDraft.UNKNOWN) {
                        draft.setRank(link.idx, 0);
                    }
                }
                callbacks.changed();
                callbacks.refresh();
            });
            LinearLayout tools = Ui.row(c);
            tools.addView(rest, Ui.wrap());
            column.addView(tools, top(12));
        }
        SimState forecast = draft.sync() == StateDraft.SyncAnswer.PARTLY ? draft.forecast() : null;
        List<Integer> order = new ArrayList<>();
        for (Rules.Link link : draft.rules.links) {
            order.add(link.idx);
        }
        LinearLayout row = null;
        for (Rules.Link link : draft.rules.links) {
            if (link.idx % 2 == 0) {
                row = Ui.row(c);
                column.addView(row, top(link.idx == 0 ? 12 : 8));
            }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Ui.dp(c, 60), 1);
            if (link.idx % 2 == 1) {
                p.leftMargin = Ui.dp(c, 8);
            }
            row.addView(rankCell(link, order, forecast), p);
        }
        return column;
    }

    private View rankCell(Rules.Link link, List<Integer> order, SimState forecast) {
        int r = draft.rank(link.idx);
        boolean edited = draft.rank[link.idx] != null;
        boolean predicted = draft.isForecastRank(link.idx);
        LinearLayout cell = Ui.row(c);
        SlantedPanelDrawable bg = Ui.pressableCard(c);
        if (edited) {
            bg.stroke(Ui.col(c, R.color.p3r_white), Ui.dp(c, 1.5f)).fill(Ui.col(c, R.color.ui_card_raised));
        } else if (predicted) {
            bg.stroke(Ui.col(c, R.color.p3r_cyan_200), Ui.dp(c, 1));
        }
        cell.setBackground(bg);
        Ui.pad(cell, 10, 4, 10, 4);
        TextView roman = Ui.numeral(c, Zh.roman(link.arcanaNo), 15, Ui.col(c, R.color.p3r_cyan_400));
        roman.setMinWidth(Ui.dp(c, 34));
        cell.addView(roman, Ui.wrap());
        LinearLayout texts = Ui.column(c);
        texts.addView(Ui.text(c, link.arcanaZh, 14, Ui.col(c, R.color.p3r_white), true));
        String value = r == StateDraft.UNKNOWN ? "?" : r == 0 ? "未建立" : Zh.rank(r);
        int color = r == StateDraft.UNKNOWN ? Ui.col(c, R.color.ui_gold)
            : r == 0 ? Ui.col(c, R.color.p3r_ink_faint) : Ui.col(c, R.color.p3r_cyan_300);
        LinearLayout valueRow = Ui.row(c);
        valueRow.addView(Ui.mixed(c, value, 13, color, true), Ui.wrap());
        if (predicted) {
            TextView tag = Ui.text(c, "预计", 10, Ui.col(c, R.color.p3r_cyan_200), true);
            LinearLayout.LayoutParams tp = Ui.wrap();
            tp.leftMargin = Ui.dp(c, 6);
            valueRow.addView(tag, tp);
        } else if (forecast != null && forecast.rank[link.idx] != r && r != StateDraft.UNKNOWN) {
            TextView tag = Ui.mixed(c, "预计 " + Zh.rankValue(forecast.rank[link.idx]), 10,
                Ui.col(c, R.color.p3r_cyan_200), true);
            LinearLayout.LayoutParams tp = Ui.wrap();
            tp.leftMargin = Ui.dp(c, 6);
            valueRow.addView(tag, tp);
        }
        texts.addView(valueRow);
        LinearLayout.LayoutParams tp = Ui.weight(1);
        tp.leftMargin = Ui.dp(c, 4);
        cell.addView(texts, tp);
        cell.setClickable(true);
        cell.setFocusable(true);
        cell.setContentDescription(link.arcanaZh + "，" + (r == StateDraft.UNKNOWN ? "不确定" : r == 0 ? "未建立" : Zh.rank(r))
            + "，点按修改");
        cell.setOnClickListener(v -> RankPad.show(c, draft.rules, order, link.idx, new RankPad.Listener() {
            @Override
            public void onPicked(int linkIdx, int rank) {
                draft.setRank(linkIdx, rank);
                callbacks.changed();
                callbacks.refresh();
            }

            @Override
            public int currentRank(int linkIdx) {
                return draft.rank(linkIdx);
            }
        }));
        return cell;
    }

    // ------------------------------------------------------------------ 解锁进度

    private View unlock() {
        LinearLayout column = page("解锁进度", "这些社群还没建立，但需要提前做准备。告诉我已经做到哪一步了。");
        for (int linkIdx : draft.unlockLinks()) {
            Rules.Link link = draft.rules.links[linkIdx];
            LinearLayout card = Ui.column(c);
            card.setBackground(Ui.card(c));
            Ui.pad(card, 16, 12, 16, 12);
            card.addView(Ui.text(c, link.labelZh, 16, Ui.col(c, R.color.p3r_white), true));
            int steps = link.unlock.steps.size();
            int current = Math.min(draft.unlockStep(linkIdx), steps - 1);
            for (int done = 0; done < steps; done++) {
                final int value = done;
                String label = StateDraft.unlockText(link, done);
                TextView chip = Ui.chip(c, label, done == current);
                chip.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                chip.setTextSize(14);
                chip.setOnClickListener(v -> {
                    draft.setUnlockStep(linkIdx, value);
                    callbacks.changed();
                    callbacks.refresh();
                });
                card.addView(chip, top(8));
            }
            column.addView(card, top(14));
        }
        return column;
    }

    // ------------------------------------------------------------------ 塔尔塔罗斯

    private View tower() {
        Rules.Block block = draft.block();
        String hint = block == null ? "这个日期还没有可以探索的区块。"
            : "当前区块按日期是「" + block.zh + "」（" + block.floorsZh + "）。";
        LinearLayout column = page("塔尔塔罗斯", hint);
        int top = 1;
        for (Rules.Block b : draft.rules.blocks) {
            top = Math.max(top, b.top);
        }
        LinearLayout floorCard = Ui.column(c);
        floorCard.setBackground(Ui.card(c));
        Ui.pad(floorCard, 16, 12, 16, 14);
        floorCard.addView(Ui.text(c, "最高到达层", 16, Ui.col(c, R.color.p3r_white), true));
        Stepper floor = new Stepper(c, 0, top, draft.floor());
        floor.setFormatter(v -> v == 0 ? "还没去过" : v + "F");
        floor.setListener(v -> {
            draft.setFloor(v);
            callbacks.changed();
        });
        floorCard.addView(floor, top(8));
        column.addView(floorCard, top(20));
        if (block != null) {
            Profile profile = draft.profile;
            LinearLayout visitCard = Ui.column(c);
            visitCard.setBackground(Ui.card(c));
            Ui.pad(visitCard, 16, 12, 16, 14);
            visitCard.addView(Ui.text(c, "这个区块已经去了几次", 16, Ui.col(c, R.color.p3r_white), true));
            visitCard.addView(Ui.mixed(c, "上限 " + profile.towerCapPerBlock + " 次，可在规划设置里修改。", 13,
                Ui.col(c, R.color.p3r_ink_muted), false));
            Stepper visits = new Stepper(c, 0, Math.max(profile.towerCapPerBlock, draft.towerVisits()), draft.towerVisits());
            visits.setFormatter(v -> v + " 次");
            visits.setListener(v -> {
                draft.setTowerVisits(v);
                callbacks.changed();
            });
            visitCard.addView(visits, top(8));
            column.addView(visitCard, top(10));
        }
        return column;
    }

    // ------------------------------------------------------------------ 确认

    private View confirm() {
        List<StateDraft.Change> changes = draft.changes();
        boolean first = draft.observed == null;
        String question;
        if (first) {
            question = "确认一下";
        } else if (changes.isEmpty() && !draft.profileChanged()) {
            question = "和上次记录一样";
        } else {
            question = "将更新 " + changes.size() + " 项";
        }
        LinearLayout column = page(question, first ? "保存后会立刻开始安排到 1月31日 的日程。"
            : "带「预计」的数值请对照游戏核对，保存就算确认。");
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.card(c));
        Ui.pad(card, 16, 8, 16, 8);
        for (StateDraft.Change change : changes) {
            LinearLayout row = Ui.row(c);
            row.setMinimumHeight(Ui.dp(c, 44));
            TextView label = Ui.text(c, change.labelZh, 14, Ui.col(c, R.color.p3r_ink_muted), true);
            label.setMinWidth(Ui.dp(c, 84));
            row.addView(label, Ui.wrap());
            LinearLayout values = Ui.row(c);
            values.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            if (change.beforeZh != null) {
                values.addView(Ui.mixed(c, change.beforeZh, 14, Ui.col(c, R.color.p3r_ink_faint), false), Ui.wrap());
                TextView arrow = Ui.text(c, "→", 14, Ui.col(c, R.color.p3r_cyan_400), true);
                Ui.pad(arrow, 8, 0, 8, 0);
                values.addView(arrow, Ui.wrap());
            }
            TextView after = Ui.mixed(c, change.afterZh, 15, Ui.col(c, R.color.p3r_white), true);
            after.setGravity(Gravity.END);
            values.addView(after, Ui.wrap());
            row.addView(values, Ui.weight(1));
            card.addView(row);
        }
        if (changes.isEmpty()) {
            card.addView(Ui.body(c, "没有需要修改的地方。"));
        }
        column.addView(card, top(18));
        return column;
    }
}
