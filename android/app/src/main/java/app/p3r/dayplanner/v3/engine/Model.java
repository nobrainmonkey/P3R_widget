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
    /**
     * Dating-site notes per playthrough. Reload sources agree the club keeps selling the note
     * (Siliconera: "unlike his other URLs, you can buy multiples of it"; atwiki: on sale without a
     * limit; a Steam player bought 30), and each one is bought and used on one evening, so the engine
     * does not cap them. {@code SimState.notesUsed} still counts them, so a cap from the rule pack
     * can be honoured here without a state change.
     */
    static final int NOTE_MAX_USES = Integer.MAX_VALUE;

    final Rules rules;
    final Tables t;
    final Profile profile;

    /** Estimated points of an ordinary meeting before multipliers (policy.ordinaryGain, ≥ 1). */
    final int ordinaryGain;
    /**
     * Per link: the player carries a Persona of that arcana on meetings (Profile.personaFor, i.e.
     * 不带 / 只给目标社群带 / 基本都带), so its points get {@link #personaFactor}.
     */
    final boolean[] persona;
    /** The matching-Persona multiplier of the pack (×1.51). */
    final double personaFactor;
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

    /** The pack's dating-site note when the player allows notes, else null. */
    final Rules.DatingNote note;
    /** Notes allowed in the whole plan ({@link #NOTE_MAX_USES}). */
    final int noteMaxUses;
    /** Link Episode tracks the player opted into (Profile.episodeTracks), by track index. */
    final boolean[] episodeWanted;

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
        this.personaFactor = Math.max(1.0, rules.policy.personaMult);
        this.charmMult = profile.charmBonus ? rules.policy.charmMult : 1.0;
        this.bestAnswers = profile.answers == Profile.AnswerPolicy.BEST;
        this.goalBuffer = profile.goalBuffer;
        this.towerCap = Math.max(0, profile.towerCapPerBlock);
        this.cashBuffer = Math.max(0, profile.cashBuffer);
        this.towerIncome = options == null ? 0 : Math.max(0, options.towerIncomePerVisit);
        Rules.DatingNote n = rules.datingNote;
        this.note = profile.datingNotes && n != null && n.gain > 0 ? n : null;
        this.noteMaxUses = NOTE_MAX_USES;

        int nl = t.nLinks;
        goal = new boolean[nl];
        persona = new boolean[nl];
        eventGain = new int[nl][];
        reqStat = new int[nl][];
        reqStatLv = new int[nl][];
        reqLink = new int[nl][];
        reqLinkRank = new int[nl][];
        for (Rules.Link link : rules.links) {
            int l = link.idx;
            goal[l] = l < profile.tier.length && profile.isGoal(l);
            persona[l] = l < profile.tier.length && profile.personaFor(l) && personaFactor > 1.0;
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
        episodeWanted = new boolean[t.nEps];
        for (int e = 0; e < t.nEps; e++) {
            Rules.Episode ep = rules.episodes[e];
            episodeWanted[e] = ep.id != null && ep.count > 0 && profile.wantsEpisodes(ep.id);
        }
    }

    /** Target rank for a link: the profile target for goals, MAX otherwise. */
    int target(int link) {
        if (!goal[link]) return 10;
        return Math.max(1, Math.min(10, profile.target[link]));
    }

    /** Persona multiplier of meetings with {@code link} (1 when the player does not carry one). */
    double personaMult(int link) {
        return persona[link] ? personaFactor : 1.0;
    }

    /** Some opted-in Link Episode track exists. */
    boolean anyEpisodes() {
        for (boolean b : episodeWanted) if (b) return true;
        return false;
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
