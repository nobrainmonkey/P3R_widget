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
 * from the bottom. One sheet at a time; {@link #dismiss()} is what the back key calls first.
 */
public final class SheetHost extends FrameLayout {
    private final View scrim;
    private final LinearLayout panel;
    private final ScrollView scroller;
    private final FrameLayout body;
    private Runnable onDismiss;

    public SheetHost(Context context) {
        super(context);
        scrim = new View(context);
        scrim.setBackgroundColor(Ui.col(context, R.color.ui_scrim));
        scrim.setContentDescription("关闭");
        scrim.setOnClickListener(v -> dismiss());
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        panel = Ui.column(context);
        int cut = Ui.dp(context, 28);
        panel.setBackground(new SlantedPanelDrawable()
            .fill(Ui.col(context, R.color.ui_sheet))
            .stroke(Ui.alpha(Ui.col(context, R.color.p3r_cyan_400), 150), Ui.dp(context, 1))
            .cuts(cut, 0, 0, 0));
        panel.setClickable(true);
        View handle = new View(context);
        handle.setBackground(new SlantedPanelDrawable().fill(Ui.col(context, R.color.p3r_ink_faint)).skew(Ui.dp(context, 3)));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(Ui.dp(context, 40), Ui.dp(context, 4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.topMargin = Ui.dp(context, 10);
        panel.addView(handle, handleParams);

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
        this.onDismiss = onDismiss;
        body.removeAllViews();
        body.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        scroller.scrollTo(0, 0);
        setVisibility(VISIBLE);
        bringToFront();
        if (Ui.animationsEnabled()) {
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(160).start();
            panel.setTranslationY(Ui.dp(getContext(), 120));
            panel.setAlpha(0f);
            panel.animate().translationY(0).alpha(1f).setDuration(200).start();
        }
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    /** Top edge of the sheet panel in this view's coordinates, or -1 before it is laid out. */
    public int panelTop() {
        return panel.getHeight() > 0 ? panel.getTop() + Math.round(panel.getTranslationY()) : -1;
    }

    /** Closes the sheet; returns false when nothing was showing. */
    public boolean dismiss() {
        if (!isShowing()) {
            return false;
        }
        setVisibility(GONE);
        body.removeAllViews();
        Runnable callback = onDismiss;
        onDismiss = null;
        if (callback != null) {
            callback.run();
        }
        return true;
    }

    /** The view currently shown (tests), or null. */
    public View content() {
        return body.getChildCount() == 0 ? null : body.getChildAt(0);
    }
}
