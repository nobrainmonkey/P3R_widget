package app.p3r.dayplanner.v3.ui;

import android.content.Context;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The ticks of the 3.0.x 逐日攻略, read-only, for 备份与恢复's 「导出旧逐日攻略的打勾进度」.
 *
 * <p>3.0.x kept the guide's checkboxes in the prefs {@value #PREFS}: {@code "completed"} ({@code
 * {taskId: true}}) and {@code "substeps"} ({@code {"taskId::n": true}}), both JSON strings. Its
 * WebView page mirrored every tick there and its widget wrote there directly, so these prefs are the
 * guide's own copy. 3.1.0 removed the guide but never deletes them (owner decision O2,
 * docs/DESIGN_v3.md §19). This class only reads them: it never opens an editor, so the prefs stay
 * exactly as 3.0.x left them.
 *
 * <p>{@link #toJson} writes the file of the old page's {@code exportProgress()}: {@code
 * JSON.stringify({format, completed, completedSubsteps, schemaVersion: 2, exportedAt}, null, 2)}, the
 * keys in that order, two-space indent, UTF-8, no trailing newline. The 2.0.0 app's guide imports it.
 * Values are written as that call writes them (JavaScript key order, string escapes and numbers), so
 * the file is the one the old page would have saved. Stored text that is not valid JSON counts as
 * empty: no completed task hides the entry, broken substeps are exported as {@code {}}.
 */
final class OldGuideProgress {
    static final String PREFS = "p3r_native_progress_v1";
    /** The old page's download name. */
    static final String FILE_NAME = "P3R_打卡进度.json";
    static final String FORMAT = "p3r_hunterjia_checklist_v1";
    private static final String KEY_COMPLETED = "completed";
    private static final String KEY_SUBSTEPS = "substeps";
    /** JSON {@code null} (a parsed value; Java null means "no value"). */
    private static final Object NULL = new Object();
    /** Deeper nesting than this is not the guide's data: the text counts as broken. */
    private static final int MAX_DEPTH = 64;
    private static final DateTimeFormatter ISO = DateTimeFormatter
        .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    /** {@code "completed"} as stored (ticked and unticked tasks, in stored order). */
    final Map<String, Object> completed;
    /** {@code "substeps"} as stored, or empty when missing or broken. */
    final Map<String, Object> substeps;
    /** Tasks ticked in {@link #completed} (value {@code true}). */
    final int ticked;

    private OldGuideProgress(Map<String, Object> completed, Map<String, Object> substeps, int ticked) {
        this.completed = completed;
        this.substeps = substeps;
        this.ticked = ticked;
    }

    /**
     * The stored progress, or null when it holds no ticked task (no prefs, nothing ticked, or
     * {@code "completed"} is not a JSON object). Reads the prefs file: call it off the main thread.
     */
    static OldGuideProgress read(Context context) {
        // getAll() returns a copy; a value stored with another type is simply not a String here
        // (getString would throw).
        Map<String, ?> all = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll();
        Map<String, Object> completed = object(all.get(KEY_COMPLETED));
        if (completed == null) {
            return null;
        }
        int ticked = 0;
        for (Object value : completed.values()) {
            if (Boolean.TRUE.equals(value)) {
                ticked++;
            }
        }
        if (ticked == 0) {
            return null;
        }
        Map<String, Object> substeps = object(all.get(KEY_SUBSTEPS));
        return new OldGuideProgress(completed, substeps == null ? new LinkedHashMap<>() : substeps, ticked);
    }

    /** The export file's text, {@code exportedAt} = {@code nowMillis} (UTC, as {@code toISOString()}). */
    String toJson(long nowMillis) {
        StringBuilder out = new StringBuilder(256);
        out.append("{\n  \"format\": ");
        quote(out, FORMAT);
        out.append(",\n  \"completed\": ");
        write(out, completed, "  ");
        out.append(",\n  \"completedSubsteps\": ");
        write(out, substeps, "  ");
        out.append(",\n  \"schemaVersion\": 2,\n  \"exportedAt\": ");
        quote(out, ISO.format(Instant.ofEpochMilli(nowMillis)));
        return out.append("\n}").toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object stored) {
        if (!(stored instanceof String)) {
            return null;
        }
        try {
            Object value = new Parser((String) stored).document();
            return value instanceof Map ? (Map<String, Object>) value : null;
        } catch (IllegalArgumentException broken) {
            return null;
        }
    }

    // ------------------------------------------------------------------ JSON.stringify(value, null, 2)

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder out, Object value, String indent) {
        String inner = indent + "  ";
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            boolean first = true;
            for (String key : jsOrder(map)) {
                out.append(first ? "" : ",\n").append(inner);
                quote(out, key);
                out.append(": ");
                write(out, map.get(key), inner);
                first = false;
            }
            out.append('\n').append(indent).append('}');
        } else if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                out.append(i == 0 ? "" : ",\n").append(inner);
                write(out, list.get(i), inner);
            }
            out.append('\n').append(indent).append(']');
        } else if (value instanceof String) {
            quote(out, (String) value);
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Double) {
            out.append(number((Double) value));
        } else {
            out.append("null");
        }
    }

    /** JavaScript's own-key order: array-index keys ascending, then the others as inserted. */
    private static List<String> jsOrder(Map<String, Object> map) {
        TreeMap<Long, String> indexes = new TreeMap<>();
        List<String> names = new ArrayList<>();
        for (String key : map.keySet()) {
            long index = arrayIndex(key);
            if (index >= 0) {
                indexes.put(index, key);
            } else {
                names.add(key);
            }
        }
        List<String> out = new ArrayList<>(indexes.values());
        out.addAll(names);
        return out;
    }

    /** The array index a key names ("0", "17"; not "01" or "-1"), or -1. */
    private static long arrayIndex(String key) {
        int n = key.length();
        if (n == 0 || n > 10 || (n > 1 && key.charAt(0) == '0')) {
            return -1;
        }
        long value = 0;
        for (int i = 0; i < n; i++) {
            char ch = key.charAt(i);
            if (ch < '0' || ch > '9') {
                return -1;
            }
            value = value * 10 + (ch - '0');
        }
        return value <= 4294967294L ? value : -1;
    }

    /** A string as JSON.stringify quotes it: only quote, backslash, controls and lone surrogates escaped. */
    private static void quote(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    boolean pairedHigh = Character.isHighSurrogate(ch) && i + 1 < s.length()
                        && Character.isLowSurrogate(s.charAt(i + 1));
                    boolean pairedLow = Character.isLowSurrogate(ch) && i > 0
                        && Character.isHighSurrogate(s.charAt(i - 1));
                    if (ch < 0x20 || (Character.isSurrogate(ch) && !pairedHigh && !pairedLow)) {
                        out.append("\\u");
                        String hex = Integer.toHexString(ch);
                        for (int pad = hex.length(); pad < 4; pad++) {
                            out.append('0');
                        }
                        out.append(hex);
                    } else {
                        out.append(ch);
                    }
            }
        }
        out.append('"');
    }

    /** A number as JavaScript prints it: 1 not 1.0, 0.000001, 1e-7, 1e+21; not finite → null. */
    static String number(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return "null";
        }
        if (d == 0) {
            return "0";
        }
        BigDecimal exact = new BigDecimal(Double.toString(d)).stripTrailingZeros();
        double a = Math.abs(d);
        if (a >= 1e-6 && a < 1e21) {
            return exact.toPlainString();
        }
        String digits = exact.unscaledValue().abs().toString();
        int exponent = digits.length() - 1 - exact.scale();
        String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        return (d < 0 ? "-" : "") + mantissa + "e" + (exponent < 0 ? "-" : "+") + Math.abs(exponent);
    }

    // ------------------------------------------------------------------ strict JSON.parse

    /**
     * RFC 8259 JSON into {@link LinkedHashMap} (stored key order kept; a repeated key keeps its first
     * place and its last value, as in JSON.parse), {@link ArrayList}, String, Boolean, Double and
     * {@link #NULL}. Anything else throws {@link IllegalArgumentException}.
     */
    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        Object document() {
            Object value = value(0);
            space();
            if (i != s.length()) {
                throw broken();
            }
            return value;
        }

        private Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw broken();
            }
            space();
            if (i >= s.length()) {
                throw broken();
            }
            char ch = s.charAt(i);
            if (ch == '{') {
                return object(depth);
            } else if (ch == '[') {
                return array(depth);
            } else if (ch == '"') {
                return string();
            } else if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            } else if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            } else if (s.startsWith("null", i)) {
                i += 4;
                return NULL;
            }
            return number();
        }

        private Map<String, Object> object(int depth) {
            Map<String, Object> map = new LinkedHashMap<>();
            i++;
            space();
            if (peek() == '}') {
                i++;
                return map;
            }
            while (true) {
                space();
                if (peek() != '"') {
                    throw broken();
                }
                String key = string();
                space();
                expect(':');
                map.put(key, value(depth + 1));
                space();
                char ch = next();
                if (ch == '}') {
                    return map;
                }
                if (ch != ',') {
                    throw broken();
                }
            }
        }

        private List<Object> array(int depth) {
            List<Object> list = new ArrayList<>();
            i++;
            space();
            if (peek() == ']') {
                i++;
                return list;
            }
            while (true) {
                list.add(value(depth + 1));
                space();
                char ch = next();
                if (ch == ']') {
                    return list;
                }
                if (ch != ',') {
                    throw broken();
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char ch = next();
                if (ch == '"') {
                    return out.toString();
                }
                if (ch < 0x20) {
                    throw broken();
                }
                if (ch != '\\') {
                    out.append(ch);
                    continue;
                }
                char esc = next();
                switch (esc) {
                    case '"': case '\\': case '/': out.append(esc); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) {
                            throw broken();
                        }
                        int code = 0;
                        for (int k = 0; k < 4; k++) {
                            int digit = Character.digit(s.charAt(i++), 16);
                            if (digit < 0) {
                                throw broken();
                            }
                            code = code * 16 + digit;
                        }
                        out.append((char) code);
                        break;
                    default:
                        throw broken();
                }
            }
        }

        private Double number() {
            int start = i;
            if (peek() == '-') {
                i++;
            }
            if (peek() == '0') {
                i++;
            } else if (digits() == 0) {
                throw broken();
            }
            if (peek() == '.') {
                i++;
                if (digits() == 0) {
                    throw broken();
                }
            }
            if (peek() == 'e' || peek() == 'E') {
                i++;
                if (peek() == '+' || peek() == '-') {
                    i++;
                }
                if (digits() == 0) {
                    throw broken();
                }
            }
            return Double.parseDouble(s.substring(start, i));
        }

        private int digits() {
            int start = i;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                i++;
            }
            return i - start;
        }

        private void space() {
            while (i < s.length()) {
                char ch = s.charAt(i);
                if (ch != ' ' && ch != '\t' && ch != '\n' && ch != '\r') {
                    return;
                }
                i++;
            }
        }

        private char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        private char next() {
            if (i >= s.length()) {
                throw broken();
            }
            return s.charAt(i++);
        }

        private void expect(char ch) {
            if (next() != ch) {
                throw broken();
            }
        }

        private static IllegalArgumentException broken() {
            return new IllegalArgumentException("not JSON");
        }
    }
}
