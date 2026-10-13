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
 * 记录历史: newest first, each record with [撤销] and, for tower returns, [修改] (a correction that
 * never adds a visit). Undo appends a void marker; later state is recomputed from the rest.
 */
final class HistoryPage {
    private static final int LIMIT = 80;

    private final HomeHost host;
    private final Context c;
    private final LinearLayout column;

    private HistoryPage(HomeHost host) {
        this.host = host;
        this.c = host.activity();
        column = Ui.column(c);
    }

    static View build(HomeHost host) {
        HistoryPage page = new HistoryPage(host);
        page.load();
        return page.column;
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
        column.removeAllViews();
        if (items.isEmpty()) {
            column.addView(Ui.body(c, "还没有记录。在「今天」里点「完成」后，记录会出现在这里。"));
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
        String meta = (item.voided ? "已撤销 · " : "") + "记录于 " + Zh.age(System.currentTimeMillis(), item.createdMillis);
        bottom.addView(Ui.caption(c, meta), Ui.weight(1));
        if (!item.voided) {
            if (item.correctable) {
                TextView fix = Ui.quiet(c, "修改");
                fix.setContentDescription("修改这条记录：" + item.summaryZh);
                fix.setOnClickListener(v -> openCorrection(item));
                bottom.addView(fix, Ui.wrap());
            }
            TextView undo = Ui.quiet(c, "撤销");
            undo.setContentDescription("撤销这条记录：" + item.summaryZh);
            undo.setOnClickListener(v -> confirmUndo(item));
            bottom.addView(undo, Ui.wrap());
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

    private void openCorrection(PlannerApi.HistoryItem item) {
        UiData d = host.data();
        Rules.Block block = d.rules.blockOn(Rules.dayOf(item.slot));
        int blockIdx = block == null ? -1 : block.idx;
        Async.run(host.activity(), () -> UiPrefs.towerLevel(c), level -> {
            LinearLayout sheet = Ui.column(c);
            Ui.pad(sheet, 20, 12, 20, 20);
            sheet.addView(Ui.title(c, "修改这次探索"));
            sheet.addView(Ui.mixed(c, item.summaryZh, 13, Ui.col(c, R.color.p3r_ink_muted), false));
            sheet.addView(Ui.muted(c, "只改数值，不会多算一次进塔。"));
            TowerPanel panel = new TowerPanel(c, d.rules, d.observed(), item.slot, blockIdx, null, true, level,
                new TowerPanel.Listener() {
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
        String id = CommandIds.of("tower_fix", rev, item.seq, tower.floor, tower.balanceMode, tower.amount, tower.level);
        Async.run(host.activity(), () -> Api.get(c).correctTower(id, rev, item.seq, tower), receipt -> {
            host.dismissSheet();
            host.showMessage(receipt.ok ? "已修改：塔尔塔罗斯 " + tower.floor + "F" : Messages.receiptError(receipt));
            host.reload();
            load();
        }, error -> host.showMessage(Messages.GENERIC_ERROR));
    }
}
