package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.p3r.dayplanner.R;

/**
 * Bottom navigation in the P3R menu style: tabs on a deep navy strip with a hairline cyan
 * edge; the selected tab sits on a cyan parallelogram with navy ink.
 */
public final class SlantTabBar extends LinearLayout {
    /** Tab selection callback. */
    public interface Listener {
        void onTabSelected(int index);
    }

    private final List<LinearLayout> tabs = new ArrayList<>();
    private final List<ImageView> icons = new ArrayList<>();
    private final List<TextView> labels = new ArrayList<>();
    private final List<Integer> iconRes = new ArrayList<>();
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint();
    private int selected = -1;
    private int edgeWidth = -1;
    private Listener listener;
    /** Position of the selection slab in tab units (animated between tabs). */
    private float indicator = -1;
    private android.animation.ValueAnimator slide;
    private final SlantedPanelDrawable slab = new SlantedPanelDrawable();

    public SlantTabBar(Context context, String[] titles, int[] iconResources) {
        super(context);
        setOrientation(HORIZONTAL);
        setWillNotDraw(false);
        setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(this, titles.length > 5 ? 4 : 8, 6, titles.length > 5 ? 4 : 8, 6);
        for (int i = 0; i < titles.length; i++) {
            final int index = i;
            LinearLayout tab = Ui.column(context);
            tab.setGravity(Gravity.CENTER);
            tab.setMinimumHeight(Ui.dp(context, 52));
            tab.setClickable(true);
            tab.setFocusable(true);
            tab.setContentDescription(titles[i]);
            tab.setOnClickListener(v -> {
                setSelectedTab(index);
                if (listener != null) {
                    listener.onTabSelected(index);
                }
            });
            ImageView icon = Ui.icon(context, iconResources[i], Ui.col(context, R.color.p3r_ink_muted), 22);
            TextView label = Ui.text(context, titles[i], 13, Ui.col(context, R.color.p3r_ink_muted), true);
            label.setGravity(Gravity.CENTER);
            tab.addView(icon);
            LayoutParams labelParams = Ui.wrap();
            labelParams.topMargin = Ui.dp(context, 2);
            tab.addView(label, labelParams);
            LayoutParams params = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
            params.leftMargin = Ui.dp(context, titles.length > 5 ? 1 : 3);
            params.rightMargin = Ui.dp(context, titles.length > 5 ? 1 : 3);
            addView(tab, params);
            tabs.add(tab);
            icons.add(icon);
            labels.add(label);
            iconRes.add(iconResources[i]);
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public int getSelectedTab() {
        return selected;
    }

    /** Highlights a tab without notifying the listener; the highlight slides over when shown. */
    public void setSelectedTab(int index) {
        int previous = selected;
        selected = index;
        Context context = getContext();
        for (int i = 0; i < tabs.size(); i++) {
            boolean on = i == index;
            LinearLayout tab = tabs.get(i);
            tab.setBackground(new SlantedPanelDrawable().fill(0)
                .pressedFill(Ui.col(context, R.color.ui_cyan_soft)).skew(Ui.dp(context, 12)));
            int ink = on ? Ui.col(context, R.color.p3r_navy_950) : Ui.col(context, R.color.p3r_ink_muted);
            icons.get(i).setImageDrawable(Ui.tinted(context, iconRes.get(i), ink));
            labels.get(i).setTextColor(ink);
            tab.setSelected(on);
            tab.setAccessibilityDelegate(new SelectedDelegate(on));
        }
        if (slide != null) {
            slide.cancel();
        }
        if (previous >= 0 && previous != index && index >= 0 && Ui.animationsEnabled() && isLaidOut()) {
            final float from = indicator;
            slide = android.animation.ValueAnimator.ofFloat(0f, 1f);
            slide.setDuration(220);
            slide.setInterpolator(Motion.SETTLE);
            slide.addUpdateListener(a -> {
                indicator = from + (index - from) * (float) a.getAnimatedValue();
                invalidate();
            });
            slide.start();
        } else {
            indicator = index;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        fill.setColor(Ui.alpha(Ui.col(getContext(), R.color.p3r_navy_950), 242));
        canvas.drawRect(0, 0, w, h, fill);
        float line = Math.max(1f, Ui.dp(getContext(), 1));
        if (edgeWidth != w) {
            edgeWidth = w;
            edge.setShader(new LinearGradient(0, 0, w, 0,
                Ui.alpha(Ui.col(getContext(), R.color.p3r_cyan_400), 30),
                Ui.alpha(Ui.col(getContext(), R.color.p3r_cyan_400), 200), Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, 0, w, line, edge);
        drawIndicator(canvas);
        super.onDraw(canvas);
    }

    /** The cyan selection slab under the selected tab, interpolated while it slides. */
    private void drawIndicator(Canvas canvas) {
        if (indicator < 0 || tabs.isEmpty()) {
            return;
        }
        int lower = Math.max(0, Math.min(tabs.size() - 1, (int) Math.floor(indicator)));
        int upper = Math.min(tabs.size() - 1, lower + 1);
        float t = indicator - lower;
        LinearLayout a = tabs.get(lower);
        LinearLayout b = tabs.get(upper);
        if (a.getWidth() == 0) {
            return;
        }
        float left = a.getLeft() + (b.getLeft() - a.getLeft()) * t;
        float right = a.getRight() + (b.getRight() - a.getRight()) * t;
        Context context = getContext();
        slab.fill(Ui.col(context, R.color.p3r_cyan_400))
            .skew(Ui.dp(context, 12))
            .slab(Ui.alpha(Ui.col(context, R.color.p3r_white), 220), Ui.dp(context, 3), Ui.dp(context, 3));
        slab.setBounds(Math.round(left), a.getTop(), Math.round(right), a.getBottom());
        slab.draw(canvas);
    }

    /** Announces the selected state of a tab to TalkBack. */
    private static final class SelectedDelegate extends AccessibilityDelegate {
        private final boolean selected;

        SelectedDelegate(boolean selected) {
            this.selected = selected;
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(android.view.View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setSelected(selected);
            info.setClassName("android.widget.Button");
        }
    }
}
