package app.p3r.dayplanner.v3.store;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * SQLite schema of the v3 observed-state ledger, file {@code p3r_v3.db} (docs/DESIGN_v3.md §6).
 *
 * <pre>
 *   meta(key PRIMARY KEY, value)                      schema_version, state_revision, profile_revision
 *   profile(id = 1, json)                             goals and planning options ({@code Profile} JSON)
 *   checkpoints(id, created, json, rules_version)     full observed states ({@code SimState} JSON plus
 *                                                     the pack layout, see {@link PackLayout})
 *   events(seq, command_id UNIQUE, checkpoint_id,     everything recorded after a checkpoint, plus one
 *          kind, json, voided, created,               journal row per checkpoint/profile/import command
 *          rules_version)
 *   plan(id = 1, state_rev, profile_rev,              latest installed plan ({@code Plan} JSON)
 *        rules_version, json)
 * </pre>
 *
 * <p>{@code rules_version} on checkpoints and events (schema 2) stamps each row with the
 * {@code Rules.version} of the pack it was written under; it is null for rows written before
 * schema 2 (or by an older app after a downgrade).
 *
 * <p>Thread rules: this helper is only used through {@link Ledger}, which the planner service calls
 * from its own worker thread or from callers' background threads, always under the service lock.
 * Nothing here may run on the main thread. Write-ahead logging is enabled so reads never block on a
 * writer.
 *
 * <p>Upgrade policy: user data is never dropped. Every schema change is additive and every
 * migration step is idempotent ({@link #migrateTo} checks before it adds). A downgrade (older app
 * over a newer database, e.g. {@code adb install -d}) keeps the data but lowers the file's version,
 * so the same steps run again on the next upgrade and must not fail on a file that has them.
 */
public final class PlanDb extends SQLiteOpenHelper {
    /** Database file name inside the app's private database directory. */
    public static final String FILE_NAME = "p3r_v3.db";
    /** Current schema version (2 adds the {@code rules_version} columns). */
    public static final int VERSION = 2;

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
        // The version-1 tables above, then exactly the steps an upgraded file gets.
        for (int version = 2; version <= VERSION; version++) {
            migrateTo(db, version);
        }
        putMeta(db, META_SCHEMA_VERSION, String.valueOf(VERSION));
        putMeta(db, META_STATE_REVISION, "0");
        putMeta(db, META_PROFILE_REVISION, "0");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        for (int version = Math.max(1, oldVersion + 1); version <= newVersion; version++) {
            migrateTo(db, version);
        }
        putMeta(db, META_SCHEMA_VERSION, String.valueOf(newVersion));
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Schema changes are additive only, so an older app can keep using a newer file as is.
        // The default implementation would throw and make the planner unusable; keep the data.
        // SQLiteOpenHelper then lowers the file's version, so the next upgrade repeats the
        // (idempotent) migration steps.
    }

    /**
     * Applies the additive migration that brings the schema to {@code version}. Each step keeps
     * every existing row and is idempotent: it may run again on a file that already has it (after
     * a downgrade and a re-upgrade). Use {@code CREATE … IF NOT EXISTS} and
     * {@link #addColumnIfMissing}; never {@code DROP} or a destructive rebuild.
     */
    static void migrateTo(SQLiteDatabase db, int version) {
        switch (version) {
            case 1:
                // Version 1 is the initial schema created in onCreate.
                break;
            case 2:
                addColumnIfMissing(db, "checkpoints", "rules_version", "TEXT");
                addColumnIfMissing(db, "events", "rules_version", "TEXT");
                break;
            default:
                // Unknown future step: nothing to do; the data stays untouched.
                break;
        }
    }

    /** {@code ALTER TABLE table ADD COLUMN column type}, unless the column already exists. */
    static void addColumnIfMissing(SQLiteDatabase db, String table, String column, String type) {
        if (!hasColumn(db, table, column)) {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }

    /** True when {@code table} has a column named {@code column}. */
    static boolean hasColumn(SQLiteDatabase db, String table, String column) {
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int name = c.getColumnIndexOrThrow("name");
            while (c.moveToNext()) {
                if (column.equalsIgnoreCase(c.getString(name))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void putMeta(SQLiteDatabase db, String key, String value) {
        db.execSQL("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", new Object[]{key, value});
    }
}
