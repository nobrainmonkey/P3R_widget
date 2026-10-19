package app.p3r.dayplanner.v3.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import app.p3r.dayplanner.R;

/**
 * The 「RANK UP!!」 flourish after a social link rank-up is recorded: a cyan band sweeps across
 * the screen, the Latin words punch in over it with the link's line below ("皇帝 · RANK 7"),
 * then everything slides away. About 1.3 s, tap to skip; never shown when animations are off.
 */
final class RankUpBurst extends View {
    private static final long DURATION = 1300;

    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint slab = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrim = new Paint();
    private final TextPaint big = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bigShadow = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint line = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    /** Latin runs of the caption ("RANK 7"), in the numeral face like everywhere else. */
    private final TextPaint lineLatin = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final String caption;
    private final String words;
    private float t;
    private ValueAnimator animator;
    private Runnable done;

    private RankUpBurst(Context context, String words, String caption) {
        super(context);
        this.words = words;
        this.caption = caption;
        band.setColor(Ui.col(context, R.color.p3r_cyan_400));
        slab.setColor(Ui.col(context, R.color.p3r_white));
        scrim.setColor(Ui.alpha(Ui.col(context, R.color.p3r_navy_950), 150));
        Typeface face = Ui.numerals(context);
        big.setTypeface(face);
        big.setColor(Ui.col(context, R.color.p3r_white));
        big.setTextSize(Ui.dp(context, 76));
        bigShadow.setTypeface(face);
        bigShadow.setColor(Ui.col(context, R.color.p3r_navy_950));
        bigShadow.setTextSize(Ui.dp(context, 76));
        line.setTypeface(Typeface.DEFAULT_BOLD);
        line.setColor(Ui.col(context, R.color.p3r_navy_950));
        line.setTextSize(Ui.dp(context, 20));
        lineLatin.setTypeface(face);
        lineLatin.setColor(Ui.col(context, R.color.p3r_navy_950));
        lineLatin.setTextSize(Ui.dp(context, 23));
        setClickable(true);
        setOnClickListener(v -> finish());
        setContentDescription(caption == null ? words : words + " " + caption);
    }

    /**
     * Plays the flourish over {@code parent} (a FrameLayout filling the window) and runs
     * {@code done} afterwards (also right away when animations are off).
     */
    static void play(ViewGroup parent, String words, String caption, Runnable done) {
        if (!Ui.animationsEnabled() || parent == null) {
            if (done != null) {
                done.run();
            }
            return;
        }
        RankUpBurst burst = new RankUpBurst(parent.getContext(), words, caption);
        burst.done = done;
        parent.addView(burst, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        burst.announceForAccessibility(burst.getContentDescription());
        burst.start();
    }

    private void start() {
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DURATION);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                finish();
            }
        });
        animator.start();
    }

    private void finish() {
        if (animator != null) {
            animator.removeAllListeners();
            animator.cancel();
            animator = null;
        }
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) {
            parent.removeView(this);
        }
        Runnable callback = done;
        done = null;
        if (callback != null) {
            callback.run();
        }
    }

    /** 0..1 progress of a phase that starts at {@code from} and lasts {@code length}, eased out. */
    private float phase(float from, float length) {
        float p = Math.max(0f, Math.min(1f, (t - from) / length));
        return 1f - (1f - p) * (1f - p) * (1f - p);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float in = phase(0f, 0.22f);
        float out = phase(0.8f, 0.2f);
        scrim.setAlpha(Math.round(150 * in * (1f - out)));
        canvas.drawRect(0, 0, w, h, scrim);

        float cy = h * 0.44f;
        float bandH = Ui.dp(getContext(), 128);
        float tilt = bandH * 0.5f;
        // The band sweeps in from the left, then leaves to the right.
        float x0 = -w * (1f - in) + w * out * 1.2f;
        float x1 = x0 + w * 1.15f;
        path.reset();
        path.moveTo(x0 + tilt, cy - bandH / 2f);
        path.lineTo(x1 + tilt, cy - bandH / 2f);
        path.lineTo(x1 - tilt, cy + bandH / 2f);
        path.lineTo(x0 - tilt, cy + bandH / 2f);
        path.close();
        canvas.save();
        canvas.translate(Ui.dp(getContext(), 6), Ui.dp(getContext(), 6));
        canvas.drawPath(path, slab);
        canvas.restore();
        canvas.drawPath(path, band);

        float pop = phase(0.12f, 0.25f);
        float textOut = out;
        if (pop > 0f) {
            float scale = 1.35f - 0.35f * pop;
            float tw = big.measureText(words);
            float tx = (w - tw) / 2f + w * textOut;
            float ty = cy + big.getTextSize() * 0.32f;
            canvas.save();
            canvas.scale(scale, scale, w / 2f, cy);
            int a = Math.round(255 * pop * (1f - textOut));
            bigShadow.setAlpha(a);
            big.setAlpha(a);
            canvas.drawText(words, tx + Ui.dp(getContext(), 4), ty + Ui.dp(getContext(), 4), bigShadow);
            canvas.drawText(words, tx, ty, big);
            canvas.restore();
        }
        if (caption != null) {
            float sub = phase(0.3f, 0.2f);
            if (sub > 0f) {
                int a = Math.round(255 * sub * (1f - textOut));
                line.setAlpha(a);
                lineLatin.setAlpha(a);
                float cw = captionWidth();
                // A long caption shrinks to fit the band instead of running off the screen.
                float fit = Math.min(1f, (w - Ui.dp(getContext(), 48)) / Math.max(1f, cw));
                float cx = (w - cw * fit) / 2f - Ui.dp(getContext(), 20) * (1f - sub) + w * textOut;
                float cyText = cy + bandH / 2f - Ui.dp(getContext(), 14);
                canvas.save();
                canvas.scale(fit, fit, cx, cyText);
                drawCaption(canvas, cx, cyText);
                canvas.restore();
            }
        }
    }

    /** True for the characters drawn in the numeral face (Latin letters, digits, the arrow). */
    private static boolean latin(char ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || ch == '→';
    }

    private float captionWidth() {
        float width = 0f;
        int i = 0;
        while (i < caption.length()) {
            int j = runEnd(i);
            width += (latin(caption.charAt(i)) ? lineLatin : line).measureText(caption, i, j);
            i = j;
        }
        return width;
    }

    private void drawCaption(Canvas canvas, float x, float y) {
        int i = 0;
        while (i < caption.length()) {
            int j = runEnd(i);
            TextPaint paint = latin(caption.charAt(i)) ? lineLatin : line;
            canvas.drawText(caption, i, j, x, y, paint);
            x += paint.measureText(caption, i, j);
            i = j;
        }
    }

    /** End of the run of the same face starting at {@code i}; a space stays with Latin before more Latin. */
    private int runEnd(int i) {
        boolean l = latin(caption.charAt(i));
        int j = i + 1;
        while (j < caption.length()) {
            char ch = caption.charAt(j);
            boolean next = ch == ' ' ? l && j + 1 < caption.length() && latin(caption.charAt(j + 1)) : latin(ch);
            if (next != l) {
                break;
            }
            j++;
        }
        return j;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.removeAllListeners();
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
        // Removed from outside before the end (window going away): still hand over.
        Runnable callback = done;
        done = null;
        if (callback != null) {
            callback.run();
        }
    }
}
