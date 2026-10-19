package app.p3r.dayplanner.v3.compendium;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** User choices only: a favorite is not a claim that a Persona is in the player's inventory. */
public final class CompendiumChoices {
    public final Set<String> favorites;
    public final Set<String> ownedDlc;
    public final Set<String> ownedPersonas;
    public final FusionBasket basket;

    public CompendiumChoices(Set<String> favorites, Set<String> ownedDlc) {
        this(favorites, ownedDlc, Collections.emptySet(), new FusionBasket());
    }

    public CompendiumChoices(Set<String> favorites, Set<String> ownedDlc,
                             Set<String> ownedPersonas, FusionBasket basket) {
        this.favorites = Collections.unmodifiableSet(new TreeSet<>(favorites));
        this.ownedDlc = Collections.unmodifiableSet(new TreeSet<>(ownedDlc));
        this.ownedPersonas = Collections.unmodifiableSet(new TreeSet<>(ownedPersonas));
        this.basket = basket;
    }

    public static CompendiumChoices empty() {
        return new CompendiumChoices(Collections.emptySet(), Collections.emptySet());
    }

    public static CompendiumChoices fromJson(JSONObject json) throws JSONException {
        return new CompendiumChoices(readIds(json, "favorites"), readIds(json, "ownedDlc"),
            readIds(json, "ownedPersonas"), json.has("basket")
                ? FusionBasket.fromJson(json.getJSONObject("basket")) : new FusionBasket());
    }

    private static Set<String> readIds(JSONObject json, String key) throws JSONException {
        if (!json.has(key)) return Collections.emptySet();
        JSONArray array = json.getJSONArray(key);
        if (array.length() > 1000) throw new JSONException("Too many compendium choices");
        Set<String> ids = new TreeSet<>();
        for (int i = 0; i < array.length(); i++) {
            Object item = array.get(i);
            if (!(item instanceof String) || !((String) item).matches("[A-Za-z0-9_:-]{1,80}")) {
                throw new JSONException("Invalid compendium choice");
            }
            ids.add((String) item);
        }
        return ids;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("favorites", new JSONArray(favorites));
        json.put("ownedDlc", new JSONArray(ownedDlc));
        json.put("ownedPersonas", new JSONArray(ownedPersonas));
        json.put("basket", basket.toJson());
        return json;
    }
}
