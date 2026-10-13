package app.p3r.dayplanner.v3.store;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * SQLite schema of the v3 observed-state ledger, file {@code p3r_v3.db} (docs/DESIGN_v3.md §6).
 *
 * <pre>
 *   meta(key PRIMARY KEY, value)                      schema_version, state_revision, profile_revision
 *   profile(id = 1, json)                             goals and planning options ({@code Profile} JSON)
 *   checkpoints(id, created, json)                    full observed states ({@code SimState} JSON)
 *   events(seq, command_id UNIQUE, checkpoint_id,     everything recorded after a checkpoint, plus one
 *          kind, json, voided, created)               journal row per checkpoint/profile/import command
 *   plan(id = 1, state_rev, profile_rev,              latest installed plan ({@code Plan} JSON)
 *        rules_version, json)
 * </pre>
 *
 * <p>Thread rules: this helper is only used through {@link Ledger}, which the planner service calls
 * from its own worker thread or from callers' background threads, always under the service lock.
 * Nothing here may run on the main thread. Write-ahead logging is enabled so reads never block on a
 * writer.
 *
 * <p>Upgrade policy: user data is never dropped. Future versions add tables or columns in
 * {@link #migrateTo}; a downgrade (older app over a newer database) keeps the data untouched,
 * because every schema change is additive.
 */
public final class PlanDb extends SQLiteOpenHelper {
    /** Database file name inside the app's private database directory. */
    public static final String FILE_NAME = "p3r_v3.db";
    /** Current schema version. */
    public static final int VERSION = 1;

    public static final String META_SCHEMA_VERSION = "schema_version";
    public static final String META_STATE_REVISION = "state_revision";
    public static final String META_PROFILE_REVISION = "profile_revision";

    /**
     * @param context any context; the application context is retained
     * @param fileName database file name, normally {@link #FILE_NAME} (tests may use another)
     */
    public PlanDb(Context context, String fileName) {
        super(context.getApplicationContext(), fileName, null, VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE meta ("
                + "key TEXT PRIMARY KEY NOT NULL, "
                + "value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE profile ("
                + "id INTEGER PRIMARY KEY CHECK (id = 1), "
                + "json TEXT NOT NULL)");
        db.execSQL("CREATE TABLE checkpoints ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "created INTEGER NOT NULL, "
                + "json TEXT NOT NULL)");
        db.execSQL("CREATE TABLE events ("
                + "seq INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "command_id TEXT NOT NULL UNIQUE, "
                + "checkpoint_id INTEGER NOT NULL DEFAULT 0, "
                + "kind TEXT NOT NULL, "
                + "json TEXT NOT NULL, "
                + "voided INTEGER NOT NULL DEFAULT 0, "
                + "created INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX events_by_checkpoint ON events (checkpoint_id, seq)");
        db.execSQL("CREATE TABLE plan ("
                + "id INTEGER PRIMARY KEY CHECK (id = 1), "
                + "state_rev INTEGER NOT NULL, "
                + "profile_rev INTEGER NOT NULL, "
                + "rules_version TEXT NOT NULL, "
                + "json TEXT NOT NULL)");
        putMeta(db, META_SCHEMA_VERSION, String.valueOf(VERSION));
        putMeta(db, META_STATE_REVISION, "0");
        putMeta(db, META_PROFILE_REVISION, "0");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        for (int version = oldVersion + 1; version <= newVersion; version++) {
            migrateTo(db, version);
        }
        putMeta(db, META_SCHEMA_VERSION, String.valueOf(newVersion));
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Schema changes are additive only, so an older app can keep using a newer file as is.
        // The default implementation would throw and make the planner unusable; keep the data.
    }

    /**
     * Applies the additive migration that brings the schema to {@code version}. Each step must
     * keep every existing row: use {@code CREATE TABLE IF NOT EXISTS} or
     * {@code ALTER TABLE … ADD COLUMN}, never {@code DROP} or a destructive rebuild.
     */
    static void migrateTo(SQLiteDatabase db, int version) {
        switch (version) {
            case 1:
                // Version 1 is the initial schema created in onCreate.
                break;
            default:
                // Unknown future step: nothing to do; the data stays untouched.
                break;
        }
    }

    private static void putMeta(SQLiteDatabase db, String key, String value) {
        db.execSQL("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", new Object[]{key, value});
    }
}
