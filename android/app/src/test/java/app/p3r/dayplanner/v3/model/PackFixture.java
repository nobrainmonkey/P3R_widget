package app.p3r.dayplanner.v3.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Loads the shipped rule pack straight from the module source tree (no Android, no Robolectric):
 * Gradle runs unit tests with the module directory ({@code android/app}) as working directory.
 */
final class PackFixture {
    private static final String[] CANDIDATES = {
            "src/main/assets/p3r/p3r_rules.json",
            "app/src/main/assets/p3r/p3r_rules.json",
            "android/app/src/main/assets/p3r/p3r_rules.json",
    };
    private static final LocalDate EPOCH = LocalDate.of(2009, 4, 1);

    private static String json;
    private static Rules rules;

    private PackFixture() {}

    /** The raw JSON text of the shipped pack. */
    static synchronized String json() {
        if (json == null) {
            for (String candidate : CANDIDATES) {
                Path path = Paths.get(candidate);
                if (Files.isRegularFile(path)) {
                    try {
                        json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new IllegalStateException("cannot read " + path.toAbsolutePath(), e);
                    }
                    break;
                }
            }
            if (json == null) {
                throw new IllegalStateException("p3r_rules.json not found from " + Paths.get("").toAbsolutePath());
            }
        }
        return json;
    }

    /** The shipped pack, parsed once. */
    static synchronized Rules rules() {
        if (rules == null) rules = RulesLoader.parse(json());
        return rules;
    }

    /** A fresh, mutable JSON tree of the shipped pack (for validation tests). */
    static JSONObject tree() {
        try {
            return new JSONObject(json());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Day ordinal of an in-game date (January–March are in 2010). */
    static int day(int month, int dayOfMonth) {
        int year = month <= 3 ? 2010 : 2009;
        return (int) ChronoUnit.DAYS.between(EPOCH, LocalDate.of(year, month, dayOfMonth));
    }
}
