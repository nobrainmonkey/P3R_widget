package app.p3r.dayplanner.v3.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import app.p3r.dayplanner.R;

/**
 * Ten slanted pips: white up to the confirmed RANK, cyan up to the forecast end RANK, hollow
 * beyond. At MAX the white pips carry a soft cyan glow. On an inverted (white) tile the confirmed
 * pips are navy and the forecast royal blue. {@link #playFill} fills them left to right once.
 */
public final class RankPips extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int observed;
    private int projected;
    private float heightDp = 10;
    private boolean inverted;
    private float forecastAlpha = 1f;
    /** 0..1 share of the pips drawn (the fill-in animation); 1 when still. */
    private float shown = 1f;
    private ValueAnimator fill;

    public RankPips(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void set(int observed, int projected) {
        this.observed = Math.max(0, Math.min(10, observed));
        this.projected = Math.max(this.observed, Math.min(10, projected));
        invalidate();
    }

    /** Pip height in dp (10 on tiles, 12 in the detail sheet). */
    public RankPips height(float dp) {
        heightDp = dp;
        requestLayout();
        return this;
    }

    /** Navy / royal ink for a white tile. */
    public void setInverted(boolean value) {
        if (inverted != value) {
            inverted = value;
            invalidate();
        }
    }

    /** Fades the forecast pips (while a new plan is being made). */
    public void setForecastAlpha(float alpha) {
        if (forecastAlpha != alpha) {
            forecastAlpha = alpha;
            invalidate();
        }
    }

    /** Fills the pips in from the left (200 ms); nothing when animations are off. */
    public void playFill() {
        if (!Ui.animationsEnabled()) {
            return;
        }
        if (fill != null) {
            fill.cancel();
        }
        fill = ValueAnimator.ofFloat(0f, 1f);
        fill.setDuration(200);
        fill.setInterpolator(Motion.EASE);
        fill.addUpdateListener(a -> {
            shown = (float) a.getAnimatedValue();
            invalidate();
        });
        shown = 0f;
        fill.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (fill != null) {
            fill.cancel();
            fill = null;
        }
        shown = 1f;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // A little room for the glow above and below the pips.
        int h = Ui.dp(getContext(), heightDp + 4);
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Context c = getContext();
        float inset = Ui.dp(c, 2);
        float top = inset;
        float h = getHeight() - inset * 2;
        float gap = Ui.dp(c, 3);
        float skew = h * 0.6f;
        float w = (getWidth() - skew - gap * 9 - inset * 2) / 10f;
        int confirmed = Ui.col(c, inverted ? R.color.p3r_navy_800 : R.color.p3r_white);
        int forecast = Ui.col(c, inverted ? R.color.p3r_royal_500 : R.color.p3r_cyan_400);
        int hollow = inverted ? Ui.alpha(Ui.col(c, R.color.p3r_navy_800), 90) : Ui.col(c, R.color.p3r_line);
        boolean max = observed >= 10;
        int visible = (int) Math.ceil(shown * 10);
        for (int i = 0; i < 10; i++) {
            float left = inset + i * (w + gap);
            path.reset();
            path.moveTo(left + skew, top);
            path.lineTo(left + skew + w, top);
            path.lineTo(left + w, top + h);
            path.lineTo(left, top + h);
            path.close();
            boolean drawn = i < visible;
            if (i < observed && drawn) {
                if (max && !inverted) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(Ui.dp(c, 3));
                    paint.setColor(Ui.alpha(Ui.col(c, R.color.p3r_cyan_400), 90));
                    canvas.drawPath(path, paint);
                }
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(confirmed);
            } else if (i < projected && drawn) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Ui.alpha(forecast, Math.round(255 * forecastAlpha)));
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Ui.dp(c, 1));
                paint.setColor(hollow);
            }
            canvas.drawPath(path, paint);
        }
    }
}
