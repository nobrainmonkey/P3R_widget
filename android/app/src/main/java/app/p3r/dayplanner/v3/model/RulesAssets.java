package app.p3r.dayplanner.v3.model;

import android.content.Context;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Process-wide access to the compiled rule pack {@code assets/p3r/p3r_rules.json}.
 *
 * <p>The first {@link #get(Context)} reads and parses the asset (about 130 KB; disk I/O plus JSON
 * parsing), so call it from a background thread, e.g. the planner service executor. Later calls
 * return the same cached {@link Rules} instance without any work; {@link #peek()} never loads and
 * is safe on the main thread (widget rendering should use its own cached projection anyway).
 *
 * <p>The returned model is shared: treat it as read-only.
 */
public final class RulesAssets {
    /** Asset path of the compiled pack. */
    public static final String ASSET_PATH = "p3r/p3r_rules.json";

    private static final String TAG = "P3rRules";
    private static final Object LOCK = new Object();
    private static volatile Rules cached;

    private RulesAssets() {}

    /**
     * The parsed pack, loading it on first use (thread-safe; the asset is read at most once per
     * process).
     *
     * @throws IllegalStateException if the asset cannot be read
     * @throws IllegalArgumentException if the asset is not a valid pack (a build defect)
     */
    public static Rules get(Context context) {
        Rules rules = cached;
        if (rules != null) return rules;
        synchronized (LOCK) {
            if (cached == null) {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    Log.w(TAG, "Loading " + ASSET_PATH + " on the main thread; call RulesAssets.get() from a background thread");
                }
                Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
                try (InputStream in = app.getAssets().open(ASSET_PATH)) {
                    cached = load(in);
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot read asset " + ASSET_PATH, e);
                }
            }
            return cached;
        }
    }

    /** The cached pack, or null when {@link #get(Context)} has not completed yet. Never does I/O. */
    public static Rules peek() {
        return cached;
    }

    /** Read a whole pack from a stream (UTF-8) and parse it. Does not touch the cache. */
    static Rules load(InputStream in) throws IOException {
        return RulesLoader.parse(readUtf8(in));
    }

    /** Read the stream to the end as UTF-8 text. */
    static String readUtf8(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(160 * 1024);
        byte[] buffer = new byte[16 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
