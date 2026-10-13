package app.p3r.dayplanner.v3.service;

import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.List;

/**
 * Facade used by the UI and the widget (docs/DESIGN_v3.md §6–7). Implemented by
 * {@link PlannerService}. All write methods are idempotent per {@code commandId} and guarded by
 * compare-and-set revisions; they run synchronously on the caller's thread (call them off the main
 * thread) and schedule a background replan on success.
 *
 * <p>Order of checks for every write: a known {@code commandId} returns its original result
 * ({@code duplicate}) or {@code conflict}; then the expected revision ({@code stale}); then the
 * values ({@code invalid} / {@code gap}, with a Chinese {@code errorZh}).
 *
 * <p>{@link #snapshot()} and {@link #rules()} never touch the disk once the service has loaded
 * (they wait for that first load only); {@link #peekSnapshot()} never waits. Snapshots share the
 * {@link Plan} instance with the service: treat it as read-only.
 */
public interface PlannerApi {

    /** Everything a screen needs to render, read atomically. */
    final class Snapshot {
        /**
         * Null before onboarding. {@code observed.slot} is the next slot the player can act in:
         * calendar-locked slots are skipped automatically.
         */
        public SimState observed;
        /** Never null; defaults until the first save ({@link #profileRevision} 0). */
        public Profile profile;
        /** Latest installed plan (may lag one revision behind while replanning), or null. */
        public Plan plan;
        public long stateRevision;
        public long profileRevision;
        /** True when {@link #plan} matches both revisions. */
        public boolean planCurrent;
        /** Replanning for the current revisions is running or queued. */
        public boolean planning;
        /** Last planning error in Chinese, or null. */
        public String planErrorZh;
        /**
         * A non-blocking note for the player in Chinese, or null. Set when records no longer fit
         * the bundled game data (typically after an app update), e.g.
         * "有 2 条记录和现在的游戏资料对不上，建议「同步现状」核对一下". The plan is still made; show
         * the note with a way to open 同步现状.
         */
        public String noticeZh;
        /** Wall time of the most recent observed record (ms), 0 if none. */
        public long lastRecordMillis;
        /** Number of non-voided events since the current checkpoint. */
        public int eventsSinceCheckpoint;
    }

    final class Receipt {
        /** The expected revision is out of date; reload and show the latest plan. */
        public static final String CODE_STALE = "stale";
        /** The command id was already used with a different payload. */
        public static final String CODE_CONFLICT = "conflict";
        /** A value was rejected; {@link #errorZh} says which. */
        public static final String CODE_INVALID = "invalid";
        /** Earlier open slots have no record yet; run the catch-up flow (see {@link #missingSlots}). */
        public static final String CODE_GAP = "gap";
        /** Storage failed; retrying with the same command id is safe. */
        public static final String CODE_IO = "io";

        public boolean ok;
        /** True when the command id had already been applied (idempotent replay). */
        public boolean duplicate;
        /** Current revisions after the command (also for duplicates and rejections). */
        public long stateRevision;
        public long profileRevision;
        /**
         * {@code observed.slot} after the command (also for duplicates and rejections): the next
         * slot to record, calendar-locked slots already skipped; -1 before onboarding. A catch-up
         * loop records the plan step at this slot next, never a locked step.
         */
        public int nextSlot = -1;
        /** Row written by the command (for duplicates: the original row), -1 if none. */
        public long eventSeq = -1;
        /** Chinese, user-facing message for rejections (stale revision, invalid value…). */
        public String errorZh;
        /** Machine-readable rejection code: stale | conflict | invalid | gap | io. */
        public String errorCode;
        /** For {@link #CODE_GAP}: open slots before the requested one that have no record yet. */
        public int missingSlots;
        /**
         * Chinese text to show instead of the usual success message, or null. Set on an
         * {@link #importBackup} duplicate: the same backup was already restored with this command
         * id, so nothing was replaced ("这份备份刚才已经恢复过了…").
         */
        public String noticeZh;
    }

    final class HistoryItem {
        public long seq;
        public long createdMillis;
        public int slot;
        /** outcome | skip | tower | tower_fix | void | checkpoint | import. */
        public String kind;
        /** e.g. "5月12日 晚上 · 探索塔尔塔罗斯 · 到达 27F · 出塔后余额 14,750 日元". */
        public String summaryZh;
        public boolean voided;
        /**
         * The record still shapes the current state (an outcome, skip, tower record or tower
         * correction recorded after the latest checkpoint and not voided), so {@code voidEvent}
         * accepts it: show 撤销 only when this is true.
         */
        public boolean undoable;
        /**
         * {@link #undoable} and a tower record ({@code kind == "tower"}), so {@code correctTower}
         * accepts it: show 修改 only when this is true. Prefill the form from {@link #tower}.
         */
        public boolean correctable;
        /**
         * For tower rows: the values now in effect (the latest correction, else the original
         * record), as the quick sheet would enter them; for tower_fix rows: that correction's
         * values. Null for other kinds.
         */
        public TowerReturn tower;
    }

    /** Tower return as entered on the quick sheet: exactly one of net change or balance. */
    final class TowerReturn {
        public int slot;
        public int block;
        public int floor;
        /** True → {@link #amount} is the new absolute balance; false → net change (may be negative). */
        public boolean balanceMode;
        public long amount;
        /** Optional protagonist level, null if not given. */
        public Integer level;
        /** Rescue id confirmed during this visit, or null. */
        public String rescuedId;
        /**
         * False when the player says the slot's side steps (the plan's "顺便：…" lines) were not
         * done; they are applied with the visit otherwise. Kept from the original record by
         * {@code correctTower}.
         */
        public boolean sideDone = true;
    }

    interface Listener {
        /** Called on the main thread after any state/profile/plan change. */
        void onChanged(Snapshot snapshot);
    }

    /** The rule pack, or null if it failed to load (then {@link Snapshot#planErrorZh} says so). */
    Rules rules();

    Snapshot snapshot();

    /**
     * Like {@link #snapshot()} but never waits: null while the service is still loading (listeners
     * are notified once it has loaded). For main-thread callers such as widget updates.
     */
    default Snapshot peekSnapshot() {
        return snapshot();
    }

    /**
     * Record the outcome of the action planned (or substituted) at {@code slot}. {@code slot} must
     * be {@code observed.slot}: an earlier slot is rejected as already recorded, a later one with
     * {@code gap} (run the catch-up flow). A {@code SKIPPED} outcome is stored as a skip.
     * Tartarus visits with floor/cash use {@link #recordTower}.
     */
    Receipt recordOutcome(String commandId, long expectedStateRevision, int slot, String actionKey, Outcome outcome);

    /**
     * Record one Tartarus visit at {@code observed.slot} (an evening). {@code block} may be -1 to
     * use the block active that day; the floor may not exceed the block's top.
     */
    Receipt recordTower(String commandId, long expectedStateRevision, TowerReturn tower);

    /**
     * Replace the values of an earlier tower record; never adds a visit. The slot and block always
     * stay those of the original record ({@code corrected.slot/block} are ignored). Values the
     * correction form does not collect are carried over from the values in effect (the latest
     * correction, else the original): the rescue when {@code corrected.rescuedId} is null, the
     * level when {@code corrected.level} is null, and always {@code sideDone}. {@code targetSeq}
     * may also be a correction row; its tower record is then corrected.
     */
    Receipt correctTower(String commandId, long expectedStateRevision, long targetSeq, TowerReturn corrected);

    /**
     * Undo an earlier record of the current checkpoint (appends a void marker, then replays).
     * Voiding a correction restores the values it replaced.
     */
    Receipt voidEvent(String commandId, long expectedStateRevision, long targetSeq);

    /**
     * Full observed state from onboarding / 同步现状 / full edit; optionally the profile too.
     *
     * <p>Rescues ({@code rescued} / {@code rescueFailed}, bit i = {@code rules.rescues[i]}): a set
     * {@code rescued} bit means 救出了, a set {@code rescueFailed} bit means the player explicitly
     * answered 没救出 (the only way a checkpoint marks a link as lost through its rescue), and
     * neither bit means "not asked / not sure". The service then applies this rule before saving:
     * a rescue that is not rescued and not explicitly failed, whose window ended before the
     * checkpoint's day ({@code rescue.toDay < Rules.dayOf(observed.slot)}), and whose linked
     * Social Link ({@code rescue.link}) is at RANK 1 or above, is saved as rescued. Inside or before
     * the window nothing is inferred. So the wizard must not copy a {@code rescueFailed} bit from a
     * prefilled state unless the player confirmed 没救出 (a missed deadline in the replay sets it
     * without asking).
     */
    Receipt saveCheckpoint(String commandId, long expectedStateRevision, SimState observed, Profile profileOrNull);

    Receipt saveProfile(String commandId, long expectedProfileRevision, Profile profile);

    /** Newest first, across all checkpoints; {@code limit <= 0} returns everything. */
    List<HistoryItem> history(int limit);

    /** Queue a replan if the installed plan is stale (no-op otherwise). */
    void requestReplan();

    /**
     * Legal alternatives for a slot (for 改做别的), labelled like plan steps. For a future slot the
     * forecast follows the installed plan up to it. Empty for past or locked slots.
     */
    List<Plan.Step> alternatives(int slot);

    /** Backup JSON (profile, checkpoints, records), or null if the service failed to load. */
    String exportBackup();

    /**
     * Validates a backup, then replaces everything atomically; all revisions move forward. Reusing
     * a command id that already restored the same backup changes nothing and returns
     * {@code ok + duplicate} with {@link Receipt#noticeZh} set; show that text, not "已恢复". A new
     * restore of the same file needs a new command id (include the current revisions in it).
     */
    Receipt importBackup(String commandId, String json);

    void addListener(Listener listener);

    void removeListener(Listener listener);
}
