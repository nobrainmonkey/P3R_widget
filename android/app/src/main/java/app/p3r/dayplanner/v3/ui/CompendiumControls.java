package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import app.p3r.dayplanner.R;

/**
 * Quiet tool plates (filter, favorites, DLC, sort direction): slanted outlines inside a 48 dp
 * target; an active tool turns cyan and shows a small dot. The strong slanted chips stay for
 * navigation and choices. Also used by the 提示 filter.
 */
final class CompendiumControls {
    private CompendiumControls() {}

    private static final View.AccessibilityDelegate BUTTON = new View.AccessibilityDelegate() {
        @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName("android.widget.Button");
            info.setSelected(host.isSelected());
        }
    };

    /** A square icon plate (no text) with {@code description} for TalkBack and the tooltip. */
    static TextView icon(Context c, String description, int drawableRes) {
        TextView button = new IconTool(c, drawableRes);
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setClickable(true);
        button.setFocusable(true);
        button.setAccessibilityDelegate(BUTTON);
        Motion.pressable(button);
        setToolActive(button, false);
        return button;
    }

    static TextView toolButton(Context c, CharSequence label, boolean active) {
        TextView button = Ui.text(c, label, 13, Ui.col(c, R.color.p3r_ice_100), false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(Ui.dp(c, 48));
        button.setMaxLines(1);
        button.setEllipsize(TextUtils.TruncateAt.END);
        Ui.pad(button, 12, 8, 12, 8);
        button.setClickable(true);
        button.setFocusable(true);
        button.setAccessibilityDelegate(BUTTON);
        setToolActive(button, active);
        return button;
    }

    static void setToolActive(TextView button, boolean active) {
        Context c = button.getContext();
        button.setSelected(active);
        button.setTextColor(Ui.col(c, active ? R.color.p3r_cyan_300 : R.color.p3r_ice_100));
        button.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        int border = active ? Ui.col(c, R.color.p3r_cyan_400) : Ui.alpha(Ui.col(c, R.color.p3r_cyan_300), 110);
        int fill = active ? Ui.col(c, R.color.ui_cyan_soft) : Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 120);
        // An inset background would replace the label's padding with its insets.
        int left = button.getPaddingLeft();
        int top = button.getPaddingTop();
        int right = button.getPaddingRight();
        int bottom = button.getPaddingBottom();
        button.setBackground(background(c, fill, border));
        button.setPadding(left, top, right, bottom);
        if (button instanceof IconTool) {
            ((IconTool) button).icon.setTint(button.getCurrentTextColor());
        }
        button.invalidate();
    }

    /** A slanted outline plate, inset to sit inside the 48 dp target. */
    private static Drawable background(Context c, int fill, int border) {
        SlantedPanelDrawable plate = new SlantedPanelDrawable()
            .fill(fill)
            .pressedFill(Ui.col(c, R.color.p3r_navy_700))
            .stroke(border, Ui.dp(c, 1.2f))
            .skew(Ui.dp(c, 6));
        return new InsetDrawable(plate, 0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
    }

    private static final class IconTool extends TextView {
        private final Drawable icon;
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);

        IconTool(Context c, int resource) {
            super(c);
            icon = c.getDrawable(resource).mutate();
            dot.setColor(Ui.col(c, R.color.p3r_cyan_400));
            setMinWidth(Ui.dp(c, 48));
            setMaxWidth(Ui.dp(c, 48));
            setMinHeight(Ui.dp(c, 48));
            setMaxHeight(Ui.dp(c, 48));
            setPadding(0, 0, 0, 0);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int size = Ui.dp(getContext(), 22);
            int left = (getWidth() - size) / 2;
            int top = (getHeight() - size) / 2;
            icon.setBounds(left, top, left + size, top + size);
            icon.draw(canvas);
            if (isSelected()) {
                float r = Ui.dp(getContext(), 3);
                canvas.drawCircle(getWidth() - Ui.dp(getContext(), 9), Ui.dp(getContext(), 11), r, dot);
            }
        }
    }
}
