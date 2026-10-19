package app.p3r.dayplanner.v3.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.p3r.dayplanner.R;

/**
 * Settings row: bold title, optional explanation, and a slanted on/off switch on the right whose
 * knob slides over (140 ms, a slight overshoot). The whole row is one ≥ 48 dp touch target and is
 * announced as a switch. {@link #flat()} drops the panel for rows inside a settings list.
 */
public final class ToggleRow extends LinearLayout {
    /** Change callback. */
    public interface Listener {
        void onToggled(boolean checked);
    }

    private final SwitchMark mark;
    private final CharSequence title;
    private final TextView head;
    private final TextView desc;
    private boolean checked;
    private Listener listener;

    public ToggleRow(Context context, CharSequence title, CharSequence description, boolean checked) {
        super(context);
        this.title = title;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(Ui.dp(context, 56));
        Ui.pad(this, 16, 12, 12, 12);
        setBackground(Ui.pressableCard(context));
        LinearLayout texts = Ui.column(context);
        head = Ui.mixed(context, title, 16, Ui.col(context, R.color.p3r_white), true);
        texts.addView(head);
        if (description != null) {
            desc = Ui.muted(context, description);
            LayoutParams p = Ui.wrap();
            p.topMargin = Ui.dp(context, 4);
            texts.addView(desc, p);
        } else {
            desc = null;
        }
        addView(texts, Ui.weight(1));
        mark = new SwitchMark(context);
        LayoutParams markParams = new LayoutParams(Ui.dp(context, 54), Ui.dp(context, 28));
        markParams.leftMargin = Ui.dp(context, 12);
        addView(mark, markParams);
        setClickable(true);
        setFocusable(true);
        setOnClickListener(v -> {
            setChecked(!this.checked);
            if (listener != null) {
                listener.onToggled(this.checked);
            }
        });
        this.checked = checked;
        mark.jumpTo(checked);
        describe();
    }

    /**
     * The list variant for settings pages: no panel, the 18 sp label of the other list rows, a fading
     * underline and a one-line 13 sp description.
     */
    public ToggleRow flat() {
        Context c = getContext();
        // No sweep: the switch itself answers the tap, over a faint cyan press.
        setBackground(new SystemList.Cursor(c, true).noSweep());
        setForeground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_faint))
            .skew(Ui.dp(c, 12)));
        Ui.pad(this, 12, 8, 4, 9);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        if (desc != null) {
            desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            ((LayoutParams) desc.getLayoutParams()).topMargin = Ui.dp(c, 1);
        }
        return this;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setChecked(boolean value) {
        boolean changed = value != checked;
        checked = value;
        if (changed && Ui.animationsEnabled() && isAttachedToWindow()) {
            mark.slideTo(value);
        } else {
            mark.jumpTo(value);
        }
        describe();
    }

    private void describe() {
        setContentDescription(title + (checked ? "，已开启" : "，已关闭"));
    }

    public boolean isChecked() {
        return checked;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Switch");
        info.setCheckable(true);
        info.setChecked(checked);
    }

    /** The drawn switch: a parallelogram track with a parallelogram knob. */
    private static final class SwitchMark extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        /** 0 = off (knob left), 1 = on (knob right); may overshoot a little while sliding. */
        private float position;
        private boolean on;
        private ValueAnimator animator;

        SwitchMark(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void jumpTo(boolean value) {
            cancel();
            on = value;
            position = value ? 1f : 0f;
            invalidate();
        }

        void slideTo(boolean value) {
            cancel();
            on = value;
            animator = ValueAnimator.ofFloat(position, value ? 1f : 0f);
            animator.setDuration(140);
            animator.setInterpolator(new OvershootInterpolator(1.6f));
            animator.addUpdateListener(a -> {
                position = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        private void cancel() {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            jumpTo(on);
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Context c = getContext();
            float w = getWidth();
            float h = getHeight();
            float skew = h * 0.35f;
            float stroke = Ui.dp(c, 1.5f);
            float half = stroke / 2f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(on ? Ui.col(c, R.color.p3r_cyan_400) : Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 200));
            parallelogram(half, half, w - half, h - half, skew);
            canvas.drawPath(path, paint);
            if (!on) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                paint.setColor(Ui.col(c, R.color.p3r_ink_faint));
                canvas.drawPath(path, paint);
            }
            float inset = Ui.dp(c, 4);
            float knob = w * 0.46f;
            float travel = w - knob - inset * 2 + skew * 0.2f;
            float left = inset + travel * position;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(on ? Ui.col(c, R.color.p3r_navy_950) : Ui.col(c, R.color.p3r_ink_muted));
            parallelogram(left, inset, left + knob - skew * 0.2f, h - inset, skew * 0.7f);
            canvas.drawPath(path, paint);
        }

        private void parallelogram(float l, float t, float r, float b, float skew) {
            path.reset();
            path.moveTo(l + skew, t);
            path.lineTo(r, t);
            path.lineTo(r - skew, b);
            path.lineTo(l, b);
            path.close();
        }
    }
}
