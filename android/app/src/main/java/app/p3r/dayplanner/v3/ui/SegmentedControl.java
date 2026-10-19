package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.p3r.dayplanner.R;

/**
 * Single-choice row of slanted segments ("白天 | 晚上", "Lv 1 … 6", "现在余额 | 本次增减").
 * Segments share the width equally; each is at least 40 dp tall. The cyan selection is one
 * parallelogram that slides to the segment chosen (150 ms; instant without animations).
 */
public final class SegmentedControl extends LinearLayout {
    /** Selection callback. */
    public interface Listener {
        void onSelected(int index);
    }

    private final List<TextView> segments = new ArrayList<>();
    private final SlantedPanelDrawable slot;
    private final SystemList.Slide slide;
    private int selected = -1;
    private Listener listener;

    public SegmentedControl(Context context, List<? extends CharSequence> labels) {
        super(context);
        setOrientation(HORIZONTAL);
        int skew = Ui.dp(context, 6);
        slot = new SlantedPanelDrawable()
            .fill(Ui.alpha(Ui.col(context, R.color.p3r_navy_950), 140))
            .stroke(Ui.alpha(Ui.col(context, R.color.p3r_line), 150), Ui.dp(context, 1))
            .skew(skew);
        slide = new SystemList.Slide(this, new SlantedPanelDrawable()
            .fill(Ui.col(context, R.color.p3r_cyan_400))
            .slab(Ui.alpha(Ui.col(context, R.color.p3r_white), 210), Ui.dp(context, 2), Ui.dp(context, 2))
            .skew(skew));
        for (int i = 0; i < labels.size(); i++) {
            final int index = i;
            TextView segment = Ui.chip(context, labels.get(i), false);
            style(segment, false);
            segment.setOnClickListener(v -> select(index, true));
            LayoutParams params = Ui.weight(1);
            if (i > 0) {
                params.leftMargin = Ui.dp(context, 4);
            }
            addView(segment, params);
            segments.add(segment);
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Selects a segment without notifying the listener (-1 clears the selection). */
    public void setSelected(int index) {
        select(index, false);
    }

    public int getSelected() {
        return selected;
    }

    /** Enables or disables one segment (disabled segments are dimmed and not clickable). */
    public void setSegmentEnabled(int index, boolean enabled) {
        TextView segment = segments.get(index);
        segment.setEnabled(enabled);
        segment.setAlpha(enabled ? 1f : 0.38f);
        invalidate();
    }

    /** The segment view, e.g. to set a content description. */
    public TextView segment(int index) {
        return segments.get(index);
    }

    private void select(int index, boolean fromUser) {
        if (index >= 0 && !segments.get(index).isEnabled()) {
            return;
        }
        boolean changed = index != selected;
        selected = index;
        for (int i = 0; i < segments.size(); i++) {
            style(segments.get(i), i == index);
        }
        slide.select(index);
        if (fromUser && listener != null && (changed || index >= 0)) {
            listener.onSelected(index);
        }
    }

    /** Ink and state of a segment; the faces are drawn by this row (slots, then the sliding cyan). */
    private void style(TextView segment, boolean on) {
        Context c = getContext();
        segment.setBackground(new SlantedPanelDrawable().fill(0)
            .pressedFill(on ? 0 : Ui.col(c, R.color.ui_cyan_soft)).skew(Ui.dp(c, 6)));
        segment.setTextColor(Ui.col(c, on ? R.color.p3r_navy_950 : R.color.p3r_ice_100));
        segment.setSelected(on);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        for (TextView segment : segments) {
            if (segment.getVisibility() != VISIBLE || segment.getWidth() == 0) {
                continue;
            }
            slot.setAlpha(Math.round(255 * segment.getAlpha()));
            slot.setBounds(segment.getLeft(), segment.getTop(), segment.getRight(), segment.getBottom());
            slot.draw(canvas);
        }
        slide.draw(canvas);
        super.dispatchDraw(canvas);
    }
}
