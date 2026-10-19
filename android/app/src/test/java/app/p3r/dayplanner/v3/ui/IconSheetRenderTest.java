package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.p3r.dayplanner.R;

/**
 * Every in-app icon (ui_ic_*) inflates as a 24dp vector, is drawn as a solid angular glyph (no
 * round caps or joins), and is rendered on the page navy at the sizes the screens use into
 * $p3r.qa.dir/v3/ui/icon_sheet.png for review.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class IconSheetRenderTest {

    private static final String[] TABS = {
        "ui_ic_tab_today", "ui_ic_tab_schedule", "ui_ic_tab_links", "ui_ic_tab_tips", "ui_ic_mask", "ui_ic_tab_me"};

    @Test
    public void everyIconInflatesAndRenders() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        List<String> names = new ArrayList<>();
        for (Field f : R.drawable.class.getFields()) {
            if (f.getName().startsWith("ui_ic_")) {
                names.add(f.getName());
            }
        }
        Collections.sort(names);
        List<Integer> ids = new ArrayList<>();
        for (String name : names) {
            ids.add(R.drawable.class.getField(name).getInt(null));
        }
        assertTrue("icon set found", names.size() >= 30);
        for (String tab : TABS) {
            assertTrue(tab, names.contains(tab));
        }

        int size = Ui.dp(c, 24);
        for (int i = 0; i < names.size(); i++) {
            Drawable d = c.getDrawable(ids.get(i));
            assertNotNull(names.get(i), d);
            assertTrue(names.get(i) + " is a vector", d instanceof VectorDrawable);
            assertEquals(names.get(i) + " width", size, d.getIntrinsicWidth());
            assertEquals(names.get(i) + " height", size, d.getIntrinsicHeight());
        }

        float density = c.getResources().getDisplayMetrics().density;
        int navy = Ui.col(c, R.color.p3r_navy_900);
        int white = Color.WHITE;
        int cyan = Ui.col(c, R.color.p3r_cyan_400);
        int muted = Ui.col(c, R.color.p3r_ink_muted);
        int columns = 5;
        int cellW = Math.round(132 * density);
        int cellH = Math.round(84 * density);
        int pad = Math.round(8 * density);
        int rows = (names.size() + columns - 1) / columns;
        int tabRowH = Math.round(70 * density);
        Bitmap sheet = Bitmap.createBitmap(columns * cellW + 2 * pad, rows * cellH + tabRowH + 2 * pad, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(sheet);
        canvas.drawColor(navy);
        Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        label.setColor(Ui.col(c, R.color.p3r_ink_muted));
        label.setTextSize(10 * density);
        Paint grid = new Paint();
        grid.setColor(Color.argb(40, 255, 255, 255));

        for (int i = 0; i < names.size(); i++) {
            int x = pad + (i % columns) * cellW;
            int y = pad + (i / columns) * cellH;
            canvas.drawRect(x + 2 * density, y + 2 * density, x + cellW - 2 * density, y + cellH - 2 * density, grid);
            int top = y + Math.round(8 * density);
            drawIcon(c, canvas, ids.get(i), white, x + Math.round(6 * density), top, 48, density);
            drawIcon(c, canvas, ids.get(i), white, x + Math.round(60 * density), top, 24, density);
            drawIcon(c, canvas, ids.get(i), cyan, x + Math.round(90 * density), top, 24, density);
            drawIcon(c, canvas, ids.get(i), cyan, x + Math.round(60 * density), top + Math.round(28 * density), 18, density);
            drawIcon(c, canvas, ids.get(i), white, x + Math.round(84 * density), top + Math.round(30 * density), 16, density);
            drawIcon(c, canvas, ids.get(i), muted, x + Math.round(106 * density), top + Math.round(30 * density), 12, density);
            canvas.drawText(names.get(i).substring("ui_ic_".length()), x + 6 * density, y + cellH - 8 * density, label);
        }

        // The tab bar row as the shell shows it: 22dp, muted, with the selected one cyan.
        int ty = pad + rows * cellH + Math.round(10 * density);
        for (int i = 0; i < TABS.length; i++) {
            int id = R.drawable.class.getField(TABS[i]).getInt(null);
            int tx = pad + Math.round((12 + i * 56) * density);
            drawIcon(c, canvas, id, i == 0 ? cyan : muted, tx, ty, 22, density);
            drawIcon(c, canvas, id, white, tx + Math.round(26 * density), ty + Math.round(4 * density), 18, density);
        }
        File file = Shots.save(sheet, "icon_sheet");
        assertTrue(file.length() > 0);

        // Solid, angular style (design §3): filled glyphs, no outlines with round caps or joins.
        File dir = new File("src/main/res/drawable");
        if (dir.isDirectory()) {
            for (String name : names) {
                String xml = new String(Files.readAllBytes(new File(dir, name + ".xml").toPath()), StandardCharsets.UTF_8);
                assertTrue(name + " is filled", xml.contains("android:fillColor=\"#FFFFFFFF\""));
                assertFalse(name + " has a round cap", xml.contains("strokeLineCap=\"round\""));
                assertFalse(name + " has a round join", xml.contains("strokeLineJoin=\"round\""));
            }
        }
    }

    private static void drawIcon(Context c, Canvas canvas, int id, int color, int x, int y, int sizeDp, float density) {
        Drawable d = Ui.tinted(c, id, color);
        int s = Math.round(sizeDp * density);
        d.setBounds(x, y, x + s, y + s);
        d.draw(canvas);
    }
}
