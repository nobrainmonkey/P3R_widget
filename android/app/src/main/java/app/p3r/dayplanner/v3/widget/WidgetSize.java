package app.p3r.dayplanner.v3.widget;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.p3r.dayplanner.R;

/** Size buckets and host-reported sizes shared by both widgets. All sizes are in dp. */
public enum WidgetSize {
    /** Narrow (under 220dp): the plate spans the whole width, no status cluster. */
    TINY(R.layout.widget_card_tiny),
    /** Mid width or short height. */
    COMPACT(R.layout.widget_card_compact),
    /** About 4x2 cells and larger. */
    REGULAR(R.layout.widget_card_regular);

    /** Hosts may report at most this many sizes before we fall back to a single guess. */
    static final int MAX_REPORTED_SIZES = 12;

    public final int layout;

    WidgetSize(int layout) {
        this.layout = layout;
    }

    public static WidgetSize of(float widthDp, float heightDp) {
        if (widthDp < 220) return TINY;
        if (widthDp < 280 || heightDp < 175) return COMPACT;
        return REGULAR;
    }

    /**
     * Sizes the host renders this widget at (API 31+ responsive layouts), or an empty list when the
     * host does not report them; then use {@link #fallback}.
     */
    public static List<SizeF> reportedSizes(Bundle options) {
        if (Build.VERSION.SDK_INT < 31 || options == null) return Collections.emptyList();
        List<SizeF> sizes = options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES);
        if (sizes == null || sizes.isEmpty() || sizes.size() > MAX_REPORTED_SIZES) return Collections.emptyList();
        List<SizeF> usable = new ArrayList<>();
        for (SizeF size : sizes) {
            if (size.getWidth() > 0 && size.getHeight() > 0) usable.add(size);
        }
        return usable;
    }

    /**
     * Best single-size guess before API 31: portrait hosts give min width × max height, landscape
     * hosts max width × min height (same rule the legacy widget has always used).
     */
    public static float[] fallback(Context context, Bundle options) {
        if (options == null) return new float[]{280, 180};
        boolean landscape = context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280);
        int maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minW);
        int minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180);
        int maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minH);
        float width = Math.max(180, landscape ? maxW : minW);
        float height = Math.max(140, landscape ? minH : maxH);
        return new float[]{width, height};
    }
}
