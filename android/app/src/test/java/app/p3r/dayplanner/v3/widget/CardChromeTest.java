package app.p3r.dayplanner.v3.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.View;
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
import java.util.ArrayList;
import java.util.List;

import app.p3r.dayplanner.R;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pixel and geometry checks of the card chrome on hand-built models ({@link CardRendererTest#sample}):
 * 40dp hit targets around 34dp button silhouettes, a flat palette surface, 当前/完成 labels whole and
 * clear of their glyphs, the plate's contents inside the plate, light shafts filling the card and the
 * wordmark clear of the plate. Ported from the 3.0.x guide widget's render test; renders go to
 * $p3r.qa.dir/v3/widget/chrome.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 31}, qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CardChromeTest {
    private static final int DAY_SURFACE = 0xff0FB1F5;
    private static final int NIGHT_SURFACE = 0xff034572;
    private static final int[][] SIZES = {{180, 160}, {200, 160}, {250, 200}, {320, 160}, {320, 180}, {320, 240}};
    private Context c;
    private File dir;

    @Before
    public void setUp() {
        c = RuntimeEnvironment.getApplication();
        dir = RenderSupport.outputDir("chrome");
    }

    private int dp(float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    private View render(CardModel model, int width, int height, String tag) throws Exception {
        View root = RenderSupport.layout(c, CardRenderer.render(c, model, width, height), width, height);
        RenderSupport.save(root, dir, tag);
        return root;
    }

    private View render(boolean night, int width, int height, String tag) throws Exception {
        return render(CardRendererTest.sample(night), width, height, tag);
    }

    @Test
    public void buttonsPlateAndStatusKeepTheirPlaceAtEverySize() throws Exception {
        List<String> issues = new ArrayList<>();
        for (boolean night : new boolean[]{false, true}) {
            for (int[] size : SIZES) {
                String tag = (night ? "night" : "day") + "-" + size[0] + "x" + size[1];
                View root = render(night, size[0], size[1], tag);
                RenderSupport.collectTextIssues(root, tag, issues);
                RenderSupport.collectOverflow(root, R.id.widget_plate, tag, issues);
                View current = root.findViewById(R.id.button_spec_current);
                View done = root.findViewById(R.id.button_spec_done);
                assertTrue(tag + " task region collapsed", root.findViewById(R.id.widget_task_area).getHeight() > 0);
                assertEquals(tag, dp(40), done.getHeight());
                assertEquals(tag, dp(40), current.getHeight());
                assertEquals(tag + " gap between 当前 and 完成", dp(8), done.getLeft() - current.getRight());
                Rect plate = RenderSupport.boundsIn(root, root.findViewById(R.id.widget_plate));
                if (plate.left < 0 || plate.right > root.getWidth()) issues.add(tag + " plate leaves the card");
                View status = root.findViewById(R.id.widget_status);
                if (status.getVisibility() == View.VISIBLE && status.getWidth() > 0
                        && Rect.intersects(plate, RenderSupport.boundsIn(root, status))) {
                    issues.add(tag + " status cluster overlaps the plate");
                }
            }
        }
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }

    @Test
    public void arrowsAreFortyDpTouchTargets() throws Exception {
        for (int width : new int[]{180, 250, 320}) {
            View root = render(false, width, 200, "arrows-" + width);
            for (int id : new int[]{R.id.widget_prev, R.id.widget_next}) {
                View arrow = root.findViewById(id);
                assertTrue("arrow width at " + width, arrow.getWidth() >= dp(40));
                assertTrue("arrow height at " + width, arrow.getHeight() >= dp(40));
            }
        }
    }

    /** Horizontal ink extent [left, right) of {@code view} drawn on its own, in root coordinates. */
    private static int[] inkSpan(View root, View view) {
        Bitmap bitmap = RenderSupport.draw(view);
        int left = Integer.MAX_VALUE;
        int right = -1;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 96) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                }
            }
        }
        assertTrue("no visible ink for " + RenderSupport.name(view), right >= left);
        int offset = RenderSupport.boundsIn(root, view).left;
        return new int[]{offset + left, offset + right + 1};
    }

    private static int gap(View root, int labelId, int iconId, boolean iconOnLeft) {
        int[] text = inkSpan(root, root.findViewById(labelId));
        int[] symbol = inkSpan(root, root.findViewById(iconId));
        return iconOnLeft ? text[0] - symbol[1] : symbol[0] - text[1];
    }

    @Test
    public void currentAndDoneLabelsStayWholeAndClearOfTheirGlyphs() throws Exception {
        List<String> issues = new ArrayList<>();
        for (boolean night : new boolean[]{false, true}) {
            for (int width : new int[]{180, 200, 250, 320}) {
                String tag = (night ? "night" : "day") + "-" + width;
                View root = render(night, width, 200, "actions-" + tag);
                boolean returnGlyph = root.findViewById(R.id.button_spec_return_icon).getVisibility() == View.VISIBLE;
                assertEquals(tag + ": only the narrow card drops the 当前 glyph", width >= 220, returnGlyph);
                if (returnGlyph) {
                    int current = gap(root, R.id.button_spec_current_text, R.id.button_spec_return_icon, true);
                    if (current < dp(1)) issues.add(tag + " 当前 touches its glyph: " + current);
                }
                int done = gap(root, R.id.button_spec_done_text, R.id.button_spec_check, false);
                if (done < dp(2)) issues.add(tag + " 完成 touches its glyph: " + done);
                for (int id : new int[]{R.id.button_spec_current_text, R.id.button_spec_done_text}) {
                    TextView label = root.findViewById(id);
                    float measured = label.getPaint().measureText(label.getText().toString());
                    assertTrue(label.getText() + " clipped at " + tag,
                            measured <= label.getWidth() - label.getPaddingLeft() - label.getPaddingRight() + 1);
                    assertEquals(1, label.getLineCount());
                }
            }
        }
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }

    @Test
    public void theSurfaceIsFlatAndTheButtonsAre34dpInside40dpTargets() throws Exception {
        for (boolean night : new boolean[]{false, true}) {
            View root = render(night, 250, 200, "silhouette-" + (night ? "night" : "day"));
            Bitmap surface = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            root.getBackground().draw(new Canvas(surface));
            for (int x : new int[]{dp(6), root.getWidth() / 2, root.getWidth() - dp(6)}) {
                assertEquals("flat palette surface at x=" + x, night ? NIGHT_SURFACE : DAY_SURFACE,
                        surface.getPixel(x, root.getHeight() / 2));
            }
            for (int id : new int[]{R.id.button_spec_current, R.id.button_spec_done}) {
                View action = root.findViewById(id);
                assertEquals(dp(40), action.getHeight());
                Bitmap bitmap = Bitmap.createBitmap(action.getWidth(), action.getHeight(), Bitmap.Config.ARGB_8888);
                action.getBackground().draw(new Canvas(bitmap));
                int top = bitmap.getHeight();
                int bottom = -1;
                for (int y = 0; y < bitmap.getHeight(); y++) {
                    for (int x = 0; x < bitmap.getWidth(); x++) {
                        if (Color.alpha(bitmap.getPixel(x, y)) > 128) {
                            top = Math.min(top, y);
                            bottom = Math.max(bottom, y);
                        }
                    }
                }
                String name = RenderSupport.name(action);
                assertEquals(name + " silhouette top", dp(3), top);
                assertEquals(name + " silhouette height", dp(34), bottom - top + 1);
            }
        }
    }

    @Test
    public void lightShaftsFillTheCardInEveryComposition() throws Exception {
        for (boolean night : new boolean[]{false, true}) {
            for (int motif = 0; motif < WidgetMotif.VARIANTS; motif++) {
                CardModel model = CardRendererTest.sample(night);
                model.motif = motif;
                View root = render(model, 250, 200, "motif-" + motif + (night ? "-night" : "-day"));
                ImageView shafts = root.findViewById(R.id.widget_shafts);
                assertEquals(ImageView.ScaleType.FIT_XY, shafts.getScaleType());
                assertEquals(root.getWidth(), shafts.getWidth());
                assertEquals(root.getHeight(), shafts.getHeight());
                assertNotNull(shafts.getDrawable());
            }
        }
    }

    @Test
    public void theWordmarkSitsInsideTheCardClearOfThePlate() throws Exception {
        for (boolean night : new boolean[]{false, true}) {
            View root = render(night, 320, 200, "wordmark-" + (night ? "night" : "day"));
            View mark = root.findViewById(R.id.widget_sees_mark);
            assertEquals(View.VISIBLE, mark.getVisibility());
            Rect box = RenderSupport.boundsIn(root, mark);
            Rect plate = RenderSupport.boundsIn(root, root.findViewById(R.id.widget_plate));
            assertTrue("wordmark inside the card", box.left >= 0 && box.right <= root.getWidth() && box.top >= 0);
            assertTrue("wordmark clear of the plate", box.left > plate.right);
        }
        View compact = render(false, 250, 200, "wordmark-compact");
        assertNull("no wordmark on compact cards", compact.findViewById(R.id.widget_sees_mark));
    }
}
