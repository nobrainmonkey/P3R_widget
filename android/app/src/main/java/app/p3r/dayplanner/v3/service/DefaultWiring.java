package app.p3r.dayplanner.v3.service;

import android.content.Context;

import app.p3r.dayplanner.v3.model.RulesLoader;
import app.p3r.dayplanner.v3.widget.PlanWidget;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Production dependencies of {@link PlannerService}: the bundled rule pack, the engine, the widget. */
final class DefaultWiring {
    /** Rule pack inside the APK assets. */
    static final String RULES_ASSET = "p3r/p3r_rules.json";

    private DefaultWiring() {}

    static Wiring create(Context context) {
        Context app = context.getApplicationContext();
        Wiring wiring = new Wiring();
        // The rules module may also offer a cached RulesAssets.get(context); parsing here keeps
        // this module independent of it. Runs once, on the service's worker thread.
        wiring.rules = () -> RulesLoader.parse(readAsset(app, RULES_ASSET));
        wiring.widgetRefresh = () -> PlanWidget.refreshAll(app);
        return wiring;
    }

    /** Reads a UTF-8 asset completely (worker thread only). */
    static String readAsset(Context context, String path) {
        try (InputStream in = context.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read asset " + path, e);
        }
    }
}
