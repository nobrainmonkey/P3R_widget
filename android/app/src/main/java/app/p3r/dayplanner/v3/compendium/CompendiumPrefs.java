package app.p3r.dayplanner.v3.compendium;

import android.content.Context;

import app.p3r.dayplanner.v3.store.Ledger;
import app.p3r.dayplanner.v3.store.PlanDb;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Set;
import java.util.TreeSet;

/** Stored alongside the planner records so favorites and DLC are included in backups. */
public final class CompendiumPrefs {
    private final Context context;

    public CompendiumPrefs(Context context) {
        this.context = context.getApplicationContext();
    }

    public Set<String> favorites() {
        return read().favorites;
    }

    public Set<String> ownedDlc() {
        return read().ownedDlc;
    }

    private CompendiumChoices read() {
        try (Ledger ledger = new Ledger(context, PlanDb.FILE_NAME)) {
            return decode(ledger.compendiumJson());
        }
    }

    public void setFavorite(String personaId, boolean favorite) {
        update(choices -> {
            Set<String> ids = new TreeSet<>(choices.favorites);
            if (favorite) ids.add(personaId); else ids.remove(personaId);
            return new CompendiumChoices(ids, choices.ownedDlc);
        });
    }

    public void setOwnedDlc(Set<String> packs) {
        update(choices -> new CompendiumChoices(choices.favorites, packs));
    }

    private interface Update {
        CompendiumChoices apply(CompendiumChoices choices);
    }

    private void update(Update update) {
        try (Ledger ledger = new Ledger(context, PlanDb.FILE_NAME)) {
            ledger.inTransaction(() -> {
                CompendiumChoices changed = update.apply(decode(ledger.compendiumJson()));
                try {
                    ledger.putCompendiumJson(changed.toJson().toString());
                } catch (JSONException impossible) {
                    throw new IllegalStateException(impossible);
                }
                return null;
            });
        }
    }

    private static CompendiumChoices decode(String text) {
        if (text == null) return CompendiumChoices.empty();
        try {
            return CompendiumChoices.fromJson(new JSONObject(text));
        } catch (JSONException invalid) {
            return CompendiumChoices.empty();
        }
    }
}
