package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Screenshot and view-lookup helpers for the UI render/behaviour tests. */
final class Shots {
    /** 360 × 780 dp at xxhdpi. */
    static final int WIDTH = 1080;
    static final int HEIGHT = 2340;

    private Shots() {}

    static File dir() {
        File dir = new File(System.getProperty("p3r.qa.dir", "build/qa"), "v3/ui");
        dir.mkdirs();
        return dir;
    }

    /** Runs pending main-thread work (results of background calls with a direct executor). */
    static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** Renders a view tree at the phone size. */
    static Bitmap render(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, WIDTH, HEIGHT);
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        root.draw(canvas);
        return bitmap;
    }

    /** Captures the activity window, plus the latest showing dialog composited at the bottom. */
    static Bitmap capture(Activity activity, boolean withDialog) {
        idle();
        Bitmap bitmap = render(activity.getWindow().getDecorView());
        Dialog dialog = withDialog ? ShadowDialog.getLatestDialog() : null;
        if (dialog != null && dialog.isShowing()) {
            View decor = dialog.getWindow().getDecorView();
            decor.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.AT_MOST));
            decor.layout(0, 0, WIDTH, decor.getMeasuredHeight());
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.argb(158, 0, 0, 0));
            canvas.save();
            canvas.translate(0, HEIGHT - decor.getMeasuredHeight());
            decor.draw(canvas);
            canvas.restore();
        }
        return bitmap;
    }

    static File save(Bitmap bitmap, String name) {
        File file = new File(dir(), name + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return file;
    }

    /** Captures, checks typography rules on every visible view, and saves the screenshot. */
    static File shot(Activity activity, String name) {
        Bitmap bitmap = capture(activity, true);
        checkTypography(activity.getWindow().getDecorView(), name);
        Dialog dialog = ShadowDialog.getLatestDialog();
        if (dialog != null && dialog.isShowing()) {
            checkTypography(dialog.getWindow().getDecorView(), name + " (dialog)");
        }
        return save(bitmap, name);
    }

    /**
     * Visual rules enforced on every render: no italic/slanted face on text that contains CJK
     * (italics only via NumeralSpan on Latin runs), no Latin-only internal ids, no text that is
     * clipped vertically by its own view, RANK/MAX/Lv.N in the numeral face
     * ({@link #checkNumeralWords}), and the line breaks of {@link #checkLines}.
     */
    static void checkTypography(View view, String where) {
        checkTypography(view, where, false);
    }

    /** Bilingual catalogue names are deliberate; all layout and internal-id checks still apply. */
    static void checkTypography(View view, String where, boolean bilingual) {
        if (view.getVisibility() != View.VISIBLE) {
            return;
        }
        if (view instanceof TextView) {
            TextView t = (TextView) view;
            String text = Zh.plain(t.getText());
            boolean cjk = text.codePoints().anyMatch(cp -> cp >= 0x2E80);
            boolean italic = t.getTypeface() != null && t.getTypeface().isItalic();
            assertTrue(where + ": CJK in italic face: " + text, !(cjk && (italic || t.getPaint().getTextSkewX() != 0)));
            assertTrue(where + ": raw id shown: " + text, !text.matches(".*\\b[a-z]+[_:][a-z_:0-9]+\\b.*"));
            // A class or exam answer the guide spells in capitals (「MAY BLUES」「MADRID」「JUNE」) is quoted verbatim.
            String latin = text.replaceAll("RANK|MAX|Lv|P3R|ATLUS|SEGA|Reload|Barlow|Condensed|SIL|DVD|DLC|aqiu384|Megaten|Database|Fusion|Tools|Unlicense", "")
                .replaceAll("[a-z0-9.-]+\\.[a-z]{2,}", "").replaceAll("\\b[IVX]+\\b", "")
                .replaceAll("「[A-Z][A-Z .'-]*」", "");
            // Decorative Latin (a page kicker or watermark, design §3; a guide-lookup English name) is
            // set in the numeral face and hidden from TalkBack. An English label in the system face
            // is caught even when hidden (a row's own description may speak for it).
            boolean decorative = !cjk && Ui.inNumeralFace(t)
                && t.getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_NO;
            assertTrue(where + ": English shown to the player: " + text,
                bilingual || decorative || !latin.matches("(?s).*[A-Za-z]{2,}.*"));
            checkNumeralWords(t, where);
            if (t.getLayout() != null && t.getLineCount() > 0 && t.getMaxLines() > 1 && t.getHeight() > 0) {
                int needed = t.getLayout().getLineBottom(t.getLineCount() - 1) + t.getCompoundPaddingTop()
                    + t.getCompoundPaddingBottom();
                assertTrue(where + ": text clipped: " + text, needed <= t.getHeight() + 2);
            }
            checkLines(t, where);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                checkTypography(group.getChildAt(i), where, bilingual);
            }
        }
    }

    /** "RANK", "MAX" and "Lv." (word joiners allowed between the letters). */
    private static final java.util.regex.Pattern NUMERAL_WORD =
        java.util.regex.Pattern.compile("R⁠?A⁠?N⁠?K|M⁠?A⁠?X|L⁠?v⁠?\\.");

    /**
     * RANK, MAX and Lv.N are set in the numeral face in every text, as in the mixed ones: a view
     * of the screens ({@link ZhTextView}) is either in the numeral face as a whole or has a
     * {@link NumeralSpan} over each of them.
     */
    static void checkNumeralWords(TextView t, String where) {
        if (!(t instanceof ZhTextView) || t.getTypeface() == Ui.numerals(t.getContext())) {
            return;
        }
        CharSequence text = t.getText();
        java.util.regex.Matcher m = NUMERAL_WORD.matcher(text);
        while (m.find()) {
            boolean styled = false;
            if (text instanceof android.text.Spanned) {
                android.text.Spanned s = (android.text.Spanned) text;
                for (NumeralSpan span : s.getSpans(m.start(), m.end(), NumeralSpan.class)) {
                    styled |= s.getSpanStart(span) <= m.start() && s.getSpanEnd(span) >= m.end();
                }
            }
            assertTrue(where + ": " + Zh.plain(m.group()) + " not in the numeral face: " + Zh.plain(text), styled);
        }
    }

    /**
     * Line breaks of a drawn text view: none inside a word, name or tag kept together by
     * {@link Zh#keepTogether} (next to a word joiner), no line that visibly ends with a " · "
     * separator (a dot there must be hidden by {@link ZhTextView}) or with a slash ("/", "／": it
     * goes with the next part), and no wrapped line holding a
     * single character ("吗？", "证）。"). Truncated lines (ellipsis) are not checked.
     */
    static void checkLines(TextView t, String where) {
        android.text.Layout layout = t.getLayout();
        CharSequence text = t.getText();
        if (layout == null || layout.getLineCount() < 2 || t.getVisibility() != View.VISIBLE) {
            return;
        }
        for (int line = 0; line < layout.getLineCount(); line++) {
            if (layout.getEllipsisCount(line) > 0) {
                return;
            }
            int start = layout.getLineStart(line);
            int end = layout.getLineEnd(line);
            String shown = Zh.plain(text.subSequence(start, end)).trim();
            String context = "«" + Zh.plain(text.subSequence(0, end)) + "|" + Zh.plain(text.subSequence(end, text.length())) + "»";
            if (line < layout.getLineCount() - 1 && end > 0 && end < text.length()) {
                boolean split = text.charAt(end - 1) == Zh.WJ || text.charAt(end) == Zh.WJ;
                assertTrue(where + ": line break inside a kept word/name/tag: " + context, !split);
            }
            if (shown.endsWith("·") && line < layout.getLineCount() - 1) {
                // Draw it (a view scrolled out of sight was skipped by the capture).
                t.draw(new Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)));
                int dot = start + text.subSequence(start, end).toString().lastIndexOf('·');
                ZhTextView.Dot[] spans = text instanceof android.text.Spanned
                    ? ((android.text.Spanned) text).getSpans(dot, dot + 1, ZhTextView.Dot.class) : new ZhTextView.Dot[0];
                assertTrue(where + ": line ends with a visible separator dot: " + context, spans.length == 1 && spans[0].hidden);
            }
            if (line < layout.getLineCount() - 1) {
                assertTrue(where + ": line ends with a slash: " + context, !shown.endsWith("/") && !shown.endsWith("／"));
            }
            boolean paragraphStart = start == 0 || text.charAt(start - 1) == '\n';
            boolean paragraphEnd = end == text.length() || text.charAt(end - 1) == '\n';
            if (!(paragraphStart && paragraphEnd) && !shown.isEmpty()) {
                long content = shown.codePoints().filter(Character::isLetterOrDigit).count();
                assertTrue(where + ": a wrapped line with a single character: " + context, content >= 2);
            }
        }
    }

    /** All views under {@code root} whose text (TextView) or content description contains {@code text}. */
    static List<View> findAll(View root, String text) {
        List<View> out = new ArrayList<>();
        collect(root, text, out);
        return out;
    }

    private static void collect(View view, String text, List<View> out) {
        if (view.getVisibility() != View.VISIBLE) {
            return;
        }
        if (view instanceof TextView && Zh.plain(((TextView) view).getText()).equals(text)) {
            out.add(view);
        } else if (view.getContentDescription() != null && Zh.plain(view.getContentDescription()).equals(text)) {
            out.add(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collect(group.getChildAt(i), text, out);
            }
        }
    }

    /** The first visible view showing exactly {@code text}; fails when absent. */
    static View find(View root, String text) {
        List<View> all = findAll(root, text);
        assertTrue("没有找到「" + text + "」", !all.isEmpty());
        return all.get(0);
    }

    static View find(Activity activity, String text) {
        return find(activity.getWindow().getDecorView(), text);
    }

    /** Clicks the view showing {@code text} and runs the resulting main-thread work. */
    static void click(Activity activity, String text) {
        View view = find(activity, text);
        assertTrue("「" + text + "」不可点", clickTarget(view).performClick());
        idle();
    }

    /** Clicks inside the latest dialog. */
    static void clickInDialog(String text) {
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull("没有对话框", dialog);
        View view = find(dialog.getWindow().getDecorView(), text);
        assertTrue(clickTarget(view).performClick());
        idle();
    }

    /** The nearest clickable ancestor (labels inside clickable rows). */
    static View clickTarget(View view) {
        View v = view;
        while (v != null && !v.isClickable()) {
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return v == null ? view : v;
    }

    static boolean has(Activity activity, String text) {
        return !findAll(activity.getWindow().getDecorView(), text).isEmpty();
    }

    /** Concatenated visible text of the activity (for assertions on copy). */
    static String allText(View root) {
        StringBuilder sb = new StringBuilder();
        appendText(root, sb);
        return sb.toString();
    }

    private static void appendText(View view, StringBuilder sb) {
        if (view.getVisibility() != View.VISIBLE) {
            return;
        }
        if (view instanceof TextView) {
            sb.append(Zh.plain(((TextView) view).getText())).append('\n');
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                appendText(group.getChildAt(i), sb);
            }
        }
    }
}
