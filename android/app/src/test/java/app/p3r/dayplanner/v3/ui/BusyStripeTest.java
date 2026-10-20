package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import app.p3r.dayplanner.v3.Intents;

/**
 * The busy stripe moves only while it is shown: the hidden stripes in the 我的 and 规划设置 rows (one
 * per row, waiting for a 从头重新安排) must not keep an endless animation going.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BusyStripeTest extends UiTestBase {
    @Test
    public void theStripeMovesOnlyWhileShown() {
        HomeActivity a = home();
        Ui.setAnimationsEnabled(true);
        FrameLayout box = new FrameLayout(a);
        box.setVisibility(View.GONE);
        BusyStripe stripe = new BusyStripe(a);
        box.addView(stripe);
        a.pushPage("测试", box);
        Shots.idle();
        assertTrue(stripe.isAttachedToWindow());
        assertFalse("attached but hidden", stripe.running());
        box.setVisibility(View.VISIBLE);
        assertTrue(stripe.running());
        box.setVisibility(View.INVISIBLE);
        assertFalse(stripe.running());
        box.setVisibility(View.VISIBLE);
        stripe.setVisibility(View.GONE);
        assertFalse(stripe.running());
        stripe.setVisibility(View.VISIBLE);
        assertTrue(stripe.running());
        a.popPage();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertFalse(stripe.isAttachedToWindow());
        assertFalse(stripe.running());
    }

    @Test
    public void nothingMovesOnTheMenusWhileNoPlanIsBeingMade() {
        Ui.setAnimationsEnabled(true);
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, "me");
        HomeActivity a = home(intent);
        Shots.click(a, "规划设置");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        List<BusyStripe> stripes = new ArrayList<>();
        collect(a.getWindow().getDecorView(), stripes);
        assertTrue("我的 and 规划设置 rows each hold one: " + stripes.size(), stripes.size() >= 8);
        for (BusyStripe stripe : stripes) {
            assertTrue(stripe.isAttachedToWindow());
            assertFalse(stripe.running());
        }
    }

    private static void collect(View v, List<BusyStripe> out) {
        if (v instanceof BusyStripe) {
            out.add((BusyStripe) v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), out);
            }
        }
    }
}
