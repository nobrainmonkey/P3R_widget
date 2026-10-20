package app.p3r.dayplanner.v3.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.animation.LinearInterpolator;

import app.p3r.dayplanner.R;

/**
 * Thin animated band of diagonal cyan stripes, used as the "正在重新安排…" indicator.
 * The animation runs only while the stripe is shown (attached, it and its parents visible, in a
 * visible window), so the hidden stripes of rows that are not busy cost nothing; it is static in
 * render tests.
 */
public final class BusyStripe extends View {
    /** Height of the band (dp). */
    static final int HEIGHT_DP = 4;

    private final Paint track = new Paint();
    private final Paint stripe = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private ValueAnimator animator;
    private float phase;

    public BusyStripe(Context context) {
        super(context);
        track.setColor(Ui.col(context, R.color.ui_cyan_faint));
        stripe.setColor(Ui.alpha(Ui.col(context, R.color.p3r_cyan_400), 170));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = Ui.dp(getContext(), HEIGHT_DP);
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(height, heightMeasureSpec));
    }

    /** Called on attach when shown, and whenever this view, a parent or the window is shown or hidden. */
    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible && isAttachedToWindow() && Ui.animationsEnabled()) {
            start();
        } else {
            stop();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    /** Whether the stripes are moving (tests). */
    boolean running() {
        return animator != null;
    }

    private void start() {
        if (animator != null) {
            return;
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(900);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        canvas.drawRect(0, 0, w, h, track);
        float period = Ui.dp(getContext(), 18);
        float band = period / 2f;
        float offset = phase * period;
        for (float x = -period * 2 + offset; x < w + period; x += period) {
            path.reset();
            path.moveTo(x + h, 0);
            path.lineTo(x + h + band, 0);
            path.lineTo(x + band, h);
            path.lineTo(x, h);
            path.close();
            canvas.drawPath(path, stripe);
        }
    }
}
