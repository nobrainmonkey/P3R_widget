package app.p3r.dayplanner.v3.ui;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi;
import app.p3r.dayplanner.v3.service.AvailableActions;

/**
 * Immutable bundle the app screens render from: the rule pack, one atomic planner snapshot and a
 * few UI-only facts. The activity creates it on the main thread; expensive derived lists are lazy.
 */
public final class UiData {
    /** Return banner threshold: last record older than 8 hours. */
    static final long RETURN_AFTER_MILLIS = 8L * 60 * 60 * 1000;

    public final Rules rules;
    public final PlannerApi.Snapshot snap;
    /**
     * "已重新安排：…" line after a replan, or how the last 从头重新安排 ended ("从头重新安排好了：…",
     * {@link PlanDiff#fullSummary}), or null.
     */
    public final String changeSummary;
    /** Show "上次记录：… · 游戏进度变了吗？". */
    public final boolean returnBanner;
    public final long nowMillis;
    /** Recorded-progress opportunities, cached per requested day without a whole-year UI build. */
    public final AvailableActions.Calendar availability;
    private final Plan displayPlan;

    public UiData(Rules rules, PlannerApi.Snapshot snap, String changeSummary, boolean returnBanner, long nowMillis) {
        this.rules = rules;
        this.snap = snap;
        this.changeSummary = changeSummary;
        this.returnBanner = returnBanner;
        this.nowMillis = nowMillis;
        this.availability = AvailableActions.build(rules, profile(), observed());
        this.displayPlan = snap == null ? null : snap.storyDisplay == null ? snap.plan
                : snap.storyDisplay.overlay(snap.plan, snap.observed == null ? -1 : snap.observed.slot);
    }

    public SimState observed() {
        return snap == null ? null : snap.observed;
    }

    public Plan plan() {
        return snap == null ? null : snap.plan;
    }

    /** Calendar shown to the player, including story days before the next actionable slot. */
    public Plan displayPlan() {
        return displayPlan;
    }

    public int displaySlot() {
        return snap != null && snap.storyDisplay != null ? snap.storyDisplay.cursorSlot : currentSlot();
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
        Plan plan = displayPlan();
        int slot = displaySlot();
        return plan == null || slot < 0 ? null : plan.at(slot);
    }

    /**
     * Whether the return banner should show for this snapshot (pure, for tests): never once the
     * calendar is over, when there is nothing left to catch up on.
     */
    static boolean returnBannerDue(Rules rules, PlannerApi.Snapshot snap, long nowMillis, long dismissedForRecord) {
        if (snap == null || snap.observed == null || snap.lastRecordMillis <= 0 || Slots.finished(rules, snap.observed)) {
            return false;
        }
        if (dismissedForRecord == snap.lastRecordMillis) {
            return false;
        }
        return nowMillis - snap.lastRecordMillis > RETURN_AFTER_MILLIS;
    }
}
