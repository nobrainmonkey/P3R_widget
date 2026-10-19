package app.p3r.dayplanner.v3;

/** Intent contract shared by the app screens and the widget (docs/DESIGN_v3.md §7). */
public final class Intents {
    private Intents() {}

    // HomeActivity
    /** Tab to show: "today" | "schedule" | "links" | "me". */
    public static final String EXTRA_TAB = "p3r.tab";
    /** Open the detail sheet for this slot on arrival (int). */
    public static final String EXTRA_OPEN_SLOT = "p3r.openSlot";

    // ResultActivity (quick result sheet)
    /** Slot being recorded (int). */
    public static final String EXTRA_SLOT = "p3r.slot";
    /** Planned action key ({@code Action.key}) for that slot (String). */
    public static final String EXTRA_ACTION = "p3r.action";
    /** Plan id the caller rendered (String); mismatch → show "安排已更新" and the current item. */
    public static final String EXTRA_PLAN_ID = "p3r.planId";
    /** State revision the caller rendered (long). */
    public static final String EXTRA_STATE_REV = "p3r.stateRev";
    /** "result" (default) | "cannot" (做不了) | "other" (改做别的) | "catchup" (Done on a future item). */
    public static final String EXTRA_MODE = "p3r.mode";
    /** Existing outcome record to correct; saves replace its result and preserve its original slot/action. */
    public static final String EXTRA_CORRECT_SEQ = "p3r.correctSeq";

    // StateActivity
    /** "onboarding" | "sync" | "edit". */
    public static final String EXTRA_STATE_MODE = "p3r.stateMode";

    public static final String MODE_RESULT = "result";
    public static final String MODE_CANNOT = "cannot";
    public static final String MODE_OTHER = "other";
    public static final String MODE_CATCHUP = "catchup";
}
