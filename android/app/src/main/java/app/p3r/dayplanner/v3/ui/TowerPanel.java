package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Arrays;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi;

/**
 * Tartarus return panel, in the order the player answers it after a night in the tower: the
 * highest floor as a big HUD numeral between [−] [+] (prefilled, 「顶层」 jumps to the block's top),
 * the rescue question while a missing person can be saved, the cash as exactly one of 出塔后余额 |
 * 这次多了多少 typed on an inline keypad (「不记得了」 records a net change of 0), and the
 * protagonist level on request. Used for new records and for corrections (which never add a
 * visit); a correction of a visit inside a rescue window asks the rescue question again, preset
 * from the record, so correcting the cash never drops a confirmed rescue.
 *
 * <p>A new record starts with an empty amount and 保存 disabled. When the cash before the visit is
 * not known, only the balance can be typed (the 这次多了多少 tab is not offered). When the plan
 * predicts the rescue on this visit, 「救出了」 is preselected.
 */
final class TowerPanel extends LinearLayout {
    /** Label of the "I don't remember the cash" chip. */
    static final String FORGOT = "不记得了";
    /** The empty amount field. */
    static final String PLACEHOLDER = "—";
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
    private final FrameLayout padHolder;
    private final TextView preview;
    private final TextView save;
    private final TextView levelToggle;
    private final LinearLayout levelRow;
    private final Stepper level;
    private final Rules.Rescue rescue;
    /** Editing an earlier record: the cash before that visit is not known here. */
    private final boolean correction;
    /** The cash before the visit is known (the net change can be typed). */
    private final boolean exactBefore;
    private SegmentedControl rescueAnswer;
    private final TextView forgotChip;
    private Long amount;
    /** 「不记得了」: saved as a net change of 0. */
    private boolean forgot;
    private boolean levelOn;
    /** The inline keypad is open under the cash tabs. */
    private boolean padOpen;

    /**
     * @param initial         values to edit (corrections), or null for a new record
     * @param rememberedLevel last protagonist level the player typed (read off the main thread)
     * @param rescuePredicted the plan step predicts the rescue on this visit (new records)
     */
    TowerPanel(Context c, Rules rules, SimState observed, int slot, int block, PlannerApi.TowerReturn initial,
               boolean correction, int rememberedLevel, boolean rescuePredicted, Listener listener) {
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

        // ---- floor HUD
        addView(caption(c, "最高到达层"));
        LinearLayout floorRow = Ui.row(c);
        int startFloor = initial != null ? initial.floor : Math.max(1, Math.min(top, observed.floor));
        floor = new Stepper(c, 1, top, startFloor);
        floor.setFormatter(v -> v + "F");
        // The floor is Tartarus's key number: a big HUD numeral between the step buttons.
        View value = floor.getChildCount() == 3 ? floor.getChildAt(1) : null;
        if (value instanceof TextView) {
            ((TextView) value).setTextSize(TypedValue.COMPLEX_UNIT_DIP, 44);
            ((TextView) value).setTextColor(Ui.col(c, R.color.p3r_white));
            ((TextView) value).setShadowLayer(0.01f, Ui.dp(c, 2), Ui.dp(c, 2),
                Ui.alpha(Ui.col(c, R.color.p3r_royal_500), 230));
        }
        floorRow.addView(floor, Ui.weight(1));
        TextView topChip = Ui.chip(c, "顶层 " + top + "F", false);
        topChip.setTextSize(13);
        // As tall as the stepper's −/+ buttons beside it.
        topChip.setMinHeight(Ui.dp(c, Ui.TOUCH));
        topChip.setMinimumHeight(Ui.dp(c, Ui.TOUCH));
        topChip.setOnClickListener(v -> floor.setValue(top));
        LinearLayout.LayoutParams tp = Ui.wrap();
        tp.leftMargin = Ui.dp(c, 10);
        floorRow.addView(topChip, tp);
        addView(floorRow, spaced(c, 2));

        // ---- rescue (story-critical, so before the cash)
        int day = Rules.dayOf(slot);
        rescue = correction ? rescueOn(rules, day) : activeRescue(rules, observed, day);
        if (rescue != null) {
            LinearLayout plate = Ui.column(c);
            plate.setBackground(new SlantedPanelDrawable()
                .fill(Ui.alpha(Ui.col(c, R.color.ui_gold), 26))
                .accent(Ui.col(c, R.color.ui_gold), Ui.dp(c, 4))
                .skew(Ui.dp(c, 8)));
            Ui.pad(plate, 18, 10, 12, 12);
            plate.addView(Ui.text(c, rescue.zh + "救出来了吗？", 16, Ui.col(c, R.color.p3r_white), true));
            rescueAnswer = new SegmentedControl(c, Arrays.asList("救出了", correction ? "没救出" : "还没"));
            // Preset: the record (corrections), else the plan's prediction for this visit.
            boolean saved = initial != null ? rescue.id.equals(initial.rescuedId)
                : correction ? rescuedBit(rules, observed, rescue) : rescuePredicted;
            rescueAnswer.setSelected(saved ? 0 : 1);
            plate.addView(rescueAnswer, spaced(c, 8));
            addView(plate, spaced(c, 16));
        }

        // ---- cash: exactly one field
        exactBefore = observed.cashExact || correction;
        LinearLayout cashHead = Ui.row(c);
        cashHead.addView(caption(c, exactBefore ? "现金" : "出塔后的现金"), Ui.weight(1));
        forgotChip = Ui.chip(c, FORGOT, false);
        forgotChip.setTextSize(13);
        forgotChip.setContentDescription("不记得钱了");
        forgotChip.setVisibility(correction ? GONE : VISIBLE);
        cashHead.addView(forgotChip, Ui.wrap());
        addView(cashHead, spaced(c, 18));
        cashMode = new SegmentedControl(c, Arrays.asList("出塔后余额", "这次多了多少"));
        cashMode.segment(0).setContentDescription("填出塔后手上的钱");
        cashMode.segment(1).setContentDescription("填这次多了多少钱");
        // Without the cash before the visit only the balance can be typed: no second tab to tap.
        cashMode.setVisibility(exactBefore ? VISIBLE : GONE);
        addView(cashMode, spaced(c, 6));
        forgotChip.setOnClickListener(v -> {
            forgot = !forgot;
            amount = null;
            padOpen = false;
            // Neither cash field applies: the visit is saved as a net change of 0.
            cashMode.setSelected(forgot ? -1 : 0);
            render();
        });
        amountView = Ui.text(c, "", 26, Ui.col(c, R.color.p3r_white), true);
        amountView.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        amountView.setMinHeight(Ui.dp(c, 64));
        Ui.pad(amountView, 16, 4, 20, 4);
        amountView.setBackground(new SlantedPanelDrawable()
            .fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 170))
            .pressedFill(Ui.col(c, R.color.p3r_navy_700))
            .stroke(Ui.col(c, R.color.p3r_cyan_400), Ui.dp(c, 1.5f))
            .skew(Ui.dp(c, 10)));
        amountView.setClickable(true);
        amountView.setFocusable(true);
        amountView.setOnClickListener(v -> openPad());
        addView(amountView, spaced(c, 8));
        padHolder = new FrameLayout(c);
        addView(padHolder, spaced(c, 4));
        preview = Ui.mixed(c, "", 14, Ui.col(c, R.color.p3r_cyan_200), false);
        addView(preview, spaced(c, 6));

        if (initial != null) {
            cashMode.setSelected(initial.balanceMode || !exactBefore ? 0 : 1);
            amount = initial.amount;
            forgot = initial.cashUnknown;
        } else {
            // Nothing is assumed: the amount starts empty and 保存 waits for it (or 「不记得了」).
            cashMode.setSelected(0);
            amount = null;
        }
        cashMode.setListener(i -> {
            forgot = false;
            amount = correction && i == 1 ? Long.valueOf(0L) : null;
            if (padOpen) {
                showPad();
            }
            render();
        });

        // ---- protagonist level, on request
        levelToggle = Ui.quiet(c, "记一下主角等级");
        levelToggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        Ui.pad(levelToggle, 0, 8, 12, 8);
        levelToggle.setOnClickListener(v -> setLevelOn(true));
        addView(levelToggle, spaced(c, 12));
        levelRow = Ui.row(c);
        levelRow.addView(caption(c, "主角等级"), Ui.wrap());
        int lastLevel = initial != null && initial.level != null ? initial.level : rememberedLevel;
        level = new Stepper(c, 1, 99, lastLevel).compact();
        level.setFormatter(v -> "Lv." + v);
        LinearLayout.LayoutParams lp = Ui.weight(1);
        lp.leftMargin = Ui.dp(c, 12);
        levelRow.addView(level, lp);
        ImageView clear = Ui.icon(c, R.drawable.ui_ic_close, Ui.col(c, R.color.p3r_ink_muted), 20);
        clear.setScaleType(ImageView.ScaleType.CENTER);
        clear.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        clear.setContentDescription("不记主角等级");
        clear.setClickable(true);
        clear.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_soft)));
        clear.setOnClickListener(v -> setLevelOn(false));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(Ui.dp(c, Ui.TOUCH), Ui.dp(c, Ui.TOUCH));
        cp.leftMargin = Ui.dp(c, 6);
        levelRow.addView(clear, cp);
        addView(levelRow, spaced(c, 12));
        setLevelOn(initial != null && initial.level != null);

        // ---- actions
        LinearLayout buttons = Ui.row(c);
        buttons.setBaselineAligned(false);
        if (!correction) {
            TextView cannot = Ui.secondary(c, "没去成");
            cannot.setOnClickListener(v -> listener.onCannot());
            buttons.addView(cannot, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
        }
        save = Ui.primary(c, correction ? "保存修改" : "保存");
        save.setOnClickListener(v -> submit());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, correction ? 1 : 1.6f);
        if (!correction) {
            sp.leftMargin = Ui.dp(c, 14);
        }
        buttons.addView(save, sp);
        addView(buttons, spaced(c, 18));
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

    /** A small muted label above a value. */
    private static TextView caption(Context c, String text) {
        return Ui.text(c, text, 13, Ui.col(c, R.color.p3r_ink_muted), true);
    }

    private static LinearLayout.LayoutParams spaced(Context c, int dp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, dp);
        return p;
    }

    private void setLevelOn(boolean on) {
        levelOn = on;
        levelRow.setVisibility(on ? VISIBLE : GONE);
        levelToggle.setVisibility(on ? GONE : VISIBLE);
    }

    private boolean balanceMode() {
        return cashMode.getSelected() == 0;
    }

    /** Opens the keypad under the cash tabs; it stands in for the amount field while open. */
    private void openPad() {
        if (forgot) {
            forgot = false;
            cashMode.setSelected(0);
        }
        showPad();
        render();
    }

    private void showPad() {
        padOpen = true;
        CashPad.Options options = new CashPad.Options();
        options.title = balanceMode() ? "出塔后手上有多少钱？" : "这次多了多少钱？";
        options.hint = balanceMode() ? null : "少了就按 ±";
        options.initial = amount;
        options.allowUnknown = false;
        options.allowNegative = !balanceMode();
        padHolder.removeAllViews();
        padHolder.addView(CashPad.inline(getContext(), options, value -> {
            if (value != null) {
                amount = value;
                forgot = false;
                render();
            }
        }), new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        Motion.enter(padHolder, 0);
    }

    private void render() {
        Context c = getContext();
        if (!padOpen) {
            padHolder.removeAllViews();
        }
        padHolder.setVisibility(padOpen && !forgot ? VISIBLE : GONE);
        // 「不记得了」 on a new record: neither field applies, so neither tab is offered.
        cashMode.setVisibility(exactBefore && !(forgot && !correction) ? VISIBLE : GONE);
        // A new record shows 「不记得了」 on its chip; a correction has no chip, so the field says it.
        amountView.setVisibility(padOpen || (forgot && !correction) ? GONE : VISIBLE);
        if (forgot) {
            amountView.setText(FORGOT);
            amountView.setTextSize(16);
            amountView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            amountView.setTextColor(Ui.col(c, R.color.p3r_ice_100));
        } else if (amount == null) {
            // Nothing typed yet: a muted dash, not a value.
            amountView.setText(PLACEHOLDER);
            amountView.setTextSize(22);
            amountView.setTypeface(android.graphics.Typeface.DEFAULT);
            amountView.setTextColor(Ui.col(c, R.color.p3r_ink_muted));
        } else {
            amountView.setTextSize(26);
            amountView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            amountView.setTextColor(Ui.col(c, R.color.p3r_white));
            String number = Zh.number(Math.abs(amount));
            String sign = balanceMode() ? "" : (amount < 0 ? "−" : "+");
            amountView.setText(Ui.money(c, sign + number));
        }
        amountView.setContentDescription(forgot ? "不记得钱了" : (balanceMode() ? "出塔后余额 " : "这次多了 ")
            + (amount == null ? "未填" : Zh.yen(amount)));
        String line = previewText();
        preview.setText(Ui.latin(c, line));
        preview.setVisibility(line.isEmpty() ? GONE : VISIBLE);
        SheetChrome.selectChoice(forgotChip, forgot);
        forgotChip.setTextSize(13);
        boolean ready = ready();
        save.setEnabled(ready);
        save.setAlpha(ready ? 1f : 0.5f);
    }

    /** 保存 is possible: an amount was typed, or the player said 「不记得了」. */
    boolean ready() {
        return amount != null || forgot;
    }

    /** True after 「不记得了」 (tests). */
    boolean forgotCash() {
        return forgot;
    }

    /**
     * The one line under the cash, only once there is something to add: "进塔前 8,200 → 出塔后
     * 14,750 日元（+6,550）"; a correction says what the record becomes ("改为：这次 +1,200 日元").
     */
    String previewText() {
        if (forgot || amount == null) {
            return "";
        }
        if (correction) {
            return balanceMode() ? "改为：出塔后 " + Zh.yen(amount) : "改为：这次 " + signed(amount) + " 日元";
        }
        if (!observed.cashExact) {
            // The field already says it all.
            return "";
        }
        long after = balanceMode() ? amount : observed.cash + amount;
        long delta = after - observed.cash;
        return "进塔前 " + Zh.number(observed.cash) + " → 出塔后 " + Zh.yen(after) + "（" + signed(delta) + "）";
    }

    private static String signed(long v) {
        return (v < 0 ? "−" : "+") + Zh.number(Math.abs(v));
    }

    /** The return as entered (null when not ready); 「不记得了」 is a net change of 0. */
    PlannerApi.TowerReturn value() {
        if (!ready()) {
            return null;
        }
        PlannerApi.TowerReturn t = new PlannerApi.TowerReturn();
        t.slot = slot;
        t.block = block;
        t.floor = floor.getValue();
        t.balanceMode = !forgot && balanceMode();
        t.amount = forgot ? 0L : amount;
        t.cashUnknown = forgot;
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
        if (forgot) {
            summary += " · 不记得钱了";
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
        b.putBoolean("forgot", forgot);
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
        forgot = b.getBoolean("forgot", false);
        level.setValue(b.getInt("level", level.getValue()));
        setLevelOn(b.getBoolean("levelOn", false));
        if (rescueAnswer != null) {
            rescueAnswer.setSelected(b.getInt("rescue", 1));
        }
        padOpen = false;
        render();
    }

    /** Test hook: sets the amount as if typed on the keypad. */
    void setAmountForTest(long value) {
        if (forgot || cashMode.getSelected() < 0) {
            cashMode.setSelected(0);
        }
        amount = value;
        forgot = false;
        render();
    }

    /** The rescue question's answer: 0 = 救出了, 1 = 还没/没救出, -1 when not asked (tests). */
    int rescueSelection() {
        return rescueAnswer == null ? -1 : rescueAnswer.getSelected();
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
