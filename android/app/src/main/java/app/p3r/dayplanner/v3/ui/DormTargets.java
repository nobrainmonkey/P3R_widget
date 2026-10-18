package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
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

/** Optional teammate characteristic goals; recorded counts and forecasts remain separate. */
final class DormTargets {
    private static final Profile.DormTarget[] TARGETS = Profile.DormTarget.values();

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

    /** Only activities backed by dorm characteristic data produce a teammate row. */
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

    static void addTo(LinearLayout column, HomeHost host, UiData data) {
        List<Partner> partners = partners(data.rules);
        if (partners.isEmpty()) return;
        Context c = host.activity();
        column.addView(Ui.sectionHeader(c, "队友特性", partners.size() + " 人"), spaced(c, 20));
        column.addView(Ui.muted(c, "点队友选择不追求、解锁或强化，并核对实际次数。宿舍活动会占用晚上，与其他活动竞争；救援和社群目标优先。"), spaced(c, 2));
        column.addView(Ui.muted(c, "不追求时不为特性额外安排；仍可能安排有用的能力值收益。"), spaced(c, 4));
        for (Partner partner : partners) column.addView(row(host, data, partner), spaced(c, 8));
    }

    private static View row(HomeHost host, UiData data, Partner partner) {
        Context c = host.activity();
        LinearLayout row = Ui.column(c);
        row.setBackground(Ui.card(c));
        Ui.pad(row, 14, 12, 14, 12);
        LinearLayout head = Ui.row(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(c, partner.name, 17, Ui.col(c, R.color.p3r_white), true), Ui.weight(1));
        Profile.DormTarget target = data.profile().dormTarget(partner.id);
        TextView badge = Ui.chip(c, label(target), target != Profile.DormTarget.NONE);
        badge.setTextSize(12);
        badge.setClickable(false);
        badge.setFocusable(false);
        head.addView(badge, Ui.wrap());
        row.addView(head);
        if (data.observed() == null) {
            row.addView(Ui.muted(c, "尚未同步实际进度"), spaced(c, 6));
        } else {
            row.addView(Ui.muted(c, "已记录：" + milestone(counts(partner, data.observed()))), spaced(c, 6));
            row.addView(Ui.mixed(c, countLine(partner, counts(partner, data.observed())), 12,
                Ui.col(c, R.color.p3r_ink_muted), false), spaced(c, 2));
        }
        if (hasCurrentPlan(data)) {
            int[] projected = projectedCounts(partner, data.observed(), data.plan());
            row.addView(Ui.mixed(c, "按当前安排预计：" + milestone(projected) + " · " + compactCounts(projected), 12,
                Ui.col(c, R.color.p3r_cyan_200), false), spaced(c, 4));
        }
        String availability = availability(partner, data.observed());
        if (!availability.isEmpty()) row.addView(Ui.muted(c, availability), spaced(c, 4));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(partner.name + " 特性目标：" + label(target));
        row.setOnClickListener(v -> edit(host, data, partner));
        return row;
    }

    private static void edit(HomeHost host, UiData data, Partner partner) {
        Context c = host.activity();
        StateDraft draft = new StateDraft(StateDraft.Mode.EDIT, data.rules, data.observed(),
            data.profile(), null, data.snap == null ? 0 : data.snap.stateRevision);
        LinearLayout sheet = Ui.column(c);
        Ui.pad(sheet, 20, 12, 20, 20);
        sheet.addView(SheetChrome.title(c, partner.name + " · 队友特性"));
        sheet.addView(Ui.muted(c, "一种活动累计 3 次解锁特性，两种各 3 次强化。达到目标后，不会只为特性继续安排。"), spaced(c, 6));
        sheet.addView(Ui.muted(c, "宿舍活动占用晚上；救援和社群目标优先。未选特性目标，仍可能为能力值安排活动。"), spaced(c, 6));
        SegmentedControl target = new SegmentedControl(c, Arrays.asList("不追求", "解锁", "强化"));
        target.setSelected(draft.profile.dormTarget(partner.id).ordinal());
        for (int i = 0; i < TARGETS.length; i++) {
            target.segment(i).setContentDescription(partner.name + " 特性目标：" + label(TARGETS[i]));
        }
        target.setListener(value -> {
            if (TARGETS[value] == Profile.DormTarget.NONE) draft.profile.dormTargets.remove(partner.id);
            else draft.profile.dormTargets.put(partner.id, TARGETS[value]);
        });
        sheet.addView(target, spaced(c, 14));

        if (data.observed() == null) {
            sheet.addView(Ui.muted(c, "可以先选目标。完成初始设置后，再到这里核对实际次数；这里不会替你创建游戏进度。"), spaced(c, 16));
        } else {
            sheet.addView(Ui.text(c, "已经完成的实际次数", 16, Ui.col(c, R.color.p3r_white), true), spaced(c, 16));
            sheet.addView(Ui.muted(c, "只填实际完成的次数。没记录过的先显示 0；补填不会再次增加能力值，预计次数不会自动填入。"), spaced(c, 4));
            TextView status = Ui.muted(c, "已记录：" + milestone(counts(partner, data.observed())));
            sheet.addView(status, spaced(c, 8));
            for (Rules.Activity activity : partner.activities) {
                int original = StateDraft.dormCount(data.observed(), activity.idx);
                sheet.addView(Ui.text(c, activity.zh, 14, Ui.col(c, R.color.p3r_white), true), spaced(c, 12));
                SegmentedControl count = new SegmentedControl(c, Arrays.asList("0 次", "1 次", "2 次", "3 次"));
                count.setSelected(original);
                boolean introduced = activity.fromDay <= Rules.dayOf(data.observed().slot);
                for (int n = 0; n <= 3; n++) {
                    count.segment(n).setContentDescription(activity.zh + "：" + n + " 次");
                    if (!introduced) count.setSegmentEnabled(n, false);
                }
                count.setListener(value -> {
                    // Returning to the displayed value preserves an older raw count above three.
                    if (value == original) draft.dormCounts[activity.idx] = null;
                    else draft.setDormCount(activity.idx, value);
                    int[] edited = new int[partner.activities.size()];
                    for (int i = 0; i < edited.length; i++) edited[i] = draft.dormCount(partner.activities.get(i).idx);
                    status.setText(c.getString(R.string.dorm_goal_record_preview, milestone(edited)));
                });
                sheet.addView(count, spaced(c, 6));
                if (!introduced) sheet.addView(Ui.muted(c, Zh.date(activity.fromDay) + "起开放，现在可先选目标。"), spaced(c, 4));
            }
        }
        boolean garden = false;
        for (Rules.Activity activity : partner.activities) garden |= activity.requiresPlantedCrop;
        if (garden) {
            sheet.addView(Ui.muted(c, data.profile().planDormGardening
                ? "园艺已允许安排，请提前购买种苗、种好作物。"
                : "该队友有园艺项目。目前未允许安排园艺；如需安排，请在「同步现状 → 宿舍活动」开启，并提前种好作物。"), spaced(c, 14));
        }
        if (hasCurrentPlan(data)) {
            int[] projected = projectedCounts(partner, data.observed(), data.plan());
            sheet.addView(Ui.mixed(c, "按保存前的安排预计：" + milestone(projected) + "\n" + countLine(partner, projected),
                13, Ui.col(c, R.color.p3r_cyan_200), false), spaced(c, 14));
        }
        TextView save = Ui.primary(c, "保存并重新安排");
        save.setOnClickListener(v -> {
            UiData latest = host.data();
            if (data.snap != null && latest.snap != null &&
                    (data.snap.stateRevision != latest.snap.stateRevision ||
                        data.snap.profileRevision != latest.snap.profileRevision)) {
                host.dismissSheet();
                host.showMessage("进度或目标已变化，请重新打开后再保存。");
                return;
            }
            boolean countsChanged = false;
            for (Rules.Activity activity : partner.activities) countsChanged |= draft.dormCounts[activity.idx] != null;
            host.dismissSheet();
            if (countsChanged && data.observed() != null) {
                // Reuse the wizard's count correction without applying unrelated date/rescue
                // normalisation from the full wizard to this small edit.
                SimState corrected = data.observed().copy();
                corrected.used = draft.build().used;
                host.saveCheckpoint(corrected, draft.profile,
                    "已记录：" + partner.name + " · 特性目标：" + label(draft.profile.dormTarget(partner.id)));
            } else if (draft.profileChanged()) {
                host.saveProfile(draft.profile);
            }
        });
        sheet.addView(save, spaced(c, 20));
        TextView cancel = Ui.quiet(c, "取消");
        cancel.setOnClickListener(v -> host.dismissSheet());
        sheet.addView(cancel, spaced(c, 4));
        host.showSheet(sheet);
    }

    static String label(Profile.DormTarget target) {
        switch (target) {
            case UNLOCK: return "解锁";
            case UPGRADE: return "强化";
            default: return "不追求";
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

    static String milestone(int[] counts) {
        int complete = 0;
        for (int count : counts) if (count >= 3) complete++;
        if (counts.length >= 2 && complete == counts.length) return "已强化";
        if (complete > 0) return "已解锁，未强化";
        return "未解锁";
    }

    private static boolean hasCurrentPlan(UiData data) {
        return data.snap != null && data.snap.planCurrent && data.observed() != null && data.plan() != null;
    }

    private static String countLine(Partner partner, int[] counts) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < counts.length; i++) {
            if (i > 0) out.append("\n");
            out.append(partner.activities.get(i).zh).append(" · ").append(counts[i]).append(" / 3 次");
        }
        return out.toString();
    }

    private static String compactCounts(int[] counts) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < counts.length; i++) {
            if (i > 0) out.append("、");
            out.append(counts[i]).append("/3");
        }
        return out.toString();
    }

    private static String availability(Partner partner, SimState observed) {
        if (observed == null) return "可以提前选择目标；实际次数请在完成初始设置后核对。";
        int first = Integer.MAX_VALUE;
        int last = -1;
        for (Rules.Activity activity : partner.activities) {
            first = Math.min(first, activity.fromDay);
            if (activity.fixedDays != null && activity.fixedDays.length > 0) {
                last = Math.max(last, activity.fixedDays[activity.fixedDays.length - 1]);
            } else last = Math.max(last, activity.toDay);
        }
        int day = Rules.dayOf(observed.slot);
        if (day < first) return Zh.date(first) + "起开放";
        if (day > last) return "已过可安排日期，仍可核对实际次数。";
        return "";
    }

    private static LinearLayout.LayoutParams spaced(Context c, int top) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, top);
        return p;
    }
}
