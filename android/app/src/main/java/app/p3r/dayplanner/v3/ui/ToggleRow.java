package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.p3r.dayplanner.R;

/**
 * Settings row: bold title, optional explanation, and a slanted on/off switch on the right.
 * The whole row is one ≥ 48 dp touch target and is announced as a switch.
 */
public final class ToggleRow extends LinearLayout {
    /** Change callback. */
    public interface Listener {
        void onToggled(boolean checked);
    }

    private final SwitchMark mark;
    private final CharSequence title;
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
        TextView head = Ui.mixed(context, title, 16, Ui.col(context, R.color.p3r_white), true);
        texts.addView(head);
        if (description != null) {
            TextView desc = Ui.muted(context, description);
            LayoutParams p = Ui.wrap();
            p.topMargin = Ui.dp(context, 4);
            texts.addView(desc, p);
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
        setChecked(checked);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setChecked(boolean value) {
        checked = value;
        mark.setOn(value);
        setContentDescription(title + (value ? "，已开启" : "，已关闭"));
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
        private boolean on;

        SwitchMark(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setOn(boolean value) {
            on = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Context c = getContext();
            float w = getWidth();
            float h = getHeight();
            float skew = h * 0.35f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(on ? Ui.col(c, R.color.ui_cyan_soft) : Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 200));
            parallelogram(0, 0, w, h, skew);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(c, 1.5f));
            paint.setColor(on ? Ui.col(c, R.color.p3r_cyan_400) : Ui.col(c, R.color.p3r_ink_faint));
            canvas.drawPath(path, paint);
            float inset = Ui.dp(c, 4);
            float knob = w * 0.46f;
            float left = on ? w - knob - inset + skew * 0.2f : inset;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(on ? Ui.col(c, R.color.p3r_cyan_400) : Ui.col(c, R.color.p3r_ink_muted));
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
