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
 */
public interface PlannerApi {

    /** Everything a screen needs to render, read atomically. */
    final class Snapshot {
        /** Null before onboarding. */
        public SimState observed;
        public Profile profile;
        /** Latest installed plan (may lag one revision behind while replanning), or null. */
        public Plan plan;
        public long stateRevision;
        public long profileRevision;
        /** True when {@link #plan} matches both revisions. */
        public boolean planCurrent;
        /** Replanning is running or queued. */
        public boolean planning;
        /** Last planning error in Chinese, or null. */
        public String planErrorZh;
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
        /** Row written by the command (for duplicates: the original row), -1 if none. */
        public long eventSeq = -1;
        /** Chinese, user-facing message for rejections (stale revision, invalid value…). */
        public String errorZh;
        /** Machine-readable rejection code: stale | conflict | invalid | gap | io. */
        public String errorCode;
        /** For {@link #CODE_GAP}: open slots before the requested one that have no record yet. */
        public int missingSlots;
    }

    final class HistoryItem {
        public long seq;
        public long createdMillis;
        public int slot;
        /** outcome | skip | tower | tower_fix | void | checkpoint | import. */
        public String kind;
        /** e.g. "5月12日 晚上 · 探索塔尔塔罗斯 · 到达 27F · 余额 14,750 日元". */
        public String summaryZh;
        public boolean voided;
        /**
         * The record still shapes the current state (recorded after the latest checkpoint and not
         * voided), so it can be undone with {@code voidEvent}; tower records can also be corrected.
         */
        public boolean correctable;
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
    }

    interface Listener {
        /** Called on the main thread after any state/profile/plan change. */
        void onChanged(Snapshot snapshot);
    }

    Rules rules();

    Snapshot snapshot();

    /** Record the outcome of the action planned (or substituted) at {@code slot}. */
    Receipt recordOutcome(String commandId, long expectedStateRevision, int slot, String actionKey, Outcome outcome);

    Receipt recordTower(String commandId, long expectedStateRevision, TowerReturn tower);

    /** Replace the values of an earlier tower record; never adds a visit. */
    Receipt correctTower(String commandId, long expectedStateRevision, long targetSeq, TowerReturn corrected);

    /** Undo an earlier event (appends a void marker, then replays). */
    Receipt voidEvent(String commandId, long expectedStateRevision, long targetSeq);

    /** Full observed state from onboarding / 同步现状 / full edit; optionally the profile too. */
    Receipt saveCheckpoint(String commandId, long expectedStateRevision, SimState observed, Profile profileOrNull);

    Receipt saveProfile(String commandId, long expectedProfileRevision, Profile profile);

    List<HistoryItem> history(int limit);

    /** Queue a replan if the installed plan is stale (no-op otherwise). */
    void requestReplan();

    /** Legal alternatives for a slot given the current observed state (for 改做别的). */
    List<Plan.Step> alternatives(int slot);

    String exportBackup();

    Receipt importBackup(String commandId, String json);

    void addListener(Listener listener);

    void removeListener(Listener listener);
}
