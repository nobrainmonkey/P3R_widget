package app.p3r.dayplanner.v3.service;

import android.util.Log;

import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.store.CheckpointRow;
import app.p3r.dayplanner.v3.store.EventKind;
import app.p3r.dayplanner.v3.store.EventRow;
import app.p3r.dayplanner.v3.store.Ledger;
import app.p3r.dayplanner.v3.store.OutcomeApplier;
import app.p3r.dayplanner.v3.store.PackLayout;
import app.p3r.dayplanner.v3.store.Replayer;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable in-memory view of the ledger: revisions, profile and the replayed observed state.
 * Rebuilt from the database after every write (a few hundred rows, cheap) on a background thread.
 */
final class LedgerView {
    private static final String TAG = "P3rLedger";

    final Rules rules;
    /** Never null: defaults until the first profile save. */
    final Profile profile;
    final long stateRevision;
    final long profileRevision;
    /** Latest checkpoint id, 0 before onboarding. */
    final long checkpointId;
    /** Rows of the latest checkpoint, in sequence order. */
    final List<EventRow> chain;
    /** Normalised observed state, null before onboarding. */
    final SimState observed;
    /** Exactly known tower gains from every checkpoint. */
    final List<Replayer.TowerGain> towerGains;
    final long lastRecordMillis;
    final int eventsSinceCheckpoint;
    /**
     * Things in the latest checkpoint and its rows that no longer fit the loaded rule pack (values
     * dropped while carrying the checkpoint over, rows that could not be replayed as recorded),
     * typically after an app update; 0 when everything fits.
     */
    final int mismatches;

    private LedgerView(Rules rules, Profile profile, long stateRevision, long profileRevision,
                       long checkpointId, List<EventRow> chain, SimState observed,
                       List<Replayer.TowerGain> towerGains, long lastRecordMillis,
                       int eventsSinceCheckpoint, int mismatches) {
        this.rules = rules;
        this.profile = profile;
        this.stateRevision = stateRevision;
        this.profileRevision = profileRevision;
        this.checkpointId = checkpointId;
        this.chain = chain;
        this.observed = observed;
        this.towerGains = towerGains;
        this.lastRecordMillis = lastRecordMillis;
        this.eventsSinceCheckpoint = eventsSinceCheckpoint;
        this.mismatches = mismatches;
    }

    /** Reads everything from {@code ledger} and replays each checkpoint's rows. */
    static LedgerView load(Ledger ledger, Rules rules, OutcomeApplier applier) {
        Profile profile = parseProfile(ledger.profileJson());
        List<CheckpointRow> checkpoints = ledger.checkpoints();
        Map<Long, List<EventRow>> chains = groupByCheckpoint(ledger.allEvents());
        Replayer replayer = new Replayer(rules, profile, applier);

        CheckpointRow latest = checkpoints.isEmpty() ? null : checkpoints.get(checkpoints.size() - 1);
        List<EventRow> chain = latest == null
                ? Collections.emptyList()
                : chains.getOrDefault(latest.id, Collections.emptyList());
        SimState observed = null;
        int mismatches = 0;
        List<Replayer.TowerGain> gains = new ArrayList<>();
        for (CheckpointRow checkpoint : checkpoints) {
            List<EventRow> rows = chains.getOrDefault(checkpoint.id, Collections.emptyList());
            boolean isLatest = checkpoint == latest;
            if (!isLatest && !hasKind(rows, EventKind.TOWER)) {
                continue;
            }
            PackLayout.Remapped start = start(checkpoint, rules);
            if (start == null) {
                mismatches += isLatest ? 1 : 0;
                continue;
            }
            Replayer.Result result = replayer.replay(start.state, rows, null);
            gains.addAll(result.towerGains);
            if (isLatest) {
                observed = result.state;
                mismatches += start.lost + result.problems;
            }
        }
        return new LedgerView(rules, profile, ledger.stateRevision(), ledger.profileRevision(),
                latest == null ? 0 : latest.id, Collections.unmodifiableList(new ArrayList<>(chain)),
                observed, Collections.unmodifiableList(gains), lastRecord(latest, chain),
                countObserved(chain), mismatches);
    }

    /** Block index at the observed slot, -1 if none. */
    int currentBlock() {
        if (observed == null) {
            return -1;
        }
        Rules.Block block = rules.blockOn(Rules.dayOf(observed.slot));
        return block == null ? -1 : block.idx;
    }

    /** Latest recorded Tartarus evening, including context carried by a checkpoint; -1 if none before now. */
    int lastTowerSlot() {
        if (observed == null) return -1;
        int last = observed.lastTowerSlot >= 0 && observed.lastTowerSlot < observed.slot
                ? observed.lastTowerSlot : -1;
        for (EventRow row : chain) {
            if (!row.voided && EventKind.TOWER.equals(row.kind) && row.slot() < observed.slot) {
                last = Math.max(last, row.slot());
            }
        }
        return last;
    }

    /** The checkpoint carried over to {@code rules} (see {@link CheckpointRow#stateFor}), or null if unreadable. */
    static PackLayout.Remapped start(CheckpointRow checkpoint, Rules rules) {
        try {
            return checkpoint.stateFor(rules);
        } catch (JSONException | RuntimeException e) {
            Log.e(TAG, "checkpoint " + checkpoint.id + " is unreadable", e);
            return null;
        }
    }

    static Profile parseProfile(String json) {
        if (json == null) {
            return new Profile();
        }
        try {
            return Profile.fromJson(new JSONObject(json));
        } catch (JSONException | RuntimeException e) {
            Log.e(TAG, "stored profile is unreadable; using defaults", e);
            return new Profile();
        }
    }

    private static Map<Long, List<EventRow>> groupByCheckpoint(List<EventRow> rows) {
        Map<Long, List<EventRow>> out = new LinkedHashMap<>();
        for (EventRow row : rows) {
            out.computeIfAbsent(row.checkpointId, k -> new ArrayList<>()).add(row);
        }
        return out;
    }

    private static boolean hasKind(List<EventRow> rows, String kind) {
        for (EventRow row : rows) {
            if (kind.equals(row.kind)) {
                return true;
            }
        }
        return false;
    }

    /** Latest of the checkpoint time and any observed record or edit after it. */
    private static long lastRecord(CheckpointRow checkpoint, List<EventRow> chain) {
        if (checkpoint == null) {
            return 0;
        }
        long last = checkpoint.created;
        for (EventRow row : chain) {
            boolean isRecord = EventKind.isObserved(row.kind)
                    || EventKind.TOWER_FIX.equals(row.kind)
                    || EventKind.VOID.equals(row.kind);
            if (isRecord) {
                last = Math.max(last, row.created);
            }
        }
        return last;
    }

    private static int countObserved(List<EventRow> chain) {
        int n = 0;
        for (EventRow row : chain) {
            if (!row.voided && EventKind.isObserved(row.kind)) {
                n++;
            }
        }
        return n;
    }
}
