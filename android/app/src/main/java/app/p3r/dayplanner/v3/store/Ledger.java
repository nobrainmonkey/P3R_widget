package app.p3r.dayplanner.v3.store;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed access to {@link PlanDb}. Pure persistence: no validation, no replay.
 *
 * <p>Thread rules: not thread-safe on its own. The planner service calls it only while holding its
 * lock, from background threads (never the main thread). Every mutation must run inside
 * {@link #inTransaction}; commands perform all checks before their first write, so a rejected
 * command leaves an empty transaction behind.
 */
public final class Ledger implements AutoCloseable {
    private static final String EVENT_COLUMNS =
            "seq, command_id, checkpoint_id, kind, json, voided, created, rules_version";
    private static final String CHECKPOINT_COLUMNS = "id, created, json, rules_version";

    private final PlanDb helper;

    /**
     * @param context any context
     * @param fileName database file name, normally {@link PlanDb#FILE_NAME}
     */
    public Ledger(Context context, String fileName) {
        this.helper = new PlanDb(context, fileName);
    }

    /** Work run inside a transaction. */
    public interface Work<T> {
        T run();
    }

    /**
     * Runs {@code work} in an immediate transaction and commits it when {@code work} returns.
     * Any exception rolls the transaction back and propagates.
     */
    public <T> T inTransaction(Work<T> work) {
        SQLiteDatabase db = db();
        db.beginTransactionNonExclusive();
        try {
            T result = work.run();
            db.setTransactionSuccessful();
            return result;
        } finally {
            db.endTransaction();
        }
    }

    /** True when write-ahead logging is active on the open database. */
    public boolean isWalEnabled() {
        return db().isWriteAheadLoggingEnabled();
    }

    @Override
    public void close() {
        helper.close();
    }

    private SQLiteDatabase db() {
        return helper.getWritableDatabase();
    }

    // ------------------------------------------------------------------------------ meta

    public long stateRevision() {
        return metaLong(PlanDb.META_STATE_REVISION);
    }

    public long profileRevision() {
        return metaLong(PlanDb.META_PROFILE_REVISION);
    }

    /** Increments the state revision and returns the new value. */
    public long bumpStateRevision() {
        long next = stateRevision() + 1;
        putMeta(PlanDb.META_STATE_REVISION, next);
        return next;
    }

    /** Increments the profile revision and returns the new value. */
    public long bumpProfileRevision() {
        long next = profileRevision() + 1;
        putMeta(PlanDb.META_PROFILE_REVISION, next);
        return next;
    }

    private long metaLong(String key) {
        try (Cursor c = db().rawQuery("SELECT value FROM meta WHERE key = ?", new String[]{key})) {
            if (!c.moveToFirst()) {
                return 0;
            }
            try {
                return Long.parseLong(c.getString(0));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    private void putMeta(String key, long value) {
        db().execSQL("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)",
                new Object[]{key, String.valueOf(value)});
    }

    /** Personal compendium choices, independent of the planner's profile and revisions. */
    public String compendiumJson() {
        try (Cursor c = db().rawQuery("SELECT value FROM meta WHERE key = ?",
                new String[]{"compendium"})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    public void putCompendiumJson(String json) {
        db().execSQL("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)",
                new Object[]{"compendium", json});
    }

    // ------------------------------------------------------------------------------ profile

    /** Stored profile JSON, or null before the first save. */
    public String profileJson() {
        try (Cursor c = db().rawQuery("SELECT json FROM profile WHERE id = 1", null)) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    public void putProfileJson(String json) {
        db().execSQL("INSERT OR REPLACE INTO profile (id, json) VALUES (1, ?)", new Object[]{json});
    }

    // ------------------------------------------------------------------------------ checkpoints

    /** Most recent checkpoint, or null before onboarding. */
    public CheckpointRow latestCheckpoint() {
        try (Cursor c = db().rawQuery(
                "SELECT " + CHECKPOINT_COLUMNS + " FROM checkpoints ORDER BY id DESC LIMIT 1", null)) {
            return c.moveToFirst() ? checkpoint(c) : null;
        }
    }

    /** All checkpoints, oldest first. */
    public List<CheckpointRow> checkpoints() {
        List<CheckpointRow> out = new ArrayList<>();
        try (Cursor c = db().rawQuery("SELECT " + CHECKPOINT_COLUMNS + " FROM checkpoints ORDER BY id", null)) {
            while (c.moveToNext()) {
                out.add(checkpoint(c));
            }
        }
        return out;
    }

    /** Inserts a new checkpoint without a rules version stamp and returns its id. */
    public long insertCheckpoint(long created, String json) {
        return insertCheckpoint(created, json, null);
    }

    /** Inserts a new checkpoint stamped with {@code rulesVersion} (may be null) and returns its id. */
    public long insertCheckpoint(long created, String json, String rulesVersion) {
        ContentValues v = new ContentValues();
        v.put("created", created);
        v.put("json", json);
        v.put("rules_version", rulesVersion);
        return db().insertOrThrow("checkpoints", null, v);
    }

    /** Inserts a checkpoint with its original id (backup import). */
    public void insertCheckpointRow(CheckpointRow row) {
        ContentValues v = new ContentValues();
        v.put("id", row.id);
        v.put("created", row.created);
        v.put("json", row.json);
        v.put("rules_version", row.rulesVersion);
        db().insertOrThrow("checkpoints", null, v);
    }

    private static CheckpointRow checkpoint(Cursor c) {
        return new CheckpointRow(c.getLong(0), c.getLong(1), c.getString(2), c.isNull(3) ? null : c.getString(3));
    }

    // ------------------------------------------------------------------------------ events

    /** Row written by {@code commandId}, or null. */
    public EventRow eventByCommand(String commandId) {
        try (Cursor c = db().rawQuery("SELECT " + EVENT_COLUMNS + " FROM events WHERE command_id = ?",
                new String[]{commandId})) {
            return c.moveToFirst() ? event(c) : null;
        }
    }

    /** Row with sequence number {@code seq}, or null. */
    public EventRow event(long seq) {
        try (Cursor c = db().rawQuery("SELECT " + EVENT_COLUMNS + " FROM events WHERE seq = ?",
                new String[]{String.valueOf(seq)})) {
            return c.moveToFirst() ? event(c) : null;
        }
    }

    /** Rows of one checkpoint, in sequence order. */
    public List<EventRow> events(long checkpointId) {
        return events("SELECT " + EVENT_COLUMNS + " FROM events WHERE checkpoint_id = ? ORDER BY seq",
                new String[]{String.valueOf(checkpointId)});
    }

    /** Highest sequence number in the table, 0 when it is empty. */
    public long lastSeq() {
        try (Cursor c = db().rawQuery("SELECT MAX(seq) FROM events", null)) {
            return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : 0;
        }
    }

    /** Every row, in sequence order. */
    public List<EventRow> allEvents() {
        return events("SELECT " + EVENT_COLUMNS + " FROM events ORDER BY seq", null);
    }

    private List<EventRow> events(String sql, String[] args) {
        List<EventRow> out = new ArrayList<>();
        try (Cursor c = db().rawQuery(sql, args)) {
            while (c.moveToNext()) {
                out.add(event(c));
            }
        }
        return out;
    }

    /** Appends a row without a rules version stamp and returns its sequence number. */
    public long insertEvent(String commandId, long checkpointId, String kind, String json, long created) {
        return insertEvent(commandId, checkpointId, kind, json, created, null);
    }

    /** Appends a row stamped with {@code rulesVersion} (may be null) and returns its sequence number. */
    public long insertEvent(String commandId, long checkpointId, String kind, String json, long created,
                            String rulesVersion) {
        ContentValues v = new ContentValues();
        v.put("command_id", commandId);
        v.put("checkpoint_id", checkpointId);
        v.put("kind", kind);
        v.put("json", json);
        v.put("voided", 0);
        v.put("created", created);
        v.put("rules_version", rulesVersion);
        return db().insertOrThrow("events", null, v);
    }

    /** Inserts a row with its original sequence number (backup import). */
    public void insertEventRow(EventRow row) {
        ContentValues v = new ContentValues();
        v.put("seq", row.seq);
        v.put("command_id", row.commandId);
        v.put("checkpoint_id", row.checkpointId);
        v.put("kind", row.kind);
        v.put("json", row.json);
        v.put("voided", row.voided ? 1 : 0);
        v.put("created", row.created);
        v.put("rules_version", row.rulesVersion);
        db().insertOrThrow("events", null, v);
    }

    public void setVoided(long seq, boolean voided) {
        db().execSQL("UPDATE events SET voided = ? WHERE seq = ?", new Object[]{voided ? 1 : 0, seq});
    }

    private static EventRow event(Cursor c) {
        return new EventRow(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3),
                c.getString(4), c.getInt(5) != 0, c.getLong(6), c.isNull(7) ? null : c.getString(7));
    }

    // ------------------------------------------------------------------------------ plan

    /** The installed plan row. */
    public static final class StoredPlan {
        public final long stateRevision;
        public final long profileRevision;
        public final String rulesVersion;
        public final String json;

        public StoredPlan(long stateRevision, long profileRevision, String rulesVersion, String json) {
            this.stateRevision = stateRevision;
            this.profileRevision = profileRevision;
            this.rulesVersion = rulesVersion;
            this.json = json;
        }
    }

    /** Installed plan, or null. */
    public StoredPlan plan() {
        try (Cursor c = db().rawQuery(
                "SELECT state_rev, profile_rev, rules_version, json FROM plan WHERE id = 1", null)) {
            if (!c.moveToFirst()) {
                return null;
            }
            return new StoredPlan(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3));
        }
    }

    public void putPlan(StoredPlan plan) {
        db().execSQL("INSERT OR REPLACE INTO plan (id, state_rev, profile_rev, rules_version, json) "
                        + "VALUES (1, ?, ?, ?, ?)",
                new Object[]{plan.stateRevision, plan.profileRevision, plan.rulesVersion, plan.json});
    }

    public void deletePlan() {
        db().execSQL("DELETE FROM plan");
    }

    // ------------------------------------------------------------------------------ import

    /** Deletes the profile, every checkpoint, every event and the plan (revisions are kept). */
    public void clearForImport() {
        SQLiteDatabase db = db();
        db.execSQL("DELETE FROM events");
        db.execSQL("DELETE FROM checkpoints");
        db.execSQL("DELETE FROM profile");
        db.execSQL("DELETE FROM plan");
    }
}
