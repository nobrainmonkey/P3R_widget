package app.p3r.dayplanner.v3.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Page background in the P3R menu style: deep navy water with a soft royal glow in the upper
 * right and a few translucent diagonal light shafts, like sunlight through the sea in the game's
 * menus. Deterministic (no randomness), so render tests are stable.
 */
public final class OceanDrawable extends Drawable {
    /** Shafts as {x at top (fraction of width), width (fraction), alpha 0..255}. */
    private static final float[][] SHAFTS = {
        {0.62f, 0.16f, 26f},
        {0.86f, 0.07f, 20f},
        {1.02f, 0.22f, 16f},
        {0.30f, 0.05f, 12f},
    };

    private final Paint base = new Paint();
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shaft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int top;
    private final int middle;
    private final int bottom;
    private final int glowColor;
    private final int lightColor;
    private int lastWidth = -1;
    private int lastHeight = -1;

    /**
     * @param top     colour at the top of the page
     * @param middle  colour at 45 % height
     * @param bottom  colour at the bottom
     * @param glow    colour of the upper-right glow (alpha is applied here)
     * @param light   colour of the light shafts (alpha is applied here)
     */
    public OceanDrawable(int top, int middle, int bottom, int glow, int light) {
        this.top = top;
        this.middle = middle;
        this.bottom = bottom;
        this.glowColor = glow;
        this.lightColor = light;
        line.setStyle(Paint.Style.STROKE);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        lastWidth = -1;
    }

    private void ensureShaders(Rect b) {
        if (b.width() == lastWidth && b.height() == lastHeight) {
            return;
        }
        lastWidth = b.width();
        lastHeight = b.height();
        base.setShader(new LinearGradient(0, b.top, 0, b.bottom,
            new int[]{top, middle, bottom}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        float radius = Math.max(b.width(), b.height()) * 0.75f;
        glow.setShader(new RadialGradient(b.right - b.width() * 0.1f, b.top + b.height() * 0.04f, radius,
            new int[]{withAlpha(glowColor, 110), withAlpha(glowColor, 30), withAlpha(glowColor, 0)},
            new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) {
            return;
        }
        ensureShaders(b);
        canvas.drawRect(b, base);
        canvas.drawRect(b, glow);

        float w = b.width();
        float h = b.height();
        float drift = h * 0.55f;
        for (float[] s : SHAFTS) {
            float x = b.left + w * s[0];
            float width = w * s[1];
            path.reset();
            path.moveTo(x, b.top);
            path.lineTo(x + width, b.top);
            path.lineTo(x + width - drift, b.bottom);
            path.lineTo(x - drift * 1.08f, b.bottom);
            path.close();
            shaft.setShader(new LinearGradient(x, b.top, x - drift, b.top + h * 0.85f,
                withAlpha(lightColor, (int) s[2]), withAlpha(lightColor, 0), Shader.TileMode.CLAMP));
            canvas.drawPath(path, shaft);
        }

        line.setStrokeWidth(Math.max(1f, w / 400f));
        line.setColor(withAlpha(lightColor, 34));
        float lx = b.left + w * 0.78f;
        canvas.drawLine(lx, b.top, lx - drift * 0.9f, b.top + h * 0.9f, line);
        line.setColor(withAlpha(lightColor, 20));
        float lx2 = b.left + w * 0.95f;
        canvas.drawLine(lx2, b.top, lx2 - drift, b.bottom, line);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    @Override
    public void setAlpha(int alpha) {
        base.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        base.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
