package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Arrays;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi;

/**
 * Tartarus return panel: 最高到达层 stepper (prefilled), cash as exactly one of 出塔后余额 | 这次多了多少
 * with a live preview ("进塔前 8,200 → 出塔后 14,750 日元"), optional 主角等级, and the rescue
 * question while a missing person can be saved. Used for new records and for corrections (which
 * never add a visit); a correction of a visit inside a rescue window asks the rescue question
 * again, preset from the record, so correcting the cash never drops a confirmed rescue.
 */
final class TowerPanel extends LinearLayout {
    /** Panel callbacks. */
    interface Listener {
        void onSave(PlannerApi.TowerReturn tower, String summaryZh);

        /** "没去成" (not offered for corrections). */
        void onCannot();
    }

    private final Rules rules;
    private final SimState observed;
    private final int slot;
    private final int block;
    private final Listener listener;
    private final Stepper floor;
    private final SegmentedControl cashMode;
    private final TextView amountView;
    private final TextView preview;
    private final TextView save;
    private final LinearLayout levelBox;
    private final TextView levelToggle;
    private final Stepper level;
    private final Rules.Rescue rescue;
    /** Editing an earlier record: the cash before that visit is not known here. */
    private final boolean correction;
    private SegmentedControl rescueAnswer;
    private Long amount;
    private boolean levelOn;

    /**
     * @param initial         values to edit (corrections), or null for a new record
     * @param rememberedLevel last protagonist level the player typed (read off the main thread)
     */
    TowerPanel(Context c, Rules rules, SimState observed, int slot, int block, PlannerApi.TowerReturn initial,
               boolean correction, int rememberedLevel, Listener listener) {
        super(c);
        setOrientation(VERTICAL);
        this.rules = rules;
        this.observed = observed;
        this.slot = slot;
        this.block = block;
        this.listener = listener;
        this.correction = correction;
        Rules.Block b = block >= 0 && block < rules.blocks.length ? rules.blocks[block] : null;
        int top = b != null ? b.top : maxTop(rules);

        addView(label(c, "最高到达层"));
        LinearLayout floorRow = Ui.row(c);
        int startFloor = initial != null ? initial.floor : Math.max(1, Math.min(top, observed.floor));
        floor = new Stepper(c, 1, top, startFloor);
        floor.setFormatter(v -> v + "F");
        floorRow.addView(floor, Ui.weight(1));
        TextView topChip = Ui.chip(c, "顶层 " + top + "F", false);
        topChip.setTextSize(13);
        topChip.setOnClickListener(v -> floor.setValue(top));
        LinearLayout.LayoutParams tp = Ui.wrap();
        tp.leftMargin = Ui.dp(c, 10);
        floorRow.addView(topChip, tp);
        addView(floorRow, spaced(c, 6));

        addView(label(c, "现金"), spaced(c, 18));
        cashMode = new SegmentedControl(c, Arrays.asList("出塔后余额", "这次多了多少"));
        cashMode.segment(0).setContentDescription("填写出塔后手上的钱");
        cashMode.segment(1).setContentDescription("填写这次进塔多了多少钱");
        addView(cashMode, spaced(c, 6));
        amountView = Ui.text(c, "", 26, Ui.col(c, R.color.p3r_white), true);
        amountView.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        amountView.setMinHeight(Ui.dp(c, 56));
        Ui.pad(amountView, 16, 4, 16, 4);
        amountView.setBackground(new SlantedPanelDrawable()
            .fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 160))
            .pressedFill(Ui.col(c, R.color.p3r_navy_700))
            .stroke(Ui.col(c, R.color.p3r_cyan_400), Ui.dp(c, 1.5f))
            .skew(Ui.dp(c, 10)));
        amountView.setClickable(true);
        amountView.setFocusable(true);
        amountView.setOnClickListener(v -> openPad());
        addView(amountView, spaced(c, 8));
        preview = Ui.mixed(c, "", 13, Ui.col(c, R.color.p3r_cyan_200), false);
        addView(preview, spaced(c, 6));

        boolean exactBefore = observed.cashExact || correction;
        cashMode.setSegmentEnabled(1, exactBefore);
        if (initial != null) {
            cashMode.setSelected(initial.balanceMode || !exactBefore ? 0 : 1);
            amount = initial.amount;
        } else {
            cashMode.setSelected(0);
            amount = observed.cashExact && !correction ? observed.cash : null;
        }
        cashMode.setListener(i -> {
            boolean prefill = i == 0 && observed.cashExact && !correction;
            amount = prefill ? Long.valueOf(observed.cash) : (i == 1 ? Long.valueOf(0L) : null);
            render();
        });

        levelToggle = Ui.quiet(c, "＋ 记录主角等级（可选）");
        levelToggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        levelToggle.setPadding(0, levelToggle.getPaddingTop(), 0, levelToggle.getPaddingBottom());
        levelToggle.setOnClickListener(v -> setLevelOn(!levelOn));
        addView(levelToggle, spaced(c, 12));
        levelBox = Ui.column(c);
        int lastLevel = initial != null && initial.level != null ? initial.level : rememberedLevel;
        level = new Stepper(c, 1, 99, lastLevel);
        level.setFormatter(v -> "Lv." + v);
        levelBox.addView(level);
        addView(levelBox, spaced(c, 4));
        setLevelOn(initial != null && initial.level != null);

        int day = Rules.dayOf(slot);
        rescue = correction ? rescueOn(rules, day) : activeRescue(rules, observed, day);
        if (rescue != null) {
            addView(label(c, rescue.zh + "救出来了吗？"), spaced(c, 18));
            rescueAnswer = new SegmentedControl(c, Arrays.asList("救出了", correction ? "没救出" : "还没"));
            boolean saved = initial != null ? rescue.id.equals(initial.rescuedId)
                : correction && rescuedBit(rules, observed, rescue);
            rescueAnswer.setSelected(saved ? 0 : 1);
            addView(rescueAnswer, spaced(c, 6));
        }

        LinearLayout buttons = Ui.row(c);
        if (!correction) {
            TextView cannot = Ui.secondary(c, "没去成");
            cannot.setOnClickListener(v -> listener.onCannot());
            buttons.addView(cannot, Ui.weight(1));
        }
        save = Ui.primary(c, correction ? "保存修改" : "保存");
        save.setOnClickListener(v -> submit());
        LinearLayout.LayoutParams sp = Ui.weight(correction ? 1 : 1.4f);
        if (!correction) {
            sp.leftMargin = Ui.dp(c, 12);
        }
        buttons.addView(save, sp);
        addView(buttons, spaced(c, 22));
        render();
    }

    private static int maxTop(Rules rules) {
        int top = 1;
        for (Rules.Block b : rules.blocks) {
            top = Math.max(top, b.top);
        }
        return top;
    }

    /** Rescue whose window covers {@code day} and is still open, or null. */
    static Rules.Rescue activeRescue(Rules rules, SimState observed, int day) {
        for (int i = 0; i < rules.rescues.length; i++) {
            Rules.Rescue r = rules.rescues[i];
            boolean done = (observed.rescued & (1L << i)) != 0 || (observed.rescueFailed & (1L << i)) != 0;
            if (!done && day >= r.fromDay && day <= r.toDay) {
                return r;
            }
        }
        return null;
    }

    /** Rescue whose window covers {@code day} (settled or not), or null. */
    static Rules.Rescue rescueOn(Rules rules, int day) {
        for (Rules.Rescue r : rules.rescues) {
            if (day >= r.fromDay && day <= r.toDay) {
                return r;
            }
        }
        return null;
    }

    /** True when the record says this rescue succeeded. */
    private static boolean rescuedBit(Rules rules, SimState observed, Rules.Rescue rescue) {
        for (int i = 0; i < rules.rescues.length && i < 63; i++) {
            if (rules.rescues[i] == rescue) {
                return (observed.rescued & (1L << i)) != 0;
            }
        }
        return false;
    }

    private static TextView label(Context c, String text) {
        return Ui.text(c, text, 15, Ui.col(c, R.color.p3r_white), true);
    }

    private static LinearLayout.LayoutParams spaced(Context c, int dp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, dp);
        return p;
    }

    private void setLevelOn(boolean on) {
        levelOn = on;
        levelBox.setVisibility(on ? VISIBLE : GONE);
        levelToggle.setText(on ? "－ 不记录主角等级" : "＋ 记录主角等级（可选）");
    }

    private boolean balanceMode() {
        return cashMode.getSelected() == 0;
    }

    private void openPad() {
        CashPad.Options options = new CashPad.Options();
        options.title = balanceMode() ? "出塔后手上有多少钱？" : "这次多了多少钱？";
        options.hint = correction ? (balanceMode() ? null : "少了就填负数")
            : observed.cashExact ? "进塔前 " + Zh.yen(observed.cash) + (balanceMode() ? "" : "，少了就填负数")
            : "进塔前的钱没有记录，请直接填出塔后的余额";
        options.initial = amount;
        options.allowUnknown = false;
        options.allowNegative = !balanceMode();
        CashPad.show(getContext(), options, value -> {
            if (value != null) {
                amount = value;
                render();
            }
        });
    }

    private void render() {
        Context c = getContext();
        if (amount == null) {
            amountView.setText("点这里输入");
            amountView.setTextSize(16);
            amountView.setTextColor(Ui.col(c, R.color.p3r_cyan_300));
        } else {
            amountView.setTextSize(26);
            amountView.setTextColor(Ui.col(c, R.color.p3r_white));
            String number = Zh.number(Math.abs(amount));
            String sign = balanceMode() ? "" : (amount < 0 ? "−" : "+");
            amountView.setText(Ui.money(c, sign + number));
        }
        amountView.setContentDescription((balanceMode() ? "出塔后余额 " : "这次多了 ")
            + (amount == null ? "未填写，点按输入" : Zh.yen(amount)));
        preview.setText(Ui.latin(c, previewText()));
        save.setEnabled(amount != null);
        save.setAlpha(amount != null ? 1f : 0.5f);
    }

    /** "进塔前 8,200 → 出塔后 14,750 日元（+6,550）". */
    String previewText() {
        if (correction) {
            if (amount == null) {
                return "填写这次探索后的现金。";
            }
            return balanceMode() ? "改为：出塔后 " + Zh.yen(amount) : "改为：这次 " + signed(amount) + " 日元";
        }
        if (!observed.cashExact) {
            if (amount == null) {
                return "进塔前的钱没有记录，请直接填出塔后的余额。";
            }
            return "出塔后 " + Zh.yen(amount);
        }
        if (amount == null) {
            return "进塔前 " + Zh.yen(observed.cash);
        }
        long after = balanceMode() ? amount : observed.cash + amount;
        long delta = after - observed.cash;
        return "进塔前 " + Zh.number(observed.cash) + " → 出塔后 " + Zh.yen(after) + "（" + signed(delta) + "）";
    }

    private static String signed(long v) {
        return (v < 0 ? "−" : "+") + Zh.number(Math.abs(v));
    }

    /** The return as entered (null amount → not ready). */
    PlannerApi.TowerReturn value() {
        if (amount == null) {
            return null;
        }
        PlannerApi.TowerReturn t = new PlannerApi.TowerReturn();
        t.slot = slot;
        t.block = block;
        t.floor = floor.getValue();
        t.balanceMode = balanceMode();
        t.amount = amount;
        t.level = levelOn ? level.getValue() : null;
        t.rescuedId = rescue != null && rescueAnswer.getSelected() == 0 ? rescue.id : null;
        return t;
    }

    private void submit() {
        PlannerApi.TowerReturn t = value();
        if (t == null) {
            return;
        }
        if (t.level != null) {
            UiPrefs.setTowerLevel(getContext(), t.level);
        }
        String summary = "探索塔尔塔罗斯 · " + t.floor + "F";
        if (t.rescuedId != null && rescue != null) {
            summary += " · 救出" + rescue.zh;
        }
        listener.onSave(t, summary);
    }

    /** Saves the entered values (rotation / process death). */
    Bundle saveState() {
        Bundle b = new Bundle();
        b.putInt("floor", floor.getValue());
        b.putInt("mode", cashMode.getSelected());
        if (amount != null) {
            b.putLong("amount", amount);
        }
        b.putBoolean("levelOn", levelOn);
        b.putInt("level", level.getValue());
        if (rescueAnswer != null) {
            b.putInt("rescue", rescueAnswer.getSelected());
        }
        return b;
    }

    void restoreState(Bundle b) {
        if (b == null) {
            return;
        }
        floor.setValue(b.getInt("floor", floor.getValue()));
        cashMode.setSelected(b.getInt("mode", 0));
        amount = b.containsKey("amount") ? b.getLong("amount") : null;
        level.setValue(b.getInt("level", level.getValue()));
        setLevelOn(b.getBoolean("levelOn", false));
        if (rescueAnswer != null) {
            rescueAnswer.setSelected(b.getInt("rescue", 1));
        }
        render();
    }

    /** Test hook: sets the amount as if typed on the keypad. */
    void setAmountForTest(long value) {
        amount = value;
        render();
    }

    SegmentedControl cashModeControl() {
        return cashMode;
    }

    Stepper floorStepper() {
        return floor;
    }

    View saveButton() {
        return save;
    }
}
