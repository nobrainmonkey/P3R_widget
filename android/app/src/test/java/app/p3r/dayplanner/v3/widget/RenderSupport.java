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

    /** Whether any line of {@code text} ends in "…". */
    static boolean ellipsized(TextView text) {
        if (text.getLayout() == null) return false;
        for (int i = 0; i < text.getLineCount(); i++) {
            if (text.getLayout().getEllipsisCount(i) > 0) return true;
        }
        return false;
    }

    /**
     * Key information is never cut short with "…" (review: 「魅力 +4 → Lv.…」, 「奢豪之…」,
     * 「结缘网站的字条 · 岳羽由…」, 「花费 1,000 日…」, 「赴约也算一次见…」): the tag chip, the location, the
     * note (cue) line and a title with a name in it (「 · 」 parts) are either whole or not shown.
     */
    static void collectCutText(View root, String tag, List<String> issues) {
        for (int id : new int[]{app.p3r.dayplanner.R.id.widget_tag, app.p3r.dayplanner.R.id.widget_location,
                app.p3r.dayplanner.R.id.widget_detail_cue, app.p3r.dayplanner.R.id.widget_dialogue_choices,
                app.p3r.dayplanner.R.id.widget_reminder}) {
            TextView text = root.findViewById(id);
            if (text.getVisibility() == View.VISIBLE && ellipsized(text)) {
                issues.add(tag + " cuts " + name(text) + ": '" + CardText.plain(text.getText().toString()) + "'");
            }
        }
        TextView title = root.findViewById(app.p3r.dayplanner.R.id.widget_task);
        if (titleText(title).contains(" · ") && ellipsized(title)) {
            issues.add(tag + " cuts the name in its title: '" + titleText(title) + "'");
        }
    }

    /** The title a reader sees, on one line as the card model holds it ({@link CardText#plainTitle}). */
    static String titleText(TextView title) {
        return CardText.plainTitle(title.getText().toString());
    }

    /**
     * Review: 「结缘网站的字条 · / 山岸风花」, 「课堂 · / 赫尔墨斯文集（3）」. A title that wraps at a 「 · 」
     * wraps there without the dot: no line of it ends (or starts) with 「·」.
     */
    static void collectDotLineEnds(View root, String tag, List<String> issues) {
        TextView title = root.findViewById(app.p3r.dayplanner.R.id.widget_task);
        if (title == null || title.getVisibility() != View.VISIBLE || title.getLayout() == null) return;
        android.text.Layout layout = title.getLayout();
        String s = title.getText().toString();
        for (int line = 0; line < layout.getLineCount(); line++) {
            String shown = CardText.plain(s.substring(layout.getLineStart(line), layout.getLineEnd(line))).trim();
            if (shown.endsWith("·") || shown.startsWith("·")) issues.add(tag + " title line '" + shown + "' of '" + lines(title) + "'");
        }
    }

    /**
     * Review: 「／3楼、严户台、港湾人工岛站自动贩卖机」 (the shortened 「宿舍2楼／3楼、…」). A shown location
     * never starts or ends with a separator.
     */
    static void collectLocationEdges(View root, String tag, List<String> issues) {
        TextView location = root.findViewById(app.p3r.dayplanner.R.id.widget_location);
        if (location == null || location.getVisibility() != View.VISIBLE) return;
        String shown = CardText.plain(location.getText().toString()).trim();
        if (shown.isEmpty()) return;
        String separators = "／/·、";
        if (separators.indexOf(shown.charAt(0)) >= 0 || separators.indexOf(shown.charAt(shown.length() - 1)) >= 0) {
            issues.add(tag + " location '" + shown + "' starts or ends with a separator");
        }
    }

    /** Bottom (root coordinates) of the lowest shown line of text in the card body. */
    static int lastTextBottom(View root) {
        ViewGroup body = root.findViewById(app.p3r.dayplanner.R.id.widget_task_area);
        int bottom = 0;
        for (int i = 0; i < body.getChildCount(); i++) {
            View child = body.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            bottom = Math.max(bottom, boundsIn(root, child).bottom);
        }
        return bottom;
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

    /** A line may start with these (a break before them is fine). Mirrors {@link CardText}. */
    private static final String OPENING = "（(「『【〔《〈“‘";
    /** A line may end with these. */
    private static final String CLOSING = "）)」』】〕》〉”’，、。；：！？…";

    /**
     * Names and dialogue choices must not split mid-word (audit: 「小田桐/秀利」, 「你说得对」/「（1）」):
     * a wrapped title with 「 · 」 parts may only break after a space or punctuation, or before an
     * opening bracket. The answers row is checked by {@link #collectAnswerSplits}. A line without any
     * break opportunity (one word wider than the card) is the only exception.
     */
    static void collectWordSplits(View root, String tag, List<String> issues) {
        TextView title = root.findViewById(app.p3r.dayplanner.R.id.widget_task);
        TextView dialogue = root.findViewById(app.p3r.dayplanner.R.id.widget_dialogue_choices);
        String plainTitle = title == null ? "" : titleText(title);
        if (plainTitle.contains(" · ")) collectSplits(title, tag + " title", issues);
        collectAnswerSplits(dialogue, tag + " answers", issues);
    }

    /**
     * The answers row (docs/DESIGN_v3.md §18) wraps only where its shaping lets it: after 「 / 」, a
     * label or a hard line break, before a 「 · 」 note, and inside a choice only in its loose form
     * (never at a word joiner, unless one unbreakable run is wider than the line). No line starts
     * with 「/」 or is one character alone, and nothing ends in "…".
     */
    static void collectAnswerSplits(TextView text, String tag, List<String> issues) {
        if (text == null || text.getVisibility() != View.VISIBLE || text.getLayout() == null) return;
        android.text.Layout layout = text.getLayout();
        String s = text.getText().toString();
        if (ellipsized(text)) issues.add(tag + " ends in …: '" + CardText.plain(s) + "'");
        for (int line = 0; line < layout.getLineCount(); line++) {
            int start = layout.getLineStart(line);
            int end = layout.getLineEnd(line);
            String shown = CardText.plain(s.substring(start, end)).trim();
            if (shown.startsWith("/")) issues.add(tag + " line starts with a slash: '" + shown + "'");
            if (shown.codePointCount(0, shown.length()) == 1) issues.add(tag + " leaves one character alone: '" + shown + "'");
            if (line == layout.getLineCount() - 1 || (end > 0 && s.charAt(end - 1) == '\n')) continue;
            boolean joined = (end > 0 && s.charAt(end - 1) == CardText.WJ) || (end < s.length() && s.charAt(end) == CardText.WJ);
            if (joined && !forcedBreak(text, end)) {
                issues.add(tag + " splits a word: '" + CardText.plain(s.substring(start, end)) + "' / '"
                        + CardText.plain(s.substring(end)) + "'");
            }
        }
    }

    /**
     * A shaped free-form title (the legacy guide, {@link CardText#guide}) never wraps inside a word it
     * keeps whole: no line ends or starts at a word joiner, unless the unbreakable run there is wider
     * than the title (the line breaker had to cut it).
     */
    static void collectJoinerBreaks(View root, String tag, List<String> issues) {
        TextView title = root.findViewById(app.p3r.dayplanner.R.id.widget_task);
        if (title == null || title.getVisibility() != View.VISIBLE || title.getLayout() == null) return;
        android.text.Layout layout = title.getLayout();
        String s = title.getText().toString();
        for (int line = 0; line < layout.getLineCount() - 1; line++) {
            if (layout.getEllipsisCount(line) > 0) continue;
            int start = layout.getLineStart(line);
            int end = layout.getLineEnd(line);
            boolean inside = (end > 0 && s.charAt(end - 1) == CardText.WJ) || (end < s.length() && s.charAt(end) == CardText.WJ);
            if (inside && !forcedBreak(title, end)) {
                issues.add(tag + " title splits a word: '" + CardText.plain(s.substring(start, end)) + "' / '"
                        + CardText.plain(s.substring(end)) + "'");
            }
        }
    }

    /** The visible lines of a title, joined with 「/」 (for reports). */
    static String lines(TextView text) {
        if (text == null || text.getLayout() == null) return "";
        android.text.Layout layout = text.getLayout();
        String s = text.getText().toString();
        StringBuilder out = new StringBuilder();
        for (int line = 0; line < layout.getLineCount(); line++) {
            if (line > 0) out.append(" / ");
            out.append(CardText.plain(s.substring(layout.getLineStart(line), layout.getLineEnd(line))).trim());
        }
        return out.toString();
    }

    /**
     * Whether a line break at offset {@code at} of a shaped text falls inside a run of characters
     * linked by word joiners (or no-break spaces) that is wider than the text box: the line breaker
     * had no choice but to cut it.
     */
    static boolean forcedBreak(TextView text, int at) {
        String s = text.getText().toString();
        int from = at;
        while (from > 0 && linked(s, from)) from = previousVisible(s, from);
        int to = at;
        while (to < s.length() && linked(s, to)) to = nextVisible(s, to);
        String run = CardText.plain(s.substring(from, to));
        return text.getPaint().measureText(run) > text.getLayout().getWidth();
    }

    /** Whether the visible characters on both sides of offset {@code k} are kept together. */
    private static boolean linked(String s, int k) {
        int before = k - 1;
        while (before >= 0 && s.charAt(before) == CardText.WJ) before--;
        int after = k;
        while (after < s.length() && s.charAt(after) == CardText.WJ) after++;
        if (before < 0 || after >= s.length()) return false;
        boolean joiner = after > before + 1;
        return joiner || s.charAt(before) == CardText.NBSP || s.charAt(after) == CardText.NBSP;
    }

    /** Offset of the start of the visible character before offset {@code k}. */
    private static int previousVisible(String s, int k) {
        int i = k - 1;
        while (i > 0 && s.charAt(i) == CardText.WJ) i--;
        return i;
    }

    /** Offset just after the visible character at or after offset {@code k}. */
    private static int nextVisible(String s, int k) {
        int i = k;
        while (i < s.length() && s.charAt(i) == CardText.WJ) i++;
        return Math.min(s.length(), i + 1);
    }

    private static void collectSplits(TextView text, String tag, List<String> issues) {
        if (text == null || text.getVisibility() != View.VISIBLE || text.getLayout() == null) return;
        android.text.Layout layout = text.getLayout();
        String s = text.getText().toString();
        for (int line = 0; line < layout.getLineCount() - 1; line++) {
            if (layout.getEllipsisCount(line) > 0) continue;
            int start = layout.getLineStart(line);
            int end = layout.getLineEnd(line);
            char before = visibleBefore(s, end);
            char after = visibleAfter(s, end);
            boolean ok = before == ' ' || before == CardText.TITLE_BREAK || CLOSING.indexOf(before) >= 0 || OPENING.indexOf(after) >= 0;
            boolean forced = s.substring(start, end).trim().indexOf(' ') < 0; // one word wider than the line
            if (!ok && !forced) {
                issues.add(tag + " splits a word: '" + CardText.plain(s.substring(start, end)) + "' / '"
                        + CardText.plain(s.substring(end)) + "'");
            }
        }
    }

    private static char visibleBefore(String s, int at) {
        for (int i = at - 1; i >= 0; i--) if (s.charAt(i) != CardText.WJ) return s.charAt(i);
        return ' ';
    }

    private static char visibleAfter(String s, int at) {
        for (int i = at; i < s.length(); i++) if (s.charAt(i) != CardText.WJ) return s.charAt(i);
        return ' ';
    }

    static String name(View v) {
        try {
            return v.getId() == View.NO_ID ? v.getClass().getSimpleName() : v.getResources().getResourceEntryName(v.getId());
        } catch (RuntimeException e) {
            return String.valueOf(v.getId());
        }
    }
}
