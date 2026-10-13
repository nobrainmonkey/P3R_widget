package app.p3r.dayplanner.v3.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Moon-phase glyph for the date plate (phase 0 = new moon … 4 = full moon … 7 = waning
 * crescent), drawn as a lit area with a terminator ellipse plus a thin rim. Decorative.
 */
public final class MoonDrawable extends Drawable {
    private final Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private int phase;

    public MoonDrawable(int litColor, int rimColor, float rimWidthPx) {
        lit.setColor(litColor);
        lit.setStyle(Paint.Style.FILL);
        rim.setColor(rimColor);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(rimWidthPx);
    }

    /** Sets the phase (0..7, wrapped). */
    public void setPhase(int phase) {
        this.phase = ((phase % 8) + 8) % 8;
        invalidateSelf();
    }

    public int getPhase() {
        return phase;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float size = Math.min(b.width(), b.height()) - rim.getStrokeWidth() * 2;
        if (size <= 0) {
            return;
        }
        float r = size / 2f;
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();
        if (phase != 0) {
            drawLit(canvas, cx, cy, r);
        }
        canvas.drawCircle(cx, cy, r, rim);
    }

    private void drawLit(Canvas canvas, float cx, float cy, float r) {
        if (phase == 4) {
            canvas.drawCircle(cx, cy, r, lit);
            return;
        }
        double angle = 2 * Math.PI * phase / 8.0;
        float terminator = (float) (r * Math.cos(angle));
        boolean waxing = phase < 4;
        path.reset();
        // Lit limb: right half while waxing, left half while waning.
        oval.set(cx - r, cy - r, cx + r, cy + r);
        path.arcTo(oval, -90, waxing ? 180 : -180, true);
        // Terminator: half-ellipse back to the top; its bulge decides crescent vs gibbous.
        float tx = Math.abs(terminator);
        oval.set(cx - tx, cy - r, cx + tx, cy + r);
        boolean crescent = phase == 1 || phase == 2 || phase == 7 || phase == 6;
        boolean bulgeTowardLit = crescent;
        float sweep;
        if (waxing) {
            sweep = bulgeTowardLit ? -180 : 180;
        } else {
            sweep = bulgeTowardLit ? 180 : -180;
        }
        if (phase == 2 || phase == 6) {
            path.lineTo(cx, cy - r);
        } else {
            path.arcTo(oval, 90, sweep, false);
        }
        path.close();
        canvas.drawPath(path, lit);
    }

    @Override
    public int getIntrinsicWidth() {
        return -1;
    }

    @Override
    public int getIntrinsicHeight() {
        return -1;
    }

    @Override
    public void setAlpha(int alpha) {
        lit.setAlpha(alpha);
        rim.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        lit.setColorFilter(colorFilter);
        rim.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
