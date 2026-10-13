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
 * Compact checkbox row: a slanted box with a check mark and a label, the whole row one ≥ 48 dp
 * touch target, announced as a checkbox ("顺便的事也做了").
 */
public final class CheckRow extends LinearLayout {
    /** Change callback. */
    public interface Listener {
        void onChanged(boolean checked);
    }

    private final Box box;
    private final CharSequence label;
    private boolean checked;
    private Listener listener;

    public CheckRow(Context context, CharSequence label, boolean checked) {
        super(context);
        this.label = label;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(Ui.dp(context, Ui.TOUCH));
        box = new Box(context);
        LayoutParams boxParams = new LayoutParams(Ui.dp(context, 26), Ui.dp(context, 22));
        boxParams.rightMargin = Ui.dp(context, 10);
        addView(box, boxParams);
        TextView text = Ui.mixed(context, label, 15, Ui.col(context, R.color.p3r_white), true);
        addView(text, Ui.weight(1));
        setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(context, R.color.ui_cyan_faint)));
        setClickable(true);
        setFocusable(true);
        setOnClickListener(v -> {
            setChecked(!this.checked);
            if (listener != null) {
                listener.onChanged(this.checked);
            }
        });
        setChecked(checked);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setChecked(boolean value) {
        checked = value;
        box.invalidate();
        setContentDescription(label + (value ? "，已勾选" : "，未勾选"));
    }

    public boolean isChecked() {
        return checked;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.CheckBox");
        info.setCheckable(true);
        info.setChecked(checked);
    }

    /** The drawn box: a parallelogram outline, filled cyan with a navy tick when checked. */
    private final class Box extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Box(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Context c = getContext();
            float w = getWidth();
            float h = getHeight();
            float skew = h * 0.28f;
            path.reset();
            path.moveTo(skew, 0);
            path.lineTo(w, 0);
            path.lineTo(w - skew, h);
            path.lineTo(0, h);
            path.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(checked ? Ui.col(c, R.color.p3r_cyan_400) : Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 200));
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(c, 1.5f));
            paint.setColor(checked ? Ui.col(c, R.color.p3r_cyan_400) : Ui.col(c, R.color.p3r_cyan_300));
            canvas.drawPath(path, paint);
            if (checked) {
                paint.setColor(Ui.col(c, R.color.p3r_navy_950));
                paint.setStrokeWidth(Ui.dp(c, 2.4f));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                path.reset();
                path.moveTo(w * 0.30f, h * 0.52f);
                path.lineTo(w * 0.45f, h * 0.74f);
                path.lineTo(w * 0.74f, h * 0.26f);
                canvas.drawPath(path, paint);
            }
        }
    }
}
