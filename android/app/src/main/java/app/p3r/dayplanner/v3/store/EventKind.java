package app.p3r.dayplanner.v3.store;

/**
 * Kinds of rows in the {@code events} table (docs/DESIGN_v3.md §6).
 *
 * <p>Observed kinds change the observed state when replayed: {@link #OUTCOME}, {@link #SKIP} and
 * {@link #TOWER}. Modifier kinds change how earlier rows replay: {@link #TOWER_FIX} replaces a
 * tower record's values and {@link #VOID} removes a row from replay. Journal kinds record that a
 * command ran (so its command id stays idempotent) and appear in the history, but never replay:
 * {@link #CHECKPOINT}, {@link #PROFILE} and {@link #IMPORT}.
 */
public final class EventKind {
    private EventKind() {}

    /** Outcome of the action at a slot: {@code {slot, action, outcome}}. */
    public static final String OUTCOME = "outcome";
    /** The slot passed without doing the action: same payload as {@link #OUTCOME}, code SKIPPED. */
    public static final String SKIP = "skip";
    /** One Tartarus visit: {@link TowerRecord} payload. */
    public static final String TOWER = "tower";
    /** Replacement values for an earlier tower record: {@code {target, tower}}. Never adds a visit. */
    public static final String TOWER_FIX = "tower_fix";
    /** Undo marker for an earlier row: {@code {target}}. */
    public static final String VOID = "void";
    /** Journal row of a saved checkpoint: {@code {checkpointId, payloadSha256}}. */
    public static final String CHECKPOINT = "checkpoint";
    /** Journal row of a saved profile: {@code {payloadSha256}}. */
    public static final String PROFILE = "profile";
    /** Journal row of an imported backup: {@code {checkpointId, payloadSha256}}. Never exported. */
    public static final String IMPORT = "import";

    /** True for rows that change the observed state when replayed. */
    public static boolean isObserved(String kind) {
        return OUTCOME.equals(kind) || SKIP.equals(kind) || TOWER.equals(kind);
    }

    /** True for rows that only record that a command ran. */
    public static boolean isJournal(String kind) {
        return CHECKPOINT.equals(kind) || PROFILE.equals(kind) || IMPORT.equals(kind);
    }

    /** True for rows the player may undo with a {@link #VOID}. */
    public static boolean isVoidable(String kind) {
        return isObserved(kind) || TOWER_FIX.equals(kind);
    }

    /** True for every kind this version understands. */
    public static boolean isKnown(String kind) {
        return isObserved(kind) || isJournal(kind) || TOWER_FIX.equals(kind) || VOID.equals(kind);
    }
}
