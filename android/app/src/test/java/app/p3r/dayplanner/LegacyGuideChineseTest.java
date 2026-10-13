package app.p3r.dayplanner;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The retained 逐日攻略 page (assets/index.html, reachable from the app) speaks Simplified Chinese
 * (audit: DAILY ROUTINE, DAY/NIGHT, APRIL…MARCH, AFTER SCHOOL…). Only display text changed: the
 * embedded guide data (apart from the official 主角), element ids and storage keys are exactly as
 * before. Plain JVM over the file.
 */
public class LegacyGuideChineseTest {
    /**
     * SHA-256 of the embedded guide data and display metadata as in 2.x. The only change since is the
     * official 主角 for the guide's 主人公 (「宿舍 · 主角房间电视」); undoing it gives these hashes back.
     */
    private static final String DATA_SHA = "07e5ef520e107cd466053e09cf70768325326f4168a011236b264c48bc24c395";
    private static final String METADATA_SHA = "e8d46f8b1f24e2d7a73435cce8bef080c6f57672241eece61215d4546b531d2b";
    /** The guide's own term, which the official zh-Hans text writes 主角. */
    private static final String OLD_TERM = "主人公房间";
    private static final String OFFICIAL_TERM = "主角房间";
    /** Two or more Latin letters in a row: an English word. */
    private static final Pattern ENGLISH = Pattern.compile("[A-Za-z]{2,}");

    private static String page() throws IOException {
        return asset("index.html");
    }

    private static String asset(String name) throws IOException {
        for (String candidate : new String[]{"src/main/assets/", "app/src/main/assets/", "android/app/src/main/assets/"}) {
            File f = new File(candidate + name);
            if (f.isFile()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }
        throw new IllegalStateException(name + " not found from " + new File(".").getAbsolutePath());
    }

    private static String block(String page, String id) {
        Matcher m = Pattern.compile("<script id=\"" + id + "\" type=\"application/json\">(.*?)</script>", Pattern.DOTALL).matcher(page);
        assertTrue(id, m.find());
        return m.group(1);
    }

    /** Same JSON value: objects key by key, arrays in order, everything else by equals. */
    private static boolean sameJson(Object a, Object b) throws Exception {
        if (a instanceof JSONObject && b instanceof JSONObject) {
            JSONObject x = (JSONObject) a;
            JSONObject y = (JSONObject) b;
            if (x.length() != y.length()) return false;
            for (java.util.Iterator<String> keys = x.keys(); keys.hasNext(); ) {
                String key = keys.next();
                if (!y.has(key) || !sameJson(x.get(key), y.get(key))) return false;
            }
            return true;
        }
        if (a instanceof JSONArray && b instanceof JSONArray) {
            JSONArray x = (JSONArray) a;
            JSONArray y = (JSONArray) b;
            if (x.length() != y.length()) return false;
            for (int i = 0; i < x.length(); i++) if (!sameJson(x.get(i), y.get(i))) return false;
            return true;
        }
        return a.equals(b);
    }

    private static String sha(String text) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))) out.append(String.format("%02x", b));
        return out.toString();
    }

    @Test
    public void guideDataIdsAndStorageKeysAreUnchanged() throws Exception {
        String page = page();
        assertEquals(DATA_SHA, sha(block(page, "data").replace(OFFICIAL_TERM, OLD_TERM)));
        assertEquals(METADATA_SHA, sha(block(page, "display-metadata").replace(OFFICIAL_TERM, OLD_TERM)));
        for (String kept : new String[]{"id=\"themeWord\"", "id=\"heroMonth\"", "id=\"heroChinese\"", "id=\"sectionText\"",
                "id=\"progressLabel\"", "id=\"matchingCount\"", "id=\"prefsTitle\"", "id=\"themeToggle\"", "id=\"months\"",
                "'p3r_hunterjia_checklist_v1'", "'p3r_hunterjia_substeps_v2'", "'p3r_minimal_motion_v1'", "'p3r_theme_mode_v1'",
                "'p3r_widget_selected_day_v1'", "window.P3RNative", "AndroidBridge"}) {
            assertTrue("kept: " + kept, page.contains(kept));
        }
    }

    @Test
    public void theGuideSaysTheOfficialProtagonistTerm() throws Exception {
        // 主角, as the official zh-Hans text and the rest of the app write it (re-audit: 主人公 98× in
        // index.html and 49× in data.json, shown on the legacy widget as 「宿舍 · 主人公房间」).
        String page = page();
        for (String[] file : new String[][]{{"index.html", page}, {"data.json", asset("data.json")},
                {"display_metadata.json", asset("display_metadata.json")}}) {
            assertFalse(file[0] + " still says 主人公", file[1].contains("主人公"));
        }
        assertTrue(page.contains("宿舍 · 主角房间电视"));
        // The page embeds exactly the guide data and display metadata the app and the widget read.
        assertTrue(sameJson(new JSONObject(block(page, "data")), new JSONObject(asset("data.json"))));
        assertTrue(sameJson(new JSONObject(block(page, "display-metadata")), new JSONObject(asset("display_metadata.json"))));
    }

    @Test
    public void everyLabelIsChinese() throws Exception {
        String page = page();
        // Markup text outside the data, scripts and styles.
        String markup = page.replaceAll("(?s)<script.*?</script>", "").replaceAll("(?s)<style>.*?</style>", "");
        List<String> english = new ArrayList<>();
        Matcher text = Pattern.compile(">([^<>]+)<").matcher(markup);
        while (text.find()) {
            if (ENGLISH.matcher(text.group(1)).find()) english.add("markup: " + text.group(1).trim());
        }
        Matcher title = Pattern.compile("<title>(.*?)</title>").matcher(page);
        assertTrue(title.find());
        assertEquals("P3R 逐日攻略", title.group(1));
        // Text the script puts on screen: textContent, alerts and confirmations, thrown messages, labels.
        String script = page.replaceAll("(?s)<script id=\"(data|display-metadata)\".*?</script>", "");
        Matcher shown = Pattern.compile("(?:textContent=|alert\\(|confirm\\(|Error\\(|'period-title',)([^;]*)").matcher(script);
        while (shown.find()) {
            Matcher literal = Pattern.compile("'([^']*)'|`([^`]*)`").matcher(shown.group(1));
            while (literal.find()) {
                String value = literal.group(1) != null ? literal.group(1) : literal.group(2);
                String visible = value.replaceAll("\\$\\{[^}]*}", "");
                if (ENGLISH.matcher(visible).find()) english.add("script: " + value);
            }
        }
        assertTrue("English labels on the guide page: " + english, english.isEmpty());

        Matcher months = Pattern.compile("const MONTHS=\\{([^}]*)}").matcher(script);
        assertTrue(months.find());
        for (String entry : months.group(1).split(",")) {
            String[] kv = entry.split(":");
            assertEquals(entry, "'" + kv[0] + "月'", kv[1]);
        }
        Matcher periods = Pattern.compile("const PERIODS=\\{([^}]*)}").matcher(script);
        assertTrue(periods.find());
        assertTrue(periods.group(1), periods.group(1).contains("'放学后':'放学后'") && periods.group(1).contains("'晚上':'晚上'")
                && periods.group(1).contains("'未标注':'当天'"));
        assertTrue(ENGLISH.matcher(periods.group(1)).find() == false);
        assertTrue("the theme toggle names the other palette in Chinese", script.contains("night?'白天':'夜晚'"));
    }
}
