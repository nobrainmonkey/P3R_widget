package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.content.SharedPreferences;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * Persists unfinished wizard input per <em>semantic</em> field key ("state.sync.link.emperor.rank",
 * "state.sync.cash", …), so drafts survive rotation and process death and are not lost when the
 * screen layout changes. A draft started from another state revision is discarded.
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

    /**
     * Restores saved edits into {@code draft} if a draft exists for its mode and revision.
     * Returns true when something was restored. Call off the main thread on first access.
     */
    public boolean restore(StateDraft draft) {
        String p = prefix(draft.mode);
        if (!prefs.contains(p + "base.rev")) {
            return false;
        }
        if (prefs.getLong(p + "base.rev", Long.MIN_VALUE) != draft.baseRevision) {
            clear(draft.mode);
            return false;
        }
        Rules rules = draft.rules;
        draft.step = prefs.getString(p + "step", null);
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
        if (prefs.contains(p + "persona")) {
            draft.profile.personaBonus = prefs.getBoolean(p + "persona", false);
        }
        if (prefs.contains(p + "others")) {
            draft.profile.otherLinks = prefs.getBoolean(p + "others", true);
        }
        return true;
    }

    /** Writes every edited field of {@code draft} (asynchronously, via apply). */
    public void save(StateDraft draft) {
        String p = prefix(draft.mode);
        SharedPreferences.Editor e = prefs.edit();
        e.putLong(p + "base.rev", draft.baseRevision);
        putString(e, p + "step", draft.step);
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
        if (draft.mode == StateDraft.Mode.ONBOARDING) {
            e.putBoolean(p + "persona", draft.profile.personaBonus);
            e.putBoolean(p + "others", draft.profile.otherLinks);
        }
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
