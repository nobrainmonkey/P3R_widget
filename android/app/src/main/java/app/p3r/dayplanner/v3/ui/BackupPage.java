package app.p3r.dayplanner.v3.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import app.p3r.dayplanner.R;

/**
 * 备份与恢复: the backup card ([分享备份] as text through the share sheet, [保存备份文件] as a .json
 * file through the system picker), the restore card ([粘贴文字] — straight to the confirmation when
 * the clipboard holds a backup — and [选择备份文件]), and the restore itself after a destructive
 * confirmation.
 *
 * <p>When the 3.0.x 逐日攻略 left ticked tasks on this phone, a last card 「旧版逐日攻略」 offers
 * 「导出打勾进度」: the old page's progress file, saved through the system file picker
 * ({@link OldGuideProgress}; read-only, the prefs are never changed). Like the other two it is a card
 * with a button, not a row with a chevron (a chevron opens a page on 我的).
 */
final class BackupPage {
    /** Request code for ACTION_OPEN_DOCUMENT, handled by {@link HomeActivity#onActivityResult}. */
    static final int REQUEST_IMPORT = 3101;
    /**
     * Request code of every ACTION_CREATE_DOCUMENT here (the backup file and the old guide's ticks),
     * handled by {@link HomeActivity#onActivityResult} through {@link #exportOldGuideTo}; which file it
     * is for is kept in {@link UiPrefs#pendingSave}, so a recreated activity still knows.
     */
    static final int REQUEST_EXPORT_OLD_GUIDE = 3102;
    static final int REQUEST_SAVE = REQUEST_EXPORT_OLD_GUIDE;
    /** {@link UiPrefs#pendingSave} while the picker saves the backup. */
    static final String SAVE_BACKUP = "backup";

    static final String BACKUP_TITLE = "备份";
    static final String BACKUP_TEXT = "进度、设置、全书收藏和合体篮都在里面";
    static final String SHARE = "分享备份";
    static final String SAVE_FILE = "保存备份文件";
    static final String RESTORE_TITLE = "从备份恢复";
    static final String PASTE = "粘贴文字";
    static final String PICK = "选择备份文件";
    static final String PASTE_TITLE = "粘贴备份";
    static final String PASTE_HINT = "把备份粘贴到这里";
    static final String FROM_CLIPBOARD = "从剪贴板粘贴";
    static final String RESTORE = "恢复";
    static final String CONFIRM_TITLE = "用这份备份替换现在的记录？";
    static final String CONFIRM_BODY = "现在的目标、设置和记录都会被换掉，换了就回不去了。";
    static final String CONFIRM = "替换";

    static final String SAVED = "备份保存好了。";
    static final String RESTORED = "恢复好了！";
    static final String ALREADY_RESTORED = "这份备份已经恢复过了。";
    static final String NOT_A_BACKUP = "这不像是 P3R 规划的备份，换一份试试？";
    static final String UNREADABLE = "读不出这个文件，选的是备份吗？";
    static final String EMPTY_PASTE = "先把备份粘贴进来。";
    static final String EXPORT_FAILED = "没导出成功，再试一次？";
    static final String NO_RECEIVER = "手机上没有能接收备份的应用。";
    static final String NO_SAVER = "手机上没有能保存文件的应用。";
    static final String NO_PICKER = "打不开文件选择器，试试「粘贴文字」？";

    static final String OLD_GUIDE_SECTION = "旧版逐日攻略";
    static final String OLD_GUIDE_BUTTON = "导出打勾进度";
    static final String OLD_GUIDE_DONE = "打勾进度已导出。";
    static final String OLD_GUIDE_GONE = "手机上已经没有旧逐日攻略的打勾进度了。";
    static final String OLD_GUIDE_FAILED = "没导出成功，换个位置试试？";
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private BackupPage() {}

    static View build(HomeHost host) {
        Context c = host.activity();
        LinearLayout column = Ui.column(c);

        LinearLayout backup = Ui.column(c);
        backup.setBackground(Ui.heroCard(c));
        Ui.pad(backup, 22, 16, 18, 18);
        backup.addView(Ui.title(c, BACKUP_TITLE));
        backup.addView(Ui.text(c, BACKUP_TEXT, 13, Ui.col(c, R.color.p3r_cyan_200), false), top(c, 2));
        TextView save = Ui.primary(c, SAVE_FILE);
        save.setOnClickListener(v -> pickBackupTarget(host));
        backup.addView(save, top(c, 16));
        TextView share = Ui.pad(Ui.quiet(c, SHARE), 6, 8, 14, 8);
        share.setOnClickListener(v -> exportBackup(host));
        backup.addView(share, wrapTop(c, 6));
        column.addView(backup);

        LinearLayout restore = Ui.column(c);
        restore.setBackground(Ui.card(c));
        Ui.pad(restore, 18, 16, 16, 14);
        restore.addView(Ui.title(c, RESTORE_TITLE));
        TextView pick = Ui.secondary(c, PICK);
        pick.setOnClickListener(v -> pickFile(host));
        restore.addView(pick, top(c, 14));
        TextView paste = Ui.pad(Ui.quiet(c, PASTE), 6, 8, 14, 8);
        paste.setOnClickListener(v -> pasteOrAsk(host));
        restore.addView(paste, wrapTop(c, 6));
        column.addView(restore, top(c, 14));

        // Shown only once the prefs are read (off the main thread) and hold a ticked task.
        LinearLayout oldGuide = Ui.column(c);
        oldGuide.setVisibility(View.GONE);
        column.addView(oldGuide, top(c, 14));
        Context app = c.getApplicationContext();
        Async.run(host.activity(), () -> OldGuideProgress.read(app), progress -> {
            if (progress != null) {
                showOldGuide(host, oldGuide, progress.ticked);
            }
        }, null);
        return column;
    }

    private static void showOldGuide(HomeHost host, LinearLayout box, int ticked) {
        Context c = host.activity();
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.card(c));
        Ui.pad(card, 18, 16, 16, 16);
        card.addView(Ui.headline(c, OLD_GUIDE_SECTION));
        TextView text = Ui.mixed(c, oldGuideText(ticked), 14, Ui.col(c, R.color.p3r_ink_muted), false);
        text.setLineSpacing(0f, 1.25f);
        card.addView(text, top(c, 4));
        TextView export = Ui.secondary(c, OLD_GUIDE_BUTTON);
        export.setOnClickListener(v -> pickOldGuideTarget(host));
        card.addView(export, top(c, 14));
        box.addView(card);
        box.setVisibility(View.VISIBLE);
    }

    /** The old guide card's text for {@code ticked} ticked tasks. */
    static String oldGuideText(int ticked) {
        return "旧版逐日攻略里还有 " + ticked + " 项打过勾，导出后能在 2.0.0「P3R」里接着用。";
    }

    /** 「P3R 规划备份 2026-10-19.json」: the backup file, named after the day it was made. */
    static String backupFileName(LocalDate today) {
        return "P3R 规划备份 " + today + ".json";
    }

    /** [保存备份文件]: asks where to save the backup as a .json file. */
    private static void pickBackupTarget(HomeHost host) {
        startSave(host, backupFileName(LocalDate.now()), SAVE_BACKUP);
    }

    /** Asks where to save the old guide's progress, under the old page's file name. */
    private static void pickOldGuideTarget(HomeHost host) {
        startSave(host, OldGuideProgress.FILE_NAME, null);
    }

    private static void startSave(HomeHost host, String fileName, String kind) {
        Context app = host.activity().getApplicationContext();
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        save.addCategory(Intent.CATEGORY_OPENABLE);
        save.setType("application/json");
        save.putExtra(Intent.EXTRA_TITLE, fileName);
        UiPrefs.setPendingSave(app, kind);
        try {
            host.activity().startActivityForResult(save, REQUEST_SAVE);
        } catch (ActivityNotFoundException e) {
            UiPrefs.setPendingSave(app, null);
            host.showError(NO_SAVER);
        }
    }

    /**
     * Writes the file picked for {@link #REQUEST_SAVE}: the backup when that is what was asked for
     * ({@link UiPrefs#pendingSave}), else the old guide's progress. For the old guide the prefs are
     * read again here, so this also works in an activity recreated meanwhile. When there is nothing to
     * write any more, or the write fails, the document the picker created is removed if it is still
     * empty ({@link #discardIfEmpty}), and the message says which it was (a new place does not help
     * when the ticks are gone).
     */
    static void exportOldGuideTo(HomeHost host, Uri uri) {
        Activity activity = host.activity();
        Context app = activity.getApplicationContext();
        boolean backup = SAVE_BACKUP.equals(UiPrefs.pendingSave(app));
        UiPrefs.setPendingSave(app, null);
        if (backup) {
            saveBackupTo(host, uri);
            return;
        }
        ContentResolver resolver = app.getContentResolver();
        Async.run(activity, () -> {
            OldGuideProgress progress = OldGuideProgress.read(app);
            if (progress == null) {
                discardIfEmpty(resolver, uri);
                return Boolean.FALSE;
            }
            write(resolver, uri, progress.toJson(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
            return Boolean.TRUE;
        }, written -> host.showMessage(written ? OLD_GUIDE_DONE : OLD_GUIDE_GONE),
            error -> host.showError(OLD_GUIDE_FAILED));
    }

    /** Writes the backup JSON into the picked document. */
    private static void saveBackupTo(HomeHost host, Uri uri) {
        Activity activity = host.activity();
        ContentResolver resolver = activity.getApplicationContext().getContentResolver();
        Async.run(activity, () -> {
            write(resolver, uri, Api.get(activity).exportBackup().getBytes(StandardCharsets.UTF_8));
            return Boolean.TRUE;
        }, done -> host.showMessage(SAVED), error -> host.showError(OLD_GUIDE_FAILED));
    }

    /** A truncating write of {@code bytes}; an empty document left by a failure is removed. */
    private static void write(ContentResolver resolver, Uri uri, byte[] bytes) throws IOException {
        try (OutputStream out = openForWrite(resolver, uri)) {
            out.write(bytes);
        } catch (IOException | RuntimeException e) {
            discardIfEmpty(resolver, uri);
            throw e;
        }
    }

    /**
     * Best effort: deletes the document at {@code uri} when it holds no bytes (the empty file
     * ACTION_CREATE_DOCUMENT made, or one a failed truncating write left empty). A document whose size
     * is unknown or not zero stays, so a file picked to overwrite that could not even be opened keeps
     * its contents. Returns whether it was deleted.
     */
    static boolean discardIfEmpty(ContentResolver resolver, Uri uri) {
        try (android.database.Cursor cursor = resolver.query(uri, new String[]{android.provider.OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor == null || !cursor.moveToFirst() || cursor.isNull(0) || cursor.getLong(0) != 0) {
                return false;
            }
        } catch (RuntimeException e) {
            return false;
        }
        try {
            return android.provider.DocumentsContract.deleteDocument(resolver, uri);
        } catch (FileNotFoundException | RuntimeException e) {
            return false;
        }
    }

    /** A truncating write ("wt": a file picked to overwrite loses its old tail), else plain "w". */
    private static OutputStream openForWrite(ContentResolver resolver, Uri uri) throws IOException {
        try {
            OutputStream out = resolver.openOutputStream(uri, "wt");
            if (out != null) {
                return out;
            }
        } catch (IllegalArgumentException | UnsupportedOperationException | FileNotFoundException e) {
            // A provider that knows only "w"; a document it has just created is empty anyway.
        }
        OutputStream out = resolver.openOutputStream(uri, "w");
        if (out == null) {
            throw new IOException("no stream");
        }
        return out;
    }

    private static LinearLayout.LayoutParams top(Context c, int dp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, dp);
        return p;
    }

    private static LinearLayout.LayoutParams wrapTop(Context c, int dp) {
        LinearLayout.LayoutParams p = Ui.wrap();
        p.topMargin = Ui.dp(c, dp);
        return p;
    }

    /** [分享备份]: the backup as text through the share sheet. */
    private static void exportBackup(HomeHost host) {
        Activity activity = host.activity();
        Async.run(activity, () -> Api.get(activity).exportBackup(), json -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "P3R 规划备份");
            send.putExtra(Intent.EXTRA_TEXT, json);
            try {
                activity.startActivity(Intent.createChooser(send, "保存备份到…"));
            } catch (ActivityNotFoundException e) {
                host.showError(NO_RECEIVER);
            }
        }, error -> host.showError(EXPORT_FAILED));
    }

    private static void pickFile(HomeHost host) {
        Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        open.addCategory(Intent.CATEGORY_OPENABLE);
        open.setType("*/*");
        open.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        try {
            host.activity().startActivityForResult(open, REQUEST_IMPORT);
        } catch (ActivityNotFoundException e) {
            // No document picker on this device: the paste path still works.
            host.showError(NO_PICKER);
        }
    }

    /** The clipboard's text when it looks like a backup (a JSON object), else null. */
    static String clipboardBackup(Context c) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData data = cm == null ? null : cm.getPrimaryClip();
        if (data == null || data.getItemCount() == 0) {
            return null;
        }
        CharSequence text = data.getItemAt(0).coerceToText(c);
        String json = text == null ? "" : text.toString().trim();
        return json.startsWith("{") ? json : null;
    }

    /** [粘贴文字]: a backup on the clipboard goes straight to the confirmation, else the paste sheet. */
    private static void pasteOrAsk(HomeHost host) {
        String json = clipboardBackup(host.activity());
        if (json != null) {
            confirmImport(host, json);
        } else {
            openPasteSheet(host);
        }
    }

    private static void openPasteSheet(HomeHost host) {
        Context c = host.activity();
        LinearLayout sheet = Ui.column(c);
        Ui.pad(sheet, 20, 12, 20, 20);
        sheet.addView(SheetChrome.title(c, PASTE_TITLE));
        EditText field = new EditText(c);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setMinLines(5);
        field.setMaxLines(8);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setTextColor(Ui.col(c, R.color.p3r_white));
        field.setHintTextColor(Ui.col(c, R.color.p3r_ink_faint));
        field.setHint(PASTE_HINT);
        field.setTextSize(13);
        field.setBackground(new SlantedPanelDrawable()
            .fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 170))
            .stroke(Ui.col(c, R.color.p3r_line), Ui.dp(c, 1)));
        Ui.pad(field, 12, 10, 12, 10);
        sheet.addView(field, top(c, 10));
        LinearLayout buttons = Ui.row(c);
        buttons.setBaselineAligned(false);
        TextView clip = Ui.quiet(c, FROM_CLIPBOARD);
        clip.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData data = cm == null ? null : cm.getPrimaryClip();
            if (data != null && data.getItemCount() > 0) {
                CharSequence text = data.getItemAt(0).coerceToText(c);
                field.setText(text);
            }
        });
        buttons.addView(clip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        TextView restore = Ui.primary(c, RESTORE);
        restore.setOnClickListener(v -> {
            String json = field.getText().toString().trim();
            if (json.isEmpty()) {
                host.showMessage(EMPTY_PASTE);
                return;
            }
            // One question at a time: the sheet goes before the confirmation comes.
            host.dismissSheet();
            confirmImport(host, json);
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        rp.leftMargin = Ui.dp(c, 12);
        buttons.addView(restore, rp);
        sheet.addView(buttons, top(c, 14));
        host.showSheet(sheet);
    }

    /** Reads a picked document off the main thread, then asks for confirmation. */
    static void importFromUri(HomeHost host, Uri uri) {
        Activity activity = host.activity();
        Async.run(activity, () -> readText(activity, uri), json -> confirmImport(host, json),
            error -> host.showError(UNREADABLE));
    }

    private static String readText(Context context, Uri uri) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("no stream");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_BYTES) {
                    throw new IOException("too large");
                }
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    static void confirmImport(HomeHost host, String json) {
        Activity activity = host.activity();
        ConfirmDialog.showDestructive(activity, CONFIRM_TITLE, CONFIRM_BODY, CONFIRM, () -> {
            // Unique per confirmed restore of this state: a retry of the same tap stays idempotent,
            // while restoring the same file again after any later record really replaces the data.
            UiData d = host.data();
            long stateRev = d == null || d.snap == null ? -1 : d.snap.stateRevision;
            long profileRev = d == null || d.snap == null ? -1 : d.snap.profileRevision;
            String id = CommandIds.of("import", stateRev, profileRev, json.length(), json.hashCode());
            Async.run(activity, () -> Api.get(activity).importBackup(id, json), receipt -> {
                host.dismissSheet();
                if (receipt.ok) {
                    host.showMessage(receipt.duplicate ? ALREADY_RESTORED : RESTORED);
                } else {
                    host.showError(backupError(receipt));
                }
                host.reload();
            }, error -> host.showError(NOT_A_BACKUP));
        });
    }

    private static String backupError(app.p3r.dayplanner.v3.service.PlannerApi.Receipt receipt) {
        if ("invalid".equals(receipt.errorCode)) {
            return NOT_A_BACKUP;
        }
        return Messages.receiptError(receipt);
    }
}
