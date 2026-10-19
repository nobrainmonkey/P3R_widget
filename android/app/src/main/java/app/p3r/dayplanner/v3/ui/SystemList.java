package app.p3r.dayplanner.v3.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import app.p3r.dayplanner.R;

/**
 * The 我的 / 规划设置 menus after the game's SYSTEM screen: flat rows with big labels, a fading cyan
 * underline instead of a box, and a slanted cyan cursor that sweeps in from the left while a row is
 * pressed (the label turns navy on it). Config rows carry their value on the right in cyan.
 *
 * <p>Also the live refresh of the pages pushed from 我的 ({@link #follow}): pushed pages are built
 * once, so a page that shows settings follows the data the 我的 tab is bound with and puts its
 * controls back to what is saved (after a rejected save, or a change made elsewhere).
 */
final class SystemList {
    private SystemList() {}

    // ------------------------------------------------------------------ rows

    /** A menu or config row: icon (optional), label, optional cyan note and value, chevron. */
    static final class Row extends LinearLayout {
        final TextView label;
        final TextView value;
        final TextView note;
        final BusyStripe busy;
        private final CharSequence title;

        Row(Context c, int icon, CharSequence title, boolean chevron) {
            super(c);
            this.title = title;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(Ui.dp(c, 56));
            Ui.pad(this, 4, 8, 4, 9);
            setBackground(new Cursor(c, true));
            ColorStateList ink = pressedInk(c, R.color.p3r_white);
            if (icon != 0) {
                ImageView glyph = new ImageView(c);
                Drawable d = c.getDrawable(icon).mutate();
                d.setTintList(pressedInk(c, R.color.p3r_cyan_400));
                glyph.setImageDrawable(d);
                glyph.setDuplicateParentStateEnabled(true);
                glyph.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                LayoutParams gp = new LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22));
                gp.leftMargin = Ui.dp(c, 8);
                gp.rightMargin = Ui.dp(c, 14);
                addView(glyph, gp);
            }
            LinearLayout texts = Ui.column(c);
            texts.setDuplicateParentStateEnabled(true);
            label = Ui.mixed(c, title, 18, Ui.col(c, R.color.p3r_white), true);
            label.setTextColor(ink);
            label.setDuplicateParentStateEnabled(true);
            texts.addView(label, Ui.wrap());
            note = Ui.mixed(c, "", 13, Ui.col(c, R.color.p3r_cyan_300), false);
            note.setTextColor(pressedInk(c, R.color.p3r_cyan_300));
            note.setDuplicateParentStateEnabled(true);
            note.setVisibility(GONE);
            texts.addView(note, Ui.wrap());
            busy = new BusyStripe(c);
            busy.setVisibility(GONE);
            LayoutParams bp = Ui.matchWrap();
            bp.topMargin = Ui.dp(c, 6);
            bp.rightMargin = Ui.dp(c, 24);
            texts.addView(busy, bp);
            LayoutParams tp = Ui.weight(1);
            if (icon == 0) {
                tp.leftMargin = Ui.dp(c, 8);
            }
            addView(texts, tp);
            value = Ui.mixed(c, "", 16, Ui.col(c, R.color.p3r_cyan_300), true);
            value.setTextColor(pressedInk(c, R.color.p3r_cyan_300));
            value.setDuplicateParentStateEnabled(true);
            value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            value.setMaxLines(1);
            value.setEllipsize(TextUtils.TruncateAt.END);
            value.setVisibility(GONE);
            LayoutParams vp = Ui.wrap();
            vp.leftMargin = Ui.dp(c, 12);
            addView(value, vp);
            if (chevron) {
                ImageView arrow = new ImageView(c);
                Drawable d = c.getDrawable(R.drawable.ui_ic_chevron).mutate();
                d.setTintList(pressedInk(c, R.color.p3r_ink_faint));
                arrow.setImageDrawable(d);
                arrow.setDuplicateParentStateEnabled(true);
                arrow.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                LayoutParams ap = new LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18));
                ap.leftMargin = Ui.dp(c, 4);
                ap.rightMargin = Ui.dp(c, 4);
                addView(arrow, ap);
            }
            describe();
        }

        /** The value on the right ("每个区块 2 次", "都婉拒了"); null hides it. */
        Row setValue(CharSequence text) {
            value.setText(text == null ? "" : Ui.latin(getContext(), text));
            value.setVisibility(text == null ? GONE : VISIBLE);
            describe();
            return this;
        }

        /** A short cyan line under the label; null hides it. */
        Row setNote(CharSequence text) {
            note.setText(text == null ? "" : Ui.latin(getContext(), text));
            note.setVisibility(text == null ? GONE : VISIBLE);
            describe();
            return this;
        }

        /** The busy stripe under the label (a 从头重新安排 in progress). */
        Row setBusy(boolean on) {
            busy.setVisibility(on ? VISIBLE : GONE);
            return this;
        }

        private void describe() {
            StringBuilder sb = new StringBuilder(Zh.plain(title));
            if (value.getVisibility() == VISIBLE && value.getText().length() > 0) {
                sb.append('，').append(Zh.plain(value.getText()));
            }
            if (note.getVisibility() == VISIBLE && note.getText().length() > 0) {
                sb.append('，').append(Zh.plain(note.getText()));
            }
            setContentDescription(sb.toString());
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Button");
        }
    }

    /** White (or {@code normal}) ink that turns navy on the pressed cyan cursor. */
    static ColorStateList pressedInk(Context c, int normal) {
        return new ColorStateList(
            new int[][]{new int[]{android.R.attr.state_pressed}, new int[]{}},
            new int[]{Ui.col(c, R.color.p3r_navy_950), Ui.col(c, normal)});
    }

    /** A tappable menu entry (我的). */
    static Row menuRow(Context c, int icon, CharSequence title, Runnable action) {
        Row row = new Row(c, icon, title, true);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> action.run());
        return row;
    }

    /** A config row: label on the left, value on the right, opens a picker or a page. */
    static Row configRow(Context c, CharSequence title, CharSequence value, Runnable action) {
        Row row = menuRow(c, 0, title, action);
        row.setValue(value);
        return row;
    }

    // ------------------------------------------------------------------ headers

    /**
     * Group header of a settings list: a small cyan Latin kicker (decoration, hidden from TalkBack)
     * over the bold CJK title and a hairline fading to the right.
     */
    static LinearLayout groupHeader(Context c, String kicker, CharSequence title) {
        LinearLayout box = Ui.column(c);
        if (kicker != null) {
            TextView en = Ui.numeral(c, kicker, 13, Ui.col(c, R.color.p3r_cyan_400));
            en.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
            en.setLetterSpacing(0.16f);
            en.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            box.addView(en, Ui.wrap());
        }
        LinearLayout line = Ui.row(c);
        TextView zh = Ui.headline(c, title);
        if (Build.VERSION.SDK_INT >= 28) {
            zh.setAccessibilityHeading(true);
        }
        line.addView(zh, Ui.wrap());
        View rule = Ui.fadingRule(c);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, Math.max(1, Ui.dp(c, 1)), 1);
        rp.leftMargin = Ui.dp(c, 12);
        line.addView(rule, rp);
        box.addView(line, Ui.matchWrap());
        return box;
    }

    // ------------------------------------------------------------------ the cursor

    /**
     * Row background: a fading cyan underline and, while pressed, a slanted cyan bar that sweeps in
     * from the left (120 ms; instant without animations).
     */
    static final class Cursor extends Drawable {
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rule = new Paint();
        private final Path path = new Path();
        private final int skew;
        private final int ruleHeight;
        private final int cyan;
        private final boolean underline;
        private float progress;
        private boolean pressed;
        private ValueAnimator animator;
        private int ruleWidth = -1;
        private boolean sweep = true;

        Cursor(Context c, boolean underline) {
            this.underline = underline;
            skew = Ui.dp(c, 12);
            ruleHeight = Math.max(1, Ui.dp(c, 1));
            cyan = Ui.col(c, R.color.p3r_cyan_400);
            bar.setColor(cyan);
        }

        /** Only the underline: the row answers presses some other way (a switch). */
        Cursor noSweep() {
            sweep = false;
            return this;
        }

        @Override
        public boolean isStateful() {
            return sweep;
        }

        @Override
        protected boolean onStateChange(int[] state) {
            if (!sweep) {
                return false;
            }
            boolean now = false;
            for (int s : state) {
                if (s == android.R.attr.state_pressed) {
                    now = true;
                }
            }
            if (now == pressed) {
                return false;
            }
            pressed = now;
            animateTo(now ? 1f : 0f);
            return true;
        }

        private void animateTo(float target) {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            if (!Ui.animationsEnabled()) {
                progress = target;
                invalidateSelf();
                return;
            }
            animator = ValueAnimator.ofFloat(progress, target);
            animator.setDuration(target > progress ? 120 : 90);
            animator.setInterpolator(target > progress ? Motion.SETTLE : Motion.EASE);
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidateSelf();
            });
            animator.start();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) {
                return;
            }
            if (underline) {
                if (ruleWidth != b.width()) {
                    ruleWidth = b.width();
                    rule.setShader(new LinearGradient(b.left, 0, b.left + b.width() * 0.9f, 0,
                        Ui.alpha(cyan, 96), Ui.alpha(cyan, 0), Shader.TileMode.CLAMP));
                }
                canvas.drawRect(b.left, b.bottom - ruleHeight, b.right, b.bottom, rule);
            }
            if (progress <= 0f) {
                return;
            }
            float top = b.top + ruleHeight;
            float bottom = b.bottom - ruleHeight * 2;
            float right = b.left + (b.width() + skew) * progress;
            path.reset();
            path.moveTo(b.left + skew, top);
            path.lineTo(right, top);
            path.lineTo(right - skew, bottom);
            path.lineTo(b.left, bottom);
            path.close();
            int save = canvas.save();
            canvas.clipRect(b);
            canvas.drawPath(path, bar);
            canvas.restoreToCount(save);
        }

        @Override
        public void setAlpha(int alpha) {
            bar.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            bar.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ------------------------------------------------------------------ sliding selection

    /**
     * A selection face drawn behind the children of a row ({@link SegmentedControl}, the 安排风格
     * tiles) that slides to the newly chosen child in 150 ms.
     */
    static final class Slide {
        private final ViewGroup host;
        private final Drawable face;
        private int index = -1;
        private float fromLeft;
        private float fromRight;
        private float progress = 1f;
        private ValueAnimator animator;

        Slide(ViewGroup host, Drawable face) {
            this.host = host;
            this.face = face;
            host.setWillNotDraw(false);
        }

        int index() {
            return index;
        }

        /** Moves the face to child {@code next} (-1 hides it); slides when shown and allowed. */
        void select(int next) {
            if (next == index) {
                return;
            }
            View from = index >= 0 && index < host.getChildCount() ? host.getChildAt(index) : null;
            boolean slide = Ui.animationsEnabled() && from != null && next >= 0 && host.isLaidOut()
                && host.isAttachedToWindow();
            if (slide) {
                float[] now = current();
                fromLeft = now[0];
                fromRight = now[1];
            }
            index = next;
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            if (!slide) {
                progress = 1f;
                host.invalidate();
                return;
            }
            progress = 0f;
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(150);
            animator.setInterpolator(Motion.SETTLE);
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                host.invalidate();
            });
            animator.start();
        }

        /** Left and right of the face now (px, in the host). */
        private float[] current() {
            View target = host.getChildAt(index);
            float l = target.getLeft();
            float r = target.getRight();
            if (progress < 1f) {
                l = fromLeft + (l - fromLeft) * progress;
                r = fromRight + (r - fromRight) * progress;
            }
            return new float[]{l, r};
        }

        /** Draws the face; call before the children are drawn. */
        void draw(Canvas canvas) {
            if (index < 0 || index >= host.getChildCount()) {
                return;
            }
            View target = host.getChildAt(index);
            if (target.getWidth() == 0) {
                return;
            }
            float[] lr = current();
            face.setBounds(Math.round(lr[0]), target.getTop(), Math.round(lr[1]), target.getBottom());
            face.draw(canvas);
        }
    }

    // ------------------------------------------------------------------ live sub-pages

    private static final List<Follower> LIVE = new ArrayList<>();

    private static final class Follower {
        final View page;
        final Consumer<UiData> onData;

        Follower(View page, Consumer<UiData> onData) {
            this.page = page;
            this.onData = onData;
        }
    }

    /**
     * {@code onData} receives every new {@link UiData} the 我的 tab is bound with while {@code page}
     * is on screen (attached), so a pushed page keeps showing what is saved.
     */
    static void follow(View page, Consumer<UiData> onData) {
        Follower follower = new Follower(page, onData);
        page.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                synchronized (LIVE) {
                    if (!LIVE.contains(follower)) {
                        LIVE.add(follower);
                    }
                }
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                synchronized (LIVE) {
                    LIVE.remove(follower);
                }
            }
        });
    }

    /** Hands {@code d} to the followed pages of {@code context}'s screen. */
    static void dispatch(Context context, UiData d) {
        List<Follower> now;
        synchronized (LIVE) {
            now = new ArrayList<>(LIVE);
        }
        for (Follower f : now) {
            if (f.page.getContext() == context && f.page.isAttachedToWindow()) {
                f.onData.accept(d);
            }
        }
    }
}
