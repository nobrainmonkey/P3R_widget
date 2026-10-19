package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.time.Duration;

/** Fast repeated navigation must not leave old content covering the current workspace. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HomeNavigationMotionTest extends UiTestBase {
    @Test public void replacingBrowsePagesKeepsOneWorkspaceAndReturnsWithoutLosingIt() {
        HomeActivity activity = home();
        View workspace = Ui.column(activity);
        activity.pushFillingPage("图鉴与合成", workspace);
        activity.pushFillingPage("第一个面具", Ui.body(activity, "第一个面具详情"));
        Ui.setAnimationsEnabled(true);
        activity.replaceFillingPage("第二个配方", Ui.body(activity, "第二个配方内容"));
        assertEquals(2, activity.pageDepth());
        activity.returnToPage(workspace);
        assertEquals(1, activity.pageDepth());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertTrue(workspace.isAttachedToWindow());
        assertFalse(Shots.has(activity, "第二个配方内容"));
        assertTrue(activity.popPage());
        assertTrue("repeated back during the same exit is consumed", activity.popPage());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertFalse(activity.popPage());
    }

    @Test public void movingDirectlyToTheNextPickerSurvivesTheOldDismissAnimation() {
        HomeActivity activity = home();
        Ui.setAnimationsEnabled(true);
        SheetHost sheets = activity.sheets();
        int[] closed = {0};
        sheets.show(new TextView(activity), () -> closed[0]++);
        assertTrue(sheets.dismiss());
        assertEquals(1, closed[0]);
        View nextPicker = Ui.body(activity, "选择下一个素材");
        sheets.show(nextPicker, () -> closed[0]++);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertTrue(sheets.isShowing());
        assertSame(nextPicker, sheets.content());
        assertEquals(1, closed[0]);
        sheets.dismiss();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertFalse(sheets.isShowing());
        assertEquals(2, closed[0]);
    }
}
