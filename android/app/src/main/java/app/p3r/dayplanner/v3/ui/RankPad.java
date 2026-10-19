package app.p3r.dayplanner.v3.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * RANK keypad for the 社群 grid: 未解锁 1–9 MAX ?. Picking a value records it and moves straight
 * to the next link in game order — the key flashes and the next arcana slides in — so the whole
 * Social Link list can be copied from the game's screen with one tap per link.
 */
public final class RankPad {
    /** Callback for each pick; {@code rank} is 0..10 or {@link StateDraft#UNKNOWN}. */
    public interface Listener {
        void onPicked(int link, int rank);

        /** Current value to highlight for {@code link}. */
        int currentRank(int link);
    }

    private static final String[][] KEYS = {
        {"未解锁", "1", "2"}, {"3", "4", "5"}, {"6", "7", "8"}, {"9", "MAX", "?"},
    };
    /** What the 「?」 key means, under it and to TalkBack. */
    static final String UNKNOWN_LABEL = "不记得";

    private final Rules rules;
    private final List<Integer> order;
    private final Listener listener;
    private final Dialog dialog;
    private final LinearLayout header;
    private final TextView numeral;
    private final TextView name;
    private final TextView person;
    private final TextView progress;
    private final Progress bar;
    private final List<TextView> keys = new ArrayList<>();
    private final TextView previous;
    private int position;
    /** A pick's flash and slide are still running; the header shows the link before. */
    private boolean advancing;

    private RankPad(Context context, Rules rules, List<Integer> order, int start, Listener listener) {
        this.rules = rules;
        this.order = order;
        this.listener = listener;
        this.position = Math.max(0, order.indexOf(start));

        LinearLayout content = Ui.column(context);
        Ui.pad(content, 20, 20, 20, 16);
        LinearLayout top = Ui.row(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        header = Ui.row(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        numeral = Ui.numeral(context, "", 40, Ui.col(context, R.color.p3r_cyan_400));
        numeral.setMinWidth(Ui.dp(context, 48));
        numeral.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(numeral, Ui.wrap());
        LinearLayout names = Ui.column(context);
        name = Ui.text(context, "", 22, Ui.col(context, R.color.p3r_white), true);
        name.setSingleLine(true);
        person = Ui.text(context, "", 14, Ui.col(context, R.color.p3r_ink_muted), false);
        person.setSingleLine(true);
        person.setEllipsize(android.text.TextUtils.TruncateAt.END);
        names.addView(name);
        names.addView(person);
        LinearLayout.LayoutParams namesParams = Ui.weight(1);
        namesParams.leftMargin = Ui.dp(context, 10);
        header.addView(names, namesParams);
        top.addView(header, Ui.weight(1));
        progress = Ui.numeral(context, "", 18, Ui.col(context, R.color.p3r_ink_muted));
        top.addView(progress, Ui.wrap());
        content.addView(top);

        bar = new Progress(context, order.size());
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            Ui.dp(context, 6));
        barParams.topMargin = Ui.dp(context, 12);
        barParams.bottomMargin = Ui.dp(context, 4);
        content.addView(bar, barParams);

        for (String[] row : KEYS) {
            TextView[] rowKeys = new TextView[row.length];
            for (int i = 0; i < row.length; i++) {
                String label = row[i];
                TextView key = Pads.key(context, label, false);
                key.setTextSize(label.length() > 2 ? 17 : 22);
                if (label.equals("?")) {
                    key.setText(unknownKey(context));
                }
                key.setContentDescription(label.equals("?") ? UNKNOWN_LABEL : label);
                final int value = valueOf(label);
                key.setOnClickListener(v -> pick(value));
                key.setTag(value);
                rowKeys[i] = key;
                keys.add(key);
            }
            content.addView(Pads.keyRow(context, rowKeys));
        }

        LinearLayout actions = Ui.row(context);
        previous = Ui.secondary(context, "‹ 上一个");
        previous.setContentDescription("上一个");
        previous.setOnClickListener(v -> move(-1));
        actions.addView(previous, Ui.weight(1));
        TextView done = Ui.primary(context, "好了");
        done.setOnClickListener(v -> dialog().dismiss());
        LinearLayout.LayoutParams doneParams = Ui.weight(1);
        doneParams.leftMargin = Ui.dp(context, 12);
        actions.addView(done, doneParams);
        LinearLayout.LayoutParams actionsParams = Ui.matchWrap();
        actionsParams.topMargin = Ui.dp(context, 16);
        content.addView(actions, actionsParams);

        dialog = Pads.bottomDialog(context, content);
        render();
    }

    /** 「?」 with a small 「不记得」 under it. */
    private static CharSequence unknownKey(Context context) {
        SpannableStringBuilder sb = new SpannableStringBuilder("?\n");
        int start = sb.length();
        sb.append(UNKNOWN_LABEL);
        sb.setSpan(new RelativeSizeSpan(0.56f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /** Opens the pad on {@code startLink}; {@code order} is the sequence auto-advance follows. */
    public static RankPad show(Context context, Rules rules, List<Integer> order, int startLink, Listener listener) {
        RankPad pad = new RankPad(context, rules, order, startLink, listener);
        pad.dialog.show();
        return pad;
    }

    static int valueOf(String label) {
        switch (label) {
            case "未解锁":
                return 0;
            case "MAX":
                return 10;
            case "?":
                return StateDraft.UNKNOWN;
            default:
                return Integer.parseInt(label);
        }
    }

    /** Records a value for the current link and advances (closing after the last link). */
    void pick(int value) {
        settle();
        int link = order.get(position);
        listener.onPicked(link, value);
        if (position + 1 >= order.size()) {
            dialog.dismiss();
            return;
        }
        position++;
        if (!Ui.animationsEnabled()) {
            render();
            return;
        }
        // The picked key lights up, then the next arcana slides in from the right.
        advancing = true;
        styleKeys(value);
        header.postDelayed(slideOut, 120);
    }

    private final Runnable slideOut = this::slideOut;

    private void slideOut() {
        float dx = Ui.dp(header.getContext(), 40);
        header.animate().translationX(-dx).alpha(0f).setDuration(90).setInterpolator(Motion.EASE)
            .withEndAction(this::slideIn).start();
    }

    private void slideIn() {
        float dx = Ui.dp(header.getContext(), 40);
        render();
        header.setTranslationX(dx);
        header.animate().translationX(0f).alpha(1f).setDuration(90).setInterpolator(Motion.SETTLE)
            .withEndAction(() -> advancing = false).start();
    }

    /** Ends a running advance at once (a quick second tap, 上一个). */
    private void settle() {
        if (!advancing) {
            return;
        }
        advancing = false;
        header.removeCallbacks(slideOut);
        header.animate().cancel();
        header.setTranslationX(0f);
        header.setAlpha(1f);
        render();
    }

    private void move(int delta) {
        settle();
        position = Math.max(0, Math.min(order.size() - 1, position + delta));
        render();
    }

    private void render() {
        int link = order.get(position);
        Rules.Link l = rules.links[link];
        numeral.setText(LinkStatus.numeral(l));
        name.setText(l.arcanaZh);
        person.setText(l.personZh);
        progress.setText((position + 1) + "/" + order.size());
        progress.setContentDescription("第 " + (position + 1) + " 个，共 " + order.size() + " 个");
        bar.setPosition(position);
        styleKeys(listener.currentRank(link));
        previous.setEnabled(position > 0);
        previous.setAlpha(position > 0 ? 1f : 0.4f);
    }

    /** The key of {@code current} filled (gold for 「?」, cyan otherwise), the rest dark. */
    private void styleKeys(int current) {
        Context context = header.getContext();
        for (TextView key : keys) {
            int value = (Integer) key.getTag();
            boolean on = value == current;
            SlantedPanelDrawable bg = new SlantedPanelDrawable().skew(Ui.dp(context, 8));
            if (on) {
                int fill = value == StateDraft.UNKNOWN ? Ui.col(context, R.color.ui_gold) : Ui.col(context, R.color.p3r_cyan_400);
                bg.fill(fill).pressedFill(Ui.col(context, R.color.p3r_cyan_200))
                    .slab(Ui.alpha(Ui.col(context, R.color.p3r_white), 200), Ui.dp(context, 2), Ui.dp(context, 2));
                key.setTextColor(Ui.col(context, R.color.p3r_navy_950));
            } else {
                bg.fill(Ui.alpha(Ui.col(context, R.color.p3r_navy_950), 150))
                    .pressedFill(Ui.col(context, R.color.p3r_navy_700))
                    .stroke(Ui.col(context, R.color.p3r_line_soft), Ui.dp(context, 1));
                key.setTextColor(value == StateDraft.UNKNOWN ? Ui.col(context, R.color.ui_gold) : Ui.col(context, R.color.p3r_white));
            }
            key.setBackground(bg);
            key.setGravity(Gravity.CENTER);
        }
    }

    Dialog dialog() {
        return dialog;
    }

    /** One slanted segment per link: white for those before, cyan for the current one. */
    private static final class Progress extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int count;
        private int position;

        Progress(Context context, int count) {
            super(context);
            this.count = Math.max(1, count);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setPosition(int value) {
            position = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Context c = getContext();
            float h = getHeight();
            float gap = Ui.dp(c, 2);
            float skew = h * 0.7f;
            float w = (getWidth() - skew - gap * (count - 1)) / count;
            for (int i = 0; i < count; i++) {
                float left = i * (w + gap);
                path.reset();
                path.moveTo(left + skew, 0);
                path.lineTo(left + skew + w, 0);
                path.lineTo(left + w, h);
                path.lineTo(left, h);
                path.close();
                paint.setStyle(Paint.Style.FILL);
                if (i < position) {
                    paint.setColor(Ui.col(c, R.color.p3r_white));
                } else if (i == position) {
                    paint.setColor(Ui.col(c, R.color.p3r_cyan_400));
                } else {
                    paint.setColor(Ui.alpha(Ui.col(c, R.color.p3r_line), 140));
                }
                canvas.drawPath(path, paint);
            }
        }
    }
}
