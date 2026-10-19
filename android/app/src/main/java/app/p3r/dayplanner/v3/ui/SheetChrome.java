package app.p3r.dayplanner.v3.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.p3r.dayplanner.R;

/**
 * One sheet language for every bottom sheet (in-app sheets, the quick result sheet, keypads):
 * deep navy glass with the 28 dp top-left cut and a bright cyan edge along that cut and the top,
 * no outline around the rest; a grip strip at the top that drags the sheet down to close it.
 */
final class SheetChrome {
    /** Dragged further than this (dp), the sheet closes on release. */
    static final int DISMISS_DRAG = 96;
    /** Height of the grip strip at the top of a sheet (dp). */
    static final int GRIP = 24;

    private SheetChrome() {}

    /** The sheet surface: the glass gradient plus the bright top edge. */
    static Drawable surface(Context c) {
        int cut = Ui.dp(c, 28);
        SlantedPanelDrawable glass = new SlantedPanelDrawable()
            .gradient(Ui.col(c, R.color.p3r_navy_700), Ui.col(c, R.color.ui_sheet))
            .cuts(cut, 0, 0, 0);
        return new LayerDrawable(new Drawable[]{glass, new TopEdge(c, cut)});
    }

    static View handle(Context c) {
        View handle = new View(c);
        handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        handle.setBackground(new SlantedPanelDrawable()
            .gradient(Ui.col(c, R.color.p3r_cyan_300), Ui.alpha(Ui.col(c, R.color.p3r_cyan_400), 80))
            .skew(Ui.dp(c, 3)));
        return handle;
    }

    /** The grip strip: a {@value #GRIP} dp touch area with the {@link #handle} centred in it. */
    static FrameLayout grip(Context c) {
        FrameLayout strip = new FrameLayout(c);
        strip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 4), Gravity.CENTER);
        strip.addView(handle(c), hp);
        strip.setMinimumHeight(Ui.dp(c, GRIP));
        return strip;
    }

    static TextView title(Context c, CharSequence label) {
        TextView title = Ui.title(c, label);
        Drawable slash = new SlantedPanelDrawable().fill(Ui.col(c, R.color.p3r_cyan_400))
            .skew(Ui.dp(c, 3));
        slash.setBounds(0, 0, Ui.dp(c, 6), Ui.dp(c, 24));
        title.setCompoundDrawables(slash, null, null, null);
        title.setCompoundDrawablePadding(Ui.dp(c, 10));
        Ui.pad(title, 0, 2, 0, 6);
        return title;
    }

    /** A small check is separate from the unchanged, searchable category label. */
    static void selectChoice(TextView choice, boolean selected) {
        Ui.setChipSelected(choice, selected);
        Context c = choice.getContext();
        Drawable check = null;
        if (selected) {
            choice.setBackground(new SlantedPanelDrawable()
                .gradient(Ui.col(c, R.color.p3r_cyan_200), Ui.col(c, R.color.p3r_cyan_400))
                .pressedFill(Ui.col(c, R.color.p3r_cyan_200))
                .stroke(Ui.col(c, R.color.p3r_white), Ui.dp(c, 1))
                .skew(Ui.dp(c, 6)));
            check = Ui.tinted(c, R.drawable.ui_ic_check, Ui.col(c, R.color.p3r_navy_950));
            check.setBounds(0, 0, Ui.dp(c, 12), Ui.dp(c, 12));
        }
        choice.setCompoundDrawables(null, null, check, null);
        choice.setCompoundDrawablePadding(Ui.dp(c, 3));
    }

    /** Whether a drag may start (e.g. not while saving). */
    interface DragGate {
        boolean canDrag();
    }

    /**
     * Drag-down to dismiss: dragging {@code grip} moves {@code panel} with the finger; released past
     * {@value #DISMISS_DRAG} dp or flung down, {@code dismiss} runs (it animates the exit from where
     * the panel is), otherwise the panel springs back.
     */
    @SuppressLint("ClickableViewAccessibility")
    static void dragToDismiss(View grip, View panel, DragGate gate, Runnable dismiss) {
        grip.setOnTouchListener(new View.OnTouchListener() {
            private float startY;
            private VelocityTracker tracker;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dragging = gate == null || gate.canDrag();
                        if (!dragging) {
                            return false;
                        }
                        startY = event.getRawY();
                        tracker = VelocityTracker.obtain();
                        track(event);
                        panel.animate().cancel();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!dragging) {
                            return false;
                        }
                        track(event);
                        panel.setTranslationY(Math.max(0f, event.getRawY() - startY));
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!dragging) {
                            return false;
                        }
                        dragging = false;
                        float velocity = 0f;
                        if (tracker != null) {
                            track(event);
                            tracker.computeCurrentVelocity(1000);
                            velocity = tracker.getYVelocity();
                            tracker.recycle();
                            tracker = null;
                        }
                        float dy = panel.getTranslationY();
                        boolean fling = velocity > Ui.dp(panel.getContext(), 900) && dy > Ui.dp(panel.getContext(), 12);
                        if (event.getActionMasked() == MotionEvent.ACTION_UP
                            && (dy > Ui.dp(panel.getContext(), DISMISS_DRAG) || fling)) {
                            dismiss.run();
                        } else if (Ui.animationsEnabled()) {
                            panel.animate().translationY(0f).setDuration(200).setInterpolator(Motion.SETTLE).start();
                        } else {
                            panel.setTranslationY(0f);
                        }
                        return true;
                    default:
                        return false;
                }
            }

            private void track(MotionEvent event) {
                if (tracker == null) {
                    return;
                }
                // Raw coordinates: the grip moves with the panel.
                MotionEvent copy = MotionEvent.obtain(event);
                copy.setLocation(event.getRawX(), event.getRawY());
                tracker.addMovement(copy);
                copy.recycle();
            }
        });
    }

    /** Where an exit slide ends: off the bottom after a drag, else a short drop. */
    static float exitTranslation(View panel) {
        float dy = panel.getTranslationY();
        return dy > 0 ? Math.max(dy, panel.getHeight()) : Ui.dp(panel.getContext(), 40);
    }

    /**
     * The bright edge of a sheet: along the top-left cut and the top, cyan fading to the right,
     * like the lit rim of the game's menu panels. No line on the sides or the bottom.
     */
    private static final class TopEdge extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int cut;
        private final int bright;
        private final int faint;
        private Shader shader;
        private int shaderWidth = -1;

        TopEdge(Context c, int cut) {
            this.cut = cut;
            bright = Ui.col(c, R.color.p3r_cyan_400);
            faint = Ui.alpha(Ui.col(c, R.color.p3r_cyan_400), 70);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(c, 2));
            paint.setStrokeJoin(Paint.Join.MITER);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) {
                return;
            }
            if (shader == null || shaderWidth != b.width()) {
                shaderWidth = b.width();
                shader = new LinearGradient(b.left, 0, b.right, 0, new int[]{bright, bright, faint},
                    new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP);
            }
            float half = paint.getStrokeWidth() / 2f;
            float c = Math.min(cut, b.height() / 2f);
            path.reset();
            path.moveTo(b.left + half, b.top + c + paint.getStrokeWidth() * 6);
            path.lineTo(b.left + half, b.top + c);
            path.lineTo(b.left + c, b.top + half);
            path.lineTo(b.right, b.top + half);
            paint.setShader(shader);
            canvas.drawPath(path, paint);
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
}
