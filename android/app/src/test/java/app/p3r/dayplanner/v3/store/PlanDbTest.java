package app.p3r.dayplanner.v3.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.HashSet;
import java.util.Set;

/** Schema, WAL, transactions and the never-drop upgrade path of {@code p3r_v3.db}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PlanDbTest {
    private Context context;
    private Ledger ledger;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        ledger = new Ledger(context, PlanDb.FILE_NAME);
    }

    @After
    public void tearDown() {
        ledger.close();
    }

    @Test
    public void createsTheDesignedTablesWithSeededRevisions() {
        assertEquals(0, ledger.stateRevision());
        assertEquals(0, ledger.profileRevision());
        assertNull(ledger.latestCheckpoint());
        assertNull(ledger.profileJson());
        assertNull(ledger.plan());
        assertTrue("write-ahead logging", ledger.isWalEnabled());

        PlanDb helper = new PlanDb(context, PlanDb.FILE_NAME);
        Set<String> tables = new HashSet<>();
        try (Cursor c = helper.getReadableDatabase()
                .rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null)) {
            while (c.moveToNext()) {
                tables.add(c.getString(0));
            }
        }
        helper.close();
        assertTrue(tables.toString(), tables.containsAll(Set.of("meta", "profile", "checkpoints", "events", "plan")));
    }

    @Test
    public void commandIdsAreUniqueAndRevisionsBump() {
        ledger.inTransaction(() -> {
            long cp = ledger.insertCheckpoint(1L, "{\"slot\":40}");
            ledger.insertEvent("cmd-1", cp, EventKind.CHECKPOINT, "{}", 1L);
            ledger.bumpStateRevision();
            return null;
        });
        assertEquals(1, ledger.stateRevision());
        assertEquals("cmd-1", ledger.eventByCommand("cmd-1").commandId);
        try {
            ledger.inTransaction(() -> ledger.insertEvent("cmd-1", 1, EventKind.OUTCOME, "{}", 2L));
            fail("duplicate command id must be refused by the UNIQUE constraint");
        } catch (android.database.SQLException expected) {
            // expected
        }
        assertEquals(1, ledger.allEvents().size());
    }

    @Test
    public void failedTransactionLeavesNothingBehind() {
        try {
            ledger.inTransaction(() -> {
                ledger.insertEvent("cmd-x", 0, EventKind.PROFILE, "{}", 1L);
                ledger.bumpStateRevision();
                throw new IllegalStateException("boom");
            });
            fail();
        } catch (IllegalStateException expected) {
            // expected
        }
        assertEquals(0, ledger.stateRevision());
        assertNull(ledger.eventByCommand("cmd-x"));
    }

    @Test
    public void upgradeAndDowngradeKeepEveryRow() {
        ledger.inTransaction(() -> {
            long cp = ledger.insertCheckpoint(5L, "{\"slot\":40}");
            ledger.insertEvent("keep-me", cp, EventKind.CHECKPOINT, "{}", 5L);
            ledger.putProfileJson("{\"schema\":\"p3r.profile.v3\"}");
            ledger.putPlan(new Ledger.StoredPlan(1, 1, "v", "{}"));
            return null;
        });
        ledger.close();

        PlanDb helper = new PlanDb(context, PlanDb.FILE_NAME);
        SQLiteDatabase db = helper.getWritableDatabase();
        helper.onUpgrade(db, 1, 3);
        helper.onDowngrade(db, 3, 1);
        helper.close();

        ledger = new Ledger(context, PlanDb.FILE_NAME);
        assertNotNull(ledger.eventByCommand("keep-me"));
        assertNotNull(ledger.latestCheckpoint());
        assertNotNull(ledger.profileJson());
        assertNotNull(ledger.plan());
    }

    @Test
    public void aVersion1FileIsUpgradedInPlaceWithItsRows() {
        ledger.close();
        String name = "v1-" + System.nanoTime() + ".db";
        SQLiteDatabase v1 = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null);
        v1.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)");
        v1.execSQL("CREATE TABLE profile (id INTEGER PRIMARY KEY CHECK (id = 1), json TEXT NOT NULL)");
        v1.execSQL("CREATE TABLE checkpoints (id INTEGER PRIMARY KEY AUTOINCREMENT, created INTEGER NOT NULL, "
                + "json TEXT NOT NULL)");
        v1.execSQL("CREATE TABLE events (seq INTEGER PRIMARY KEY AUTOINCREMENT, command_id TEXT NOT NULL UNIQUE, "
                + "checkpoint_id INTEGER NOT NULL DEFAULT 0, kind TEXT NOT NULL, json TEXT NOT NULL, "
                + "voided INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL)");
        v1.execSQL("CREATE TABLE plan (id INTEGER PRIMARY KEY CHECK (id = 1), state_rev INTEGER NOT NULL, "
                + "profile_rev INTEGER NOT NULL, rules_version TEXT NOT NULL, json TEXT NOT NULL)");
        v1.execSQL("INSERT INTO meta (key, value) VALUES ('state_revision', '7')");
        v1.execSQL("INSERT INTO checkpoints (created, json) VALUES (5, '{\"slot\":40}')");
        v1.execSQL("INSERT INTO events (command_id, checkpoint_id, kind, json, created) "
                + "VALUES ('old', 1, 'outcome', '{\"slot\":40}', 6)");
        v1.setVersion(1);
        v1.close();

        ledger = new Ledger(context, name);
        assertEquals(7, ledger.stateRevision());
        assertNull("rows from before schema 2 have no stamp", ledger.latestCheckpoint().rulesVersion);
        assertNull(ledger.eventByCommand("old").rulesVersion);
        long seq = ledger.inTransaction(() -> ledger.insertEvent("new", 1, EventKind.SKIP, "{}", 7L, "pack|2"));
        assertEquals("pack|2", ledger.event(seq).rulesVersion);
    }

    @Test
    public void migrationStepsRunAgainAfterADowngradeWithoutFailing() {
        ledger.inTransaction(() -> {
            long cp = ledger.insertCheckpoint(5L, "{\"slot\":40}", "pack|1");
            ledger.insertEvent("stamped", cp, EventKind.CHECKPOINT, "{}", 5L, "pack|1");
            return null;
        });
        ledger.close();
        // An older app (adb install -d) leaves the columns but lowers the file's version.
        SQLiteDatabase raw = SQLiteDatabase.openDatabase(context.getDatabasePath(PlanDb.FILE_NAME).getPath(), null,
                SQLiteDatabase.OPEN_READWRITE);
        raw.setVersion(1);
        raw.close();

        ledger = new Ledger(context, PlanDb.FILE_NAME);
        assertEquals("pack|1", ledger.latestCheckpoint().rulesVersion);
        assertEquals("pack|1", ledger.eventByCommand("stamped").rulesVersion);
        PlanDb helper = new PlanDb(context, PlanDb.FILE_NAME);
        SQLiteDatabase db = helper.getWritableDatabase();
        assertEquals(PlanDb.VERSION, db.getVersion());
        PlanDb.migrateTo(db, 2);
        assertTrue(PlanDb.hasColumn(db, "events", "rules_version"));
        helper.close();
    }

    @Test
    public void voidFlagAndImportClearing() {
        long seq = ledger.inTransaction(() -> ledger.insertEvent("a", 1, EventKind.OUTCOME, "{\"slot\":1}", 1L));
        ledger.inTransaction(() -> {
            ledger.setVoided(seq, true);
            return null;
        });
        assertTrue(ledger.event(seq).voided);
        ledger.inTransaction(() -> {
            ledger.clearForImport();
            ledger.insertEventRow(new EventRow(42, "b", 3, EventKind.SKIP, "{\"slot\":2}", false, 9L));
            ledger.insertCheckpointRow(new CheckpointRow(3, 8L, "{\"slot\":2}"));
            return null;
        });
        assertNull(ledger.event(seq));
        assertEquals(42, ledger.eventByCommand("b").seq);
        assertEquals(1, ledger.events(3).size());
        assertEquals(3, ledger.latestCheckpoint().id);
        long next = ledger.inTransaction(() -> ledger.insertEvent("c", 3, EventKind.SKIP, "{\"slot\":3}", 1L));
        assertTrue("sequence numbers keep growing after an import", next > 42);
    }
}
