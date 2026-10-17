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

    public SlantTabBar(Context context, String[] titles, int[] iconResources) {
        super(context);
        setOrientation(HORIZONTAL);
        setWillNotDraw(false);
        setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(this, 8, 6, 8, 6);
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
            TextView label = Ui.text(context, titles[i], 12, Ui.col(context, R.color.p3r_ink_muted), true);
            label.setGravity(Gravity.CENTER);
            tab.addView(icon);
            LayoutParams labelParams = Ui.wrap();
            labelParams.topMargin = Ui.dp(context, 2);
            tab.addView(label, labelParams);
            LayoutParams params = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
            params.leftMargin = Ui.dp(context, 3);
            params.rightMargin = Ui.dp(context, 3);
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

    /** Highlights a tab without notifying the listener. */
    public void setSelectedTab(int index) {
        selected = index;
        Context context = getContext();
        for (int i = 0; i < tabs.size(); i++) {
            boolean on = i == index;
            LinearLayout tab = tabs.get(i);
            if (on) {
                tab.setBackground(new SlantedPanelDrawable()
                    .fill(Ui.col(context, R.color.p3r_cyan_400))
                    .skew(Ui.dp(context, 12))
                    .slab(Ui.alpha(Ui.col(context, R.color.p3r_white), 200), Ui.dp(context, 3), -Ui.dp(context, 3)));
            } else {
                tab.setBackground(new SlantedPanelDrawable().fill(0)
                    .pressedFill(Ui.col(context, R.color.ui_cyan_soft)).skew(Ui.dp(context, 12)));
            }
            int ink = on ? Ui.col(context, R.color.p3r_navy_950) : Ui.col(context, R.color.p3r_ink_muted);
            icons.get(i).setImageDrawable(Ui.tinted(context, iconRes.get(i), ink));
            labels.get(i).setTextColor(ink);
            tab.setSelected(on);
            tab.setAccessibilityDelegate(new SelectedDelegate(on));
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
        super.onDraw(canvas);
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
