package app.p3r.dayplanner.v3.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.IntFunction;

import app.p3r.dayplanner.R;

/**
 * "[−] 27F [+]" stepper with press-and-hold repeat. Values are clamped to [min, max]; the label
 * uses the numeral face through a caller-supplied formatter. On a wide range (or after
 * {@link #typeable}) tapping the value opens a number pad, so 96F is typed instead of held for.
 */
public final class Stepper extends LinearLayout {
    /** Value callback (after every change made by the player). */
    public interface Listener {
        void onValue(int value);
    }

    private static final long REPEAT_DELAY = 380;
    private static final long REPEAT_INTERVAL = 70;
    /** A range wider than this can also be typed. */
    private static final int TYPE_RANGE = 30;

    private final TextView label;
    private final ImageView minus;
    private final ImageView plus;
    private int min;
    private int max;
    private int value;
    private IntFunction<CharSequence> formatter = String::valueOf;
    private IntFunction<String> describer;
    private Listener listener;
    private Runnable repeater;
    private boolean hero;
    private boolean typeable;
    private String padTitle;
    private String padUnit = "";

    public Stepper(Context context, int min, int max, int value) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        this.min = min;
        this.max = Math.max(min, max);
        minus = stepButton(context, R.drawable.ui_ic_minus, "减少", -1);
        plus = stepButton(context, R.drawable.ui_ic_plus, "增加", +1);
        label = Ui.text(context, "", 24, Ui.col(context, R.color.p3r_white), true);
        label.setGravity(Gravity.CENTER);
        label.setMinWidth(Ui.dp(context, 88));
        label.setOnClickListener(v -> openPad());
        addView(minus);
        addView(label, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        addView(plus);
        setValue(value);
    }

    public void setFormatter(IntFunction<CharSequence> formatter) {
        this.formatter = formatter;
        render();
    }

    /** Spoken text of a value when the formatter's text is not plain Chinese ("最高到达 96F"). */
    public void setDescriber(IntFunction<String> describer) {
        this.describer = describer;
        render();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Smaller value label (for a stepper that shares its row with a label). */
    public Stepper compact() {
        label.setMinWidth(Ui.dp(getContext(), 64));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        int w = Ui.dp(getContext(), 46);
        minus.getLayoutParams().width = w;
        plus.getLayoutParams().width = w;
        requestLayout();
        return this;
    }

    /**
     * The value as a big numeral (the formatter must return Latin text only, e.g. "96F/117F"
     * with spans); the −/+ buttons stay for small nudges.
     */
    public Stepper hero() {
        hero = true;
        label.setTypeface(Ui.numerals(getContext()));
        label.setIncludeFontPadding(false);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 44);
        float shadow = Ui.dp(getContext(), 2);
        label.setShadowLayer(0.01f, shadow, shadow, Ui.alpha(Ui.col(getContext(), R.color.p3r_royal_500), 220));
        label.setMinHeight(Ui.dp(getContext(), 56));
        render();
        return this;
    }

    /** Tapping the value opens a number pad titled {@code title}; {@code unit} follows the number ("F"). */
    public Stepper typeable(String title, String unit) {
        typeable = true;
        padTitle = title;
        padUnit = unit == null ? "" : unit;
        render();
        return this;
    }

    /** Value label text (tests). */
    CharSequence labelText() {
        return label.getText();
    }

    /** The value label, which opens the number pad when the value can be typed (tests). */
    TextView valueView() {
        return label;
    }

    public void setRange(int min, int max) {
        this.min = min;
        this.max = Math.max(min, max);
        setValue(value);
    }

    public void setValue(int newValue) {
        value = Math.max(min, Math.min(max, newValue));
        render();
    }

    public int getValue() {
        return value;
    }

    /** Applies a delta as if the player pressed a button (notifies the listener). */
    public void step(int delta) {
        int before = value;
        setValue(value + delta);
        if (value != before && listener != null) {
            listener.onValue(value);
        }
    }

    /** Sets a typed value as if the player entered it (clamped; notifies the listener). */
    void enter(int typed) {
        step(typed - value);
    }

    private boolean canType() {
        return typeable || max - min > TYPE_RANGE;
    }

    private void openPad() {
        if (!canType() || !isEnabled()) {
            return;
        }
        CashPad.Options options = new CashPad.Options();
        options.title = padTitle;
        options.initial = (long) value;
        options.allowUnknown = false;
        options.unit = padUnit;
        options.max = (long) max;
        options.tripleZero = false;
        CashPad.show(getContext(), options, typed -> {
            if (typed != null) {
                enter((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, typed)));
            }
        });
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        render();
    }

    private void render() {
        CharSequence text = formatter.apply(value);
        label.setText(hero ? text : Ui.latin(getContext(), text));
        String spoken = describer != null ? describer.apply(value) : Zh.plain(text);
        label.setContentDescription(spoken);
        boolean type = canType() && isEnabled();
        label.setClickable(type);
        label.setFocusable(type);
        label.setBackground(type ? new SlantedPanelDrawable().fill(0)
            .pressedFill(Ui.col(getContext(), R.color.ui_cyan_soft)).skew(Ui.dp(getContext(), 8)) : null);
        minus.setEnabled(value > min);
        minus.setAlpha(value > min ? 1f : 0.35f);
        plus.setEnabled(value < max);
        plus.setAlpha(value < max ? 1f : 0.35f);
    }

    @SuppressLint("ClickableViewAccessibility")
    private ImageView stepButton(Context context, int icon, String description, int delta) {
        ImageView button = new ImageView(context);
        button.setImageDrawable(Ui.tinted(context, icon, Ui.col(context, R.color.p3r_white)));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(description);
        button.setBackground(new SlantedPanelDrawable()
            .fill(Ui.alpha(Ui.col(context, R.color.p3r_navy_950), 140))
            .pressedFill(Ui.col(context, R.color.p3r_royal_600))
            .stroke(Ui.col(context, R.color.p3r_line), Ui.dp(context, 1))
            .skew(Ui.dp(context, 6)));
        int size = Ui.dp(context, 52);
        button.setLayoutParams(new LayoutParams(size, Ui.dp(context, Ui.TOUCH)));
        button.setClickable(true);
        button.setOnClickListener(v -> step(delta));
        button.setOnLongClickListener(v -> {
            startRepeat(delta);
            return true;
        });
        button.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                stopRepeat();
            }
            return false;
        });
        return button;
    }

    private void startRepeat(int delta) {
        stopRepeat();
        repeater = new Runnable() {
            @Override
            public void run() {
                step(delta);
                if ((delta < 0 && value > min) || (delta > 0 && value < max)) {
                    postDelayed(this, REPEAT_INTERVAL);
                }
            }
        };
        postDelayed(repeater, REPEAT_DELAY - REPEAT_INTERVAL);
    }

    private void stopRepeat() {
        if (repeater != null) {
            removeCallbacks(repeater);
            repeater = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopRepeat();
        super.onDetachedFromWindow();
    }
}
