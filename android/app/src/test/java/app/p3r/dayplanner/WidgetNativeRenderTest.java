package app.p3r.dayplanner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.p3r.dayplanner.v3.widget.GameCalendar;
import app.p3r.dayplanner.v3.widget.WidgetMotif;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pixel-level checks of the restyled legacy guide widget at its supported sizes (2x density): no
 * clipped text, 40dp hit targets around 34dp silhouettes, a flat palette surface, labels clear of
 * their icons, and the calendar plate's contents inside the plate.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en-rUS-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WidgetNativeRenderTest {
    private static final int W = 73;
    private static final int DAY_SURFACE = 0xff0FB1F5;
    private static final int NIGHT_SURFACE = 0xff034572;
    private Context c;
    private final File output = new File(System.getProperty("p3r.qa.dir", "build/qa") + "/p3r_native_final_qa");

    @Before
    public void clean() {
        c = RuntimeEnvironment.getApplication();
        c.getSharedPreferences("p3r_native_progress_v1", 0).edit().clear().commit();
        output.mkdirs();
    }

    private View render(String id, int width, int height, String tag) throws Exception {
        PlannerStore.setWidgetCursor(c, W, id);
        View root = PlannerWidget.renderForTest(c, W, width, height).apply(c, new FrameLayout(c));
        root.measure(View.MeasureSpec.makeMeasureSpec(width * 2, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height * 2, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width * 2, height * 2);
        Bitmap image = draw(root);
        try (FileOutputStream file = new FileOutputStream(new File(output, tag + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, file));
        }
        return root;
    }

    private static Bitmap draw(View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static Rect bounds(View root, View child) {
        Rect r = new Rect(0, 0, child.getWidth(), child.getHeight());
        ((ViewGroup) root).offsetDescendantRectToMyCoords(child, r);
        return r;
    }

    private static void checkText(View view, String tag, List<String> issues) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.getLayout() == null || text.getLineCount() == 0 || text.getText().length() == 0) return;
            int last = text.getLineCount() - 1;
            int bottom = text.getLayout().getLineBottom(last) + text.getCompoundPaddingTop() + text.getCompoundPaddingBottom();
            if (bottom > text.getHeight() + 1) issues.add(tag + " clips " + text.getText() + " needs=" + bottom + " has=" + text.getHeight());
            if (text.getMaxLines() == 1 && text.getLayout().getEllipsisCount(0) == 0) {
                float needed = text.getPaint().measureText(text.getText().toString());
                int available = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
                if (needed > available + 1) issues.add(tag + " cuts " + text.getText() + " needs=" + needed + " has=" + available);
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) checkText(group.getChildAt(i), tag, issues);
        }
    }

    @Test
    public void captureDayNightCompactStandardTallAndLongTitle() throws Exception {
        String[] tasks = {"t005_01", "t005_04", "t016_03", "t048_02"};
        int[][] sizes = {{180, 160}, {200, 160}, {250, 200}, {320, 160}, {320, 180}, {320, 240}};
        List<String> metrics = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        for (String id : tasks) {
            for (int[] size : sizes) {
                String tag = id + "-" + size[0] + "x" + size[1];
                View root = render(id, size[0], size[1], tag);
                TextView title = root.findViewById(R.id.widget_task);
                View area = root.findViewById(R.id.widget_task_area);
                View done = root.findViewById(R.id.button_spec_done);
                View current = root.findViewById(R.id.button_spec_current);
                metrics.add(tag + " titleLines=" + title.getLineCount() + " titleHeight=" + title.getHeight()
                        + " areaHeight=" + area.getHeight() + " plateWidth=" + root.findViewById(R.id.widget_plate).getWidth());
                checkText(root, tag, issues);
                assertTrue("Task region collapsed " + tag, area.getHeight() > 0);
                assertEquals(80, done.getHeight());
                assertEquals(80, current.getHeight());
                assertEquals(16, done.getLeft() - current.getRight());
                ViewGroup plate = root.findViewById(R.id.widget_plate);
                for (int i = 0; i < plate.getChildCount(); i++) {
                    View child = plate.getChildAt(i);
                    if (child.getVisibility() == View.VISIBLE && (child.getLeft() < 0 || child.getRight() > plate.getWidth())) {
                        issues.add(tag + " plate child outside the plate " + child.getId());
                    }
                }
                Rect plateBox = bounds(root, plate);
                if (plateBox.right > root.getWidth()) issues.add(tag + " plate leaves the card");
            }
        }
        Files.write(new File(output, "layout-metrics.txt").toPath(), metrics);
        assertTrue(String.join("; ", issues), issues.isEmpty());
    }

    @Test
    public void arrowsAreFortyDpTouchTargets() throws Exception {
        for (int width : new int[]{180, 250, 320}) {
            View root = render("t005_04", width, 200, "arrows-" + width);
            for (int id : new int[]{R.id.widget_prev, R.id.widget_next}) {
                View arrow = root.findViewById(id);
                assertTrue("arrow width at " + width, arrow.getWidth() >= 80);
                assertTrue("arrow height at " + width, arrow.getHeight() >= 80);
            }
        }
    }

    /** Horizontal ink extent [left, right) of {@code view} drawn on its own, in root coordinates. */
    private static int[] inkSpan(View root, View view) {
        Bitmap bitmap = draw(view);
        int left = Integer.MAX_VALUE, right = -1;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 96) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                }
            }
        }
        assertTrue("No visible ink for " + view.getId(), right >= left);
        int offset = bounds(root, view).left;
        return new int[]{offset + left, offset + right + 1};
    }

    private static int gap(View root, int labelId, int iconId, boolean iconOnLeft) {
        int[] text = inkSpan(root, root.findViewById(labelId));
        int[] symbol = inkSpan(root, root.findViewById(iconId));
        return iconOnLeft ? text[0] - symbol[1] : symbol[0] - text[1];
    }

    @Test
    public void doneAndCurrentLabelsStayWholeAndClearOfTheirIcons() throws Exception {
        List<String> issues = new ArrayList<>();
        for (int width : new int[]{180, 200, 250, 320}) {
            View root = render("t005_04", width, 200, "action-fit-" + width);
            int current = gap(root, R.id.button_spec_current_text, R.id.button_spec_return_icon, true);
            int done = gap(root, R.id.button_spec_done_text, R.id.button_spec_check, false);
            if (current < 2) issues.add("width=" + width + " 当前 touches its icon: " + current);
            if (done < 4) issues.add("width=" + width + " 完成 touches its icon: " + done);
            for (int id : new int[]{R.id.button_spec_current_text, R.id.button_spec_done_text}) {
                TextView label = root.findViewById(id);
                float measured = label.getPaint().measureText(label.getText().toString());
                assertTrue(label.getText() + " clipped at widgetWidth " + width,
                        measured <= label.getWidth() - label.getPaddingLeft() - label.getPaddingRight() + 1);
                assertEquals(1, label.getLineCount());
            }
        }
        assertTrue(String.join("; ", issues), issues.isEmpty());
    }

    @Test
    public void appThemeNeverChangesTheWidgetPalette() throws Exception {
        c.getSharedPreferences("p3r_native_progress_v1", 0).edit().putString("theme", "day").commit();
        View night = render("t016_03", 250, 200, "night-with-day-app-theme");
        assertEquals(PlannerStore.taskById(c, "t016_03").period, ((TextView) night.findViewById(R.id.widget_period_word)).getText().toString());
        assertEquals(NIGHT_SURFACE, surfacePixel(night));
        c.getSharedPreferences("p3r_native_progress_v1", 0).edit().putString("theme", "night").commit();
        View day = render("t005_01", 250, 200, "day-with-night-app-theme");
        assertEquals(PlannerStore.taskById(c, "t005_01").period, ((TextView) day.findViewById(R.id.widget_period_word)).getText().toString());
        assertEquals(DAY_SURFACE, surfacePixel(day));
    }

    private static int surfacePixel(View root) {
        Bitmap surface = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.getBackground().draw(new Canvas(surface));
        return surface.getPixel(root.getWidth() / 2, root.getHeight() / 2);
    }

    @Test
    public void actionSilhouetteIs34dpInside40dpHitTarget() throws Exception {
        for (String task : new String[]{"t005_04", "t016_03"}) {
            View root = render(task, 250, 200, "material-height-" + task);
            int palette = task.equals("t016_03") ? NIGHT_SURFACE : DAY_SURFACE;
            Bitmap surface = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            root.getBackground().draw(new Canvas(surface));
            for (int x : new int[]{12, root.getWidth() / 2, root.getWidth() - 12}) {
                assertEquals("Flat palette surface " + task, palette, surface.getPixel(x, root.getHeight() / 2));
            }
            for (int id : new int[]{R.id.button_spec_current, R.id.button_spec_done}) {
                View action = root.findViewById(id);
                assertEquals(80, action.getHeight());
                Bitmap bitmap = Bitmap.createBitmap(action.getWidth(), action.getHeight(), Bitmap.Config.ARGB_8888);
                action.getBackground().draw(new Canvas(bitmap));
                int top = bitmap.getHeight(), bottom = -1;
                for (int y = 0; y < bitmap.getHeight(); y++) {
                    for (int x = 0; x < bitmap.getWidth(); x++) {
                        if (Color.alpha(bitmap.getPixel(x, y)) > 128) {
                            top = Math.min(top, y);
                            bottom = Math.max(bottom, y);
                        }
                    }
                }
                assertEquals(6, top);
                assertEquals(68, bottom - top + 1);
            }
        }
    }

    @Test
    public void lightShaftsFillTheCardAndShareTheDateKeyWithThePlanWidget() throws Exception {
        Map<String, String> examples = new LinkedHashMap<>();
        for (PlannerStore.Day day : PlannerStore.days(c)) {
            for (PlannerStore.Task task : day.tasks) {
                String key = PlannerWidget.motifIndex(day.month, day.day) + (PlannerWidget.isNightPeriod(task.period) ? "-night" : "-day");
                examples.putIfAbsent(key, task.id);
                assertEquals(WidgetMotif.forDay(GameCalendar.ordinalOfGuideDate(day.month, day.day)),
                        PlannerWidget.motifIndex(day.month, day.day));
            }
        }
        assertEquals("All shaft compositions need both palettes", 2 * WidgetMotif.VARIANTS, examples.size());
        for (Map.Entry<String, String> example : examples.entrySet()) {
            View root = render(example.getValue(), 250, 200, "motif-" + example.getKey());
            ImageView shafts = root.findViewById(R.id.widget_shafts);
            assertEquals(ImageView.ScaleType.FIT_XY, shafts.getScaleType());
            assertEquals(root.getWidth(), shafts.getWidth());
            assertEquals(root.getHeight(), shafts.getHeight());
        }
    }

    @Test
    public void wordmarkSitsInsideTheCardClearOfThePlate() throws Exception {
        for (String task : new String[]{"t005_04", "t016_03"}) {
            View root = render(task, 320, 200, "badge-" + task);
            View badge = root.findViewById(R.id.widget_sees_mark);
            assertEquals(View.VISIBLE, badge.getVisibility());
            Rect mark = bounds(root, badge);
            Rect plate = bounds(root, root.findViewById(R.id.widget_plate));
            assertTrue("wordmark inside the card", mark.left >= 0 && mark.right <= root.getWidth() && mark.top >= 0);
            assertTrue("wordmark clear of the plate", mark.left > plate.right);
        }
        View compact = render("t005_04", 250, 200, "badge-compact");
        assertEquals("no wordmark on compact cards", null, compact.findViewById(R.id.widget_sees_mark));
    }
}
