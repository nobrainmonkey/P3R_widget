package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.Map;
import java.util.TreeMap;

/**
 * Rules + profile (+ engine options) compiled into flat arrays for the simulation kernel. Cheap to
 * build (the calendar tables are shared through {@link Tables#of}); immutable after construction.
 */
final class Model {
    final Rules rules;
    final Tables t;
    final Profile profile;

    /** Estimated points of an ordinary meeting before multipliers (policy.ordinaryGain, ≥ 1). */
    final int ordinaryGain;
    /** Persona multiplier when the player declared matching Personas, else 1. */
    final double personaMult;
    /** Charm Lv6 multiplier when the profile enables it, else 1. */
    final double charmMult;
    final boolean bestAnswers;
    final boolean goalBuffer;
    final boolean[] goal;
    /** Event gains per link, romance route aware. */
    final int[][] eventGain;
    final boolean[] excluded;
    final int towerCap;
    final int cashBuffer;
    final int towerIncome;

    /** Unlock prerequisites per link as parallel arrays (deterministic order). */
    final int[][] reqStat;
    final int[][] reqStatLv;
    final int[][] reqLink;
    final int[][] reqLinkRank;

    Model(Rules rules, Profile profile, Engine.Options options) {
        this.rules = rules;
        this.t = Tables.of(rules);
        this.profile = profile;
        this.ordinaryGain = Math.max(1, rules.policy.ordinaryGain);
        this.personaMult = profile.personaBonus ? rules.policy.personaMult : 1.0;
        this.charmMult = profile.charmBonus ? rules.policy.charmMult : 1.0;
        this.bestAnswers = profile.answers == Profile.AnswerPolicy.BEST;
        this.goalBuffer = profile.goalBuffer;
        this.towerCap = Math.max(0, profile.towerCapPerBlock);
        this.cashBuffer = Math.max(0, profile.cashBuffer);
        this.towerIncome = options == null ? 0 : Math.max(0, options.towerIncomePerVisit);

        int n = t.nLinks;
        goal = new boolean[n];
        eventGain = new int[n][];
        reqStat = new int[n][];
        reqStatLv = new int[n][];
        reqLink = new int[n][];
        reqLinkRank = new int[n][];
        for (Rules.Link link : rules.links) {
            int l = link.idx;
            goal[l] = l < profile.tier.length && profile.isGoal(l);
            boolean romance = l < profile.route.length && profile.route[l] == Profile.Route.ROMANCE;
            eventGain[l] = romance && link.eventGainRomance != null ? link.eventGainRomance : link.eventGain;
            Map<Integer, Integer> stats = new TreeMap<>(link.unlock.statLv);
            reqStat[l] = keys(stats);
            reqStatLv[l] = values(stats);
            Map<Integer, Integer> links = new TreeMap<>(link.unlock.linkRank);
            reqLink[l] = keys(links);
            reqLinkRank[l] = values(links);
        }
        excluded = new boolean[t.nActs];
        for (Rules.Activity a : rules.activities) excluded[a.idx] = profile.excluded(a.id);
    }

    /** Target rank for a link: the profile target for goals, MAX otherwise. */
    int target(int link) {
        if (!goal[link]) return 10;
        return Math.max(1, Math.min(10, profile.target[link]));
    }

    private static int[] keys(Map<Integer, Integer> map) {
        int[] out = new int[map.size()];
        int i = 0;
        for (int k : map.keySet()) out[i++] = k;
        return out;
    }

    private static int[] values(Map<Integer, Integer> map) {
        int[] out = new int[map.size()];
        int i = 0;
        for (int v : map.values()) out[i++] = v;
        return out;
    }
}
