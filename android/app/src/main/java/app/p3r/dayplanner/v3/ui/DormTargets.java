package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * 队友特性: optional teammate characteristic goals as a grid of tiles (name, three pips per
 * activity — white recorded, cyan from the current 安排, hollow to do — and the target or the
 * milestone reached), and a sheet to pick the target and correct the counts. A forecast is never
 * shown as a recorded count.
 */
final class DormTargets {
    private static final Profile.DormTarget[] TARGETS = Profile.DormTarget.values();
    /** Tiles per row. */
    private static final int COLUMNS = 3;
    /** The sheet's one rule line (glossary wording). */
    static final String RULE = "同一种活动做 3 次解锁特性，两种各 3 次强化特性";
    /** Said when a new record changed a count the player was editing. */
    static final String COUNTS_MOVED = "刚刚有新记录，次数更新了，再看一眼？";

    private DormTargets() {}

    static final class Partner {
        final String id;
        final String name;
        final List<Rules.Activity> activities = new ArrayList<>();

        Partner(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** Only activities backed by dorm characteristic data produce a teammate. */
    static List<Partner> partners(Rules rules) {
        Map<String, Partner> groups = new LinkedHashMap<>();
        for (Rules.Activity activity : rules.activities) {
            if (!StateDraft.isDorm(activity)) continue;
            String id = activity.dormPartnerId();
            if (id == null || id.isEmpty()) continue;
            Partner partner = groups.get(id);
            if (partner == null) {
                partner = new Partner(id, activity.dormPartnerZh);
                groups.put(id, partner);
            }
            partner.activities.add(activity);
        }
        return new ArrayList<>(groups.values());
    }

    // ------------------------------------------------------------------ grid

    /** The teammates as tiles, three to a row; null when the pack has none. */
    static View grid(HomeHost host, UiData data) {
        List<Partner> partners = partners(data.rules);
        if (partners.isEmpty()) return null;
        Context c = host.activity();
        LinearLayout grid = Ui.column(c);
        LinearLayout row = null;
        for (int i = 0; i < partners.size(); i++) {
            if (i % COLUMNS == 0) {
                row = Ui.row(c);
                row.setGravity(Gravity.TOP);
                row.setBaselineAligned(false);
                grid.addView(row, spaced(c, i == 0 ? 4 : 8));
            }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            if (i % COLUMNS > 0) p.leftMargin = Ui.dp(c, 8);
            row.addView(tile(host, data, partners.get(i)), p);
        }
        // Keep the last row's tiles the width of the others.
        for (int i = partners.size() % COLUMNS; i > 0 && i < COLUMNS; i++) {
            View empty = new View(c);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1);
            p.leftMargin = Ui.dp(c, 8);
            row.addView(empty, p);
        }
        return grid;
    }

    private static View tile(HomeHost host, UiData data, Partner partner) {
        Context c = host.activity();
        LinearLayout tile = Ui.column(c);
        tile.setBackground(Ui.pressableCard(c));
        tile.setMinimumHeight(Ui.dp(c, 112));
        Ui.pad(tile, 12, 12, 8, 12);
        TextView name = Ui.text(c, partner.name, 15, Ui.col(c, R.color.p3r_white), true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(name, Ui.matchWrap());

        int[] recorded = counts(partner, data.observed());
        int[] projected = hasCurrentPlan(data) ? projectedCounts(partner, data.observed(), data.plan()) : recorded;
        for (int i = 0; i < recorded.length; i++) {
            Pips pips = new Pips(c, recorded[i], projected[i]);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(Ui.dp(c, 66), Ui.dp(c, 10));
            pp.topMargin = Ui.dp(c, i == 0 ? 10 : 6);
            tile.addView(pips, pp);
        }
        View spacer = new View(c);
        tile.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));

        Profile.DormTarget target = data.profile().dormTarget(partner.id);
        int level = level(recorded);
        TextView tag = null;
        if (level > 0) {
            tag = Ui.tag(c, stamp(level), Ui.col(c, R.color.p3r_white), Ui.col(c, R.color.p3r_navy_950));
        }
        if (target != Profile.DormTarget.NONE && level < target.ordinal()) {
            tag = outlineTag(c, label(target), Ui.col(c, R.color.p3r_cyan_400));
        }
        if (tag != null) {
            LinearLayout.LayoutParams tp = Ui.wrap();
            tp.topMargin = Ui.dp(c, 10);
            tile.addView(tag, tp);
        }
        String opens = opens(partner, data.observed());
        if (opens != null) {
            TextView when = Ui.tag(c, opens, Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 120), Ui.col(c, R.color.p3r_ink_faint));
            LinearLayout.LayoutParams wp = Ui.wrap();
            wp.topMargin = Ui.dp(c, tag == null ? 10 : 4);
            tile.addView(when, wp);
        }
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setContentDescription(partner.name + " 特性目标：" + label(target));
        tile.setOnClickListener(v -> edit(host, host.data(), partner, null, null));
        return tile;
    }

    private static TextView outlineTag(Context c, String text, int ink) {
        TextView tag = Ui.tag(c, text, 0, ink);
        tag.setBackground(new SlantedPanelDrawable().stroke(ink, Ui.dp(c, 1.2f)).skew(Ui.dp(c, 5)));
        return tag;
    }

    /** 「6月16日起」 while the teammate's activities have not opened yet, else null. */
    private static String opens(Partner partner, SimState observed) {
        if (observed == null) return null;
        int first = Integer.MAX_VALUE;
        for (Rules.Activity activity : partner.activities) first = Math.min(first, activity.fromDay);
        return Rules.dayOf(observed.slot) < first ? Zh.date(first) + "起" : null;
    }

    /** Three slanted pips: white recorded, cyan forecast, hollow to do. */
    private static final class Pips extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int recorded;
        private final int projected;

        Pips(Context c, int recorded, int projected) {
            super(c);
            this.recorded = recorded;
            this.projected = Math.max(recorded, projected);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Context c = getContext();
            float h = getHeight();
            float gap = Ui.dp(c, 3);
            float skew = h * 0.6f;
            float w = (getWidth() - skew - gap * 2) / 3f;
            for (int i = 0; i < 3; i++) {
                float left = i * (w + gap);
                path.reset();
                path.moveTo(left + skew, 0);
                path.lineTo(left + skew + w, 0);
                path.lineTo(left + w, h);
                path.lineTo(left, h);
                path.close();
                if (i < recorded) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(Ui.col(c, R.color.p3r_white));
                } else if (i < projected) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(Ui.col(c, R.color.p3r_cyan_400));
                } else {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(Ui.dp(c, 1));
                    paint.setColor(Ui.col(c, R.color.p3r_line));
                }
                canvas.drawPath(path, paint);
            }
        }
    }

    // ------------------------------------------------------------------ sheet

    /**
     * The teammate sheet: name with the milestone stamp, the rule line, 目标, the counts already
     * done (from 开始设置 on) with 「预计 N」 where the current 安排 adds more, and 种菜 for a
     * gardening teammate. Nothing is saved until 「保存」; back or a swipe discards the edits.
     * {@code presetTarget} / {@code presetGarden} carry the player's choices into a refreshed sheet.
     */
    static void edit(HomeHost host, UiData data, Partner partner, Profile.DormTarget presetTarget, Boolean presetGarden) {
        Context c = host.activity();
        StateDraft draft = new StateDraft(StateDraft.Mode.EDIT, data.rules, data.observed(),
            data.profile(), null, data.snap == null ? 0 : data.snap.stateRevision);
        if (presetTarget != null) setTarget(draft.profile, partner, presetTarget);
        if (presetGarden != null) draft.setDormGardening(presetGarden);
        LinearLayout sheet = Ui.column(c);
        Ui.pad(sheet, 20, 12, 20, 24);
        LinearLayout head = Ui.row(c);
        head.addView(SheetChrome.title(c, partner.name), Ui.weight(1));
        TextView stamp = Ui.tag(c, "", 0, 0);
        head.addView(stamp, Ui.wrap());
        sheet.addView(head, Ui.matchWrap());
        sheet.addView(Ui.muted(c, RULE), spaced(c, 4));

        sheet.addView(fieldLabel(c, "目标"), spaced(c, 18));
        SegmentedControl target = new SegmentedControl(c, Arrays.asList("未选", "解锁", "强化"));
        target.setSelected(draft.profile.dormTarget(partner.id).ordinal());
        for (int i = 0; i < TARGETS.length; i++) {
            target.segment(i).setContentDescription(partner.name + " 特性目标：" + label(TARGETS[i]));
        }
        target.setListener(value -> setTarget(draft.profile, partner, TARGETS[value]));
        sheet.addView(target, spaced(c, 6));

        SimState observed = data.observed();
        int[] shown = counts(partner, observed);
        Runnable restamp = () -> {
            int[] edited = new int[partner.activities.size()];
            for (int i = 0; i < edited.length; i++) edited[i] = draft.dormCount(partner.activities.get(i).idx);
            styleStamp(c, stamp, level(edited));
        };
        if (observed != null) {
            sheet.addView(fieldLabel(c, "做过几次？"), spaced(c, 20));
            int[] projected = hasCurrentPlan(data) ? projectedCounts(partner, observed, data.plan()) : shown;
            for (int a = 0; a < partner.activities.size(); a++) {
                Rules.Activity activity = partner.activities.get(a);
                int original = StateDraft.dormCount(observed, activity.idx);
                boolean introduced = activity.fromDay <= Rules.dayOf(observed.slot);
                LinearLayout title = Ui.row(c);
                title.addView(Ui.text(c, activity.zh, 14, Ui.col(c, R.color.p3r_white), true), Ui.weight(1));
                if (!introduced) {
                    title.addView(Ui.tag(c, Zh.date(activity.fromDay) + "起",
                        Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 140), Ui.col(c, R.color.p3r_ink_faint)), Ui.wrap());
                } else if (projected[a] > original) {
                    title.addView(Ui.tag(c, "预计 " + projected[a], Ui.alpha(Ui.col(c, R.color.p3r_cyan_200), 40),
                        Ui.col(c, R.color.p3r_cyan_200)), Ui.wrap());
                }
                sheet.addView(title, spaced(c, 14));
                LinearLayout line = Ui.row(c);
                SegmentedControl count = new SegmentedControl(c, Arrays.asList("0", "1", "2", "3"));
                count.setSelected(original);
                for (int n = 0; n <= 3; n++) {
                    count.segment(n).setTextSize(18);
                    count.segment(n).setContentDescription(activity.zh + "：" + n + " 次");
                    if (!introduced) count.setSegmentEnabled(n, false);
                }
                count.setListener(value -> {
                    // Returning to the displayed value preserves an older raw count above three.
                    if (value == original) draft.dormCounts[activity.idx] = null;
                    else draft.setDormCount(activity.idx, value);
                    restamp.run();
                });
                line.addView(count, Ui.weight(1));
                TextView unit = Ui.text(c, "次", 14, Ui.col(c, R.color.p3r_ink_muted), false);
                LinearLayout.LayoutParams up = Ui.wrap();
                up.leftMargin = Ui.dp(c, 10);
                line.addView(unit, up);
                sheet.addView(line, spaced(c, 6));
            }
        }
        restamp.run();
        stamp.setVisibility(observed == null ? View.GONE : View.VISIBLE);

        boolean garden = false;
        for (Rules.Activity activity : partner.activities) garden |= activity.requiresPlantedCrop;
        if (garden) {
            ToggleRow crops = new ToggleRow(c, "种菜也排进来", "记得先买好种苗种下去", draft.profile.planDormGardening);
            crops.setListener(draft::setDormGardening);
            sheet.addView(crops, spaced(c, 20));
        }
        TextView save = Ui.primary(c, "保存");
        save.setOnClickListener(v -> save(host, data, partner, draft));
        sheet.addView(save, spaced(c, 24));
        host.showSheet(sheet);
    }

    /**
     * Applies the sheet onto the latest data: the target (and the 种菜 choice when it was
     * touched) onto the latest profile, edited counts onto the latest record. A count that a new
     * record changed meanwhile is never overwritten: the sheet reopens with the new counts.
     */
    private static void save(HomeHost host, UiData opened, Partner partner, StateDraft draft) {
        UiData latest = host.data();
        SimState before = opened.observed();
        SimState now = latest.observed();
        Profile.DormTarget chosen = draft.profile.dormTarget(partner.id);
        boolean countsChanged = false;
        for (Rules.Activity activity : partner.activities) {
            if (draft.dormCounts[activity.idx] == null) continue;
            countsChanged = true;
            if (now == null || StateDraft.dormCount(now, activity.idx) != StateDraft.dormCount(before, activity.idx)) {
                host.dismissSheet();
                edit(host, latest, partner, chosen, draft.dormGardening);
                host.showMessage(COUNTS_MOVED);
                return;
            }
        }
        Profile profile = latest.profile().copy();
        setTarget(profile, partner, chosen);
        if (draft.dormGardening != null) profile.planDormGardening = draft.dormGardening;
        host.dismissSheet();
        if (countsChanged && now != null) {
            SimState corrected = now.copy();
            if (corrected.used.length < latest.rules.activities.length) {
                corrected.used = Arrays.copyOf(corrected.used, latest.rules.activities.length);
            }
            // Corrected counts only: no stats are granted again, nothing else of the record moves.
            for (Rules.Activity activity : partner.activities) {
                Integer count = draft.dormCounts[activity.idx];
                if (count != null) corrected.used[activity.idx] = count;
            }
            host.saveCheckpoint(corrected, profile, "已记录：" + partner.name + "的宿舍活动");
        } else if (differs(profile, latest.profile())) {
            host.saveProfile(profile);
        }
    }

    private static boolean differs(Profile a, Profile b) {
        try {
            return !a.toJson().toString().equals(b.toJson().toString());
        } catch (org.json.JSONException e) {
            return true;
        }
    }

    private static void setTarget(Profile profile, Partner partner, Profile.DormTarget target) {
        if (target == Profile.DormTarget.NONE) profile.dormTargets.remove(partner.id);
        else profile.dormTargets.put(partner.id, target);
    }

    private static TextView fieldLabel(Context c, String text) {
        return Ui.text(c, text, 15, Ui.col(c, R.color.p3r_ice_100), true);
    }

    /** The milestone stamp beside the sheet title: white once reached, outlined before. */
    private static void styleStamp(Context c, TextView stamp, int level) {
        stamp.setText(stamp(level));
        SlantedPanelDrawable bg = new SlantedPanelDrawable().skew(Ui.dp(c, 5));
        if (level > 0) {
            bg.fill(Ui.col(c, R.color.p3r_white));
            stamp.setTextColor(Ui.col(c, R.color.p3r_navy_950));
        } else {
            bg.stroke(Ui.alpha(Ui.col(c, R.color.p3r_ink_faint), 200), Ui.dp(c, 1));
            stamp.setTextColor(Ui.col(c, R.color.p3r_ink_muted));
        }
        stamp.setBackground(bg);
    }

    static String label(Profile.DormTarget target) {
        switch (target) {
            case UNLOCK: return "解锁";
            case UPGRADE: return "强化";
            default: return "未选";
        }
    }

    static int[] counts(Partner partner, SimState observed) {
        int[] counts = new int[partner.activities.size()];
        for (int i = 0; i < counts.length; i++) counts[i] = StateDraft.dormCount(observed, partner.activities.get(i).idx);
        return counts;
    }

    static int[] projectedCounts(Partner partner, SimState observed, Plan plan) {
        int[] counts = counts(partner, observed);
        if (observed == null || plan == null) return counts;
        for (Plan.Step step : plan.steps) {
            if (step.slot < observed.slot || !step.productive()) continue;
            for (int i = 0; i < counts.length; i++) {
                if (("act:" + partner.activities.get(i).id).equals(step.action)) counts[i] = Math.min(3, counts[i] + 1);
            }
        }
        return counts;
    }

    /** 0 = 未解锁, 1 = 已解锁 (one activity done 3 times), 2 = 已强化 (both). */
    static int level(int[] counts) {
        int complete = 0;
        for (int count : counts) if (count >= 3) complete++;
        if (counts.length >= 2 && complete == counts.length) return 2;
        return complete > 0 ? 1 : 0;
    }

    /** 「未解锁」 / 「已解锁」 / 「已强化」. */
    static String milestone(int[] counts) {
        return stamp(level(counts));
    }

    private static String stamp(int level) {
        return level >= 2 ? "已强化" : level == 1 ? "已解锁" : "未解锁";
    }

    private static boolean hasCurrentPlan(UiData data) {
        return data.snap != null && data.snap.planCurrent && data.observed() != null && data.plan() != null;
    }

    private static LinearLayout.LayoutParams spaced(Context c, int top) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, top);
        return p;
    }
}
