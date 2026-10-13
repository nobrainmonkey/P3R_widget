package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Single-choice row of slanted segments ("白天 | 晚上", "Lv 1 … 6", "现在余额 | 本次增减").
 * Segments share the width equally; each is at least 40 dp tall.
 */
public final class SegmentedControl extends LinearLayout {
    /** Selection callback. */
    public interface Listener {
        void onSelected(int index);
    }

    private final List<TextView> segments = new ArrayList<>();
    private int selected = -1;
    private Listener listener;

    public SegmentedControl(Context context, List<? extends CharSequence> labels) {
        super(context);
        setOrientation(HORIZONTAL);
        for (int i = 0; i < labels.size(); i++) {
            final int index = i;
            TextView segment = Ui.chip(context, labels.get(i), false);
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
    }

    /** The segment view, e.g. to set a content description. */
    public TextView segment(int index) {
        return segments.get(index);
    }

    public int size() {
        return segments.size();
    }

    private void select(int index, boolean fromUser) {
        if (index >= 0 && !segments.get(index).isEnabled()) {
            return;
        }
        boolean changed = index != selected;
        selected = index;
        for (int i = 0; i < segments.size(); i++) {
            Ui.setChipSelected(segments.get(i), i == index);
        }
        if (fromUser && listener != null && (changed || index >= 0)) {
            listener.onSelected(index);
        }
    }
}
