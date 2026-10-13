package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * 规划设置: the profile options (人格面具 / 魅力加成 / 目标余量 / 回答方式 / 其余社群, Tartarus visits
 * per block and cap, cash buffer) and the activities the player asked never to schedule. Every
 * change is saved at once and triggers a replan.
 */
final class SettingsPage {
    private SettingsPage() {}

    static View build(HomeHost host) {
        Context c = host.activity();
        Profile p = host.data().profile();
        LinearLayout column = Ui.column(c);

        column.addView(Ui.sectionHeader(c, "社群点数", null));
        column.addView(toggle(host, "约会时带着同阿尔卡那的人格面具",
            "带着时社群点数 ×1.51。拿不准就关掉，计划会更保守。", p.personaBonus,
            (q, on) -> q.personaBonus = on), gapTop(c, 4));
        column.addView(toggle(host, "魅力 Lv.6 后的加成",
            "魅力达到 Lv.6 后，社群点数 ×1.51（游戏数据）。", p.charmBonus,
            (q, on) -> q.charmBonus = on), gapTop(c, 8));
        column.addView(toggle(host, "目标多留一次会面",
            "目标社群每次升级多排一次会面，以防估计偏高。", p.goalBuffer,
            (q, on) -> q.goalBuffer = on), gapTop(c, 8));
        column.addView(answers(host, p), gapTop(c, 8));
        column.addView(toggle(host, "其余社群也尽量提升",
            "排完目标后，空出来的时段用来提升其他社群。", p.otherLinks,
            (q, on) -> q.otherLinks = on), gapTop(c, 8));

        column.addView(Ui.sectionHeader(c, "塔尔塔罗斯", null), gapTop(c, 20));
        Stepper cap = new Stepper(c, Math.max(1, p.towerVisitsPerBlock), 12, p.towerCapPerBlock);
        Stepper visits = new Stepper(c, 0, 8, p.towerVisitsPerBlock);
        visits.setFormatter(v -> v + " 次");
        cap.setFormatter(v -> v + " 次");
        visits.setListener(v -> {
            cap.setRange(Math.max(1, v), 12);
            edit(host, q -> {
                q.towerVisitsPerBlock = v;
                q.towerCapPerBlock = Math.max(q.towerCapPerBlock, v);
            });
        });
        cap.setListener(v -> edit(host, q -> q.towerCapPerBlock = Math.max(v, q.towerVisitsPerBlock)));
        column.addView(stepperCard(c, "每个区块计划去几次", "计划会在每个区块里安排这么多次探索。", visits), gapTop(c, 4));
        column.addView(stepperCard(c, "每个区块最多去几次", "上限，不会超过。", cap), gapTop(c, 8));

        column.addView(Ui.sectionHeader(c, "现金", null), gapTop(c, 20));
        column.addView(cashBuffer(host, p), gapTop(c, 4));

        column.addView(Ui.sectionHeader(c, "不再安排的活动", null), gapTop(c, 20));
        column.addView(excluded(host, p), gapTop(c, 4));
        return column;
    }

    private static LinearLayout.LayoutParams gapTop(Context c, int dp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, dp);
        return p;
    }

    /** Applies an edit to the latest profile and saves it. */
    private static void edit(HomeHost host, Consumer<Profile> change) {
        Profile edited = host.data().profile().copy();
        change.accept(edited);
        host.saveProfile(edited);
    }

    private interface BoolEdit {
        void apply(Profile profile, boolean on);
    }

    private static View toggle(HomeHost host, String title, String description, boolean value, BoolEdit edit) {
        ToggleRow row = new ToggleRow(host.activity(), title, description, value);
        row.setListener(on -> edit(host, q -> edit.apply(q, on)));
        return row;
    }

    private static View answers(HomeHost host, Profile p) {
        Context c = host.activity();
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.card(c));
        Ui.pad(card, 16, 12, 16, 14);
        card.addView(Ui.text(c, "回答方式", 16, Ui.col(c, R.color.p3r_white), true));
        card.addView(Ui.muted(c, "升级场景里的回答会影响下一级需要的点数。"));
        SegmentedControl seg = new SegmentedControl(c, Arrays.asList("选最好的回答", "不计回答加成"));
        seg.setSelected(p.answers == Profile.AnswerPolicy.BEST ? 0 : 1);
        seg.setListener(i -> edit(host, q -> q.answers = i == 0 ? Profile.AnswerPolicy.BEST : Profile.AnswerPolicy.NONE));
        card.addView(seg, gapTop(c, 10));
        return card;
    }

    private static View stepperCard(Context c, String title, String description, Stepper stepper) {
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.card(c));
        Ui.pad(card, 16, 12, 16, 12);
        card.addView(Ui.text(c, title, 16, Ui.col(c, R.color.p3r_white), true));
        card.addView(Ui.muted(c, description));
        card.addView(stepper, gapTop(c, 8));
        return card;
    }

    private static View cashBuffer(HomeHost host, Profile p) {
        Context c = host.activity();
        LinearLayout row = Ui.row(c);
        row.setBackground(Ui.pressableCard(c));
        row.setMinimumHeight(Ui.dp(c, 64));
        Ui.pad(row, 16, 10, 16, 10);
        LinearLayout texts = Ui.column(c);
        texts.addView(Ui.text(c, "现金至少保留", 16, Ui.col(c, R.color.p3r_white), true));
        texts.addView(Ui.muted(c, "需要花钱的安排会留出这笔钱。"));
        row.addView(texts, Ui.weight(1));
        TextView value = Ui.mixed(c, Zh.yen(p.cashBuffer), 18, Ui.col(c, R.color.p3r_cyan_400), true);
        value.setGravity(Gravity.END);
        row.addView(value, Ui.wrap());
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription("现金至少保留 " + Zh.yen(p.cashBuffer) + "，点按修改");
        row.setOnClickListener(v -> {
            CashPad.Options options = new CashPad.Options();
            options.title = "现金至少保留";
            options.initial = (long) host.data().profile().cashBuffer;
            options.allowUnknown = false;
            CashPad.show(c, options, amount -> {
                if (amount == null) {
                    return;
                }
                int buffer = (int) Math.min(Integer.MAX_VALUE, Math.max(0, amount));
                value.setText(Ui.latin(c, Zh.yen(buffer)));
                edit(host, q -> q.cashBuffer = buffer);
            });
        });
        return row;
    }

    private static View excluded(HomeHost host, Profile p) {
        Context c = host.activity();
        LinearLayout box = Ui.column(c);
        if (p.excludedActivities.length == 0) {
            TextView none = Ui.muted(c, "还没有。遇到不想做的安排时，在「做不了」里选「我不想做」，就可以让它以后不再出现。");
            box.addView(none);
            return box;
        }
        Rules rules = host.data().rules;
        for (String id : p.excludedActivities) {
            Rules.Activity activity = rules.activity(id);
            String name = activity == null ? "已不存在的活动" : activity.zh;
            LinearLayout row = Ui.row(c);
            row.setBackground(Ui.card(c));
            Ui.pad(row, 16, 4, 8, 4);
            row.addView(Ui.text(c, name, 15, Ui.col(c, R.color.p3r_white), false), Ui.weight(1));
            TextView restore = Ui.quiet(c, "恢复安排");
            restore.setOnClickListener(v -> edit(host, q -> q.excludedActivities = without(q.excludedActivities, id)));
            row.addView(restore, Ui.wrap());
            box.addView(row, gapTop(c, box.getChildCount() == 0 ? 0 : 8));
        }
        return box;
    }

    static String[] without(String[] ids, String id) {
        List<String> out = new ArrayList<>();
        for (String s : ids) {
            if (!s.equals(id)) {
                out.add(s);
            }
        }
        return out.toArray(new String[0]);
    }

    static String[] with(String[] ids, String id) {
        List<String> out = new ArrayList<>(Arrays.asList(ids));
        if (!out.contains(id)) {
            out.add(id);
        }
        return out.toArray(new String[0]);
    }
}
