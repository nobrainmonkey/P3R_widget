package app.p3r.dayplanner.v3.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.IntFunction;

import app.p3r.dayplanner.R;

/**
 * "[−] 27F [+]" stepper with press-and-hold repeat. Values are clamped to [min, max]; the label
 * uses the numeral face through a caller-supplied formatter.
 */
public final class Stepper extends LinearLayout {
    /** Value callback (after every change made by the player). */
    public interface Listener {
        void onValue(int value);
    }

    private static final long REPEAT_DELAY = 380;
    private static final long REPEAT_INTERVAL = 70;

    private final TextView label;
    private final ImageView minus;
    private final ImageView plus;
    private int min;
    private int max;
    private int value;
    private IntFunction<CharSequence> formatter = String::valueOf;
    private Listener listener;
    private Runnable repeater;

    public Stepper(Context context, int min, int max, int value) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        this.min = min;
        this.max = Math.max(min, max);
        minus = stepButton(context, R.drawable.ui_ic_minus, "减少", -1);
        plus = stepButton(context, R.drawable.ui_ic_plus, "增加", +1);
        label = Ui.numeral(context, "", 28, Ui.col(context, R.color.p3r_white));
        label.setGravity(Gravity.CENTER);
        label.setMinWidth(Ui.dp(context, 88));
        addView(minus);
        addView(label, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        addView(plus);
        setValue(value);
    }

    public void setFormatter(IntFunction<CharSequence> formatter) {
        this.formatter = formatter;
        render();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
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

    private void render() {
        label.setText(formatter.apply(value));
        label.setContentDescription(formatter.apply(value));
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
