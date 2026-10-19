package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import app.p3r.dayplanner.R;

/** Quiet tools and scope selectors; the strong slanted tabs remain navigation only. */
final class CompendiumControls {
    private CompendiumControls() {}

    static TextView toolButton(Context c, CharSequence label, boolean active) {
        TextView button = Ui.text(c, label, 13, Ui.col(c, R.color.p3r_ice_100), false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(Ui.dp(c, 48));
        button.setMaxLines(1);
        button.setEllipsize(TextUtils.TruncateAt.END);
        Ui.pad(button, 10, 8, 10, 8);
        button.setClickable(true);
        button.setFocusable(true);
        button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
                info.setSelected(host.isSelected());
            }
        });
        setToolActive(button, active);
        return button;
    }

    static void setToolActive(TextView button, boolean active) {
        Context c = button.getContext();
        button.setSelected(active);
        button.setTextColor(Ui.col(c, active ? R.color.p3r_cyan_300 : R.color.p3r_ice_100));
        button.setTypeface(Typeface.DEFAULT);
        int border = active ? Ui.alpha(Ui.col(c, R.color.p3r_cyan_300), 150)
            : Ui.col(c, R.color.p3r_line_soft);
        button.setBackground(background(c, Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 90), border));
        Drawable[] icons = button.getCompoundDrawables();
        Drawable indicator = null;
        if (active) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(Ui.col(c, R.color.p3r_cyan_300));
            dot.setBounds(0, 0, Ui.dp(c, 4), Ui.dp(c, 4));
            indicator = dot;
        }
        button.setCompoundDrawables(icons[0], icons[1], indicator, icons[3]);
        button.setCompoundDrawablePadding(Ui.dp(c, 5));
    }

    static TextView scopeButton(Context c, String label, boolean selected) {
        TextView button = toolButton(c, label, false);
        setScopeSelected(button, selected);
        return button;
    }

    static void setScopeSelected(TextView button, boolean selected) {
        Context c = button.getContext();
        button.setSelected(selected);
        button.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        button.setTextColor(Ui.col(c, selected ? R.color.p3r_white : R.color.p3r_ink_muted));
        button.setBackground(background(c, selected ? Ui.col(c, R.color.p3r_navy_700) : Color.TRANSPARENT,
            selected ? Ui.alpha(Ui.col(c, R.color.p3r_cyan_300), 90) : Color.TRANSPARENT));
    }

    private static Drawable background(Context c, int fill, int border) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_pressed}, rounded(c, Ui.col(c, R.color.p3r_navy_700), border));
        states.addState(new int[0], rounded(c, fill, border));
        return new InsetDrawable(states, 0, Ui.dp(c, 8), 0, Ui.dp(c, 8));
    }

    private static GradientDrawable rounded(Context c, int fill, int border) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(Ui.dp(c, 6));
        shape.setStroke(Ui.dp(c, 1), border);
        return shape;
    }
}
