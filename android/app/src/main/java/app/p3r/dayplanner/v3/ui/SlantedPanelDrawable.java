package app.p3r.dayplanner.v3.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * The P3R panel shape: a parallelogram (top edge shifted right by {@code skew}) with optional
 * diagonal corner cuts, a solid or two-colour diagonal gradient fill, an outline and an offset
 * "slab" behind the face (the menu-button shadow of the game, drawn as a flat colour).
 *
 * <p>The drawable is stateful when pressed/disabled colours are configured, so a single instance
 * can back a button. All sizes are in pixels.
 */
public final class SlantedPanelDrawable extends Drawable {
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint slabPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path face = new Path();
    private final Path slab = new Path();
    private final Path accent = new Path();

    private int fill;
    private int fillEnd;
    private boolean gradient;
    private int pressedFill;
    private boolean hasPressed;
    private int stroke;
    private float strokeWidth;
    private int pressedStroke;
    private float skew;
    private final float[] cuts = new float[4];
    private int slabColor;
    private float slabDx;
    private float slabDy;
    private int accentColor;
    private float accentWidth;
    private boolean pressed;
    private boolean enabled = true;
    private int alpha = 255;
    private Shader gradientShader;
    private final RectF gradientRect = new RectF();

    /** Solid fill colour (ARGB). */
    public SlantedPanelDrawable fill(int color) {
        this.fill = color;
        this.gradient = false;
        invalidateSelf();
        return this;
    }

    /** Diagonal gradient from top-left ({@code start}) to bottom-right ({@code end}). */
    public SlantedPanelDrawable gradient(int start, int end) {
        this.fill = start;
        this.fillEnd = end;
        this.gradient = true;
        this.gradientShader = null;
        invalidateSelf();
        return this;
    }

    /** Fill used while pressed; makes the drawable stateful. */
    public SlantedPanelDrawable pressedFill(int color) {
        this.pressedFill = color;
        this.hasPressed = true;
        return this;
    }

    /** Outline colour and width (px). */
    public SlantedPanelDrawable stroke(int color, float widthPx) {
        this.stroke = color;
        this.pressedStroke = color;
        this.strokeWidth = widthPx;
        invalidateSelf();
        return this;
    }

    /** Outline colour while pressed. */
    public SlantedPanelDrawable pressedStroke(int color) {
        this.pressedStroke = color;
        this.hasPressed = true;
        return this;
    }

    /** Horizontal shift of the top edge (px); 0 gives a rectangle. */
    public SlantedPanelDrawable skew(float px) {
        this.skew = px;
        invalidateSelf();
        return this;
    }

    /** Diagonal corner cuts (px): top-left, top-right, bottom-right, bottom-left. */
    public SlantedPanelDrawable cuts(float topLeft, float topRight, float bottomRight, float bottomLeft) {
        cuts[0] = topLeft;
        cuts[1] = topRight;
        cuts[2] = bottomRight;
        cuts[3] = bottomLeft;
        invalidateSelf();
        return this;
    }

    /** Flat offset slab drawn behind the face, shifted by (dx, dy) px. */
    public SlantedPanelDrawable slab(int color, float dx, float dy) {
        this.slabColor = color;
        this.slabDx = dx;
        this.slabDy = dy;
        invalidateSelf();
        return this;
    }

    /** A slanted accent bar along the left edge (px width), as on the game's selected rows. */
    public SlantedPanelDrawable accent(int color, float widthPx) {
        this.accentColor = color;
        this.accentWidth = widthPx;
        invalidateSelf();
        return this;
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean nowPressed = false;
        boolean nowEnabled = false;
        for (int s : state) {
            if (s == android.R.attr.state_pressed) {
                nowPressed = true;
            } else if (s == android.R.attr.state_enabled) {
                nowEnabled = true;
            }
        }
        boolean changed = nowPressed != pressed || nowEnabled != enabled;
        pressed = nowPressed;
        enabled = nowEnabled;
        if (changed) {
            invalidateSelf();
        }
        return changed;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) {
            return;
        }
        float half = strokeWidth / 2f;
        float left = b.left + half + Math.max(0, -slabDx);
        float right = b.right - half - Math.max(0, slabDx);
        float top = b.top + half + Math.max(0, -slabDy);
        float bottom = b.bottom - half - Math.max(0, slabDy);
        buildPath(face, left, top, right, bottom);
        int effectiveAlpha = enabled ? alpha : alpha * 2 / 5;

        if (slabColor != 0 && (slabDx != 0 || slabDy != 0)) {
            float sdx = pressed ? slabDx * 0.4f : slabDx;
            float sdy = pressed ? slabDy * 0.4f : slabDy;
            buildPath(slab, left + sdx, top + sdy, right + sdx, bottom + sdy);
            slabPaint.setColor(slabColor);
            slabPaint.setAlpha(scaleAlpha(slabColor, effectiveAlpha));
            canvas.drawPath(slab, slabPaint);
        }

        int faceColor = pressed && hasPressed ? pressedFill : fill;
        fillPaint.setStyle(Paint.Style.FILL);
        if (gradient && !(pressed && hasPressed)) {
            fillPaint.setShader(gradientFor(left, top, right, bottom));
            fillPaint.setAlpha(effectiveAlpha);
        } else {
            fillPaint.setShader(null);
            fillPaint.setColor(faceColor);
            fillPaint.setAlpha(scaleAlpha(faceColor, effectiveAlpha));
        }
        if (faceColor != 0 || gradient) {
            canvas.drawPath(face, fillPaint);
        }

        if (accentWidth > 0 && accentColor != 0) {
            float h = bottom - top;
            float shift = h <= 0 ? 0 : skew;
            accent.reset();
            accent.moveTo(left + shift, top + cuts[0]);
            accent.lineTo(left + shift + accentWidth, top + cuts[0]);
            accent.lineTo(left + accentWidth, bottom - cuts[3]);
            accent.lineTo(left, bottom - cuts[3]);
            accent.close();
            accentPaint.setColor(accentColor);
            accentPaint.setAlpha(scaleAlpha(accentColor, effectiveAlpha));
            canvas.drawPath(accent, accentPaint);
        }

        if (strokeWidth > 0 && stroke != 0) {
            int strokeColor = pressed && hasPressed ? pressedStroke : stroke;
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(strokeWidth);
            strokePaint.setStrokeJoin(Paint.Join.MITER);
            strokePaint.setColor(strokeColor);
            strokePaint.setAlpha(scaleAlpha(strokeColor, effectiveAlpha));
            canvas.drawPath(face, strokePaint);
        }
    }

    /** The diagonal fill gradient, rebuilt only when the face rectangle changes. */
    private Shader gradientFor(float left, float top, float right, float bottom) {
        if (gradientShader == null || gradientRect.left != left || gradientRect.top != top
            || gradientRect.right != right || gradientRect.bottom != bottom) {
            gradientRect.set(left, top, right, bottom);
            gradientShader = new LinearGradient(left, top, right, bottom, fill, fillEnd, Shader.TileMode.CLAMP);
        }
        return gradientShader;
    }

    /** Builds the chamfered parallelogram for the given face rectangle. */
    private void buildPath(Path path, float left, float top, float right, float bottom) {
        float s = Math.min(skew, (right - left) / 3f);
        PointF[] corners = {
            new PointF(left + Math.max(0, s), top),
            new PointF(right + Math.min(0, s), top),
            new PointF(right - Math.max(0, s), bottom),
            new PointF(left - Math.min(0, s), bottom),
        };
        path.reset();
        boolean first = true;
        for (int i = 0; i < 4; i++) {
            PointF corner = corners[i];
            PointF prev = corners[(i + 3) % 4];
            PointF next = corners[(i + 1) % 4];
            float cut = Math.min(cuts[i], Math.min(distance(corner, prev), distance(corner, next)) / 2f);
            if (cut <= 0) {
                first = lineOrMove(path, corner.x, corner.y, first);
                continue;
            }
            PointF a = towards(corner, prev, cut);
            PointF c = towards(corner, next, cut);
            first = lineOrMove(path, a.x, a.y, first);
            path.lineTo(c.x, c.y);
        }
        path.close();
    }

    private static boolean lineOrMove(Path path, float x, float y, boolean first) {
        if (first) {
            path.moveTo(x, y);
        } else {
            path.lineTo(x, y);
        }
        return false;
    }

    private static float distance(PointF a, PointF b) {
        return (float) Math.hypot(a.x - b.x, a.y - b.y);
    }

    private static PointF towards(PointF from, PointF to, float length) {
        float d = distance(from, to);
        if (d == 0) {
            return new PointF(from.x, from.y);
        }
        return new PointF(from.x + (to.x - from.x) * length / d, from.y + (to.y - from.y) * length / d);
    }

    private static int scaleAlpha(int color, int alpha) {
        return (Color.alpha(color) * alpha) / 255;
    }

    @Override
    public void getOutline(Outline outline) {
        Rect b = getBounds();
        outline.setRect(b);
        outline.setAlpha(0f);
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        fillPaint.setColorFilter(colorFilter);
        strokePaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

}
