package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowActivity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 备份与恢复's 「旧版逐日攻略」 → 「导出打勾进度」 (docs/DESIGN_v3.md §19): the
 * 3.0.x guide's ticks in the prefs p3r_native_progress_v1 are shown only when there is a ticked task,
 * saved through ACTION_CREATE_DOCUMENT as the old page's exportProgress() file, byte for byte, and the
 * prefs are never changed. The expected files were produced by the old page's own expression
 * {@code JSON.stringify({format, completed, completedSubsteps, schemaVersion: 2, exportedAt}, null, 2)}
 * run in Node over the same stored text.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class OldGuideExportTest extends UiTestBase {
    /** As 3.0.x stored them (PlannerStore.acceptWebState: JSONObject.toString of the page's state). */
    private static final String COMPLETED = "{\"t001_01\":true,\"t002_01\":false,\"t005_03\":true,\"t005_07\":true}";
    private static final String SUBSTEPS = "{\"t005_03::0\":true,\"t005_03::1\":true,\"t005_03::2\":false}";
    private static final String SUBTITLE = "3.0.x 逐日攻略里打过勾的 3 项还在手机上，导出后可以在旧版 2.0.0「P3R」里导入。";
    private static final Uri DOC = Uri.parse(
        "content://com.android.externalstorage.documents/document/primary%3ADownload%2FP3R_%E6%89%93%E5%8D%A1.json");
    private static final Pattern EXPORTED_AT = Pattern.compile("\"exportedAt\": \"([^\"]+)\"");

    @Before
    public void noOldPrefs() {
        // Deleted, not cleared: a commit would create the file the first test expects to stay absent.
        app().deleteSharedPreferences(OldGuideProgress.PREFS);
    }

    private static Application app() {
        return RuntimeEnvironment.getApplication();
    }

    private static SharedPreferences oldPrefs() {
        return app().getSharedPreferences(OldGuideProgress.PREFS, Context.MODE_PRIVATE);
    }

    private static File oldPrefsFile() {
        return new File(app().getDataDir(), "shared_prefs/" + OldGuideProgress.PREFS + ".xml");
    }

    /** The guide's prefs as 3.0.x left them, with its other keys (date, theme, widget cursor). */
    private static void store(String completed, String substeps) {
        SharedPreferences.Editor edit = oldPrefs().edit()
            .putString("selectedDate", "4月21日")
            .putString("theme", "night")
            .putString("widgetCursor_7", "t005_03")
            .putLong("widgetActionRevision", 12L);
        if (completed != null) {
            edit.putString("completed", completed);
        }
        if (substeps != null) {
            edit.putString("substeps", substeps);
        }
        assertTrue(edit.commit());
    }

    private HomeActivity backupPage() {
        HomeActivity a = home();
        a.selectTab(HomeActivity.TAB_ME);
        Shots.click(a, "备份与恢复");
        return a;
    }

    /** Taps the entry and checks the save request: ACTION_CREATE_DOCUMENT, JSON, the old file name. */
    private static ShadowActivity.IntentForResult tapExport(HomeActivity a) {
        Shots.click(a, BackupPage.OLD_GUIDE_BUTTON);
        ShadowActivity.IntentForResult request = shadowOf(a).getNextStartedActivityForResult();
        assertNotNull(request);
        assertEquals(BackupPage.REQUEST_EXPORT_OLD_GUIDE, request.requestCode);
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.getAction());
        assertEquals("application/json", request.intent.getType());
        assertTrue(request.intent.getCategories().contains(Intent.CATEGORY_OPENABLE));
        assertEquals("P3R_打卡进度.json", request.intent.getStringExtra(Intent.EXTRA_TITLE));
        return request;
    }

    private static String exportedAt(String file) {
        Matcher m = EXPORTED_AT.matcher(file);
        assertTrue(file, m.find());
        return m.group(1);
    }

    /** JSON.stringify(...) of the old page for {@link #COMPLETED} and {@link #SUBSTEPS}. */
    private static String oldPageFile(String exportedAt) {
        return "{\n"
            + "  \"format\": \"p3r_hunterjia_checklist_v1\",\n"
            + "  \"completed\": {\n"
            + "    \"t001_01\": true,\n"
            + "    \"t002_01\": false,\n"
            + "    \"t005_03\": true,\n"
            + "    \"t005_07\": true\n"
            + "  },\n"
            + "  \"completedSubsteps\": {\n"
            + "    \"t005_03::0\": true,\n"
            + "    \"t005_03::1\": true,\n"
            + "    \"t005_03::2\": false\n"
            + "  },\n"
            + "  \"schemaVersion\": 2,\n"
            + "  \"exportedAt\": \"" + exportedAt + "\"\n"
            + "}";
    }

    @Test
    public void withoutATickedTaskThereIsNoEntry() {
        HomeActivity a = backupPage();
        assertFalse(Shots.has(a, BackupPage.OLD_GUIDE_BUTTON));
        assertFalse(Shots.allText(a.getWindow().getDecorView()).contains("逐日攻略"));
        // Looking did not create the prefs.
        assertTrue(oldPrefs().getAll().isEmpty());
        assertFalse(oldPrefsFile().exists());

        // Unticked tasks and ticked substeps only: nothing to carry over either.
        store("{\"t001_01\":false}", "{\"t005_03::0\":true}");
        a = backupPage();
        assertFalse(Shots.has(a, BackupPage.OLD_GUIDE_BUTTON));
        assertNull(OldGuideProgress.read(app()));
    }

    @Test
    public void tickedTasksShowTheEntryWithTheirCount() {
        store(COMPLETED, SUBSTEPS);
        HomeActivity a = backupPage();
        assertTrue(Shots.has(a, "旧版逐日攻略"));
        assertTrue(Shots.has(a, BackupPage.OLD_GUIDE_BUTTON));
        assertTrue(Shots.allText(a.getWindow().getDecorView()), Shots.has(a, SUBTITLE));
        assertEquals(SUBTITLE, BackupPage.oldGuideText(3));
        // A card with a button like the page's other two sections, not a 我的-style row with a chevron
        // (review after 3.1.0: the chevron promised a page, the tap opens the system save dialog).
        View button = Shots.find(a, BackupPage.OLD_GUIDE_BUTTON);
        assertTrue(button.isClickable());
        assertTrue(button instanceof TextView);
        assertEquals(BackupPage.OLD_GUIDE_BUTTON, Zh.plain(((TextView) button).getText()));
        Shots.shot(a, "home_backup_old_guide");
    }

    @Test
    public void exportSavesTheOldPagesFileAndLeavesThePrefsAlone() throws IOException {
        store(COMPLETED, SUBSTEPS);
        Map<String, ?> before = new HashMap<>(oldPrefs().getAll());
        byte[] fileBefore = Files.readAllBytes(oldPrefsFile().toPath());
        HomeActivity a = backupPage();
        ShadowActivity.IntentForResult request = tapExport(a);

        ByteArrayOutputStream saved = new ByteArrayOutputStream();
        shadowOf(app().getContentResolver()).registerOutputStream(DOC, saved);
        long start = System.currentTimeMillis();
        shadowOf(a).receiveResult(request.intent, Activity.RESULT_OK, new Intent().setData(DOC));
        Shots.idle();
        long end = System.currentTimeMillis();

        String file = new String(saved.toByteArray(), StandardCharsets.UTF_8);
        String at = exportedAt(file);
        long millis = Instant.parse(at).toEpochMilli();
        assertTrue(at, millis >= start && millis <= end);
        assertTrue(at, at.matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z"));
        assertEquals(oldPageFile(at), file);
        assertArrayEquals(oldPageFile(at).getBytes(StandardCharsets.UTF_8), saved.toByteArray());
        assertEquals("打勾进度已导出。", a.undoBar().messageText().toString());

        // Read-only: same keys, values and file, so a second export writes the same ticks.
        assertEquals(before, oldPrefs().getAll());
        assertArrayEquals(fileBefore, Files.readAllBytes(oldPrefsFile().toPath()));
        assertTrue(Shots.has(a, BackupPage.OLD_GUIDE_BUTTON));
    }

    @Test
    public void theFileIsWhatJsonStringifyWrites() {
        store(COMPLETED, SUBSTEPS);
        OldGuideProgress progress = OldGuideProgress.read(app());
        assertNotNull(progress);
        assertEquals(3, progress.ticked);
        // new Date(1760430896789).toISOString()
        assertEquals(oldPageFile("2025-10-14T08:34:56.789Z"), progress.toJson(1760430896789L));

        // What JSON.parse then JSON.stringify(…, null, 2) make of odd stored text: array-index keys
        // first, a repeated key keeps its first place and its last value, "\/" unescaped, controls and a
        // lone surrogate escaped, numbers as JavaScript prints them, 1E400 → null, empty {} and [].
        store("{\"t2\":true,\"10\":true,\"2\":false,\"a\\/b\":\"x\\\"y\\n\\u0001\\t中\\ud83d\\ude00\\ud800\","
            + "\"n\":[1.0,1.5,-0,1e21,1e-7,0.000001,123456789012,1E400,[],{}],\"z\":null,\"01\":true,\"t2\":false}", "{}");
        progress = OldGuideProgress.read(app());
        assertNotNull(progress);
        assertEquals(2, progress.ticked);
        assertEquals("{\n"
            + "  \"format\": \"p3r_hunterjia_checklist_v1\",\n"
            + "  \"completed\": {\n"
            + "    \"2\": false,\n"
            + "    \"10\": true,\n"
            + "    \"t2\": false,\n"
            + "    \"a/b\": \"x\\\"y\\n\\u0001\\t中\uD83D\uDE00\\ud800\",\n"
            + "    \"n\": [\n"
            + "      1,\n"
            + "      1.5,\n"
            + "      0,\n"
            + "      1e+21,\n"
            + "      1e-7,\n"
            + "      0.000001,\n"
            + "      123456789012,\n"
            + "      null,\n"
            + "      [],\n"
            + "      {}\n"
            + "    ],\n"
            + "    \"z\": null,\n"
            + "    \"01\": true\n"
            + "  },\n"
            + "  \"completedSubsteps\": {},\n"
            + "  \"schemaVersion\": 2,\n"
            + "  \"exportedAt\": \"1970-01-01T00:00:00.000Z\"\n"
            + "}", progress.toJson(0L));
        assertEquals("-2.5e-7", OldGuideProgress.number(-2.5e-7));
        assertEquals("100000000000000000000", OldGuideProgress.number(1e20));
        assertEquals("1.2345e+22", OldGuideProgress.number(1.2345e22));
    }

    @Test
    public void brokenStoredTextNeverCrashes() throws IOException {
        // Not a JSON object: no ticked task, so no entry.
        for (String broken : new String[]{"{\"t001_01\":true", "[true]", "true", "", "{'t001_01':true}",
            "{\"t001_01\":true,}", "{\"t001_01\":tru}", "{\"t001_01\":true} x"}) {
            store(broken, SUBSTEPS);
            assertNull(broken, OldGuideProgress.read(app()));
        }
        assertTrue(oldPrefs().edit().clear().putInt("completed", 3).commit());
        assertNull(OldGuideProgress.read(app()));
        assertTrue(oldPrefs().edit().clear().commit());
        store("{\"t001_01\":true", SUBSTEPS);
        HomeActivity a = backupPage();
        assertFalse(Shots.has(a, BackupPage.OLD_GUIDE_BUTTON));

        // Broken substeps: the ticked tasks are still exported, with completedSubsteps {}.
        store("{\"t001_01\":true}", "{\"t005_03::0\":tr");
        Map<String, ?> before = new HashMap<>(oldPrefs().getAll());
        a = backupPage();
        assertTrue(Shots.has(a, BackupPage.oldGuideText(1)));
        ShadowActivity.IntentForResult request = tapExport(a);
        ByteArrayOutputStream saved = new ByteArrayOutputStream();
        shadowOf(app().getContentResolver()).registerOutputStream(DOC, saved);
        shadowOf(a).receiveResult(request.intent, Activity.RESULT_OK, new Intent().setData(DOC));
        Shots.idle();
        String file = new String(saved.toByteArray(), StandardCharsets.UTF_8);
        assertEquals("{\n"
            + "  \"format\": \"p3r_hunterjia_checklist_v1\",\n"
            + "  \"completed\": {\n"
            + "    \"t001_01\": true\n"
            + "  },\n"
            + "  \"completedSubsteps\": {},\n"
            + "  \"schemaVersion\": 2,\n"
            + "  \"exportedAt\": \"" + exportedAt(file) + "\"\n"
            + "}", file);
        assertEquals(before, oldPrefs().getAll());
    }

    @Test
    public void aFailedSaveSaysSoAndACancelWritesNothing() {
        store(COMPLETED, SUBSTEPS);
        Map<String, ?> before = new HashMap<>(oldPrefs().getAll());
        HomeActivity a = backupPage();

        ShadowActivity.IntentForResult request = tapExport(a);
        ByteArrayOutputStream untouched = new ByteArrayOutputStream();
        shadowOf(app().getContentResolver()).registerOutputStream(DOC, untouched);
        shadowOf(a).receiveResult(request.intent, Activity.RESULT_CANCELED, null);
        Shots.idle();
        assertEquals(0, untouched.size());
        assertFalse(a.undoBar().isShowing());

        request = tapExport(a);
        shadowOf(app().getContentResolver()).registerOutputStream(DOC, new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("disk full");
            }
        });
        shadowOf(a).receiveResult(request.intent, Activity.RESULT_OK, new Intent().setData(DOC));
        Shots.idle();
        assertEquals(BackupPage.OLD_GUIDE_FAILED, a.undoBar().messageText().toString());
        assertEquals(before, oldPrefs().getAll());
    }

    /**
     * Review after 3.1.0: ticks gone by the time the picker returns said 「导出失败，请换个位置再试。」, which
     * another place cannot fix. Nothing is written and the message says the ticks are gone.
     */
    @Test
    public void ticksGoneBeforeTheSaveSaySo() {
        store(COMPLETED, SUBSTEPS);
        HomeActivity a = backupPage();
        ShadowActivity.IntentForResult request = tapExport(a);
        assertTrue(oldPrefs().edit().clear().commit());
        ByteArrayOutputStream saved = new ByteArrayOutputStream();
        shadowOf(app().getContentResolver()).registerOutputStream(DOC, saved);
        shadowOf(a).receiveResult(request.intent, Activity.RESULT_OK, new Intent().setData(DOC));
        Shots.idle();
        assertEquals(0, saved.size());
        assertEquals(BackupPage.OLD_GUIDE_GONE, a.undoBar().messageText().toString());
    }

    /**
     * Review after 3.1.0: a failed write left an empty P3R_打卡进度.json where the picker created it. The
     * document goes when it is empty, and stays when it has bytes or its size is unknown (a file picked to
     * overwrite that could not be opened keeps its contents).
     */
    @Test
    public void anEmptyDocumentLeftByAFailedSaveIsRemoved() {
        FakeDocuments docs = Robolectric.setupContentProvider(FakeDocuments.class, FakeDocuments.AUTHORITY);
        Uri doc = Uri.parse("content://" + FakeDocuments.AUTHORITY + "/document/P3R");
        docs.size = 0L;
        assertTrue(BackupPage.discardIfEmpty(app().getContentResolver(), doc));
        assertEquals("android:deleteDocument", docs.called);
        for (Long size : new Long[]{12L, null}) {
            docs.called = null;
            docs.size = size;
            assertFalse(String.valueOf(size), BackupPage.discardIfEmpty(app().getContentResolver(), doc));
            assertNull(docs.called);
        }
        // No provider answers for the picker's usual authority here: nothing is deleted, nothing throws.
        assertFalse(BackupPage.discardIfEmpty(app().getContentResolver(), DOC));
    }

    /** A documents provider that reports one document's size and records a delete. */
    public static final class FakeDocuments extends ContentProvider {
        static final String AUTHORITY = "app.p3r.planner.test.documents";
        Long size;
        String called;

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.SIZE});
            cursor.addRow(new Object[]{size});
            return cursor;
        }

        @Override
        public Bundle call(String method, String arg, Bundle extras) {
            called = method;
            return new Bundle();
        }

        @Override
        public String getType(Uri uri) {
            return "application/json";
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return null;
        }

        @Override
        public int delete(Uri uri, String selection, String[] args) {
            return 0;
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] args) {
            return 0;
        }
    }
}
