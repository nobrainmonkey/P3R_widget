package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.nio.charset.StandardCharsets;

/**
 * Stable plan id: FNV-1a 64 over a canonical serialisation of the inputs (rule pack version,
 * profile, observed state, result-relevant options) and the planned action keys. Independent of
 * JSON key order, locale and wall-clock time, so equal inputs and equal steps give equal ids.
 */
final class PlanHash {
    private PlanHash() {}

    private static final long OFFSET = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private long h = OFFSET;

    static String of(Rules rules, Profile profile, SimState start, Engine.Options options, Plan plan) {
        PlanHash x = new PlanHash();
        x.str(rules.version).str(rules.packVersion);
        x.profile(profile);
        x.state(start);
        x.num(options.candidates).num(options.towerIncomePerVisit);
        x.num(plan.fromSlot).num(plan.toSlot);
        for (Plan.Step s : plan.steps) {
            x.num(s.slot).str(s.action);
            for (String side : s.side) x.str(side);
        }
        StringBuilder hex = new StringBuilder(Long.toHexString(x.h));
        while (hex.length() < 16) hex.insert(0, '0');
        return hex.toString();
    }

    private PlanHash profile(Profile p) {
        for (int i = 0; i < p.tier.length; i++) {
            str(p.tier[i].name()).num(p.order[i]).num(p.target[i]).str(p.route[i].name());
        }
        bool(p.otherLinks).bool(p.personaBonus).bool(p.charmBonus).bool(p.goalBuffer);
        str(p.effectivePersonaMode().name()).bool(p.datingNotes);
        num(p.episodeTracks.length);
        for (String e : p.episodeTracks) str(e);
        str(p.answers.name()).num(p.towerVisitsPerBlock).num(p.towerCapPerBlock).num(p.cashBuffer);
        for (String e : p.excludedActivities) str(e);
        // Added in 3.0.1; left out while unset so earlier plan ids stay the same.
        if (!p.towerCapByBlock.isEmpty() || !p.towerVisitsByBlock.isEmpty() || p.christmasTaken()) {
            str("blocks");
            for (java.util.Map.Entry<String, Integer> e : new java.util.TreeMap<>(p.towerCapByBlock).entrySet()) str(e.getKey()).num(e.getValue() == null ? -1 : e.getValue());
            str("visits");
            for (java.util.Map.Entry<String, Integer> e : new java.util.TreeMap<>(p.towerVisitsByBlock).entrySet()) str(e.getKey()).num(e.getValue() == null ? -1 : e.getValue());
            str(p.christmasTaken() ? p.christmasLink.trim() : null);
        }
        // Added in 3.1.0; 「尽量多 MAX」 is left out so its plan ids stay the same as before.
        if (p.style != Profile.Style.MAX) str("style").str(p.style.json);
        // Added in 3.3.0; the default false preserves hashes for profiles without this option.
        if (p.planDormGardening) str("planDormGardening").bool(true);
        // Goals are now explicit opt-ins. Tag even an empty map so a pre-option plan is stale.
        str("dormTargets.explicit.v1");
        for (java.util.Map.Entry<String, Profile.DormTarget> e : new java.util.TreeMap<>(p.dormTargets).entrySet()) {
            if (e.getKey() != null && !e.getKey().isEmpty() && e.getValue() != null
                    && e.getValue() != Profile.DormTarget.NONE) str(e.getKey()).str(e.getValue().name());
        }
        return this;
    }

    private PlanHash state(SimState s) {
        num(s.slot).num(s.cash).bool(s.cashExact).arr(s.statPts).arr(s.rank).arr(s.pts);
        for (boolean b : s.ready) bool(b);
        arr(s.unlockStep).arr(s.lastStepDay).num(s.rescued).num(s.rescueFailed);
        arr(s.towerVisits).num(s.floor).arr(s.shifts).arr(s.used).arr(s.heldRank);
        arr(s.episodes).num(s.notesUsed);
        return this;
    }

    private PlanHash arr(int[] a) {
        num(a.length);
        for (int v : a) num(v);
        return this;
    }

    private PlanHash bool(boolean b) {
        return num(b ? 1 : 0);
    }

    private PlanHash num(long v) {
        for (int i = 0; i < 8; i++) {
            h ^= (v >>> (8 * i)) & 0xff;
            h *= PRIME;
        }
        return this;
    }

    private PlanHash str(String s) {
        if (s == null) return num(-1);
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        num(bytes.length);
        for (byte b : bytes) {
            h ^= b & 0xff;
            h *= PRIME;
        }
        return this;
    }
}
