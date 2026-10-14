package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.p3r.dayplanner.R;

/**
 * Snackbar-style confirmation shown after a record is saved in the app:
 * "已记录：皇帝 RANK 7 · 撤销". Hides itself after {@link #DURATION_MILLIS}.
 */
public final class UndoBar extends LinearLayout {
    static final long DURATION_MILLIS = 8000;

    private final TextView message;
    private final TextView action;
    private final Runnable hide = this::hide;
    private Runnable onAction;

    public UndoBar(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(android.view.Gravity.CENTER_VERTICAL);
        setBackground(new SlantedPanelDrawable()
            .fill(Ui.col(context, R.color.p3r_ice_100))
            .skew(Ui.dp(context, 10))
            .slab(Ui.col(context, R.color.p3r_cyan_400), Ui.dp(context, 4), Ui.dp(context, 4)));
        Ui.pad(this, 20, 4, 12, 8);
        message = Ui.mixed(context, "", 15, Ui.col(context, R.color.p3r_navy_900), true);
        message.setMaxLines(2);
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
        removeCallbacks(hide);
        this.onAction = onAction;
        message.setText(Ui.latin(getContext(), text));
        action.setVisibility(actionLabel == null ? GONE : VISIBLE);
        action.setText(actionLabel == null ? "" : actionLabel);
        setVisibility(VISIBLE);
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
        announceForAccessibility(text);
        postDelayed(hide, DURATION_MILLIS);
    }

    public void hide() {
        removeCallbacks(hide);
        onAction = null;
        setVisibility(GONE);
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

    /** Triggers the action as if tapped (tests). */
    void performAction() {
        action.performClick();
    }
}
