package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * 规划设置 → 塔尔塔罗斯: the default 想去几次 / 最多几次 for every block, then each block of the rule
 * pack (name, floors, dates) with its own two steppers, starting at the defaults
 * (Profile.visitsFor / capFor). A block's own values are stored in Profile.towerVisitsByBlock /
 * towerCapByBlock; 「恢复默认」 removes them. 想去 never exceeds 最多: raising one or lowering the
 * other moves both. Every change is saved at once (saveProfile) and triggers a replan.
 */
final class TowerSettingsPage {
    static final int MAX_VISITS = 8;
    static final int MAX_CAP = 12;

    private TowerSettingsPage() {}

    static View build(HomeHost host) {
        Context c = host.activity();
        UiData d = host.data();
        Rules rules = d.rules;
        Profile p = d.profile();
        LinearLayout column = Ui.column(c);
        column.addView(Ui.muted(c, "每个区块按「想去几次」安排探索；「最多几次」包括救失踪者在内。"
            + "设成 0 就不安排这个区块，也不会去救那里的失踪者。"));
        List<BlockRows> blocks = new ArrayList<>();

        LinearLayout defaults = card(c);
        defaults.addView(Ui.text(c, "所有区块的默认", 16, Ui.col(c, R.color.p3r_white), true));
        defaults.addView(Ui.caption(c, "下面没有单独设置的区块都按这个安排。"));
        Stepper dv = stepper(c, MAX_VISITS, p.towerVisitsPerBlock);
        Stepper dc = stepper(c, MAX_CAP, p.towerCapPerBlock);
        dv.setContentDescription("所有区块默认想去几次");
        dc.setContentDescription("所有区块默认最多几次");
        Runnable refresh = () -> {
            Profile q = host.data().profile();
            dv.setValue(q.towerVisitsPerBlock);
            dc.setValue(q.towerCapPerBlock);
            for (BlockRows b : blocks) {
                b.refresh(q);
            }
        };
        dv.setListener(v -> {
            SettingsPage.edit(host, q -> {
                q.towerVisitsPerBlock = v;
                q.towerCapPerBlock = Math.max(q.towerCapPerBlock, v);
                keepVisitsWithinCaps(rules, q);
            });
            refresh.run();
        });
        dc.setListener(v -> {
            SettingsPage.edit(host, q -> {
                q.towerCapPerBlock = v;
                q.towerVisitsPerBlock = Math.min(q.towerVisitsPerBlock, v);
                keepVisitsWithinCaps(rules, q);
            });
            refresh.run();
        });
        defaults.addView(labelled(c, "想去几次", dv), top(c, 8));
        defaults.addView(labelled(c, "最多几次", dc), top(c, 4));
        column.addView(defaults, top(c, 12));

        if (rules == null || rules.blocks.length == 0) {
            return column;
        }
        column.addView(Ui.sectionHeader(c, "各个区块", rules.blocks.length + " 个"), top(c, 20));
        SimState obs = d.observed();
        int today = obs == null ? -1 : Rules.dayOf(obs.slot);
        for (Rules.Block block : rules.blocks) {
            BlockRows rows = new BlockRows(host, rules, block, today, refresh);
            blocks.add(rows);
            column.addView(rows.card, top(c, 8));
        }
        refresh.run();
        return column;
    }

    /** A block whose 想去 (own or default) is above its own 最多 gets 想去 = 最多. */
    static void keepVisitsWithinCaps(Rules rules, Profile q) {
        if (rules == null) {
            return;
        }
        for (Rules.Block b : rules.blocks) {
            if (q.visitsFor(b.id) > q.capFor(b.id)) {
                q.towerVisitsByBlock.put(b.id, q.capFor(b.id));
            }
        }
    }

    /** True when the block has a value of its own. */
    static boolean ownValues(Profile q, String blockId) {
        return q.towerVisitsByBlock.containsKey(blockId) || q.towerCapByBlock.containsKey(blockId);
    }

    /** "2–22F · 4月21日–5月9日". */
    static String blockLine(Rules.Block b) {
        String floors = b.floorsZh == null || b.floorsZh.trim().isEmpty() ? "" : b.floorsZh.trim() + " · ";
        return floors + Zh.dateRange(b.fromDay, b.toDay);
    }

    /** One block's card. */
    private static final class BlockRows {
        final LinearLayout card;
        final Stepper visits;
        final Stepper cap;
        final TextView own;
        final TextView reset;
        final String id;

        BlockRows(HomeHost host, Rules rules, Rules.Block block, int today, Runnable refresh) {
            Context c = host.activity();
            id = block.id;
            card = card(c);
            boolean past = today >= 0 && block.toDay < today;
            boolean now = today >= block.fromDay && today <= block.toDay;
            LinearLayout head = Ui.row(c);
            head.addView(Ui.text(c, block.zh, 16, Ui.col(c, R.color.p3r_white), true), Ui.weight(1));
            if (now || past) {
                TextView tag = Ui.text(c, now ? "现在" : "已经过了", 11,
                    now ? Ui.col(c, R.color.p3r_navy_950) : Ui.col(c, R.color.p3r_ink_muted), true);
                SlantedPanelDrawable bg = new SlantedPanelDrawable().skew(Ui.dp(c, 4));
                if (now) {
                    bg.fill(Ui.col(c, R.color.p3r_cyan_400));
                } else {
                    bg.stroke(Ui.col(c, R.color.p3r_line), Ui.dp(c, 1));
                }
                tag.setBackground(bg);
                Ui.pad(tag, 7, 1, 7, 1);
                head.addView(tag, Ui.wrap());
            }
            card.addView(head);
            card.addView(Ui.mixed(c, blockLine(block), 13, Ui.col(c, R.color.p3r_cyan_300), false));
            for (Rules.Rescue r : rules.rescues) {
                if (r.fromDay <= block.toDay && r.toDay >= block.fromDay) {
                    card.addView(Ui.mixed(c, r.zh + "在" + Zh.dateRange(r.fromDay, r.toDay) + "失踪，救人也算一次探索。",
                        12, Ui.col(c, R.color.ui_gold), false), top(c, 2));
                }
            }
            Profile p = host.data().profile();
            visits = stepper(c, MAX_VISITS, p.visitsFor(id));
            cap = stepper(c, MAX_CAP, p.capFor(id));
            visits.setListener(v -> {
                SettingsPage.edit(host, q -> {
                    q.towerVisitsByBlock.put(id, v);
                    if (q.capFor(id) < v) {
                        q.towerCapByBlock.put(id, v);
                    }
                });
                refresh.run();
            });
            cap.setListener(v -> {
                SettingsPage.edit(host, q -> {
                    q.towerCapByBlock.put(id, v);
                    if (q.visitsFor(id) > v) {
                        q.towerVisitsByBlock.put(id, v);
                    }
                });
                refresh.run();
            });
            card.addView(labelled(c, "想去几次", visits), top(c, 8));
            card.addView(labelled(c, "最多几次", cap), top(c, 4));
            LinearLayout foot = Ui.row(c);
            own = Ui.caption(c, "单独设置，不跟默认");
            foot.addView(own, Ui.weight(1));
            reset = Ui.quiet(c, "恢复默认");
            reset.setTextSize(14);
            reset.setOnClickListener(v -> {
                SettingsPage.edit(host, q -> {
                    q.towerVisitsByBlock.remove(id);
                    q.towerCapByBlock.remove(id);
                    keepVisitsWithinCaps(rules, q);
                });
                refresh.run();
            });
            foot.addView(reset, Ui.wrap());
            card.addView(foot, top(c, 2));
            card.setAlpha(past ? 0.6f : 1f);
            visits.setContentDescription(block.zh + "（" + block.floorsZh + "）想去几次");
            cap.setContentDescription(block.zh + "（" + block.floorsZh + "）最多几次");
        }

        void refresh(Profile q) {
            visits.setValue(q.visitsFor(id));
            cap.setValue(q.capFor(id));
            boolean mine = ownValues(q, id);
            own.setVisibility(mine ? View.VISIBLE : View.GONE);
            reset.setVisibility(mine ? View.VISIBLE : View.GONE);
        }
    }

    private static Stepper stepper(Context c, int max, int value) {
        Stepper s = new Stepper(c, 0, max, value).compact();
        s.setFormatter(v -> v + " 次");
        return s;
    }

    /** "想去几次      [−] 2 次 [+]". */
    private static View labelled(Context c, String label, Stepper stepper) {
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.text(c, label, 15, Ui.col(c, R.color.p3r_ice_100), false), Ui.weight(1));
        row.addView(stepper, Ui.wrap());
        return row;
    }

    private static LinearLayout card(Context c) {
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.card(c));
        Ui.pad(card, 16, 12, 12, 10);
        return card;
    }

    private static LinearLayout.LayoutParams top(Context c, int dp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, dp);
        return p;
    }
}
