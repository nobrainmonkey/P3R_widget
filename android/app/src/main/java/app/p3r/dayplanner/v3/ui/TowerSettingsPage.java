package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * 规划设置 → 塔尔塔罗斯: the default 想去几次 / 最多几次 for every block on a hero strip, then the
 * block open now, the blocks still to come as compact rows (floors in numerals, the 庭 name, the
 * dates, an inline 想去 stepper and a 「最多 N 次」 that opens the second one), and the blocks
 * already past folded at the bottom. A block's own values are stored in
 * Profile.towerVisitsByBlock / towerCapByBlock; 「恢复默认」 removes them. 想去 never exceeds 最多:
 * raising one or lowering the other moves both. A block whose 最多 is 0 while a 失踪者 is still to
 * be saved there says so in gold. Every change is saved at once (saveProfile) and triggers a replan.
 */
final class TowerSettingsPage {
    static final int MAX_VISITS = 8;
    static final int MAX_CAP = 12;
    /** The one rule worth saying, once, on the defaults. */
    static final String RESCUE_COUNTS = "救失踪者也算一次";
    static final String RESET = "恢复默认";
    static final String OWN = "单独设置";
    static final String NOW = "现在";

    private TowerSettingsPage() {}

    static View build(HomeHost host) {
        Context c = host.activity();
        UiData d = host.data();
        Rules rules = d.rules;
        Profile p = d.profile();
        LinearLayout column = Ui.column(c);
        List<BlockRows> blocks = new ArrayList<>();

        LinearLayout defaults = Ui.column(c);
        defaults.setBackground(Ui.heroCard(c));
        Ui.pad(defaults, 22, 14, 14, 14);
        defaults.addView(Ui.text(c, "默认每个区块", 16, Ui.col(c, R.color.p3r_white), true));
        Stepper dv = new Stepper(c, 0, MAX_VISITS, p.towerVisitsPerBlock);
        dv.setFormatter(v -> v + " 次");
        Stepper dc = compactStepper(c, MAX_CAP, p.towerCapPerBlock);
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
        defaults.addView(labelled(c, "想去", 17, dv), top(c, 8));
        defaults.addView(labelled(c, "最多", 15, dc), top(c, 4));
        defaults.addView(Ui.text(c, RESCUE_COUNTS, 13, Ui.col(c, R.color.p3r_cyan_200), false), top(c, 6));
        column.addView(defaults);

        if (rules == null || rules.blocks.length == 0) {
            SystemList.follow(column, x -> refresh.run());
            return column;
        }
        SimState obs = d.observed();
        int today = obs == null ? -1 : Rules.dayOf(obs.slot);
        List<Rules.Block> past = new ArrayList<>();
        LinearLayout upcoming = Ui.column(c);
        for (Rules.Block block : rules.blocks) {
            boolean isPast = today >= 0 && block.toDay < today;
            if (isPast) {
                past.add(block);
                continue;
            }
            boolean now = today >= block.fromDay && today <= block.toDay;
            BlockRows rows = new BlockRows(host, rules, block, today, now, false, refresh);
            blocks.add(rows);
            if (now) {
                column.addView(rows.card, top(c, 16));
            } else {
                upcoming.addView(rows.card);
            }
        }
        if (upcoming.getChildCount() > 0) {
            column.addView(upcoming, top(c, blocks.size() > upcoming.getChildCount() ? 8 : 16));
        }
        if (!past.isEmpty()) {
            LinearLayout pastBox = Ui.column(c);
            pastBox.setVisibility(View.GONE);
            for (Rules.Block block : past) {
                BlockRows rows = new BlockRows(host, rules, block, today, false, true, refresh);
                blocks.add(rows);
                pastBox.addView(rows.card);
            }
            column.addView(fold(c, "已经过了 " + past.size() + " 个区块", pastBox), top(c, 14));
            column.addView(pastBox, Ui.matchWrap());
        }
        refresh.run();
        SystemList.follow(column, x -> refresh.run());
        return column;
    }

    /** 「已经过了 3 个区块 ⌄」: opens the folded rows below it. */
    private static View fold(Context c, String label, View box) {
        LinearLayout row = Ui.row(c);
        row.setMinimumHeight(Ui.dp(c, Ui.TOUCH));
        Ui.pad(row, 4, 4, 4, 4);
        row.addView(Ui.mixed(c, label, 15, Ui.col(c, R.color.p3r_cyan_300), true), Ui.wrap());
        ImageView arrow = Ui.icon(c, R.drawable.ui_ic_down, Ui.col(c, R.color.p3r_cyan_300), 18);
        ((LinearLayout.LayoutParams) arrow.getLayoutParams()).leftMargin = Ui.dp(c, 6);
        row.addView(arrow);
        row.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_faint))
            .skew(Ui.dp(c, 8)));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(label + "，已收起");
        row.setOnClickListener(v -> {
            boolean open = box.getVisibility() != View.VISIBLE;
            box.setVisibility(open ? View.VISIBLE : View.GONE);
            row.setContentDescription(label + (open ? "，已展开" : "，已收起"));
            if (Ui.animationsEnabled()) {
                arrow.animate().rotation(open ? 180f : 0f).setDuration(160).setInterpolator(Motion.SETTLE).start();
                if (open) {
                    Motion.cascade((LinearLayout) box);
                }
            } else {
                arrow.setRotation(open ? 180f : 0f);
            }
        });
        return row;
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
        String floors = floors(b);
        return (floors.isEmpty() ? "" : floors + " · ") + Zh.dateRange(b.fromDay, b.toDay);
    }

    private static String floors(Rules.Block b) {
        return b.floorsZh == null ? "" : b.floorsZh.trim();
    }

    /** 「提齐亚（69–117F）」, or the name alone when the pack gives no floors (steppers' labels). */
    static String blockLabel(Rules.Block b) {
        String floors = floors(b);
        return b.zh + (floors.isEmpty() ? "" : "（" + floors + "）");
    }

    /** 「失踪者：舞子 · 10月21日–11月2日」. */
    static String rescueTag(Rules.Rescue r) {
        return "失踪者：" + r.zh + " · " + Zh.dateRange(r.fromDay, r.toDay);
    }

    /** 「设成 0，舞子就救不出来了」. */
    static String rescueWarning(Rules.Rescue r) {
        return "设成 0，" + r.zh + "就救不出来了";
    }

    /** One block: header, inline 想去, the folded 最多, its 失踪者 and 「恢复默认」. */
    private static final class BlockRows {
        final LinearLayout card;
        final Stepper visits;
        final Stepper cap;
        final TextView capToggle;
        final LinearLayout capRow;
        final LinearLayout foot;
        final TextView warning;
        final List<Rules.Rescue> rescues = new ArrayList<>();
        final String id;
        final String label;

        BlockRows(HomeHost host, Rules rules, Rules.Block block, int today, boolean now, boolean past,
                  Runnable refresh) {
            Context c = host.activity();
            id = block.id;
            label = blockLabel(block);
            card = Ui.column(c);
            if (now) {
                card.setBackground(Ui.card(c).accent(Ui.col(c, R.color.p3r_cyan_400), Ui.dp(c, 4)));
                Ui.pad(card, 18, 12, 10, 12);
            } else {
                card.setBackground(new SystemList.Cursor(c, true).noSweep());
                Ui.pad(card, 4, 12, 0, 12);
            }
            if (past) {
                card.setAlpha(0.7f);
            }

            LinearLayout main = Ui.row(c);
            main.setGravity(Gravity.TOP);
            main.setBaselineAligned(false);
            LinearLayout head = Ui.column(c);
            LinearLayout first = Ui.row(c);
            String floors = floors(block);
            TextView floorView = Ui.numeral(c, floors.isEmpty() ? block.zh : floors, 20,
                Ui.col(c, now ? R.color.p3r_cyan_400 : R.color.p3r_white));
            floorView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 20);
            first.addView(floorView, Ui.wrap());
            if (now) {
                TextView tag = Ui.tag(c, NOW, Ui.col(c, R.color.p3r_cyan_400), Ui.col(c, R.color.p3r_navy_950));
                LinearLayout.LayoutParams tp = Ui.wrap();
                tp.leftMargin = Ui.dp(c, 8);
                first.addView(tag, tp);
            }
            head.addView(first, Ui.wrap());
            if (!floors.isEmpty()) {
                head.addView(Ui.text(c, block.zh, 14, Ui.col(c, R.color.p3r_ice_100), false), top(c, 2));
            }
            head.addView(Ui.mixed(c, Zh.dateRange(block.fromDay, block.toDay), 13,
                Ui.col(c, R.color.p3r_ink_muted), false), Ui.wrap());
            head.setContentDescription(block.zh + "，" + blockLine(block));
            main.addView(head, Ui.weight(1));

            Profile p = host.data().profile();
            LinearLayout controls = Ui.column(c);
            controls.setGravity(Gravity.END);
            visits = compactStepper(c, MAX_VISITS, p.visitsFor(id));
            controls.addView(visits, Ui.wrap());
            capToggle = Ui.text(c, "", 13, Ui.col(c, R.color.p3r_cyan_300), true);
            capToggle.setGravity(Gravity.CENTER);
            capToggle.setMinHeight(Ui.dp(c, Ui.TOUCH));
            Ui.pad(capToggle, 10, 0, 10, 0);
            capToggle.setBackground(new SlantedPanelDrawable().fill(0)
                .pressedFill(Ui.col(c, R.color.ui_cyan_faint)).skew(Ui.dp(c, 6)));
            capToggle.setClickable(true);
            capToggle.setFocusable(true);
            controls.addView(capToggle, Ui.wrap());
            main.addView(controls, Ui.wrap());
            card.addView(main, Ui.matchWrap());

            capRow = Ui.row(c);
            capRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            capRow.addView(Ui.text(c, "最多", 14, Ui.col(c, R.color.p3r_ink_muted), false), Ui.wrap());
            cap = compactStepper(c, MAX_CAP, p.capFor(id));
            LinearLayout.LayoutParams cp = Ui.wrap();
            cp.leftMargin = Ui.dp(c, 10);
            capRow.addView(cap, cp);
            capRow.setVisibility(View.GONE);
            card.addView(capRow, top(c, 2));
            capToggle.setOnClickListener(v -> openCap());

            if (!past) {
                for (Rules.Rescue r : rules.rescues) {
                    boolean overlaps = r.fromDay <= block.toDay && r.toDay >= block.fromDay;
                    if (overlaps && (today < 0 || r.toDay >= today)) {
                        rescues.add(r);
                        TextView tag = Ui.tag(c, rescueTag(r), Ui.alpha(Ui.col(c, R.color.ui_gold), 40),
                            Ui.col(c, R.color.ui_gold));
                        tag.setMaxLines(2);
                        card.addView(tag, wrapTop(c, 6));
                    }
                }
            }
            warning = Ui.text(c, "", 13, Ui.col(c, R.color.ui_gold), true);
            warning.setVisibility(View.GONE);
            card.addView(warning, top(c, 6));

            foot = Ui.row(c);
            TextView own = Ui.tag(c, OWN, Ui.alpha(Ui.col(c, R.color.p3r_cyan_400), 40), Ui.col(c, R.color.p3r_cyan_200));
            foot.addView(own, Ui.wrap());
            foot.addView(new View(c), Ui.weight(1));
            TextView reset = Ui.quiet(c, RESET);
            reset.setTextSize(14);
            reset.setContentDescription(label + RESET);
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
            visits.setContentDescription(label + "想去几次");
            cap.setContentDescription(label + "最多几次");
        }

        private void openCap() {
            capRow.setVisibility(View.VISIBLE);
            capToggle.setVisibility(View.GONE);
            Motion.enter(capRow, 0);
        }

        void refresh(Profile q) {
            visits.setValue(q.visitsFor(id));
            int capValue = q.capFor(id);
            cap.setValue(capValue);
            String capText = "最多 " + capValue + " 次";
            capToggle.setText(Ui.latin(capToggle.getContext(), capText + " ›"));
            capToggle.setContentDescription(label + capText);
            if (q.towerCapByBlock.containsKey(id) || capValue == 0) {
                // A 最多 of its own (or 0) is shown, not hidden behind the toggle.
                capRow.setVisibility(View.VISIBLE);
                capToggle.setVisibility(View.GONE);
            }
            foot.setVisibility(ownValues(q, id) ? View.VISIBLE : View.GONE);
            if (capValue == 0 && !rescues.isEmpty()) {
                warning.setText(rescueWarning(rescues.get(0)));
                warning.setVisibility(View.VISIBLE);
            } else {
                warning.setVisibility(View.GONE);
            }
        }
    }

    private static Stepper compactStepper(Context c, int max, int value) {
        Stepper s = new Stepper(c, 0, max, value).compact();
        s.setFormatter(v -> v + " 次");
        return s;
    }

    /** "想去      [−] 2 次 [+]". */
    private static View labelled(Context c, String label, float sp, Stepper stepper) {
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.text(c, label, sp, Ui.col(c, R.color.p3r_ice_100), true), Ui.weight(1));
        row.addView(stepper, Ui.wrap());
        return row;
    }

    private static LinearLayout.LayoutParams top(Context c, int dp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, dp);
        return p;
    }

    private static LinearLayout.LayoutParams wrapTop(Context c, int dp) {
        LinearLayout.LayoutParams p = Ui.wrap();
        p.topMargin = Ui.dp(c, dp);
        return p;
    }
}
