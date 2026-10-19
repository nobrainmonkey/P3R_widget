package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.drawable.InsetDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi;

/**
 * 记录历史, newest first and grouped by game day: each record is its period tag, its result, how long
 * ago, and compact 「修改」「撤销」 where the store accepts them. The latest 同步现状 is a divider plate;
 * the records under it are dimmed and offer nothing. Undo journal rows are not listed (the undone
 * record itself is struck through). The past-day sheet shows the same rows for one day, the tapped
 * slot's first.
 */
final class HistoryPage {
    static final int LIMIT = 80;
    private static final String TOWER = "tower";
    private static final String VOID = "void";
    /** Kinds that record what happened in a slot (as opposed to journal rows such as 同步现状). */
    private static final java.util.Set<String> RECORD_KINDS = new java.util.HashSet<>(
        java.util.Arrays.asList("outcome", "skip", "outcome_fix", TOWER, "tower_fix"));

    static final String EMPTY = "还没有记录";
    static final String EMPTY_DAY = "这天没有记录";
    static final String LOAD_FAILED = "记录没读出来";
    static final String MORE = "再看更早的";

    private final HomeHost host;
    private final Context c;
    private final LinearLayout column;
    private final Integer day;
    private final int focusSlot;
    private int limit = LIMIT;
    private boolean cascaded;

    private HistoryPage(HomeHost host, Integer day, int focusSlot) {
        this.host = host;
        this.c = host.activity();
        this.day = day;
        this.focusSlot = focusSlot;
        column = Ui.column(c);
    }

    static View build(HomeHost host) {
        HistoryPage page = new HistoryPage(host, null, -1);
        page.load(false);
        return page.column;
    }

    static View buildForDay(HomeHost host, int day) {
        return buildForDay(host, day, -1);
    }

    /** The records of {@code day}, those of {@code slot} first (the day sheet of a past slot). */
    static View buildForDay(HomeHost host, int day, int slot) {
        HistoryPage page = new HistoryPage(host, day, slot);
        page.load(false);
        return page.column;
    }

    static List<PlannerApi.HistoryItem> forDay(List<PlannerApi.HistoryItem> items, int day) {
        List<PlannerApi.HistoryItem> out = new ArrayList<>();
        for (PlannerApi.HistoryItem item : items) {
            if (item.slot >= 0 && Rules.dayOf(item.slot) == day) out.add(item);
        }
        return out;
    }

    static PlannerApi.HistoryItem activeAtSlot(List<PlannerApi.HistoryItem> items, int slot) {
        PlannerApi.HistoryItem original = null;
        for (PlannerApi.HistoryItem item : items) {
            if (item.slot != slot || item.voided
                    || !("outcome".equals(item.kind) || "skip".equals(item.kind) || TOWER.equals(item.kind))) continue;
            if (original == null || item.seq > original.seq) original = item;
        }
        if (original == null) return null;
        PlannerApi.HistoryItem active = original;
        for (PlannerApi.HistoryItem item : items) {
            if (item.slot == slot && !item.voided && item.originalSeq == original.seq
                    && ("outcome_fix".equals(item.kind) || "tower_fix".equals(item.kind)) && item.seq > active.seq) {
                active = item;
            }
        }
        return active;
    }

    /**
     * A record's result without its date and period (「10月21日 晚上 · 在宿舍房间学习 · 学力 +2」 →
     * 「在宿舍房间学习 · 学力 +2」) and without the correction prefix (「修改了这次结果 · …」).
     */
    static String body(String summary) {
        if (summary == null) {
            return "已记录";
        }
        int divider = summary.indexOf(" · ");
        String body = divider < 0 ? summary : summary.substring(divider + 3);
        for (String prefix : new String[]{"修改了这次结果 · ", "修改了这次探索 · "}) {
            if (body.startsWith(prefix)) {
                return body.substring(prefix.length());
            }
        }
        return body;
    }

    /** The list as shown: undo journal rows left out (the undone record is struck through instead). */
    static List<PlannerApi.HistoryItem> shown(List<PlannerApi.HistoryItem> items) {
        List<PlannerApi.HistoryItem> out = new ArrayList<>();
        for (PlannerApi.HistoryItem item : items) {
            if (!VOID.equals(item.kind)) out.add(item);
        }
        return out;
    }

    /** A row that is not a slot's record (同步现状, an import): a divider plate. */
    private static boolean journal(PlannerApi.HistoryItem item) {
        return !RECORD_KINDS.contains(item.kind) || item.slot < 0;
    }

    /** Reads the records; {@code more} keeps what is shown until the longer list is in. */
    private void load(boolean more) {
        if (!more) {
            column.removeAllViews();
        }
        int asked = limit;
        Async.run(host.activity(), () -> Api.get(c).history(asked), items -> show(items, items.size() >= asked),
            error -> failed());
    }

    private void failed() {
        column.removeAllViews();
        LinearLayout row = Ui.row(c);
        row.addView(Ui.text(c, LOAD_FAILED, 16, Ui.col(c, R.color.p3r_white), true), Ui.weight(1));
        TextView retry = Ui.quiet(c, "重试");
        retry.setOnClickListener(v -> load(false));
        row.addView(retry, Ui.wrap());
        column.addView(row, Ui.matchWrap());
    }

    private void show(List<PlannerApi.HistoryItem> all, boolean limited) {
        List<PlannerApi.HistoryItem> items = shown(all);
        if (day != null) {
            items = forDay(items, day);
            // The tapped slot's records first; the rest of the day after them.
            List<PlannerApi.HistoryItem> first = new ArrayList<>();
            List<PlannerApi.HistoryItem> rest = new ArrayList<>();
            for (PlannerApi.HistoryItem item : items) {
                (item.slot == focusSlot ? first : rest).add(item);
            }
            first.addAll(rest);
            items = first;
        }
        column.removeAllViews();
        if (items.isEmpty()) {
            column.addView(Ui.muted(c, day == null ? EMPTY : EMPTY_DAY), Ui.matchWrap());
            return;
        }
        UiData d = host.data();
        Rules rules = d == null ? null : d.rules;
        int shownDay = Integer.MIN_VALUE;
        boolean older = false;
        for (PlannerApi.HistoryItem item : items) {
            if (journal(item)) {
                if (day == null) {
                    column.addView(divider(item), gap(older ? 14 : 18));
                }
                // Records under the latest 同步现状 no longer shape the progress.
                older = true;
                shownDay = Integer.MIN_VALUE;
                continue;
            }
            int itemDay = Rules.dayOf(item.slot);
            if (day == null && itemDay != shownDay) {
                shownDay = itemDay;
                View header = dayHeader(rules, itemDay);
                header.setAlpha(older ? 0.55f : 1f);
                column.addView(header, gap(column.getChildCount() == 0 ? 0 : 18));
            }
            column.addView(row(rules, item, older), gap(day == null ? 2 : 4));
        }
        if (limited && day == null) {
            TextView more = Ui.quiet(c, MORE);
            more.setOnClickListener(v -> {
                more.setEnabled(false);
                limit *= 2;
                load(true);
            });
            column.addView(more, gap(12));
        }
        if (!cascaded) {
            cascaded = true;
            Motion.cascade(column);
        }
    }

    /** 「21」 with its weekday plate and 「10月」, like the date plates, and a rule running out. */
    private View dayHeader(Rules rules, int itemDay) {
        LinearLayout row = Ui.row(c);
        TextView number = Ui.numeral(c, String.valueOf(Zh.dateOf(itemDay).getDayOfMonth()), 12,
            Ui.col(c, R.color.p3r_white));
        number.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 30);
        float shadow = Ui.dp(c, 2);
        number.setShadowLayer(0.01f, shadow, shadow, Ui.alpha(Ui.col(c, R.color.p3r_royal_500), 230));
        number.setPadding(0, 0, (int) shadow + Ui.dp(c, 4), (int) shadow);
        row.addView(number, Ui.wrap());
        LinearLayout side = Ui.column(c);
        Rules.Day info = rules == null ? null : rules.day(itemDay);
        TextView weekday = Ui.text(c, info != null ? Zh.weekday(info.wd) : Zh.weekdayOf(itemDay), 12,
            Ui.col(c, R.color.p3r_navy_950), true);
        boolean rest = info != null && (info.type == Rules.DayType.SUNDAY || info.type == Rules.DayType.HOLIDAY);
        weekday.setTextColor(Ui.col(c, rest ? R.color.p3r_white : R.color.p3r_navy_950));
        weekday.setBackground(new SlantedPanelDrawable()
            .fill(Ui.col(c, rest ? R.color.ui_holiday : R.color.p3r_white)).skew(Ui.dp(c, 4)));
        Ui.pad(weekday, 6, 0, 7, 0);
        side.addView(weekday, Ui.wrap());
        TextView month = Ui.mixed(c, Zh.dateOf(itemDay).getMonthValue() + "月", 12,
            Ui.col(c, R.color.p3r_cyan_300), true);
        side.addView(month, Ui.wrap());
        row.addView(side, Ui.wrap());
        View rule = Ui.fadingRule(c);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, Math.max(1, Ui.dp(c, 1)), 1);
        rp.leftMargin = Ui.dp(c, 12);
        row.addView(rule, rp);
        row.setContentDescription(Zh.dayAndWeekday(itemDay));
        return row;
    }

    /** 「10月18日 · 同步现状」 across the page: what came before it is history only. */
    private View divider(PlannerApi.HistoryItem item) {
        LinearLayout plate = Ui.row(c);
        plate.setBackground(Ui.band(c));
        Ui.pad(plate, 16, 10, 12, 10);
        plate.addView(Ui.icon(c, R.drawable.ui_ic_sync, Ui.col(c, R.color.p3r_cyan_300), 16));
        TextView text = Ui.mixed(c, item.summaryZh, 14, Ui.col(c, R.color.p3r_cyan_200), true);
        LinearLayout.LayoutParams tp = Ui.weight(1);
        tp.leftMargin = Ui.dp(c, 8);
        plate.addView(text, tp);
        TextView age = Ui.text(c, Zh.age(System.currentTimeMillis(), item.createdMillis), 12,
            Ui.col(c, R.color.p3r_ink_faint), false);
        plate.addView(age, Ui.wrap());
        return plate;
    }

    /**
     * One record: period tag, the result (struck through once undone), how long ago, and 「修改」
     * 「撤销」 when the store accepts them. Reads out as the whole summary.
     */
    private View row(Rules rules, PlannerApi.HistoryItem item, boolean older) {
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.TOP);
        TextView period = Ui.text(c, rules == null ? "" : Zh.period(rules, item.slot), 13,
            Ui.col(c, R.color.p3r_cyan_200), true);
        period.setGravity(Gravity.CENTER);
        period.setMinWidth(Ui.dp(c, 50));
        Ui.pad(period, 4, 1, 4, 2);
        SlantedPanelDrawable tag = new SlantedPanelDrawable().skew(Ui.dp(c, 4));
        if (Rules.isEvening(item.slot)) {
            tag.fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 200));
        } else {
            tag.stroke(Ui.alpha(Ui.col(c, R.color.p3r_cyan_200), 170), Ui.dp(c, 1));
        }
        period.setBackground(tag);
        LinearLayout.LayoutParams pp = Ui.wrap();
        pp.topMargin = Ui.dp(c, 2);
        pp.rightMargin = Ui.dp(c, 10);
        row.addView(period, pp);

        LinearLayout texts = Ui.column(c);
        TextView result = Ui.mixed(c, body(item.summaryZh), 15,
            Ui.col(c, item.voided ? R.color.p3r_ink_faint : R.color.p3r_white), false);
        if (item.voided) {
            result.setPaintFlags(result.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        }
        texts.addView(result, Ui.matchWrap());
        LinearLayout meta = Ui.row(c);
        meta.setMinimumHeight(Ui.dp(c, item.undoable && !item.voided ? Ui.TOUCH : 24));
        if (item.voided) {
            TextView undone = Ui.tag(c, "已撤销", Ui.alpha(Ui.col(c, R.color.p3r_ink_faint), 60),
                Ui.col(c, R.color.p3r_ink_muted));
            undone.setTextSize(12);
            LinearLayout.LayoutParams up = Ui.wrap();
            up.rightMargin = Ui.dp(c, 8);
            meta.addView(undone, up);
        }
        meta.addView(Ui.text(c, Zh.age(System.currentTimeMillis(), item.createdMillis), 12,
            Ui.col(c, R.color.p3r_ink_faint), false), Ui.weight(1));
        // PlannerApi.HistoryItem: 撤销 for every record that still shapes the progress (undoable),
        // 修改 only where the store accepts a correction and can supply the original values.
        if (!item.voided && item.undoable) {
            if (item.correctable && TOWER.equals(item.kind) && item.tower != null) {
                TextView fix = action("修改");
                fix.setContentDescription("修改这条记录：" + item.summaryZh);
                fix.setOnClickListener(v -> openCorrection(item));
                meta.addView(fix, Ui.wrap());
            } else if (item.correctable && ("outcome".equals(item.kind) || "skip".equals(item.kind)
                    || "outcome_fix".equals(item.kind))) {
                TextView fix = action("修改");
                fix.setContentDescription("修改这条记录：" + item.summaryZh);
                fix.setOnClickListener(v -> {
                    android.content.Intent intent = new android.content.Intent(c, ResultActivity.class);
                    intent.putExtra(app.p3r.dayplanner.v3.Intents.EXTRA_CORRECT_SEQ, item.seq);
                    intent.putExtra(HomeActivity.EXTRA_FROM_APP, true);
                    host.activity().startActivity(intent);
                });
                meta.addView(fix, Ui.wrap());
            }
            TextView undo = action("撤销");
            undo.setContentDescription("撤销这条记录：" + item.summaryZh);
            undo.setOnClickListener(v -> confirmUndo(item));
            LinearLayout.LayoutParams ap = Ui.wrap();
            ap.leftMargin = Ui.dp(c, 6);
            meta.addView(undo, ap);
        }
        texts.addView(meta, Ui.matchWrap());
        row.addView(texts, Ui.weight(1));
        // Under the latest 同步现状: history only, dimmed as a whole.
        row.setAlpha(older ? 0.55f : 1f);
        row.setContentDescription(item.summaryZh + (item.voided ? "，已撤销" : ""));
        return row;
    }

    /** A compact slanted outline action (「修改」「撤销」) in a 48 dp target. */
    private TextView action(String label) {
        TextView button = Ui.text(c, label, 14, Ui.col(c, R.color.p3r_cyan_200), true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(Ui.dp(c, Ui.TOUCH));
        button.setMinWidth(Ui.dp(c, 56));
        Ui.pad(button, 12, 0, 12, 0);
        int inset = Ui.dp(c, 8);
        button.setBackground(new InsetDrawable(new SlantedPanelDrawable()
            .stroke(Ui.alpha(Ui.col(c, R.color.p3r_cyan_300), 150), Ui.dp(c, 1))
            .pressedFill(Ui.col(c, R.color.ui_cyan_soft))
            .skew(Ui.dp(c, 5)), 0, inset, 0, inset));
        button.setClickable(true);
        button.setFocusable(true);
        Motion.pressable(button);
        return button;
    }

    private void confirmUndo(PlannerApi.HistoryItem item) {
        ConfirmDialog.show(c, UNDO_TITLE, item.summaryZh, "撤销", () -> {
            long rev = host.data().snap.stateRevision;
            String id = CommandIds.of("void", rev, item.seq);
            Async.run(host.activity(), () -> Api.get(c).voidEvent(id, rev, item.seq), receipt -> {
                if (receipt.ok) {
                    host.showMessage("已撤销：" + item.summaryZh);
                } else {
                    host.showError(Messages.receiptError(receipt));
                }
                host.reload();
                load(false);
            }, error -> host.showError(Messages.GENERIC_ERROR));
        });
    }

    /** The undo confirmation's question. */
    static final String UNDO_TITLE = "撤销这条记录？";

    /** Block of a tower record: the record's own, else the block active on its day (-1 if none). */
    static int blockOf(Rules rules, PlannerApi.HistoryItem item) {
        if (item.tower != null && item.tower.block >= 0 && item.tower.block < rules.blocks.length) {
            return item.tower.block;
        }
        Rules.Block block = rules.blockOn(Rules.dayOf(item.slot));
        return block == null ? -1 : block.idx;
    }

    /**
     * 修改这次探索 in a sheet, prefilled with the record's own values; opened from a day sheet, closing
     * it (saved or not) brings that day back.
     */
    private void openCorrection(PlannerApi.HistoryItem item) {
        UiData d = host.data();
        int blockIdx = blockOf(d.rules, item);
        Async.run(host.activity(), () -> UiPrefs.towerLevel(c), level -> {
            LinearLayout sheet = Ui.column(c);
            Ui.pad(sheet, 20, 12, 20, 20);
            sheet.addView(SheetChrome.title(c, "修改这次探索"));
            sheet.addView(Ui.mixed(c, item.summaryZh, 13, Ui.col(c, R.color.p3r_ink_muted), false));
            // Prefilled from the record's own values (item.tower: the latest correction, else the
            // original), never from today's floor or cash.
            TowerPanel panel = new TowerPanel(c, d.rules, d.observed(), item.slot, blockIdx, item.tower, true, level,
                false, new TowerPanel.Listener() {
                    @Override
                    public void onSave(PlannerApi.TowerReturn tower, String summaryZh) {
                        saveCorrection(item, tower);
                    }

                    @Override
                    public void onCannot() {
                        host.dismissSheet();
                    }
                });
            LinearLayout.LayoutParams p = Ui.matchWrap();
            p.topMargin = Ui.dp(c, 16);
            sheet.addView(panel, p);
            Runnable back = day == null ? null : () -> host.openDetail(item.slot);
            host.showSheet(sheet, back);
        }, null);
    }

    /** Saves the correction; a rejected one keeps the sheet and what was typed into it. */
    private void saveCorrection(PlannerApi.HistoryItem item, PlannerApi.TowerReturn tower) {
        long rev = host.data().snap.stateRevision;
        String id = CommandIds.of("tower_fix", rev, item.seq, tower.floor, tower.balanceMode, tower.amount, tower.level,
            tower.rescuedId);
        Async.run(host.activity(), () -> Api.get(c).correctTower(id, rev, item.seq, tower), receipt -> {
            if (receipt.ok) {
                host.dismissSheet();
                host.showMessage("已修改：探索塔尔塔罗斯 · " + tower.floor + "F");
                host.reload();
                load(false);
            } else {
                host.showError(Messages.receiptError(receipt));
                host.reload();
            }
        }, error -> host.showError(Messages.GENERIC_ERROR));
    }

    private LinearLayout.LayoutParams gap(int topDp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, topDp);
        return p;
    }
}
