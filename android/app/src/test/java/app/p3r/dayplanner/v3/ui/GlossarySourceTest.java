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
    /** The legacy guide's own word list: guide text, not app copy. */
    private static final String GUIDE_WORDS = "GuideWords.java";
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
        out.add(new File(root, "PlannerWidget.java"));
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
            if (f.getName().equals(GUIDE_WORDS)) {
                continue;
            }
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
