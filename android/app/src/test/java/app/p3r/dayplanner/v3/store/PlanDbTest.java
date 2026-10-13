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
