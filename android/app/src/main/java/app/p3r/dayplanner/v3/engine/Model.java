package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.Map;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
    /** Activity indices of each teammate's two dorm projects, and group per activity (-1 otherwise). */
    final int[][] dormTracks;
    final int[] dormGroup;
    /** Explicit characteristic goal per teammate; ordinary useful stat gains do not require one. */
    final Profile.DormTarget[] dormTargets;
    /** Per activity: allowed activities unlocked by its recorded use (usually empty). */
    final int[][] unlockedActivities;
    /** Most points one ordinary allowed activity gives each stat; scarce fixed-date bonuses are excluded. */
    final int[] maxGain;
    /** Per activity: bit s set when it raises stat s. */
    final int[] gainMask;
    /** Hard cap of Tartarus visits per block index ({@link Profile#capFor}). */
    final int[] towerCap;
    /** Visits the plan should make per block index: {@code min(Profile#visitsFor, cap)}. */
    final int[] towerWant;
    final int cashBuffer;
    final int towerIncome;
    /** Latest observed tower evening, used when grouping missing-person rescues. */
    final int lastTowerSlot;
    /** Forecast-only rescue policy; initialized with a copied plan start. */
    RescueBatches batches;

    /** The pack's dating-site note when the player allows notes, else null. */
    final Rules.DatingNote note;
    /**
     * Notes allowed in one playthrough: the pack's {@code maxUses} ({@link Rules.DatingNote#UNLIMITED}
     * when the sources say the club keeps selling it; Siliconera and Prima Games both say it can be
     * bought again), 0 without a note.
     */
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
        this.t = Tables.of(rules, profile.christmasTaken() && rules.christmasEveDay() >= 0);
        this.profile = profile;
        this.ordinaryGain = Math.max(1, rules.policy.ordinaryGain);
        this.personaFactor = Math.max(1.0, rules.policy.personaMult);
        this.charmMult = profile.charmBonus ? rules.policy.charmMult : 1.0;
        this.bestAnswers = profile.answers == Profile.AnswerPolicy.BEST;
        this.goalBuffer = profile.goalBuffer;
        this.towerCap = new int[t.nBlocks];
        this.towerWant = new int[t.nBlocks];
        for (int b = 0; b < t.nBlocks; b++) {
            String id = rules.blocks[b].id;
            towerCap[b] = Math.max(0, profile.capFor(id));
            towerWant[b] = Math.max(0, Math.min(profile.visitsFor(id), towerCap[b]));
        }
        this.cashBuffer = Math.max(0, profile.cashBuffer);
        this.towerIncome = options == null ? 0 : Math.max(0, options.towerIncomePerVisit);
        this.lastTowerSlot = options == null ? -1 : options.lastTowerSlot;
        Rules.DatingNote n = rules.datingNote;
        this.note = profile.datingNotes && n != null && n.gain > 0 ? n : null;
        this.noteMaxUses = note == null ? 0 : Math.max(1, note.maxUses);

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
            // Route UNDECIDED (the default) counts as the friend route.
            boolean romance = profile.romance(l);
            eventGain[l] = romance && link.eventGainRomance != null ? link.eventGainRomance : link.eventGain;
            Map<Integer, Integer> stats = new TreeMap<>(link.unlock.statLv);
            reqStat[l] = keys(stats);
            reqStatLv[l] = values(stats);
            Map<Integer, Integer> links = new TreeMap<>(link.unlock.linkRank);
            reqLink[l] = keys(links);
            reqLinkRank[l] = values(links);
        }
        excluded = new boolean[t.nActs];
        Map<String, List<Integer>> dorms = new TreeMap<>();
        for (Rules.Activity a : rules.activities) {
            excluded[a.idx] = profile.excluded(a.id) || (a.requiresPlantedCrop && !profile.planDormGardening);
            String partnerId = a.dormPartnerId();
            if (partnerId != null) dorms.computeIfAbsent(partnerId, ignored -> new ArrayList<>()).add(a.idx);
        }
        dormTracks = new int[dorms.size()][];
        dormTargets = new Profile.DormTarget[dorms.size()];
        dormGroup = new int[t.nActs];
        Arrays.fill(dormGroup, -1);
        int group = 0;
        for (Map.Entry<String, List<Integer>> entry : dorms.entrySet()) {
            List<Integer> tracks = entry.getValue();
            dormTargets[group] = profile.dormTarget(entry.getKey());
            dormTracks[group] = new int[tracks.size()];
            for (int i = 0; i < tracks.size(); i++) {
                int a = tracks.get(i);
                dormTracks[group][i] = a;
                dormGroup[a] = group;
            }
            group++;
        }
        int[] unlockCounts = new int[t.nActs];
        for (Rules.Activity a : rules.activities) {
            if (a.requiresActivity >= 0 && !excluded[a.idx]) unlockCounts[a.requiresActivity]++;
        }
        unlockedActivities = new int[t.nActs][];
        for (int a = 0; a < t.nActs; a++) unlockedActivities[a] = new int[unlockCounts[a]];
        java.util.Arrays.fill(unlockCounts, 0);
        for (Rules.Activity a : rules.activities) {
            if (a.requiresActivity >= 0 && !excluded[a.idx]) {
                int prerequisite = a.requiresActivity;
                unlockedActivities[prerequisite][unlockCounts[prerequisite]++] = a.idx;
            }
        }
        gainMask = new int[t.nActs];
        for (Rules.Activity a : rules.activities) {
            for (int s = 0; s < Math.min(Math.min(a.gain.length, t.nStats), 31); s++) if (a.gain[s] > 0) gainMask[a.idx] |= 1 << s;
        }
        maxGain = new int[t.nStats];
        for (Rules.Activity a : rules.activities) {
            // Rare dated bonuses cannot make every future training slot look equally efficient.
            if (excluded[a.idx] || a.fixedDays != null) continue;
            for (int s = 0; s < Math.min(a.gain.length, t.nStats); s++) maxGain[s] = Math.max(maxGain[s], a.gain[s]);
        }
        episodeWanted = new boolean[t.nEps];
        for (int e = 0; e < t.nEps; e++) {
            Rules.Episode ep = rules.episodes[e];
            episodeWanted[e] = ep.id != null && ep.count > 0 && profile.wantsEpisodes(ep.id);
        }
    }

    void initializeBatches(SimState start) {
        batches = new RescueBatches(this, start, lastTowerSlot);
        if (!batches.hasGroups()) {
            batches = null;
            return;
        }
        start.plannedRescueGroups |= batches.initialMask();
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

    /** Whether this completion can still advance the player's chosen characteristic goal. */
    boolean dormTraitUseful(SimState state, int activity) {
        int group = dormGroup[activity];
        if (group < 0 || dormTargets[group] == Profile.DormTarget.NONE || state.used[activity] >= 3) return false;
        if (dormTargets[group] == Profile.DormTarget.UNLOCK) {
            int bestProgress = -1;
            for (int track : dormTracks[group]) {
                if (state.used[track] >= 3) return false;
                int progress = Math.max(0, state.used[track]);
                // Commit pure trait evenings to one finishable project. Otherwise alternating
                // calendars can consume evenings on both projects before either unlocks anything.
                // A closed/excluded project cannot strand another project's viable unlock.
                if (!excluded[track] && t.actRemain[track][state.slot] >= 3 - progress
                        && progress > bestProgress) {
                    bestProgress = progress;
                }
            }
            int progress = Math.max(0, state.used[activity]);
            // Tied projects may both compete, so today's available project can establish the
            // lead; subsequent pure trait evenings follow it instead of splitting the effort.
            return progress == bestProgress && !excluded[activity]
                    && t.actRemain[activity][state.slot] >= 3 - progress;
        }
        return true;
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
