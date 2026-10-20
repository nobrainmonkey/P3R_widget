package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.p3r.dayplanner.R;

/**
 * Snackbar-style confirmation shown after a record is saved in the app:
 * "已记录：皇帝 RANK 7 · 撤销". Hides itself after {@link #DURATION_MILLIS}; the message is never cut
 * silently (an ellipsis at worst), and the 「RANK UP」 badge steps aside when it would push the
 * message past two lines.
 */
public final class UndoBar extends LinearLayout {
    static final long DURATION_MILLIS = 8000;

    /** Look of a message: a saved record, a rank-up record, or something that went wrong. */
    enum Style { RECORD, RANK_UP, ERROR }

    private final TextView badge;
    private final TextView message;
    private final TextView action;
    private final SlantedPanelDrawable panel;
    private final Runnable hide = this::timedOut;
    private Runnable onAction;
    private Runnable onTimeout;

    public UndoBar(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(android.view.Gravity.CENTER_VERTICAL);
        panel = new SlantedPanelDrawable()
            .fill(Ui.col(context, R.color.p3r_ice_100))
            .skew(Ui.dp(context, 10))
            .slab(Ui.col(context, R.color.p3r_cyan_400), Ui.dp(context, 4), Ui.dp(context, 4));
        setBackground(panel);
        Ui.pad(this, 20, 4, 12, 8);
        badge = Ui.numeral(context, "", 18, Ui.col(context, R.color.p3r_white));
        badge.setBackground(new SlantedPanelDrawable().fill(Ui.col(context, R.color.p3r_royal_600)).skew(Ui.dp(context, 5)));
        Ui.pad(badge, 9, 3, 11, 3);
        badge.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        badge.setVisibility(GONE);
        LayoutParams badgeParams = Ui.wrap();
        badgeParams.rightMargin = Ui.dp(context, 10);
        addView(badge, badgeParams);
        message = Ui.mixed(context, "", 15, Ui.col(context, R.color.p3r_navy_900), true);
        message.setMaxLines(3);
        message.setEllipsize(android.text.TextUtils.TruncateAt.END);
        addView(message, Ui.weight(1));
        action = Ui.text(context, "撤销", 15, Ui.col(context, R.color.p3r_royal_600), true);
        action.setGravity(android.view.Gravity.CENTER);
        action.setMinHeight(Ui.dp(context, Ui.TOUCH));
        action.setMinWidth(Ui.dp(context, 64));
        action.setClickable(true);
        action.setFocusable(true);
        action.setOnClickListener(v -> {
            Runnable callback = onAction;
            hide();
            if (callback != null) {
                callback.run();
            }
        });
        addView(action, Ui.wrap());
        setVisibility(GONE);
    }

    /** Shows the bar; {@code actionLabel} null hides the action. */
    public void show(CharSequence text, String actionLabel, Runnable onAction) {
        show(text, actionLabel, onAction, Style.RECORD);
    }

    /** Shows the bar in {@code style}: rank-ups get a 「RANK UP」 badge, errors a red slab and 「!」. */
    public void show(CharSequence text, String actionLabel, Runnable onAction, Style style) {
        removeCallbacks(hide);
        Context context = getContext();
        panel.slab(Ui.col(context, style == Style.ERROR ? R.color.p3r_danger : R.color.p3r_cyan_400),
            Ui.dp(context, 4), Ui.dp(context, 4));
        if (style == Style.RECORD) {
            badge.setVisibility(GONE);
        } else {
            badge.setText(style == Style.ERROR ? "!" : "RANK UP");
            // Navy on the light red (as ConfirmDialog's destructive button), white on royal.
            badge.setTextColor(Ui.col(context, style == Style.ERROR ? R.color.p3r_navy_950 : R.color.p3r_white));
            ((SlantedPanelDrawable) badge.getBackground()).fill(Ui.col(context,
                style == Style.ERROR ? R.color.p3r_danger : R.color.p3r_royal_600));
            badge.setVisibility(VISIBLE);
            if (style == Style.RANK_UP && Ui.animationsEnabled()) {
                badge.setTranslationX(Ui.dp(context, 40));
                badge.setAlpha(0f);
                badge.animate().translationX(0f).alpha(1f).setDuration(360).setInterpolator(Motion.SETTLE).start();
            }
        }
        this.onAction = onAction;
        message.setText(Ui.latin(getContext(), text));
        action.setVisibility(actionLabel == null ? GONE : VISIBLE);
        action.setText(actionLabel == null ? "" : actionLabel);
        boolean appearing = getVisibility() != VISIBLE;
        setVisibility(VISIBLE);
        if (appearing && Ui.animationsEnabled()) {
            setTranslationX(-Ui.dp(context, 48));
            setAlpha(0f);
            animate().translationX(0f).alpha(1f).setDuration(260).setInterpolator(Motion.SETTLE).start();
        }
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
        announceForAccessibility(text);
        postDelayed(hide, DURATION_MILLIS);
    }

    public void hide() {
        removeCallbacks(hide);
        onAction = null;
        setVisibility(GONE);
    }

    /** Hidden after {@link #DURATION_MILLIS}: then {@link #setOnTimeout} runs. */
    private void timedOut() {
        hide();
        if (onTimeout != null) {
            onTimeout.run();
        }
    }

    /** Runs whenever a message has had its time and hides (not when it is replaced or acted on). */
    void setOnTimeout(Runnable onTimeout) {
        this.onTimeout = onTimeout;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (badge.getVisibility() == VISIBLE && message.getLineCount() > 2) {
            // Large text or a narrow phone: the decoration goes, the words stay whole.
            badge.animate().cancel();
            badge.setVisibility(GONE);
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    public boolean isShowing() {
        return getVisibility() == View.VISIBLE;
    }

    /** Showing with its action (「撤销」) still on offer. */
    public boolean hasAction() {
        return isShowing() && action.getVisibility() == View.VISIBLE;
    }

    /** Current message text (tests). */
    public CharSequence messageText() {
        return Zh.plain(message.getText());
    }

    /** The 「RANK UP」 / 「!」 badge is drawn (tests). */
    boolean showsBadge() {
        return isShowing() && badge.getVisibility() == VISIBLE;
    }

    /** The message ends in an ellipsis (tests). */
    boolean messageCut() {
        android.text.Layout layout = message.getLayout();
        return layout != null && layout.getLineCount() > 0 && layout.getEllipsisCount(layout.getLineCount() - 1) > 0;
    }

    /** Triggers the action as if tapped (tests). */
    void performAction() {
        action.performClick();
    }
}
