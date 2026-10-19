package app.p3r.dayplanner.v3.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/**
 * A giant Latin word drawn as an outline behind content and cropped by the bounds, like the
 * WEEKDAY / WEEKEND lettering behind the game's calendar. Decoration only.
 */
final class WatermarkDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String word;
    private final float rotation;
    private final float xFraction;

    /**
     * @param size      text size in px
     * @param stroke    outline width in px (0 fills the letters)
     * @param rotation  degrees, negative tilts up to the right
     * @param xFraction where the word starts, as a fraction of the width
     */
    WatermarkDrawable(String word, Typeface face, int color, float size, float stroke, float rotation, float xFraction) {
        this.word = word;
        this.rotation = rotation;
        this.xFraction = xFraction;
        paint.setTypeface(face);
        paint.setTextSize(size);
        paint.setColor(color);
        if (stroke > 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }
    }

    void setWord(String value) {
        if (value == null ? word != null : !value.equals(word)) {
            word = value;
            invalidateSelf();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty() || word == null || word.isEmpty()) {
            return;
        }
        int save = canvas.save();
        canvas.clipRect(b);
        float x = b.left + b.width() * xFraction;
        float y = b.top + b.height() * 0.5f + paint.getTextSize() * 0.36f;
        canvas.rotate(rotation, x, y);
        canvas.drawText(word, x, y, paint);
        canvas.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
