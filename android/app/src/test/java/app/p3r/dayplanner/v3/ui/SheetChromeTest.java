package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.time.Duration;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.Intents;

/**
 * The one sheet language: the surface lights only its top edge and cut, the grip drags a sheet
 * down to close it (in the app and on the quick result sheet), and a recorded rank-up plays the
 * flourish before the result sheet closes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SheetChromeTest extends UiTestBase {

    @Test
    public void theSurfaceLightsTheTopEdgeOnly() {
        android.content.Context c = RuntimeEnvironment.getApplication();
        Drawable surface = SheetChrome.surface(c);
        int w = 600;
        int h = 400;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        surface.setBounds(0, 0, w, h);
        surface.draw(new Canvas(bitmap));
        int cyan = Ui.col(c, R.color.p3r_cyan_400);
        // The top edge is bright cyan near the cut, ...
        assertTrue(Integer.toHexString(bitmap.getPixel(w / 4, 1)), near(bitmap.getPixel(w / 4, 1), cyan, 60));
        // ... the right side and the bottom carry no outline: the same glass as just inside.
        assertFalse(near(bitmap.getPixel(w - 1, h / 2), cyan, 120));
        assertFalse(near(bitmap.getPixel(w / 2, h - 1), cyan, 120));
        assertTrue(near(bitmap.getPixel(w - 1, h / 2), bitmap.getPixel(w - 12, h / 2), 24));
    }

    private static boolean near(int a, int b, int tolerance) {
        return Math.abs(Color.red(a) - Color.red(b)) <= tolerance && Math.abs(Color.green(a) - Color.green(b)) <= tolerance
            && Math.abs(Color.blue(a) - Color.blue(b)) <= tolerance;
    }

    @Test
    public void draggingTheGripFarClosesAnInAppSheet() {
        HomeActivity a = home();
        SheetHost sheets = a.sheets();
        int[] closed = {0};
        sheets.show(Ui.body(a, "筛选提示"), () -> closed[0]++);
        Shots.capture(a, false);
        View grip = grip(sheets);
        drag(grip, Ui.dp(a, 40));
        assertTrue("a short drag springs back", sheets.isShowing());
        assertEquals(0f, ((View) grip.getParent()).getTranslationY(), 0.5f);
        assertEquals(0, closed[0]);
        drag(grip, Ui.dp(a, SheetChrome.DISMISS_DRAG + 30));
        assertFalse(sheets.isShowing());
        assertEquals(1, closed[0]);
    }

    /** A grip caught while the sheet still fades in (a fast second tap) never leaves it see-through. */
    @Test
    public void aGripCaughtDuringTheEntranceLeavesTheSheetOpaque() {
        HomeActivity a = home();
        SheetHost sheets = a.sheets();
        sheets.show(Ui.body(a, "筛选提示"), null);
        Shots.capture(a, false);
        View grip = grip(sheets);
        View panel = (View) grip.getParent();
        panel.setAlpha(0.4f);
        drag(grip, Ui.dp(a, 20));
        assertTrue(sheets.isShowing());
        assertEquals(1f, panel.getAlpha(), 0.01f);

        Ui.setAnimationsEnabled(true);
        panel.setAlpha(0.4f);
        drag(grip, Ui.dp(a, 20));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertTrue(sheets.isShowing());
        assertEquals(1f, panel.getAlpha(), 0.01f);
        assertEquals(0f, panel.getTranslationY(), 0.5f);
    }

    @Test
    public void draggingTheGripClosesTheResultSheet() {
        ResultActivity r = result(Fixtures.SLOT_NOW, "meet:emperor", Intents.MODE_RESULT);
        Shots.capture(r, false);
        View grip = findGrip(r.getWindow().getDecorView());
        assertNotNull("the result sheet has the same grip", grip);
        drag(grip, Ui.dp(r, SheetChrome.DISMISS_DRAG + 30));
        assertTrue(r.isFinishing());
        assertTrue(api.calls.isEmpty());
    }

    @Test
    public void aRecordedRankUpPlaysTheFlourishBeforeTheSheetCloses() {
        ResultActivity r = result(Fixtures.SLOT_NOW, "meet:emperor", Intents.MODE_RESULT);
        Ui.setAnimationsEnabled(true);
        Shots.click(r, "升到 RANK 7 了");
        assertEquals("recordOutcome", api.last().method);
        assertEquals("皇帝 · RANK 7", r.playedBurst());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000));
        // The flourish hands over to the usual exit: the sheet closes after it.
        assertNull(findBurst(r.getWindow().getDecorView()));
        assertTrue(r.isFinishing());
        UndoCenter.Recorded recorded = UndoCenter.take();
        assertEquals("皇帝 RANK 7", recorded.summaryZh);
        assertTrue("the app's message bar shows 「RANK UP」", recorded.rankUp);
    }

    @Test
    public void anUnexpectedRankUpToMaxPlaysTheFlourishToo() {
        api.snap.observed.rank[4] = 9;
        api.snap.plan.steps.set(0, Fixtures.social(api.rules, Fixtures.SLOT_NOW, "emperor", 9, 10, true, true));
        api.snap.planCurrent = false;
        ResultActivity r = result(Fixtures.SLOT_NOW, "meet:emperor", Intents.MODE_RESULT);
        Ui.setAnimationsEnabled(true);
        Shots.click(r, "升到 MAX 了");
        assertEquals(app.p3r.dayplanner.v3.engine.Outcome.Code.RANK_UP, api.last().outcome.code);
        assertEquals("皇帝 · MAX", r.playedBurst());
    }

    @Test
    public void noFlourishWithoutARankUp() {
        ResultActivity r = result(Fixtures.SLOT_NOW, "meet:emperor", Intents.MODE_RESULT);
        Ui.setAnimationsEnabled(true);
        Shots.click(r, "没升级");
        Shots.click(r, "没有");
        assertNull(r.playedBurst());
        UndoCenter.Recorded recorded = UndoCenter.take();
        assertNotNull(recorded);
        assertFalse("no 「RANK UP」 in the app's message bar either", recorded.rankUp);
    }

    @Test
    public void anOrdinaryRecordHasNoFlourish() {
        int slot = Fixtures.SLOT_NOW + 1;
        api.snap.observed.slot = slot;
        ResultActivity r = result(slot, "act:study_room", Intents.MODE_RESULT);
        Ui.setAnimationsEnabled(true);
        Shots.click(r, "做完了");
        assertNull(findBurst(r.getWindow().getDecorView()));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
        assertTrue(r.isFinishing());
    }

    /** The grip strip of an in-app sheet: the first child of its panel. */
    private static View grip(SheetHost sheets) {
        ViewGroup panel = (ViewGroup) sheets.getChildAt(1);
        return panel.getChildAt(0);
    }

    /** The result sheet's grip: the first child of the panel that holds the scrolling body. */
    private static View findGrip(View v) {
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            if (g.getChildCount() == 2 && g.getChildAt(1) instanceof android.widget.ScrollView
                && g.getChildAt(0) instanceof android.widget.FrameLayout) {
                return g.getChildAt(0);
            }
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findGrip(g.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static View findBurst(View v) {
        if (v instanceof RankUpBurst) {
            return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findBurst(g.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Drags {@code grip} straight down by {@code dy} px and releases. */
    private static void drag(View grip, int dy) {
        long t = SystemClock.uptimeMillis();
        float x = 100;
        float y = 10;
        grip.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0));
        for (int i = 1; i <= 4; i++) {
            grip.dispatchTouchEvent(MotionEvent.obtain(t, t + i * 60L, MotionEvent.ACTION_MOVE, x, y + dy * i / 4f, 0));
        }
        grip.dispatchTouchEvent(MotionEvent.obtain(t, t + 400, MotionEvent.ACTION_UP, x, y + dy, 0));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
