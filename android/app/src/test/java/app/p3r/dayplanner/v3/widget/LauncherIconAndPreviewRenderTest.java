package app.p3r.dayplanner.v3.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

import app.p3r.dayplanner.R;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * QA renders of the adaptive launcher icon (circle and rounded-square masks, themed layer) and of
 * the widget picker preview. The legacy-density PNG icons and the previewImage PNG in the source
 * tree are copies of these outputs.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LauncherIconAndPreviewRenderTest {

    @After
    public void tearDown() {
        WidgetApi.setOverrideForTest(null);
        WidgetWorker.setForTest(null, null);
    }

    private static void save(Bitmap bitmap, File dir, String name) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
    }

    /** Draws the full-bleed layers and clips them with a mask (circle or rounded square). */
    private static Bitmap masked(AdaptiveIconDrawable icon, int px, boolean circle) {
        Bitmap layers = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(layers);
        // Adaptive layers are 108dp with the visible 72dp in the middle: draw them 1.5x larger.
        int bleed = px / 4;
        for (Drawable layer : new Drawable[]{icon.getBackground(), icon.getForeground()}) {
            layer.setBounds(-bleed, -bleed, px + bleed, px + bleed);
            layer.draw(canvas);
        }
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas result = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Path mask = new Path();
        if (circle) mask.addCircle(px / 2f, px / 2f, px / 2f, Path.Direction.CW);
        else mask.addRoundRect(new RectF(0, 0, px, px), px * 0.22f, px * 0.22f, Path.Direction.CW);
        result.drawPath(mask, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        result.drawBitmap(layers, 0, 0, paint);
        return out;
    }

    @Test
    public void adaptiveIconRendersWithEveryMaskAndAThemedLayer() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        File dir = RenderSupport.outputDir("icon");
        Drawable drawable = c.getDrawable(R.mipmap.ic_launcher);
        assertTrue("adaptive icon", drawable instanceof AdaptiveIconDrawable);
        AdaptiveIconDrawable icon = (AdaptiveIconDrawable) drawable;
        assertNotNull("themed icon layer", icon.getMonochrome());
        save(masked(icon, 512, true), dir, "icon-circle-512");
        save(masked(icon, 512, false), dir, "icon-squircle-512");
        int[] legacy = {48, 72, 96, 144, 192};
        String[] buckets = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        for (int i = 0; i < legacy.length; i++) {
            save(masked(icon, legacy[i], false), dir, "ic_launcher-" + buckets[i]);
        }

        Bitmap themed = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(themed);
        canvas.drawColor(0xFF2E3440);
        Drawable mono = icon.getMonochrome();
        mono.setBounds(-128, -128, 640, 640);
        mono.setTint(0xFFD8E6FF);
        mono.draw(canvas);
        save(themed, dir, "icon-themed-512");

        // Foreground ink stays inside the 66dp safe zone except the decorative slash.
        Drawable text = c.getDrawable(R.drawable.ic_launcher_monochrome);
        Bitmap ink = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888);
        text.setBounds(0, 0, 108, 108);
        text.draw(new Canvas(ink));
        for (int y = 0; y < 108; y++) {
            for (int x = 0; x < 108; x++) {
                if (Color.alpha(ink.getPixel(x, y)) > 128) {
                    double distance = Math.hypot(x + 0.5 - 54, y + 0.5 - 54);
                    assertTrue("ink outside safe zone at " + x + "," + y, distance <= 33.5);
                }
            }
        }
    }

    @Test
    public void widgetPickerPreviewsAreRealRenders() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        File dir = RenderSupport.outputDir("preview");
        FakePlannerApi fake = new FakePlannerApi();
        fake.observeAt("meet:emperor");
        WidgetApi.setOverrideForTest(fake);
        WidgetWorker.setForTest(Runnable::run, (task, delay) -> { });
        c.getSharedPreferences(WidgetPrefs.PREFS, 0).edit().clear().commit();
        View plan = RenderSupport.layout(c, PlanWidget.renderForTest(c, 77, 320, 180), 320, 180);
        save(RenderSupport.draw(plan), dir, "widget_preview_plan");
        View sample = RenderSupport.layout(c,
                new android.widget.RemoteViews(c.getPackageName(), R.layout.widget_card_regular), 320, 180);
        save(RenderSupport.draw(sample), dir, "preview-layout-xml-defaults");
        assertTrue(plan.getWidth() > 0 && sample.getWidth() > 0);
    }
}
