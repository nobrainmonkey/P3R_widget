package app.p3r.dayplanner.v3.ui;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic command ids for planner writes. The same tap on the same rendered state always
 * yields the same id, so double taps, retries and process restarts are applied once by the store
 * (idempotency per {@code commandId}, docs/DESIGN_v3.md §6).
 */
public final class CommandIds {
    private CommandIds() {}

    /** "ui-" + the first 24 hex digits of SHA-1 over the parts joined with '|'. */
    public static String of(Object... parts) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                joined.append('|');
            }
            joined.append(parts[i]);
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha.digest(joined.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("ui-");
            for (int i = 0; i < 12; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
