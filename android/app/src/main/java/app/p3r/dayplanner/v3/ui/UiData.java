package app.p3r.dayplanner.v3.ui;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi;

/**
 * Immutable bundle the app screens render from: the rule pack, one atomic planner snapshot and a
 * few UI-only facts. Built off the main thread; views only read it.
 */
public final class UiData {
    /** Return banner threshold: last record older than 8 hours. */
    static final long RETURN_AFTER_MILLIS = 8L * 60 * 60 * 1000;

    public final Rules rules;
    public final PlannerApi.Snapshot snap;
    /** "已重新规划：…" line after a replan, or null. */
    public final String changeSummary;
    /** Show "上次记录：… · 游戏进度变了吗？". */
    public final boolean returnBanner;
    public final long nowMillis;

    public UiData(Rules rules, PlannerApi.Snapshot snap, String changeSummary, boolean returnBanner, long nowMillis) {
        this.rules = rules;
        this.snap = snap;
        this.changeSummary = changeSummary;
        this.returnBanner = returnBanner;
        this.nowMillis = nowMillis;
    }

    public SimState observed() {
        return snap == null ? null : snap.observed;
    }

    public Plan plan() {
        return snap == null ? null : snap.plan;
    }

    public Profile profile() {
        return snap == null || snap.profile == null ? new Profile() : snap.profile;
    }

    /** True once onboarding saved a first state. */
    public boolean hasState() {
        return observed() != null;
    }

    /** The next slot to act in, or -1 before onboarding. */
    public int currentSlot() {
        SimState s = observed();
        return s == null ? -1 : s.slot;
    }

    /** The plan step for the next slot, or null while the plan is missing or lagging. */
    public Plan.Step current() {
        Plan plan = plan();
        int slot = currentSlot();
        return plan == null || slot < 0 ? null : plan.at(slot);
    }

    /** Whether the return banner should show for this snapshot (pure, for tests). */
    static boolean returnBannerDue(PlannerApi.Snapshot snap, long nowMillis, long dismissedForRecord) {
        if (snap == null || snap.observed == null || snap.lastRecordMillis <= 0) {
            return false;
        }
        if (dismissedForRecord == snap.lastRecordMillis) {
            return false;
        }
        return nowMillis - snap.lastRecordMillis > RETURN_AFTER_MILLIS;
    }
}
