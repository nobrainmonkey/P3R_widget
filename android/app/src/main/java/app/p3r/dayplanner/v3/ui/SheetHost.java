package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import app.p3r.dayplanner.R;

/**
 * In-app bottom sheet layer: a dimmed scrim plus a panel with a diagonal top edge that slides up
 * from the bottom ({@link SheetChrome}: bright top edge, grip). Dragging the grip down past
 * {@value SheetChrome#DISMISS_DRAG} dp (or flinging it) closes the sheet. One sheet at a time;
 * {@link #dismiss()} is what the back key calls first.
 */
public final class SheetHost extends FrameLayout {
    private final View scrim;
    private final LinearLayout panel;
    private final ScrollView scroller;
    private final FrameLayout body;
    private Runnable onDismiss;
    private long motion;
    private boolean closing;

    public SheetHost(Context context) {
        super(context);
        scrim = new View(context);
        scrim.setBackgroundColor(Ui.col(context, R.color.ui_scrim));
        scrim.setContentDescription("关闭");
        scrim.setOnClickListener(v -> dismiss());
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        panel = Ui.column(context);
        panel.setBackground(SheetChrome.surface(context));
        panel.setClickable(true);
        // The grip: drag the sheet down to close it.
        View grip = SheetChrome.grip(context);
        LinearLayout.LayoutParams gripParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            Ui.dp(context, SheetChrome.GRIP));
        gripParams.topMargin = Ui.dp(context, 2);
        panel.addView(grip, gripParams);
        SheetChrome.dragToDismiss(grip, panel, () -> !closing, this::dismiss);

        scroller = new ScrollView(context);
        scroller.setFillViewport(false);
        scroller.setClipToPadding(false);
        body = new FrameLayout(context);
        scroller.addView(body, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT,
            ScrollView.LayoutParams.WRAP_CONTENT));
        panel.addView(scroller, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        LayoutParams panelParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM);
        panelParams.topMargin = Ui.dp(context, 72);
        addView(panel, panelParams);
        setVisibility(GONE);
    }

    /** Bottom system-bar inset so sheet content clears the navigation bar. */
    public void setBottomInset(int px) {
        scroller.setPadding(0, 0, 0, px);
    }

    /** Shows {@code content} in the sheet (replacing any current one). */
    public void show(View content, Runnable onDismiss) {
        boolean replacing = getVisibility() == VISIBLE;
        motion++;
        closing = false;
        panel.animate().withEndAction(null).cancel();
        scrim.animate().cancel();
        body.animate().cancel();
        this.onDismiss = onDismiss;
        body.removeAllViews();
        body.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        scroller.scrollTo(0, 0);
        setVisibility(VISIBLE);
        bringToFront();
        panel.setTranslationY(0);
        panel.setAlpha(1f);
        body.setTranslationY(0);
        body.setAlpha(1f);
        if (Ui.animationsEnabled()) {
            if (!replacing) scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(180).start();
            if (replacing) {
                body.setAlpha(0.3f);
                body.setTranslationY(Ui.dp(getContext(), 8));
                body.animate().translationY(0).alpha(1f).setDuration(140).start();
            } else {
                panel.setTranslationY(Ui.dp(getContext(), 48));
                panel.setAlpha(0f);
                panel.animate().translationY(0).alpha(1f).setDuration(220)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
            }
        } else {
            scrim.setAlpha(1f);
        }
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE && !closing;
    }

    /** Top edge of the sheet panel in this view's coordinates, or -1 before it is laid out. */
    public int panelTop() {
        return panel.getHeight() > 0 ? panel.getTop() + Math.round(panel.getTranslationY()) : -1;
    }

    /** Closes the sheet; returns false when nothing was showing. */
    public boolean dismiss() {
        if (closing) return true;
        if (!isShowing()) {
            return false;
        }
        closing = true;
        long ticket = ++motion;
        Runnable callback = onDismiss;
        onDismiss = null;
        panel.animate().withEndAction(null).cancel();
        scrim.animate().cancel();
        if (Ui.animationsEnabled()) {
            scrim.animate().alpha(0f).setDuration(160).start();
            panel.animate().translationY(SheetChrome.exitTranslation(panel)).alpha(0f).setDuration(160)
                .setInterpolator(Motion.EASE).withEndAction(() -> finishDismiss(ticket)).start();
        } else {
            finishDismiss(ticket);
        }
        if (callback != null) {
            callback.run();
        }
        return true;
    }

    private void finishDismiss(long ticket) {
        if (ticket != motion || !closing) return;
        setVisibility(GONE);
        body.removeAllViews();
        closing = false;
    }

    /** The view currently shown (tests), or null. */
    public View content() {
        return body.getChildCount() == 0 ? null : body.getChildAt(0);
    }
}
