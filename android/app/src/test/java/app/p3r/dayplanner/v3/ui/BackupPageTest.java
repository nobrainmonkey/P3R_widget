package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDialog;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * 备份与恢复 (DESIGN_3.6_UI): the backup card saves a .json file through the picker or shares the text;
 * 「粘贴文字」 goes straight to the confirmation when the clipboard holds a backup; the confirmation is
 * destructive ([先不了] [替换]); the copy says 全书 and 合体篮.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BackupPageTest extends UiTestBase {
    private static final Uri DOC = Uri.parse(
        "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fbackup.json");

    private HomeActivity backupPage() {
        HomeActivity a = home();
        a.selectTab(HomeActivity.TAB_ME);
        Shots.click(a, "备份与恢复");
        return a;
    }

    private static Context app() {
        return RuntimeEnvironment.getApplication();
    }

    @Test
    public void theCardsSayWhatIsInsideInTheGlossarysWords() {
        HomeActivity a = backupPage();
        String text = Shots.allText(a.getWindow().getDecorView());
        assertTrue(text, text.contains(BackupPage.BACKUP_TEXT));
        assertTrue(BackupPage.BACKUP_TEXT.contains("全书") && BackupPage.BACKUP_TEXT.contains("合体篮"));
        assertFalse(text, text.contains("图鉴") || text.contains("合成篮") || text.contains("建议先分享"));
        assertTrue(Shots.has(a, BackupPage.SAVE_FILE));
        assertTrue(Shots.has(a, BackupPage.SHARE));
        assertTrue(Shots.has(a, BackupPage.PICK));
        assertTrue(Shots.has(a, BackupPage.PASTE));
        Shots.shot(a, "home_backup_cards");
    }

    @Test
    public void saveBackupFileWritesTheExportThroughThePicker() {
        assertEquals("P3R 规划备份 2026-10-19.json", BackupPage.backupFileName(LocalDate.of(2026, 10, 19)));
        HomeActivity a = backupPage();
        Shots.click(a, BackupPage.SAVE_FILE);
        ShadowActivity.IntentForResult request = shadowOf(a).getNextStartedActivityForResult();
        assertNotNull(request);
        assertEquals(BackupPage.REQUEST_SAVE, request.requestCode);
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.getAction());
        assertEquals("application/json", request.intent.getType());
        assertTrue(request.intent.getStringExtra(Intent.EXTRA_TITLE).endsWith(".json"));
        assertEquals(BackupPage.SAVE_BACKUP, UiPrefs.pendingSave(app()));

        ByteArrayOutputStream saved = new ByteArrayOutputStream();
        shadowOf(app().getContentResolver()).registerOutputStream(DOC, saved);
        shadowOf(a).receiveResult(request.intent, Activity.RESULT_OK, new Intent().setData(DOC));
        Shots.idle();
        assertEquals(api.backupJson, new String(saved.toByteArray(), StandardCharsets.UTF_8));
        assertEquals(BackupPage.SAVED, a.undoBar().messageText().toString());
        assertNull("the next save is the old guide's again", UiPrefs.pendingSave(app()));
    }

    @Test
    public void aBackupOnTheClipboardGoesStraightToTheConfirmation() {
        HomeActivity a = backupPage();
        ClipboardManager cm = (ClipboardManager) app().getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("backup", "  " + api.backupJson + "\n"));
        Shots.click(a, BackupPage.PASTE);
        assertFalse("no paste sheet", a.sheets().isShowing());
        Dialog confirm = ShadowDialog.getLatestDialog();
        assertTrue(confirm.isShowing());
        Shots.find(confirm.getWindow().getDecorView(), BackupPage.CONFIRM_TITLE);
        Shots.find(confirm.getWindow().getDecorView(), BackupPage.CONFIRM_BODY);
        Shots.shot(a, "home_backup_confirm");
        Shots.clickInDialog(ConfirmDialog.NOT_NOW);
        assertFalse(confirm.isShowing());
        assertTrue("先不了 restores nothing", api.callsOf("importBackup").isEmpty());

        Shots.click(a, BackupPage.PASTE);
        Shots.clickInDialog(BackupPage.CONFIRM);
        assertEquals(1, api.callsOf("importBackup").size());
        assertEquals(BackupPage.RESTORED, a.undoBar().messageText().toString());
    }

    @Test
    public void otherTextOnTheClipboardOpensThePasteSheet() {
        HomeActivity a = backupPage();
        ClipboardManager cm = (ClipboardManager) app().getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("note", "明天去图书馆"));
        Shots.click(a, BackupPage.PASTE);
        assertTrue(a.sheets().isShowing());
        assertFalse("no instruction line", Shots.has(a, "把之前分享出来的整段文字粘贴到下面。"));
        Shots.shot(a, "home_backup_paste_sheet");
        assertNull(BackupPage.clipboardBackup(a));
    }

    @Test
    public void thePasteSheetClosesBeforeTheConfirmation() {
        HomeActivity a = backupPage();
        Shots.click(a, BackupPage.PASTE);
        assertTrue(a.sheets().isShowing());
        android.widget.EditText field = findField(a.sheets());
        field.setText(api.backupJson);
        Shots.click(a, BackupPage.RESTORE);
        assertFalse("one question at a time", a.sheets().isShowing());
        assertTrue(ShadowDialog.getLatestDialog().isShowing());
    }

    private static android.widget.EditText findField(android.view.View v) {
        if (v instanceof android.widget.EditText) {
            return (android.widget.EditText) v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.widget.EditText found = findField(g.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
