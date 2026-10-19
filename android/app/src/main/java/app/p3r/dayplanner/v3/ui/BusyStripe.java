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
 * The animation runs only while attached and visible; it is static in render tests.
 */
public final class BusyStripe extends View {
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
        int height = Ui.dp(getContext(), 4);
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (Ui.animationsEnabled()) {
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
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
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
