package app.p3r.dayplanner.v3.widget;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.widget.ViewFlipper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import app.p3r.dayplanner.R;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The day/night crossfade host on hand-built cards ({@link CardRendererTest#sample}): both palette
 * slots, the settled static tree, and a real attached crossfade drawn frame by frame (frames in
 * $p3r.qa.dir/v3/widget/fade). Ported from the 3.0.x guide widget's fade test.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 31}, qualifiers = "zh-rCN-w360dp-h800dp-xhdpi", shadows = {RealViewAnimator.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PaletteHostTest {
    private static final int W = 250;
    private static final int H = 200;

    private static RemoteViews host(Context c, boolean night, boolean animate) {
        return PaletteHost.build(c, night, animate, n -> CardRenderer.render(c, CardRendererTest.sample(n), W, H));
    }

    private static String title(View card) {
        return RenderSupport.titleText(card.findViewById(R.id.widget_task));
    }

    private static String period(View card) {
        return ((TextView) card.findViewById(R.id.widget_period_word)).getText().toString();
    }

    @Test
    public void aTransitionShowsTheIncomingPaletteWithoutStartingItself() {
        Context c = RuntimeEnvironment.getApplication();
        for (boolean night : new boolean[]{false, true}) {
            View root = host(c, night, true).apply(c, new FrameLayout(c));
            ViewFlipper flipper = root.findViewById(R.id.widget_palette_flipper);
            assertEquals(2, flipper.getChildCount());
            assertEquals(night ? 1 : 0, flipper.getDisplayedChild());
            assertFalse(flipper.isFlipping());
            assertFalse(flipper.getAnimateFirstView());
            assertEquals(220, flipper.getInAnimation().getDuration());
            assertEquals(220, flipper.getOutAnimation().getDuration());
            assertEquals(View.VISIBLE, flipper.getVisibility());
            assertEquals(View.GONE, root.findViewById(R.id.widget_palette_static).getVisibility());
            assertEquals("皇帝 · 小田桐秀利", title(flipper.getCurrentView()));
            assertEquals(night ? "晚上" : "放学后", period(flipper.getCurrentView()));
        }
    }

    @Test
    public void aSettledHostShowsTheStaticCardAndFillsBothSlots() {
        Context c = RuntimeEnvironment.getApplication();
        for (boolean night : new boolean[]{false, true}) {
            View root = host(c, night, false).apply(c, new FrameLayout(c));
            ViewFlipper flipper = root.findViewById(R.id.widget_palette_flipper);
            View settled = root.findViewById(R.id.widget_palette_static);
            assertEquals(View.INVISIBLE, flipper.getVisibility());
            assertEquals(View.VISIBLE, settled.getVisibility());
            assertEquals("皇帝 · 小田桐秀利", title(settled));
            assertEquals(night ? "晚上" : "放学后", period(settled));
            assertEquals("放学后", period(flipper.getChildAt(0)));
            assertEquals("晚上", period(flipper.getChildAt(1)));
        }
    }

    /**
     * On a host that is attached and drawn (as on a launcher), the transition reapplies into the same
     * ViewFlipper and really animates: the outgoing card starts opaque, and the start, middle and end
     * frames differ. The settle update then restores the static card in the same tree.
     */
    @Test
    public void theCrossfadeSurvivesAttachAndDrawsInBetweenFrames() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout parent = new FrameLayout(activity);
        activity.setContentView(parent);
        View root = host(activity, false, false).apply(activity, parent);
        parent.addView(root);
        int w = Math.round(W * activity.getResources().getDisplayMetrics().density);
        int h = Math.round(H * activity.getResources().getDisplayMetrics().density);
        layout(root, w, h);
        ViewFlipper original = root.findViewById(R.id.widget_palette_flipper);

        host(activity, true, true).reapply(activity, root);
        ViewFlipper flipper = root.findViewById(R.id.widget_palette_flipper);
        assertSame("the animator survives reapply", original, flipper);
        assertEquals(View.GONE, root.findViewById(R.id.widget_palette_static).getVisibility());
        layout(root, w, h);
        assertNotNull("incoming animation after the attached reapply", flipper.getCurrentView().getAnimation());
        for (int i = 0; i < 2; i++) flipper.getChildAt(i).getAnimation().setStartTime(1000);

        File dir = RenderSupport.outputDir("fade");
        int[] pixels = new int[3];
        long[] times = {1000, 1110, 1230};
        for (int i = 0; i < times.length; i++) {
            Object attach = ReflectionHelpers.getField(root, "mAttachInfo");
            ReflectionHelpers.setField(attach, "mDrawingTime", times[i]);
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(bitmap));
            pixels[i] = bitmap.getPixel(w / 2, h * 2 / 5);
            try (FileOutputStream out = new FileOutputStream(new File(dir, "fade-frame-" + i + "-sdk" + android.os.Build.VERSION.SDK_INT + ".png"))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
            }
        }
        Files.write(new File(dir, "fade-frame-pixels-sdk" + android.os.Build.VERSION.SDK_INT + ".txt").toPath(),
                Collections.singletonList(Arrays.toString(pixels)));
        assertEquals("the outgoing card is opaque at the start", 255, Color.alpha(pixels[0]));
        assertNotEquals("start differs from the middle", pixels[0], pixels[1]);
        assertNotEquals("middle differs from the end", pixels[1], pixels[2]);
        assertNotEquals("start differs from the end", pixels[0], pixels[2]);

        host(activity, true, false).reapply(activity, root);
        assertSame(original, root.findViewById(R.id.widget_palette_flipper));
        assertEquals(View.VISIBLE, root.findViewById(R.id.widget_palette_static).getVisibility());
        assertEquals(View.INVISIBLE, original.getVisibility());
    }

    private static void layout(View root, int w, int h) {
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
    }
}
