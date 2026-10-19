package app.p3r.dayplanner.v3.store;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Deterministic JSON text: object keys sorted, no whitespace. Used for every payload the store
 * writes, so equal values always produce equal text (idempotency checks, byte-stable backups).
 */
public final class Canonical {
    private Canonical() {}

    /** Canonical text of a JSON value ({@link JSONObject}, {@link JSONArray}, String, number, boolean, null). */
    public static String of(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    /** Re-serialises JSON text canonically. */
    public static String normalize(String json) throws JSONException {
        return of(new JSONObject(json));
    }

    /** Lower-case hex SHA-256 of the UTF-8 bytes of {@code text}. */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null || value == JSONObject.NULL) {
            out.append("null");
        } else if (value instanceof JSONObject) {
            writeObject(out, (JSONObject) value);
        } else if (value instanceof JSONArray) {
            writeArray(out, (JSONArray) value);
        } else if (value instanceof String) {
            out.append(JSONObject.quote((String) value));
        } else if (value instanceof Boolean) {
            out.append(value.toString());
        } else if (value instanceof Number) {
            writeNumber(out, (Number) value);
        } else {
            throw new IllegalArgumentException("not a JSON value: " + value.getClass().getName());
        }
    }

    private static void writeObject(StringBuilder out, JSONObject object) {
        List<String> keys = new ArrayList<>();
        Iterator<String> it = object.keys();
        while (it.hasNext()) {
            keys.add(it.next());
        }
        Collections.sort(keys);
        out.append('{');
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            String key = keys.get(i);
            out.append(JSONObject.quote(key)).append(':');
            write(out, object.opt(key));
        }
        out.append('}');
    }

    private static void writeArray(StringBuilder out, JSONArray array) {
        out.append('[');
        for (int i = 0; i < array.length(); i++) {
            if (i > 0) {
                out.append(',');
            }
            write(out, array.opt(i));
        }
        out.append(']');
    }

    private static void writeNumber(StringBuilder out, Number number) {
        if (number instanceof Double || number instanceof Float) {
            double d = number.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("non-finite number");
            }
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                out.append((long) d);
            } else {
                out.append(d);
            }
        } else {
            out.append(number.longValue());
        }
    }
}
