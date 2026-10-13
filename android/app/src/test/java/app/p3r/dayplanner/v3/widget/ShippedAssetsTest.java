package app.p3r.dayplanner.v3.widget;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Every bitmap in {@code res/drawable-nodpi} is referenced by the app (audit: 2.2 MB of unused
 * character art and an unused logo shipped in the APK). Plain JVM over the source tree: Gradle runs
 * unit tests with the module directory as working directory.
 */
public class ShippedAssetsTest {
    private static final String[] REMOVED = {"character_day", "character_night", "character_day_compact", "official_p3r_logo_color"};

    private static File module() {
        for (String candidate : new String[]{".", "app", "android/app"}) {
            File dir = new File(candidate);
            if (new File(dir, "src/main/res").isDirectory()) return dir;
        }
        throw new IllegalStateException("module not found from " + new File(".").getAbsolutePath());
    }

    /** All Java, XML and HTML sources of the app (where a drawable can be referenced). */
    private static String sources(File module) throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.walk(new File(module, "src/main").toPath())) {
            for (Path p : files.filter(Files::isRegularFile).collect(Collectors.toList())) {
                String name = p.getFileName().toString();
                if (name.endsWith(".java") || name.endsWith(".xml") || name.endsWith(".html")) {
                    all.append(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).append('\n');
                }
            }
        }
        return all.toString();
    }

    @Test
    public void everyNodpiBitmapIsUsedAndTheDeadArtIsGone() throws IOException {
        File module = module();
        File nodpi = new File(module, "src/main/res/drawable-nodpi");
        String sources = sources(module);
        List<String> unused = new ArrayList<>();
        File[] files = nodpi.listFiles();
        assertTrue(files != null && files.length > 0);
        for (File f : files) {
            String name = f.getName().replaceFirst("\\.[^.]+$", "");
            boolean used = sources.contains("R.drawable." + name) || sources.contains("@drawable/" + name);
            if (!used) unused.add(f.getName());
        }
        assertTrue("unused bitmaps ship in the APK: " + unused, unused.isEmpty());
        for (String name : REMOVED) {
            assertFalse(name + " is back", new File(nodpi, name + ".png").exists());
            assertFalse(name + " is referenced", sources.contains(name));
        }
        assertTrue("the Today header art stays", new File(nodpi, "ui_art_protagonist.png").isFile());
    }
}
