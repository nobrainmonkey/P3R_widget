package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * docs/GLOSSARY_zh.md over the app and widget sources (re-audit 3.0.1): every string literal says
 * 「结缘网站的字条」 in full, never the short 「字条」 alone (StepKind 「社群 · 字条」, Outcomes 「没用字条」,
 * the widget's 「在放纵俱乐部买字条…」), uses 安排 rather than 规划 in sentences, and only the date
 * plates write a date as M/D ({@link Zh#shortDate}). Plain JVM over the source files.
 */
public class GlossarySourceTest {
    /** 规划 is the app name and two menu items only. */
    private static final Pattern PLAN_WORD = Pattern.compile("规划(?!设置|说明)");

    private static File sourceRoot() {
        for (String candidate : new String[]{"src/main/java", "app/src/main/java", "android/app/src/main/java"}) {
            File f = new File(candidate, "app/p3r/dayplanner");
            if (f.isDirectory()) {
                return f;
            }
        }
        throw new IllegalStateException("sources not found from " + new File(".").getAbsolutePath());
    }

    private static List<File> sources() {
        File root = sourceRoot();
        List<File> out = new ArrayList<>();
        for (String dir : new String[]{"v3/ui", "v3/widget"}) {
            File[] files = new File(root, dir).listFiles((d, name) -> name.endsWith(".java"));
            if (files != null) {
                for (File f : files) {
                    out.add(f);
                }
            }
        }
        assertTrue(out.size() > 40);
        return out;
    }

    /** The string literals of a Java source, comments and char literals skipped (escapes kept as written). */
    static List<String> literals(String src) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < src.length()) {
            char ch = src.charAt(i);
            if (src.startsWith("//", i)) {
                int end = src.indexOf('\n', i);
                i = end < 0 ? src.length() : end;
            } else if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? src.length() : end + 2;
            } else if (ch == '\'' || ch == '"') {
                int j = i + 1;
                while (j < src.length() && src.charAt(j) != ch) {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                if (ch == '"') {
                    out.add(src.substring(i + 1, Math.min(j, src.length())));
                }
                i = j + 1;
            } else {
                i++;
            }
        }
        return out;
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void theDatingSiteNoteIsAlwaysCalledByItsFullName() throws IOException {
        assertTrue("the scan sees the literals", literals(read(new File(sourceRoot(), "v3/ui/StepKind.java"))).contains("结缘网站的字条"));
        assertTrue(literals("a(\"x\\\"字条\"); // \"字条\"\n").contains("x\\\"字条"));
        List<String> issues = new ArrayList<>();
        for (File f : sources()) {
            for (String literal : literals(read(f))) {
                if (literal.replace("结缘网站的字条", "").contains("字条")) {
                    issues.add(f.getName() + ": \"" + literal + "\"");
                }
            }
        }
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }

    @Test
    public void sentencesSayAnpaiNotThePlannerName() throws IOException {
        List<String> issues = new ArrayList<>();
        for (File f : sources()) {
            for (String literal : literals(read(f))) {
                if (PLAN_WORD.matcher(literal.replace("P3R 规划", "")).find()) {
                    issues.add(f.getName() + ": \"" + literal + "\"");
                }
            }
        }
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }

    /** Half-width '?', '!' or "..." next to Chinese; 「?」 is the RANK pad's own key. */
    private static final Pattern HALF_WIDTH = Pattern.compile(
        "[\\u4e00-\\u9fff」）][?!]|[?!][\\u4e00-\\u9fff「（]|\\.\\.\\.");
    /** "预计1月31日前": the glossary writes 「预计 11月20日」. */
    private static final Pattern ESTIMATE_DATE = Pattern.compile("预计\\d");

    @Test
    public void chineseTextUsesFullWidthPunctuationAndSpacedDates() throws IOException {
        assertTrue(HALF_WIDTH.matcher("完成了吗?").find() && HALF_WIDTH.matcher("改做别的...").find());
        assertFalse(HALF_WIDTH.matcher("完成了吗？改做别的…不记得就选「?」").find());
        List<String> issues = new ArrayList<>();
        File[] files = new File(sourceRoot(), "v3/ui").listFiles((d, name) -> name.endsWith(".java"));
        assertTrue(files != null && files.length > 40);
        for (File f : files) {
            for (String literal : literals(read(f))) {
                if (!literal.codePoints().anyMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF)) {
                    continue;
                }
                if (HALF_WIDTH.matcher(literal.replace("「?」", "")).find() || ESTIMATE_DATE.matcher(literal).find()) {
                    issues.add(f.getName() + ": \"" + literal + "\"");
                }
            }
        }
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }

    /** "69-117F", "5月12日-1月29日": a range is written with the en dash, as the pack and Zh#dateRange do. */
    private static final Pattern HYPHEN_RANGE = Pattern.compile("[0-9日F]\\s*-\\s*[0-9]");

    @Test
    public void rangesUseTheEnDash() throws IOException {
        assertTrue(HYPHEN_RANGE.matcher("提齐亚（69-117F）").find() && HYPHEN_RANGE.matcher("5月12日-1月29日").find());
        assertFalse(HYPHEN_RANGE.matcher("提齐亚（69–117F）· 5月12日–1月29日").find());
        List<String> issues = new ArrayList<>();
        File[] files = new File(sourceRoot(), "v3/ui").listFiles((d, name) -> name.endsWith(".java"));
        assertTrue(files != null && files.length > 40);
        for (File f : files) {
            for (String literal : literals(read(f))) {
                boolean chinese = literal.codePoints().anyMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF);
                if (chinese && !literal.contains("[") && HYPHEN_RANGE.matcher(literal).find()) {
                    issues.add(f.getName() + ": \"" + literal + "\"");
                }
            }
        }
        assertTrue(String.join("\n", issues), issues.isEmpty());
    }

    @Test
    public void onlyTheDatePlatesWriteMonthSlashDay() throws IOException {
        for (File f : sources()) {
            String text = read(f);
            if (!f.getName().equals("DatePlate.java") && !f.getName().equals("Zh.java")) {
                assertFalse(f.getName() + " uses the plate's M/D in text", text.contains("Zh.shortDate("));
            }
        }
        assertTrue(read(new File(sourceRoot(), "v3/ui/DatePlate.java")).contains("Zh.shortDate("));
    }
}
