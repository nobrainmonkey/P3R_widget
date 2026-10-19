package app.p3r.dayplanner.v3.ui;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * The app's motion language, after the game's menus: things slide in from the right and settle
 * fast, lists cascade in one row after another, and pressed buttons sink a little. Everything
 * is skipped when {@link Ui#animationsEnabled()} is false, so render tests stay deterministic.
 */
final class Motion {
    /** Fast out, long settle: the menu slide. */
    static final Interpolator SETTLE = new PathInterpolator(0.16f, 1f, 0.3f, 1f);
    /** Ordinary ease for exits. */
    static final Interpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);

    /** Delay between two rows of a cascade (ms). */
    static final int STAGGER = 32;
    /** At most this many rows take part in a cascade; the rest simply appear. */
    private static final int MAX_STAGGERED = 10;

    private Motion() {}

    /** Slides {@code view} in from the right and fades it in, after {@code index} stagger steps. */
    static void enter(View view, int index) {
        if (!Ui.animationsEnabled() || view == null) {
            return;
        }
        if (index >= MAX_STAGGERED) {
            return;
        }
        float dx = Ui.dp(view.getContext(), 28);
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationX(dx);
        view.animate().alpha(1f).translationX(0f).setStartDelay((long) index * STAGGER)
            .setDuration(280).setInterpolator(SETTLE).withEndAction(() -> {
                view.setAlpha(1f);
                view.setTranslationX(0f);
            }).start();
    }

    /** Cascades the children of {@code group} in from the right, top to bottom. */
    static void cascade(ViewGroup group) {
        if (!Ui.animationsEnabled() || group == null) {
            return;
        }
        int shown = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }
            enter(child, shown++);
        }
    }

    /** Cross-slide when a tab's content replaces another: in from the side of travel. */
    static void tabIn(View view, boolean forward) {
        if (!Ui.animationsEnabled() || view == null) {
            return;
        }
        float dx = Ui.dp(view.getContext(), 24) * (forward ? 1 : -1);
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationX(dx);
        view.animate().alpha(1f).translationX(0f).setStartDelay(0).setDuration(200).setInterpolator(SETTLE)
            .withEndAction(() -> {
                view.setAlpha(1f);
                view.setTranslationX(0f);
            }).start();
    }

    /** Replaces the hero card: the old one slides out to the left, the new one in from the right. */
    static void swapIn(View view) {
        if (!Ui.animationsEnabled() || view == null) {
            return;
        }
        float dx = Ui.dp(view.getContext(), 56);
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationX(dx);
        view.setRotation(-1.5f);
        view.animate().alpha(1f).translationX(0f).rotation(0f).setStartDelay(60).setDuration(320)
            .setInterpolator(SETTLE).withEndAction(() -> {
                view.setAlpha(1f);
                view.setTranslationX(0f);
                view.setRotation(0f);
            }).start();
    }

    /** Buttons sink a little while pressed and spring back on release. */
    @SuppressLint("ClickableViewAccessibility")
    static void pressable(View view) {
        view.setOnTouchListener((v, event) -> {
            if (!Ui.animationsEnabled()) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (v.isEnabled()) {
                        v.animate().scaleX(0.96f).scaleY(0.96f).setStartDelay(0).setDuration(70)
                            .setInterpolator(EASE).start();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(160)
                        .setInterpolator(SETTLE).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }
}
