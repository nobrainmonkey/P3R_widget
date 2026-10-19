package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.graphics.Paint;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi;

/**
 * 记录历史: newest first. Records that still shape the current progress get [撤销] and [修改]
 * when their values can be corrected; older records say they predate the last
 * 同步现状. Undo appends a void marker; later state is recomputed from the rest.
 */
final class HistoryPage {
    private static final int LIMIT = 80;
    private static final String TOWER = "tower";
    /** Kinds that record what happened in a slot (as opposed to journal rows such as 同步现状). */
    private static final java.util.Set<String> RECORD_KINDS = new java.util.HashSet<>(
        java.util.Arrays.asList("outcome", "skip", "outcome_fix", TOWER, "tower_fix"));

    private final HomeHost host;
    private final Context c;
    private final LinearLayout column;
    private final Integer day;

    private HistoryPage(HomeHost host, Integer day) {
        this.host = host;
        this.c = host.activity();
        this.day = day;
        column = Ui.column(c);
    }

    static View build(HomeHost host) {
        HistoryPage page = new HistoryPage(host, null);
        page.load();
        return page.column;
    }

    static View buildForDay(HomeHost host, int day) {
        HistoryPage page = new HistoryPage(host, day);
        page.load();
        return page.column;
    }

    static List<PlannerApi.HistoryItem> forDay(List<PlannerApi.HistoryItem> items, int day) {
        List<PlannerApi.HistoryItem> out = new java.util.ArrayList<>();
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

    private void load() {
        column.removeAllViews();
        column.addView(Ui.muted(c, "正在读取记录…"));
        Async.run(host.activity(), () -> Api.get(c).history(LIMIT), this::show, error -> {
            column.removeAllViews();
            column.addView(Ui.body(c, "读取记录失败，请稍后再试。"));
        });
    }

    private void show(List<PlannerApi.HistoryItem> items) {
        if (day != null) items = forDay(items, day);
        column.removeAllViews();
        if (items.isEmpty()) {
            column.addView(Ui.body(c, day == null ? "还没有记录。在「今天」里点「完成」后，记录会出现在这里。" : "这天没有记录。"));
            return;
        }
        column.addView(Ui.muted(c, "撤销后会按剩下的记录重新计算，后面的安排也会随之更新。"));
        for (PlannerApi.HistoryItem item : items) {
            LinearLayout.LayoutParams p = Ui.matchWrap();
            p.topMargin = Ui.dp(c, 8);
            column.addView(row(item), p);
        }
    }

    private View row(PlannerApi.HistoryItem item) {
        LinearLayout card = Ui.column(c);
        card.setBackground(item.voided ? new SlantedPanelDrawable().fill(Ui.col(c, R.color.ui_card_sunken))
            .cuts(Ui.dp(c, 14), 0, Ui.dp(c, 14), 0) : Ui.card(c));
        Ui.pad(card, 16, 10, 8, 6);
        TextView summary = Ui.mixed(c, item.summaryZh, 15,
            item.voided ? Ui.col(c, R.color.p3r_ink_faint) : Ui.col(c, R.color.p3r_white), !item.voided);
        if (item.voided) {
            summary.setPaintFlags(summary.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        }
        card.addView(summary);
        LinearLayout bottom = Ui.row(c);
        bottom.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bottom.setMinimumHeight(Ui.dp(c, Ui.TOUCH));
        String meta = (item.voided ? "已撤销 · " : "") + Zh.age(System.currentTimeMillis(), item.createdMillis) + "记录";
        bottom.addView(Ui.caption(c, meta), Ui.weight(1));
        // PlannerApi.HistoryItem: 撤销 for every record that still shapes the progress (undoable),
        // 修改 only where the store accepts a correction and can supply the original values.
        if (!item.voided && item.undoable) {
            if (item.correctable && TOWER.equals(item.kind) && item.tower != null) {
                TextView fix = Ui.quiet(c, "修改");
                fix.setContentDescription("修改这条记录：" + item.summaryZh);
                fix.setOnClickListener(v -> openCorrection(item));
                bottom.addView(fix, Ui.wrap());
            } else if (item.correctable && ("outcome".equals(item.kind) || "skip".equals(item.kind)
                    || "outcome_fix".equals(item.kind))) {
                TextView fix = Ui.quiet(c, "修改");
                fix.setContentDescription("修改这条记录：" + item.summaryZh);
                fix.setOnClickListener(v -> {
                    android.content.Intent intent = new android.content.Intent(c, ResultActivity.class);
                    intent.putExtra(app.p3r.dayplanner.v3.Intents.EXTRA_CORRECT_SEQ, item.seq);
                    intent.putExtra(HomeActivity.EXTRA_FROM_APP, true);
                    host.activity().startActivity(intent);
                });
                bottom.addView(fix, Ui.wrap());
            }
            TextView undo = Ui.quiet(c, "撤销");
            undo.setContentDescription("撤销这条记录：" + item.summaryZh);
            undo.setOnClickListener(v -> confirmUndo(item));
            bottom.addView(undo, Ui.wrap());
        } else if (!item.voided && RECORD_KINDS.contains(item.kind)) {
            bottom.addView(Ui.caption(c, "同步现状之前的记录"), Ui.wrap());
        }
        card.addView(bottom);
        return card;
    }

    private void confirmUndo(PlannerApi.HistoryItem item) {
        ConfirmDialog.show(c, "撤销这条记录？", item.summaryZh + "\n撤销后会按剩下的记录重新计算。", "撤销", () -> {
            long rev = host.data().snap.stateRevision;
            String id = CommandIds.of("void", rev, item.seq);
            Async.run(host.activity(), () -> Api.get(c).voidEvent(id, rev, item.seq), receipt -> {
                if (receipt.ok) {
                    host.showMessage("已撤销：" + item.summaryZh);
                    host.reload();
                    load();
                } else {
                    host.showMessage(Messages.receiptError(receipt));
                    host.reload();
                }
            }, error -> host.showMessage(Messages.GENERIC_ERROR));
        });
    }

    /** Block of a tower record: the record's own, else the block active on its day (-1 if none). */
    static int blockOf(Rules rules, PlannerApi.HistoryItem item) {
        if (item.tower != null && item.tower.block >= 0 && item.tower.block < rules.blocks.length) {
            return item.tower.block;
        }
        Rules.Block block = rules.blockOn(Rules.dayOf(item.slot));
        return block == null ? -1 : block.idx;
    }

    private void openCorrection(PlannerApi.HistoryItem item) {
        UiData d = host.data();
        int blockIdx = blockOf(d.rules, item);
        Async.run(host.activity(), () -> UiPrefs.towerLevel(c), level -> {
            LinearLayout sheet = Ui.column(c);
            Ui.pad(sheet, 20, 12, 20, 20);
            sheet.addView(Ui.title(c, "修改这次探索"));
            sheet.addView(Ui.mixed(c, item.summaryZh, 13, Ui.col(c, R.color.p3r_ink_muted), false));
            sheet.addView(Ui.muted(c, "只改数值，不会多算一次探索。"));
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
            host.showSheet(sheet);
        }, null);
    }

    private void saveCorrection(PlannerApi.HistoryItem item, PlannerApi.TowerReturn tower) {
        long rev = host.data().snap.stateRevision;
        String id = CommandIds.of("tower_fix", rev, item.seq, tower.floor, tower.balanceMode, tower.amount, tower.level,
            tower.rescuedId);
        Async.run(host.activity(), () -> Api.get(c).correctTower(id, rev, item.seq, tower), receipt -> {
            host.dismissSheet();
            host.showMessage(receipt.ok ? "已修改：探索塔尔塔罗斯 · " + tower.floor + "F" : Messages.receiptError(receipt));
            host.reload();
            load();
        }, error -> host.showMessage(Messages.GENERIC_ERROR));
    }
}
