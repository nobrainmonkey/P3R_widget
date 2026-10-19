package app.p3r.dayplanner.v3.ui;

import android.app.Dialog;
import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * RANK keypad for the 社群 grid: 未解锁 1–9 MAX ?. Picking a value records it and moves straight
 * to the next link in game order, so the whole Social Link list can be copied from the game's
 * screen with one tap per link.
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

    private final Rules rules;
    private final List<Integer> order;
    private final Listener listener;
    private final Dialog dialog;
    private final TextView numeral;
    private final TextView name;
    private final TextView person;
    private final TextView progress;
    private final List<TextView> keys = new ArrayList<>();
    private final TextView previous;
    private int position;

    private RankPad(Context context, Rules rules, List<Integer> order, int start, Listener listener) {
        this.rules = rules;
        this.order = order;
        this.listener = listener;
        this.position = Math.max(0, order.indexOf(start));

        LinearLayout content = Ui.column(context);
        Ui.pad(content, 20, 20, 20, 16);
        LinearLayout header = Ui.row(context);
        numeral = Ui.numeral(context, "", 34, Ui.col(context, R.color.p3r_cyan_400));
        header.addView(numeral, Ui.wrap());
        LinearLayout names = Ui.column(context);
        name = Ui.title(context, "");
        person = Ui.muted(context, "");
        names.addView(name);
        names.addView(person);
        LinearLayout.LayoutParams namesParams = Ui.weight(1);
        namesParams.leftMargin = Ui.dp(context, 14);
        header.addView(names, namesParams);
        progress = Ui.numeral(context, "", 16, Ui.col(context, R.color.p3r_ink_muted));
        header.addView(progress, Ui.wrap());
        content.addView(header);

        TextView hint = Ui.muted(context, "对照游戏里的社群列表，选完会自动跳到下一个。");
        LinearLayout.LayoutParams hintParams = Ui.matchWrap();
        hintParams.topMargin = Ui.dp(context, 8);
        hintParams.bottomMargin = Ui.dp(context, 4);
        content.addView(hint, hintParams);

        for (String[] row : KEYS) {
            TextView[] rowKeys = new TextView[row.length];
            for (int i = 0; i < row.length; i++) {
                String label = row[i];
                TextView key = Pads.key(context, label, false);
                key.setTextSize(label.length() > 2 ? 17 : 22);
                key.setContentDescription(label.equals("?") ? "不确定" : label.equals("MAX") ? "RANK MAX" : label);
                final int value = valueOf(label);
                key.setOnClickListener(v -> pick(value));
                key.setTag(value);
                rowKeys[i] = key;
                keys.add(key);
            }
            content.addView(Pads.keyRow(context, rowKeys));
        }

        LinearLayout actions = Ui.row(context);
        previous = Ui.secondary(context, "上一个");
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
        int link = order.get(position);
        listener.onPicked(link, value);
        if (position + 1 < order.size()) {
            position++;
            render();
        } else {
            dialog.dismiss();
        }
    }

    private void move(int delta) {
        position = Math.max(0, Math.min(order.size() - 1, position + delta));
        render();
    }

    private void render() {
        Context context = dialog == null ? numeral.getContext() : dialog.getContext();
        int link = order.get(position);
        Rules.Link l = rules.links[link];
        numeral.setText(Zh.roman(l.arcanaNo));
        name.setText(l.arcanaZh);
        person.setText(Zh.keepTogether(l.personZh + " · " + Zh.dots(l.genderZh)));
        progress.setText((position + 1) + "/" + order.size());
        int current = listener.currentRank(link);
        for (TextView key : keys) {
            int value = (Integer) key.getTag();
            boolean on = value == current;
            SlantedPanelDrawable bg = new SlantedPanelDrawable().skew(Ui.dp(context, 8));
            if (on) {
                bg.fill(Ui.col(context, R.color.p3r_cyan_400)).pressedFill(Ui.col(context, R.color.p3r_cyan_200));
                key.setTextColor(Ui.col(context, R.color.p3r_navy_950));
            } else {
                bg.fill(Ui.alpha(Ui.col(context, R.color.p3r_navy_950), 150))
                    .pressedFill(Ui.col(context, R.color.p3r_navy_700))
                    .stroke(Ui.col(context, R.color.p3r_line_soft), Ui.dp(context, 1));
                key.setTextColor(Ui.col(context, R.color.p3r_white));
            }
            key.setBackground(bg);
            key.setGravity(Gravity.CENTER);
        }
        previous.setEnabled(position > 0);
        previous.setAlpha(position > 0 ? 1f : 0.4f);
    }

    Dialog dialog() {
        return dialog;
    }
}
