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
     * (italics only via NumeralSpan on Latin runs), no Latin-only internal ids, and no text that is
     * clipped vertically by its own view.
     */
    static void checkTypography(View view, String where) {
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
            String latin = text.replaceAll("RANK|MAX|Lv|P3R|ATLUS|Reload|Barlow|Condensed|SIL", "")
                .replaceAll("[a-z0-9.-]+\\.[a-z]{2,}", "").replaceAll("\\b[IVX]+\\b", "");
            assertTrue(where + ": English shown to the player: " + text, !latin.matches("(?s).*[A-Za-z]{2,}.*"));
            if (t.getLayout() != null && t.getLineCount() > 0 && t.getMaxLines() > 1 && t.getHeight() > 0) {
                int needed = t.getLayout().getLineBottom(t.getLineCount() - 1) + t.getCompoundPaddingTop()
                    + t.getCompoundPaddingBottom();
                assertTrue(where + ": text clipped: " + text, needed <= t.getHeight() + 2);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                checkTypography(group.getChildAt(i), where);
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
