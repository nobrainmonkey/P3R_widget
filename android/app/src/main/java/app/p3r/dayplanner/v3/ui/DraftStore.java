package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.content.SharedPreferences;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * Persists unfinished wizard input per <em>semantic</em> field key ("state.sync.link.emperor.rank",
 * "state.sync.cash", …), so drafts survive rotation and process death and are not lost when the
 * screen layout changes. When a record was made in the meantime (another state revision), the
 * player's edits are carried over onto the newer record, as after a save conflict.
 */
public final class DraftStore {
    static final String PREFS = "p3r_v3_ui_drafts";

    private final SharedPreferences prefs;

    public DraftStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String prefix(StateDraft.Mode mode) {
        return "state." + mode.key + ".";
    }

    /** Result of {@link #restore}. */
    public enum Restored {
        /** No saved draft for this mode. */
        NONE,
        /** Edits restored onto the same record they were made on. */
        SAME,
        /** Edits restored onto a newer record (something was recorded in the meantime). */
        REBASED
    }

    /**
     * Restores saved edits into {@code draft} if a draft exists for its mode; edits made on an
     * older revision are kept and rebased onto the draft's record. Call off the main thread on
     * first access.
     */
    public Restored restore(StateDraft draft) {
        String p = prefix(draft.mode);
        if (!prefs.contains(p + "base.rev")) {
            return Restored.NONE;
        }
        boolean rebased = prefs.getLong(p + "base.rev", Long.MIN_VALUE) != draft.baseRevision;
        Rules rules = draft.rules;
        draft.step = prefs.getString(p + "step", null);
        draft.returnTo = prefs.getString(p + "step.return", null);
        String sync = prefs.getString(p + "sync", null);
        draft.sync = sync == null ? null : StateDraft.SyncAnswer.valueOf(sync);
        draft.slot = getInt(p + "slot");
        if (prefs.contains(p + "cash")) {
            draft.cash = prefs.getLong(p + "cash", 0);
        }
        if (prefs.contains(p + "cash.unknown")) {
            draft.cashUnknown = prefs.getBoolean(p + "cash.unknown", false);
        }
        for (Rules.Stat stat : rules.stats) {
            if (stat.idx < 3) {
                draft.statLv[stat.idx] = getInt(p + "stat." + stat.id);
            }
        }
        for (Rules.Link link : rules.links) {
            draft.rank[link.idx] = getInt(p + "link." + link.id + ".rank");
            draft.unlock[link.idx] = getInt(p + "link." + link.id + ".unlock");
            String tier = prefs.getString(p + "goal." + link.id + ".tier", null);
            if (tier != null) {
                draft.profile.tier[link.idx] = Profile.Tier.valueOf(tier);
                draft.profile.order[link.idx] = prefs.getInt(p + "goal." + link.id + ".order", 0);
            }
        }
        draft.floor = getInt(p + "tower.floor");
        draft.towerVisits = getInt(p + "tower.visits");
        for (int i = 0; i < rules.rescues.length; i++) {
            draft.rescue[i] = getInt(p + "rescue." + rules.rescues[i].id);
        }
        for (Rules.Episode track : rules.episodes) {
            draft.episodes[track.idx] = getInt(p + "episode." + track.id);
        }
        for (Rules.Activity activity : rules.activities) {
            if (!StateDraft.isDorm(activity)) continue;
            Integer count = getInt(p + "dorm." + activity.id);
            if (count != null) draft.setDormCount(activity.idx, count);
        }
        draft.dormAsked = prefs.getBoolean(p + "dorm.asked", false);
        String persona = prefs.getString(p + "persona.mode", null);
        if (persona != null) {
            try {
                draft.setPersonaMode(Profile.PersonaMode.valueOf(persona));
            } catch (IllegalArgumentException ignored) {
                // An unknown value from a newer build: keep the profile's own answer.
            }
        }
        if (prefs.contains(p + "others")) {
            draft.profile.otherLinks = prefs.getBoolean(p + "others", true);
        }
        String christmas = prefs.getString(p + "christmas", null);
        if (christmas != null) {
            draft.setChristmasLink(christmas.isEmpty() ? null : christmas);
        }
        if (rebased) {
            save(draft);
        }
        return rebased ? Restored.REBASED : Restored.SAME;
    }

    /** Writes every edited field of {@code draft} (asynchronously, via apply). */
    public void save(StateDraft draft) {
        String p = prefix(draft.mode);
        SharedPreferences.Editor e = prefs.edit();
        e.putLong(p + "base.rev", draft.baseRevision);
        putString(e, p + "step", draft.step);
        putString(e, p + "step.return", draft.returnTo);
        putString(e, p + "sync", draft.sync == null ? null : draft.sync.name());
        putInt(e, p + "slot", draft.slot);
        if (draft.cash != null) {
            e.putLong(p + "cash", draft.cash);
        } else {
            e.remove(p + "cash");
        }
        if (draft.cashUnknown != null) {
            e.putBoolean(p + "cash.unknown", draft.cashUnknown);
        } else {
            e.remove(p + "cash.unknown");
        }
        Rules rules = draft.rules;
        for (Rules.Stat stat : rules.stats) {
            if (stat.idx < 3) {
                putInt(e, p + "stat." + stat.id, draft.statLv[stat.idx]);
            }
        }
        for (Rules.Link link : rules.links) {
            putInt(e, p + "link." + link.id + ".rank", draft.rank[link.idx]);
            putInt(e, p + "link." + link.id + ".unlock", draft.unlock[link.idx]);
            if (draft.mode == StateDraft.Mode.ONBOARDING) {
                e.putString(p + "goal." + link.id + ".tier", draft.profile.tier[link.idx].name());
                e.putInt(p + "goal." + link.id + ".order", draft.profile.order[link.idx]);
            }
        }
        putInt(e, p + "tower.floor", draft.floor);
        putInt(e, p + "tower.visits", draft.towerVisits);
        for (int i = 0; i < rules.rescues.length; i++) {
            putInt(e, p + "rescue." + rules.rescues[i].id, draft.rescue[i]);
        }
        for (Rules.Episode track : rules.episodes) {
            putInt(e, p + "episode." + track.id, draft.episodes[track.idx]);
        }
        for (Rules.Activity activity : rules.activities) {
            if (StateDraft.isDorm(activity)) {
                putInt(e, p + "dorm." + activity.id, draft.dormCounts[activity.idx]);
            }
        }
        if (draft.dormAsked) e.putBoolean(p + "dorm.asked", true);
        else e.remove(p + "dorm.asked");
        // Gardening is a 队友特性 choice now; a draft from an older wizard does not carry it over.
        e.remove(p + "dorm.gardening");
        if (draft.mode == StateDraft.Mode.ONBOARDING) {
            e.putString(p + "persona.mode", draft.profile.effectivePersonaMode().name());
            e.putBoolean(p + "others", draft.profile.otherLinks);
        }
        putString(e, p + "christmas", draft.christmas);
        e.apply();
    }

    /** Removes the draft of one mode (after a successful save). */
    public void clear(StateDraft.Mode mode) {
        String p = prefix(mode);
        SharedPreferences.Editor e = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(p)) {
                e.remove(key);
            }
        }
        e.apply();
    }

    private Integer getInt(String key) {
        return prefs.contains(key) ? prefs.getInt(key, 0) : null;
    }

    private static void putInt(SharedPreferences.Editor e, String key, Integer value) {
        if (value == null) {
            e.remove(key);
        } else {
            e.putInt(key, value);
        }
    }

    private static void putString(SharedPreferences.Editor e, String key, String value) {
        if (value == null) {
            e.remove(key);
        } else {
            e.putString(key, value);
        }
    }
}
