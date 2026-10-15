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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import app.p3r.dayplanner.R;

/**
 * 备份与恢复: export everything (profile, checkpoints, records) as JSON through the share sheet,
 * and restore from pasted text or a picked file after an explicit confirmation.
 *
 * <p>When the 3.0.x 逐日攻略 left ticked tasks on this phone, a last section 「旧版逐日攻略」 offers
 * 「导出打勾进度」: the old page's progress file, saved through the system file picker
 * ({@link OldGuideProgress}; read-only, the prefs are never changed). Like the other two sections it is
 * a card with a button, not a row with a chevron (a chevron opens a page on 我的).
 */
final class BackupPage {
    /** Request code for ACTION_OPEN_DOCUMENT, handled by {@link HomeActivity#onActivityResult}. */
    static final int REQUEST_IMPORT = 3101;
    /** Request code for the old guide's ACTION_CREATE_DOCUMENT, handled by {@link HomeActivity#onActivityResult}. */
    static final int REQUEST_EXPORT_OLD_GUIDE = 3102;
    static final String OLD_GUIDE_SECTION = "旧版逐日攻略";
    static final String OLD_GUIDE_BUTTON = "导出打勾进度";
    static final String OLD_GUIDE_DONE = "打勾进度已导出。";
    static final String OLD_GUIDE_GONE = "手机上已经没有旧逐日攻略的打勾进度了。";
    static final String OLD_GUIDE_FAILED = "导出失败，请换个位置再试。";
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private BackupPage() {}

    static View build(HomeHost host) {
        Context c = host.activity();
        LinearLayout column = Ui.column(c);

        column.addView(Ui.sectionHeader(c, "导出备份", null));
        LinearLayout export = card(c, "把目标、设置和全部记录导出成一段文字，发到聊天或存到文件里都可以。");
        TextView share = Ui.primary(c, "分享备份");
        share.setOnClickListener(v -> exportBackup(host));
        export.addView(share, top(c, 12));
        column.addView(export, top(c, 4));

        column.addView(Ui.sectionHeader(c, "从备份恢复", null), top(c, 20));
        LinearLayout restore = card(c, "恢复会替换现在的目标、设置和全部记录。建议先分享一份当前的备份。");
        LinearLayout buttons = Ui.row(c);
        TextView paste = Ui.secondary(c, "粘贴文字");
        paste.setOnClickListener(v -> openPasteSheet(host));
        buttons.addView(paste, Ui.weight(1));
        TextView pick = Ui.secondary(c, "选择文件");
        pick.setOnClickListener(v -> pickFile(host.activity()));
        LinearLayout.LayoutParams pp = Ui.weight(1);
        pp.leftMargin = Ui.dp(c, 12);
        buttons.addView(pick, pp);
        restore.addView(buttons, top(c, 12));
        column.addView(restore, top(c, 4));

        // Shown only once the prefs are read (off the main thread) and hold a ticked task.
        LinearLayout oldGuide = Ui.column(c);
        oldGuide.setVisibility(View.GONE);
        column.addView(oldGuide, top(c, 20));
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
        box.addView(Ui.sectionHeader(c, OLD_GUIDE_SECTION, null));
        LinearLayout card = card(c, oldGuideText(ticked));
        TextView export = Ui.secondary(c, OLD_GUIDE_BUTTON);
        export.setOnClickListener(v -> pickOldGuideTarget(host));
        card.addView(export, top(c, 12));
        box.addView(card, top(c, 4));
        box.setVisibility(View.VISIBLE);
    }

    /** The old guide section's text for {@code ticked} ticked tasks. */
    static String oldGuideText(int ticked) {
        return "3.0.x 逐日攻略里打过勾的 " + ticked + " 项还在手机上，导出后可以在旧版 2.0.0「P3R」里导入。";
    }

    /** Asks where to save the old guide's progress, under the old page's file name. */
    private static void pickOldGuideTarget(HomeHost host) {
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        save.addCategory(Intent.CATEGORY_OPENABLE);
        save.setType("application/json");
        save.putExtra(Intent.EXTRA_TITLE, OldGuideProgress.FILE_NAME);
        try {
            host.activity().startActivityForResult(save, REQUEST_EXPORT_OLD_GUIDE);
        } catch (ActivityNotFoundException e) {
            host.showMessage("没有可以保存文件的应用。");
        }
    }

    /**
     * Writes the old guide's progress to the document picked for {@link #REQUEST_EXPORT_OLD_GUIDE}.
     * The prefs are read again here, so this also works in an activity recreated meanwhile. When there
     * is nothing to write any more, or the write fails, the document the picker created is removed if it
     * is still empty ({@link #discardIfEmpty}), and the message says which it was (a new place does not
     * help when the ticks are gone).
     */
    static void exportOldGuideTo(HomeHost host, Uri uri) {
        Activity activity = host.activity();
        Context app = activity.getApplicationContext();
        ContentResolver resolver = app.getContentResolver();
        Async.run(activity, () -> {
            OldGuideProgress progress = OldGuideProgress.read(app);
            if (progress == null) {
                discardIfEmpty(resolver, uri);
                return Boolean.FALSE;
            }
            byte[] bytes = progress.toJson(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = openForWrite(resolver, uri)) {
                out.write(bytes);
            } catch (IOException | RuntimeException e) {
                discardIfEmpty(resolver, uri);
                throw e;
            }
            return Boolean.TRUE;
        }, written -> host.showMessage(written ? OLD_GUIDE_DONE : OLD_GUIDE_GONE),
            error -> host.showMessage(OLD_GUIDE_FAILED));
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

    private static LinearLayout card(Context c, String text) {
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.card(c));
        Ui.pad(card, 16, 14, 16, 16);
        TextView body = Ui.body(c, text);
        body.setLineSpacing(0f, 1.3f);
        card.addView(body);
        return card;
    }

    private static LinearLayout.LayoutParams top(Context c, int dp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, dp);
        return p;
    }

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
                host.showMessage("没有可以接收备份的应用。");
            }
        }, error -> host.showMessage("导出失败，请稍后再试。"));
    }

    private static void pickFile(Activity activity) {
        Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        open.addCategory(Intent.CATEGORY_OPENABLE);
        open.setType("*/*");
        open.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        try {
            activity.startActivityForResult(open, REQUEST_IMPORT);
        } catch (ActivityNotFoundException e) {
            // No document picker on this device; the paste path still works.
        }
    }

    private static void openPasteSheet(HomeHost host) {
        Context c = host.activity();
        LinearLayout sheet = Ui.column(c);
        Ui.pad(sheet, 20, 12, 20, 20);
        sheet.addView(Ui.title(c, "粘贴备份"));
        sheet.addView(Ui.muted(c, "把之前分享出来的整段文字粘贴到下面。"));
        EditText field = new EditText(c);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setMinLines(5);
        field.setMaxLines(8);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setTextColor(Ui.col(c, R.color.p3r_white));
        field.setHintTextColor(Ui.col(c, R.color.p3r_ink_faint));
        field.setHint("备份内容");
        field.setTextSize(13);
        field.setBackground(new SlantedPanelDrawable()
            .fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 170))
            .stroke(Ui.col(c, R.color.p3r_line), Ui.dp(c, 1)));
        Ui.pad(field, 12, 10, 12, 10);
        sheet.addView(field, top(c, 12));
        LinearLayout buttons = Ui.row(c);
        TextView clip = Ui.secondary(c, "从剪贴板粘贴");
        clip.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData data = cm == null ? null : cm.getPrimaryClip();
            if (data != null && data.getItemCount() > 0) {
                CharSequence text = data.getItemAt(0).coerceToText(c);
                field.setText(text);
            }
        });
        buttons.addView(clip, Ui.weight(1));
        TextView restore = Ui.primary(c, "恢复");
        restore.setOnClickListener(v -> {
            String json = field.getText().toString().trim();
            if (json.isEmpty()) {
                host.showMessage("先粘贴备份内容。");
                return;
            }
            confirmImport(host, json);
        });
        LinearLayout.LayoutParams rp = Ui.weight(1);
        rp.leftMargin = Ui.dp(c, 12);
        buttons.addView(restore, rp);
        sheet.addView(buttons, top(c, 16));
        host.showSheet(sheet);
    }

    /** Reads a picked document off the main thread, then asks for confirmation. */
    static void importFromUri(HomeHost host, Uri uri) {
        Activity activity = host.activity();
        Async.run(activity, () -> readText(activity, uri), json -> confirmImport(host, json),
            error -> host.showMessage("读取文件失败，请确认选的是备份文件。"));
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
        ConfirmDialog.show(activity, "用这份备份替换现在的记录？",
            "现在的目标、设置和全部记录都会被替换，替换后无法撤销。", "替换", () -> {
                // Unique per confirmed restore of this state: a retry of the same tap stays idempotent,
                // while restoring the same file again after any later record really replaces the data.
                UiData d = host.data();
                long stateRev = d == null || d.snap == null ? -1 : d.snap.stateRevision;
                long profileRev = d == null || d.snap == null ? -1 : d.snap.profileRevision;
                String id = CommandIds.of("import", stateRev, profileRev, json.length(), json.hashCode());
                Async.run(activity, () -> Api.get(activity).importBackup(id, json), receipt -> {
                    host.dismissSheet();
                    if (receipt.ok) {
                        host.showMessage(receipt.duplicate ? "这份备份已经恢复过了，记录没有变化。" : "已从备份恢复。");
                    } else {
                        host.showMessage(backupError(receipt));
                    }
                    host.reload();
                }, error -> host.showMessage("恢复失败：备份内容无法识别。"));
            });
    }

    private static String backupError(app.p3r.dayplanner.v3.service.PlannerApi.Receipt receipt) {
        if ("invalid".equals(receipt.errorCode)) {
            return "恢复失败：备份内容无法识别。";
        }
        return Messages.receiptError(receipt);
    }
}
