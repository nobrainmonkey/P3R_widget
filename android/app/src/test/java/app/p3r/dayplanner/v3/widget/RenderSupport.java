package app.p3r.dayplanner.v3.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertTrue;

/** Test helpers: inflate RemoteViews like a launcher, lay them out at a dp size and save PNGs. */
final class RenderSupport {
    private RenderSupport() {}

    static File outputDir(String sub) {
        File dir = new File(System.getProperty("p3r.qa.dir", "build/qa") + "/v3/widget/" + sub);
        dir.mkdirs();
        return dir;
    }

    /** Applies {@code views} and lays the result out at widthDp × heightDp. */
    static View layout(Context c, RemoteViews views, float widthDp, float heightDp) {
        View root = views.apply(c, new FrameLayout(c));
        float density = c.getResources().getDisplayMetrics().density;
        int w = Math.round(widthDp * density);
        int h = Math.round(heightDp * density);
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        return root;
    }

    static Bitmap draw(View root) {
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        return bitmap;
    }

    static void save(View root, File dir, String name) throws IOException {
        Bitmap bitmap = draw(root);
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + "-sdk" + Build.VERSION.SDK_INT + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
    }

    /** Bounds of {@code child} in {@code root} coordinates. */
    static Rect boundsIn(View root, View child) {
        Rect r = new Rect(0, 0, child.getWidth(), child.getHeight());
        ((ViewGroup) root).offsetDescendantRectToMyCoords(child, r);
        return r;
    }

    /**
     * Collects clipping problems of every visible TextView: lines cut at the bottom, text lost at the
     * end, or a single-line label wider than its box.
     */
    static void collectTextIssues(View view, String tag, List<String> issues) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.getText().length() == 0 || text.getLayout() == null) return;
            int last = text.getLineCount() - 1;
            int bottom = text.getLayout().getLineBottom(last) + text.getCompoundPaddingTop() + text.getCompoundPaddingBottom();
            if (bottom > text.getHeight() + 1) {
                issues.add(tag + " vertical clip id=" + name(text) + " '" + text.getText() + "' needs=" + bottom + " has=" + text.getHeight());
            }
            boolean ellipsized = false;
            for (int i = 0; i <= last; i++) ellipsized |= text.getLayout().getEllipsisCount(i) > 0;
            if (!ellipsized && text.getLayout().getLineEnd(last) < text.getText().length()) {
                issues.add(tag + " lost text id=" + name(text) + " '" + text.getText() + "'");
            }
            if (text.getMaxLines() == 1 && !ellipsized) {
                float needed = text.getPaint().measureText(text.getText().toString());
                int available = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
                if (needed > available + 1) {
                    issues.add(tag + " horizontal clip id=" + name(text) + " '" + text.getText() + "' needs=" + needed + " has=" + available);
                }
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectTextIssues(group.getChildAt(i), tag, issues);
        }
    }

    /** Children of a horizontal container must not overlap or leave it. */
    static void collectOverflow(View root, int containerId, String tag, List<String> issues) {
        View found = root.findViewById(containerId);
        if (!(found instanceof ViewGroup) || found.getVisibility() != View.VISIBLE) return;
        ViewGroup group = (ViewGroup) found;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child.getLeft() < 0 || child.getRight() > group.getWidth() + 1) {
                issues.add(tag + " child " + name(child) + " leaves " + name(group) + " [" + child.getLeft() + "," + child.getRight() + "] of " + group.getWidth());
            }
        }
    }

    /** A shown location must keep enough width to read (a long tag chip must not starve it). */
    static void collectSqueezedLocation(View root, String tag, List<String> issues) {
        View location = root.findViewById(app.p3r.dayplanner.R.id.widget_location);
        if (location == null || location.getVisibility() != View.VISIBLE) return;
        float density = root.getResources().getDisplayMetrics().density;
        if (location.getWidth() < 40 * density) {
            issues.add(tag + " location squeezed to " + location.getWidth() / density + "dp '" + ((TextView) location).getText() + "'");
        }
    }

    /** The status cluster (progress label/ticks, wordmark) must never overlap the calendar plate. */
    static void collectHeaderOverlap(View root, String tag, List<String> issues) {
        View plate = root.findViewById(app.p3r.dayplanner.R.id.widget_plate);
        View status = root.findViewById(app.p3r.dayplanner.R.id.widget_status);
        if (plate == null || status == null || status.getVisibility() != View.VISIBLE || status.getWidth() == 0) return;
        Rect a = boundsIn(root, plate);
        Rect b = boundsIn(root, status);
        if (Rect.intersects(a, b)) issues.add(tag + " status cluster overlaps the plate " + a + " / " + b);
        if (b.right > root.getWidth()) issues.add(tag + " status cluster leaves the card");
    }

    static String name(View v) {
        try {
            return v.getId() == View.NO_ID ? v.getClass().getSimpleName() : v.getResources().getResourceEntryName(v.getId());
        } catch (RuntimeException e) {
            return String.valueOf(v.getId());
        }
    }
}
