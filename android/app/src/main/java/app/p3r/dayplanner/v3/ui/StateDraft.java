package app.p3r.dayplanner.v3.ui;

import java.util.ArrayList;
import java.util.List;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * Editing model behind the state wizard (onboarding / 同步现状 / 修改进度).
 *
 * <p>Every field starts from a prefill — the last confirmed value, or in sync mode after "是" the
 * plan's forecast — and only edits are stored (null = untouched). {@link #build()} produces the
 * new observed state: untouched values keep their stored hidden estimates exactly ("unknown is
 * not zero"); a changed rank resets that link's hidden points to the conservative 0; a changed
 * stat Lv sets the hidden points to that level's lower bound.
 */
public final class StateDraft {
    /** Wizard flavour (Intents.EXTRA_STATE_MODE). */
    public enum Mode {
        ONBOARDING("onboarding"), SYNC("sync"), EDIT("edit");

        public final String key;

        Mode(String key) {
            this.key = key;
        }

        public static Mode of(String key) {
            for (Mode m : values()) {
                if (m.key.equals(key)) {
                    return m;
                }
            }
            return EDIT;
        }
    }

    /** Answer to "上次记到 10月21日 晚上，之后照着安排玩了吗？" (基本照着玩了 / 玩了一部分 / 没怎么照着玩). */
    public enum SyncAnswer { YES, PARTLY, NO }

    /** Answer to "北村文吉救出来了吗？" (stored per rescue index). */
    public static final int RESCUE_UNSURE = 0;
    public static final int RESCUE_DONE = 1;
    public static final int RESCUE_FAILED = 2;

    /** RANK value meaning 「不记得」 (keep what was stored; on a first run saved as 未解锁). */
    public static final int UNKNOWN = -1;

    /** How a link left at {@link #UNKNOWN} reads in 同步现状 / 修改进度 (the record is kept). */
    public static final String UNSURE_RANK = "不记得";
    /** Tag beside a rescue the record says failed, while that answer is preselected. */
    public static final String RECORDED_FAILURE_NOTE = "上次记录";

    /** One line of the confirmation diff ("皇帝 RANK 6 → 7"). */
    public static final class Change {
        public final String labelZh;
        public final String beforeZh;
        public final String afterZh;
        /** Wizard page that edits this value ({@code StateSteps.Page} name), or null. */
        public final String page;
        /** The new value is the plan's forecast (after 「基本照着玩了」), not typed by the player. */
        public final boolean forecast;

        Change(String labelZh, String beforeZh, String afterZh) {
            this(labelZh, beforeZh, afterZh, null, false);
        }

        Change(String labelZh, String beforeZh, String afterZh, String page, boolean forecast) {
            this.labelZh = labelZh;
            this.beforeZh = beforeZh;
            this.afterZh = afterZh;
            this.page = page;
            this.forecast = forecast;
        }
    }

    public final Mode mode;
    public final Rules rules;
    /** Confirmed state the draft is based on (null on first run). */
    public final SimState observed;
    /** Starting point: the confirmed state, or a new-game state on first run. */
    public final SimState base;
    public final Plan plan;
    private final Profile originalProfile;
    /** Working copy of the profile (goals and the 人格面具 answer are edited in onboarding). */
    public final Profile profile;
    /** State revision the draft was started from; drafts from other revisions are discarded. */
    public final long baseRevision;

    // ---- edits (null = untouched) ----
    SyncAnswer sync;
    Integer slot;
    Long cash;
    Boolean cashUnknown;
    final Integer[] statLv = new Integer[3];
    final Integer[] rank = new Integer[Profile.LINKS];
    final Integer[] unlock = new Integer[Profile.LINKS];
    Integer floor;
    Integer towerVisits;
    /** Rescue answers per index into {@code rules.rescues} ({@link #RESCUE_DONE} …), null = untouched. */
    final Integer[] rescue;
    /** 羁绊剧情 watched per track (index into {@code rules.episodes}), null = untouched. */
    final Integer[] episodes;
    /** Actual dorm activity counts per stable activity (null = keep the record / confirmed sync prefill). */
    final Integer[] dormCounts;
    /** Explicit gardening planning choice (队友特性's sheet); independent of the counts already completed. */
    Boolean dormGardening;
    /** 「做过」 was answered on the 宿舍活动 page (the counts are asked even while all are 0). */
    boolean dormAsked;
    /** 平安夜 answer: null = untouched, "" = 都婉拒了, else the id of the link whose invitation was accepted. */
    String christmas;
    String step;
    /** Page a single page was opened from (修改哪一项？ or the review), null while walking the pages. */
    String returnTo;

    private int cachedPrefillSlot = Integer.MIN_VALUE;
    private SyncAnswer cachedPrefillAnswer;
    private SimState cachedPrefill;

    public StateDraft(Mode mode, Rules rules, SimState observed, Profile profile, Plan plan, long baseRevision) {
        this.mode = mode;
        this.rules = rules;
        this.observed = observed;
        this.plan = plan;
        this.baseRevision = baseRevision;
        this.originalProfile = profile == null ? new Profile() : profile;
        this.profile = this.originalProfile.copy();
        this.base = observed != null ? observed.copy() : newGame(rules);
        this.rescue = new Integer[rules.rescues.length];
        this.episodes = new Integer[rules.episodes.length];
        this.dormCounts = new Integer[rules.activities.length];
    }

    /** A new-game state at the first free slot: everything at the bottom, cash unknown. */
    public static SimState newGame(Rules rules) {
        SimState s = new SimState(rules.blocks.length, rules.activities.length);
        s.slot = firstFreeSlot(rules);
        s.cash = 0;
        s.cashExact = false;
        for (int i = 0; i < Math.min(3, rules.stats.length); i++) {
            s.statPts[i] = rules.stats[i].thresholds[1];
        }
        return s;
    }

    /** First slot of the calendar that is not locked. */
    public static int firstFreeSlot(Rules rules) {
        for (int slot = rules.firstSlot(); slot <= rules.lastSlot(); slot++) {
            if (rules.lockOf(slot) == null) {
                return slot;
            }
        }
        return rules.firstSlot();
    }

    // ------------------------------------------------------------------ prefill

    /** The state values are shown from before edits (forecast after "是" in sync mode). */
    public SimState prefill() {
        int at = slot();
        if (cachedPrefill == null || cachedPrefillSlot != at || cachedPrefillAnswer != sync) {
            if (mode == Mode.SYNC && sync == SyncAnswer.YES && plan != null && observed != null) {
                cachedPrefill = Forecast.visibleAt(rules, base, plan, at);
            } else {
                cachedPrefill = base.copy();
                cachedPrefill.slot = at;
            }
            cachedPrefillSlot = at;
            cachedPrefillAnswer = sync;
        }
        return cachedPrefill;
    }

    /** Forecast at the chosen slot regardless of the answer (for "预计" hints after "一部分"). */
    public SimState forecast() {
        if (plan == null || observed == null) {
            return null;
        }
        return Forecast.visibleAt(rules, base, plan, slot());
    }

    // ------------------------------------------------------------------ getters (display values)

    public SyncAnswer sync() {
        return sync;
    }

    public int slot() {
        return slot != null ? slot : base.slot;
    }

    /** True when the player said 「不记得」 (or, on first run, has not entered the cash yet). */
    public boolean cashUnknown() {
        if (cashUnknown != null) {
            return cashUnknown;
        }
        if (cash != null) {
            return false;
        }
        if (mode == Mode.ONBOARDING) {
            return true;
        }
        return !base.cashExact;
    }

    public long cash() {
        return cash != null ? cash : prefill().cash;
    }

    public int statLv(int stat) {
        if (statLv[stat] != null) {
            return statLv[stat];
        }
        return rules.stats[stat].levelFor(prefill().statPts[stat]);
    }

    /** Displayed rank; {@link #UNKNOWN} when the player chose "?". */
    public int rank(int link) {
        return rank[link] != null ? rank[link] : prefill().rank[link];
    }

    public int unlockStep(int link) {
        return unlock[link] != null ? unlock[link] : prefill().unlockStep[link];
    }

    /** Block active on the chosen date, or null. */
    public Rules.Block block() {
        return rules.blockOn(Rules.dayOf(slot()));
    }

    /** Highest floor reached, never above what the chosen date allows. */
    public int floor() {
        return Math.max(0, Math.min(towerTop(), floor != null ? floor : prefill().floor));
    }

    public int towerVisits() {
        if (towerVisits != null) {
            return towerVisits;
        }
        Rules.Block b = block();
        SimState p = prefill();
        return b == null || b.idx >= p.towerVisits.length ? 0 : p.towerVisits[b.idx];
    }

    /** Highest floor reachable by the chosen date: the top of the last block that has opened, 0 if none. */
    public int towerTop() {
        int day = Rules.dayOf(slot());
        int top = 0;
        for (Rules.Block b : rules.blocks) {
            if (b.fromDay <= day) {
                top = Math.max(top, b.top);
            }
        }
        return top;
    }

    /**
     * Rescues to ask about on the 塔尔塔罗斯 page: those that decide whether a link can continue
     * and whose window has started by the chosen date (indices into {@code rules.rescues}).
     */
    public List<Integer> rescuesToAsk() {
        List<Integer> out = new ArrayList<>();
        int day = Rules.dayOf(slot());
        for (int i = 0; i < rules.rescues.length && i < 63; i++) {
            Rules.Rescue r = rules.rescues[i];
            if (r.link >= 0 && day >= r.fromDay) {
                out.add(i);
            }
        }
        return out;
    }

    /** True while the rescue window is still running on the chosen date ("还没救出" is fine then). */
    public boolean rescueOpen(int index) {
        return Rules.dayOf(slot()) <= rules.rescues[index].toDay;
    }

    /**
     * Displayed answer: the edit, else "救出了" when the record (or the forecast) says so, else
     * "没救出" when the record says so (its window has closed), else unknown ({@link #RESCUE_UNSURE}). A recorded result is
     * kept unless the player changes it here ({@link #build}).
     */
    public int rescueAnswer(int index) {
        if (rescue[index] != null) {
            return rescue[index];
        }
        long bit = 1L << index;
        if ((prefill().rescued & bit) != 0) {
            return RESCUE_DONE;
        }
        return recordSaysFailed(index) ? RESCUE_FAILED : RESCUE_UNSURE;
    }

    /**
     * True when the record says 没救出 for this rescue (its window has closed by the chosen date)
     * and the player has not answered here yet: 没救出 is preselected with
     * {@link #RECORDED_FAILURE_NOTE}.
     */
    public boolean recordSaysFailed(int index) {
        long bit = 1L << index;
        return rescue[index] == null && (base.rescueFailed & bit) != 0 && (prefill().rescued & bit) == 0
            && !rescueOpen(index);
    }

    // ------------------------------------------------------------------ 羁绊剧情

    /** Opted-in 羁绊剧情 tracks (indices into {@code rules.episodes}), asked on their own page. */
    public List<Integer> episodeTracks() {
        List<Integer> out = new ArrayList<>();
        for (Rules.Episode track : rules.episodes) {
            if (profile.wantsEpisodes(track.id)) {
                out.add(track.idx);
            }
        }
        return out;
    }

    /**
     * Opted-in tracks worth asking about at the chosen date: an episode could be watched on or
     * before it, or some are already counted as watched.
     */
    public List<Integer> visibleEpisodeTracks() {
        List<Integer> out = new ArrayList<>();
        int day = Rules.dayOf(slot());
        for (int track : episodeTracks()) {
            Rules.Episode e = rules.episodes[track];
            boolean open = false;
            for (int d = 0; d <= day && d < e.dayMask.length && !open; d++) {
                open = e.dayMask[d] != 0;
            }
            if (open || episodesWatched(track) > 0) {
                out.add(track);
            }
        }
        return out;
    }

    /** Episodes of a track already watched (0..count): the edit, else the record / forecast. */
    public int episodesWatched(int track) {
        if (episodes[track] != null) {
            return episodes[track];
        }
        return watched(rules, prefill(), track);
    }

    public void setEpisodesWatched(int track, int value) {
        episodes[track] = Math.max(0, Math.min(rules.episodes[track].count, value));
    }

    /** True when the displayed count comes from the plan forecast and differs from the record. */
    public boolean isForecastEpisodes(int track) {
        return episodes[track] == null && watched(rules, prefill(), track) != watched(rules, base, track);
    }

    static int watched(Rules rules, SimState s, int track) {
        if (s == null || track < 0 || track >= s.episodes.length || track >= rules.episodes.length) {
            return 0;
        }
        return Math.max(0, Math.min(rules.episodes[track].count, s.episodes[track]));
    }

    /** "还没看" / "已看 2 话" / "全部看完". */
    static String episodesText(Rules.Episode track, int watched) {
        if (track.hasUnplannedScenes()) {
            return watched <= 0 ? "还没看"
                : "已看第 " + track.sceneOf(Math.min(track.count, watched)) + " 话";
        }
        if (watched <= 0) {
            return "还没看";
        }
        return watched >= track.count ? "全部看完" : "已看 " + watched + " 话";
    }

    static String episodeChipText(Rules.Episode track, int watched) {
        if (track.hasUnplannedScenes()) return episodesText(track, watched);
        return watched == 0 ? "还没看" : watched == track.count ? "全部 " + watched + " 话" : watched + " 话";
    }

    // ------------------------------------------------------------------ 宿舍活动

    /** Dorm activity types introduced by the chosen game date, including those already completed. */
    public List<Rules.Activity> dormActivities() {
        List<Rules.Activity> out = new ArrayList<>();
        int day = Rules.dayOf(slot());
        for (Rules.Activity activity : rules.activities) {
            if (isDorm(activity) && activity.fromDay <= day) {
                out.add(activity);
            }
        }
        return out;
    }

    static boolean isDorm(Rules.Activity activity) {
        return activity.dormPartnerZh != null && !activity.dormPartnerZh.isEmpty();
    }

    /** Counts are capped for display at the three completions needed for this activity's milestone. */
    public int dormCount(int activity) {
        return dormCounts[activity] != null ? dormCounts[activity] : dormCount(prefill(), activity);
    }

    public void setDormCount(int activity, int count) {
        if (activity >= 0 && activity < rules.activities.length && isDorm(rules.activities[activity])) {
            dormCounts[activity] = Math.max(0, Math.min(3, count));
        }
    }

    static int dormCount(SimState state, int activity) {
        return state == null || activity < 0 || activity >= state.used.length
            ? 0 : Math.max(0, Math.min(3, state.used[activity]));
    }

    public boolean isForecastDormCount(int activity) {
        return dormCounts[activity] == null && dormCount(prefill(), activity) != dormCount(base, activity);
    }

    /** Trait reached with the displayed counts (「特性已强化」 / 「特性已解锁」), null when none yet. */
    public String dormMilestone(String partner) {
        int tracks = 0;
        int completed = 0;
        for (Rules.Activity activity : rules.activities) {
            if (partner.equals(activity.dormPartnerZh)) {
                tracks++;
                if (dormCount(activity.idx) >= 3) completed++;
            }
        }
        if (tracks >= 2 && completed == tracks) return "特性已强化";
        if (completed > 0) return "特性已解锁";
        return null;
    }

    /** True when every displayed dorm count of the chosen date is 0 (「做过宿舍活动吗？」 comes first). */
    public boolean noDormYet() {
        for (Rules.Activity activity : dormActivities()) {
            if (dormCount(activity.idx) > 0) {
                return false;
            }
        }
        return true;
    }

    public void setDormGardening(boolean enabled) {
        dormGardening = enabled;
        profile.planDormGardening = enabled;
    }

    public void setRescue(int index, int answer) {
        rescue[index] = Math.max(RESCUE_UNSURE, Math.min(RESCUE_FAILED, answer));
    }

    // ------------------------------------------------------------------ 平安夜

    /**
     * The wizard asks about 12/24 from 12/20 to 12/24 (before and after, it is a 规划设置 matter),
     * and only when someone could have invited the player.
     */
    public boolean asksChristmas() {
        int day = Rules.dayOf(slot());
        return day >= ChristmasChoice.askFromDay() && day <= ChristmasChoice.askUntilDay()
            && !christmasCandidates().isEmpty();
    }

    /** Links whose 12/24 invitation can be chosen, with the displayed ranks. */
    public List<Rules.Link> christmasCandidates() {
        return ChristmasChoice.candidates(rules, i -> Math.max(0, rank(i)), christmasLink());
    }

    /** Link id of the accepted 12/24 invitation (the edit, else the profile's), null = 都婉拒了. */
    public String christmasLink() {
        if (christmas != null) {
            return christmas.isEmpty() ? null : christmas;
        }
        return profile.christmasLink;
    }

    /** Records the 平安夜 answer (null = 都婉拒了); it is saved with the profile. */
    public void setChristmasLink(String linkId) {
        christmas = linkId == null ? "" : linkId;
        profile.christmasLink = linkId;
    }

    public String currentStep() {
        return step;
    }

    /** True when a displayed value comes from the plan forecast and differs from the record. */
    public boolean isForecastRank(int link) {
        return rank[link] == null && prefill().rank[link] != base.rank[link];
    }

    public boolean isForecastStat(int stat) {
        return statLv[stat] == null && rules.stats[stat].levelFor(prefill().statPts[stat])
            != rules.stats[stat].levelFor(base.statPts[stat]);
    }

    public boolean isForecastCash() {
        return cash == null && cashUnknown == null && prefill().cash != base.cash;
    }

    /** The cash as 修改哪一项？ lists it: 「18,500 日元」, a known lower bound 「290+ 日元」 (as on 今天), or 「不记得」. */
    public String cashText() {
        return cashText(cashUnknown(), cash());
    }

    /**
     * True when the player has answered or changed anything here (not only walked the pages): a
     * draft restored onto a newer record only then keeps 「你填的」.
     */
    public boolean hasEdits() {
        if (sync != null || slot != null || cash != null || cashUnknown != null || floor != null
            || towerVisits != null || christmas != null || profileChanged()) {
            return true;
        }
        for (Integer[] edits : new Integer[][]{statLv, rank, unlock, rescue, episodes, dormCounts}) {
            for (Integer edit : edits) {
                if (edit != null) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ setters (player edits)

    public void setSync(SyncAnswer answer) {
        sync = answer;
    }

    public void setSlot(int value) {
        slot = value;
        towerVisits = null;
    }

    public void setCash(long value) {
        cash = Math.max(0, value);
        cashUnknown = false;
    }

    public void setCashUnknown() {
        cashUnknown = true;
        cash = null;
    }

    public void setStatLv(int stat, int level) {
        statLv[stat] = Math.max(1, Math.min(6, level));
    }

    /** Sets a rank (0..10) or {@link #UNKNOWN}. */
    public void setRank(int link, int value) {
        rank[link] = Math.max(UNKNOWN, Math.min(10, value));
    }

    public void setUnlockStep(int link, int value) {
        unlock[link] = Math.max(0, value);
    }

    public void setFloor(int value) {
        floor = Math.max(0, value);
    }

    public void setTowerVisits(int value) {
        towerVisits = Math.max(0, value);
    }

    public void setStep(String value) {
        step = value;
    }

    /** Cycles a goal tier 未选→想要→必须→未选 (placing the link last in its new tier). */
    public Profile.Tier cycleTier(int link) {
        return Goals.cycle(profile, link);
    }

    // ------------------------------------------------------------------ derived lists

    /** Links whose unlock progress matters now: not yet established, multi-step, reachable. */
    public List<Integer> unlockLinks() {
        List<Integer> out = new ArrayList<>();
        int day = Rules.dayOf(slot());
        for (Rules.Link link : rules.links) {
            boolean wanted = profile.isGoal(link.idx) || profile.otherLinks;
            if (!wanted || link.kind != Rules.LinkKind.MANUAL) {
                continue;
            }
            int r = rank(link.idx);
            if (r != 0 || link.unlock.steps.size() < 2 || day < link.unlock.earliestDay) {
                continue;
            }
            out.add(link.idx);
        }
        return out;
    }

    // ------------------------------------------------------------------ result

    /** The new observed state built from the prefill plus the player's edits. */
    public SimState build() {
        SimState p = prefill();
        SimState s = p.copy();
        s.slot = slot();
        if (cashUnknown()) {
            s.cash = cash != null ? cash : p.cash;
            s.cashExact = false;
        } else {
            s.cash = cash();
            s.cashExact = true;
        }
        for (int i = 0; i < Math.min(3, rules.stats.length); i++) {
            int lv = statLv(i);
            if (lv != rules.stats[i].levelFor(p.statPts[i])) {
                // Back to the recorded Lv (the forecast differed): the record's points stay.
                boolean recorded = lv == rules.stats[i].levelFor(base.statPts[i]);
                s.statPts[i] = recorded ? base.statPts[i] : rules.stats[i].thresholds[lv];
            }
        }
        for (Rules.Link link : rules.links) {
            int i = link.idx;
            int r = rank(i);
            if (r == p.rank[i]) {
                continue;
            }
            if (r == UNKNOWN || r == base.rank[i]) {
                // "?" or the recorded RANK keeps the confirmed record (points, cue, unlock
                // progress), never the forecast shown after "是".
                s.rank[i] = base.rank[i];
                s.pts[i] = base.pts[i];
                s.ready[i] = base.ready[i];
                s.unlockStep[i] = base.unlockStep[i];
                s.lastStepDay[i] = base.lastStepDay[i];
                continue;
            }
            s.rank[i] = r;
            s.pts[i] = 0;
            s.ready[i] = false;
            if (r >= 1) {
                s.unlockStep[i] = Math.max(s.unlockStep[i], link.unlock.steps.size());
            }
        }
        for (int i = 0; i < Profile.LINKS; i++) {
            if (unlock[i] != null && s.rank[i] == 0) {
                s.unlockStep[i] = unlock[i];
            }
        }
        Rules.Block b = block();
        if (b != null && b.idx < s.towerVisits.length) {
            s.towerVisits[b.idx] = towerVisits();
        }
        s.floor = floor();
        // A recorded result (救出了 / 没救出) stays unless the player changes it here; a window that
        // is still open on the chosen date cannot have failed yet.
        int day = Rules.dayOf(s.slot);
        for (int i = 0; i < rescue.length && i < 63; i++) {
            long bit = 1L << i;
            if (rescue[i] == null) {
                if (day <= rules.rescues[i].toDay) {
                    s.rescueFailed &= ~bit;
                }
                continue;
            }
            s.rescued &= ~bit;
            s.rescueFailed &= ~bit;
            if (rescue[i] == RESCUE_DONE) {
                s.rescued |= bit;
            } else if (rescue[i] == RESCUE_FAILED) {
                s.rescueFailed |= bit;
            }
        }
        boolean episodeEdits = false;
        for (Integer e : episodes) {
            episodeEdits |= e != null;
        }
        if (episodeEdits) {
            if (s.episodes.length < rules.episodes.length) {
                s.episodes = java.util.Arrays.copyOf(s.episodes, rules.episodes.length);
            }
            for (int i = 0; i < episodes.length; i++) {
                if (episodes[i] != null) {
                    s.episodes[i] = episodes[i];
                }
            }
        }
        for (Rules.Activity activity : rules.activities) {
            if (isDorm(activity) && dormCounts[activity.idx] != null) {
                if (s.used.length < rules.activities.length) {
                    s.used = java.util.Arrays.copyOf(s.used, rules.activities.length);
                }
                // Correcting past activity counts does not grant past social-stat points again.
                s.used[activity.idx] = dormCounts[activity.idx];
            }
        }
        return s;
    }

    /**
     * The profile to save with the checkpoint: always on onboarding, otherwise only when it was
     * changed here (the 平安夜 answer); else null.
     */
    public Profile profileToSave() {
        return mode == Mode.ONBOARDING || profileChanged() ? profile : null;
    }

    /** What saving will change, compared with the confirmed record. */
    public List<Change> changes() {
        List<Change> out = new ArrayList<>();
        SimState after = build();
        SimState before = observed;
        if (before == null) {
            return summaryForFirstRun(after);
        }
        SimState shown = prefill();
        if (before.slot != after.slot) {
            // 「10月25日 白天」, as in the rest of the app's text (M/D is for date plates only).
            out.add(new Change("日期", Zh.slot(rules, before.slot), Zh.slot(rules, after.slot), "DATE", false));
        }
        // As 今天 shows it: a lower bound reads 「6,000+ 日元」, never 「不记得 → 不记得」.
        String cashBefore = cashText(before);
        String cashAfter = cashText(after);
        if (!cashBefore.equals(cashAfter)) {
            out.add(new Change("现金", cashBefore, cashAfter, "CASH", isForecastCash()));
        }
        for (int i = 0; i < Math.min(3, rules.stats.length); i++) {
            int a = rules.stats[i].levelFor(before.statPts[i]);
            int b = rules.stats[i].levelFor(after.statPts[i]);
            if (a != b) {
                out.add(new Change(rules.stats[i].zh, Zh.lv(a), Zh.lv(b), "STATS", isForecastStat(i)));
            }
        }
        for (Rules.Link link : rules.links) {
            int a = before.rank[link.idx];
            int b = after.rank[link.idx];
            if (a != b) {
                out.add(new Change(link.arcanaZh, Zh.rank(a), Zh.rank(b), "LINKS", isForecastRank(link.idx)));
            } else if (b == 0 && before.unlockStep[link.idx] != after.unlockStep[link.idx]) {
                boolean predicted = unlock[link.idx] == null && rank[link.idx] == null
                    && shown.unlockStep[link.idx] != base.unlockStep[link.idx];
                out.add(new Change(link.arcanaZh + " · 准备", unlockText(link, before.unlockStep[link.idx]),
                    unlockText(link, after.unlockStep[link.idx]), "UNLOCK", predicted));
            }
        }
        if (before.floor != after.floor) {
            out.add(new Change("最高到达", before.floor + "F", after.floor + "F", "TOWER",
                floor == null && shown.floor != base.floor));
        }
        for (int i = 0; i < rules.rescues.length && i < 63; i++) {
            String a = rescueText(before, i, rescueOpen(i));
            String c = rescueText(after, i, rescueOpen(i));
            if (!a.equals(c)) {
                long bit = 1L << i;
                out.add(new Change("失踪者 · " + rules.rescues[i].zh, a, c, "TOWER",
                    rescue[i] == null && (shown.rescued & bit) != (base.rescued & bit)));
            }
        }
        for (Rules.Episode track : rules.episodes) {
            int a = watched(rules, before, track.idx);
            int e = watched(rules, after, track.idx);
            if (a != e) {
                out.add(new Change(Outcomes.episodeName(track), episodesText(track, a), episodesText(track, e),
                    "EPISODES", isForecastEpisodes(track.idx)));
            }
        }
        for (Rules.Activity activity : rules.activities) {
            if (!isDorm(activity)) continue;
            int a = dormCount(before, activity.idx);
            int c = dormCount(after, activity.idx);
            if (a != c) {
                out.add(new Change(activity.zh, a + " 次", c + " 次", "DORM", isForecastDormCount(activity.idx)));
            }
        }
        Rules.Block b = block();
        if (b != null && b.idx < before.towerVisits.length && before.towerVisits[b.idx] != after.towerVisits[b.idx]) {
            boolean predicted = towerVisits == null && b.idx < shown.towerVisits.length
                && shown.towerVisits[b.idx] != base.towerVisits[b.idx];
            out.add(new Change(b.zh + " 去过", before.towerVisits[b.idx] + " 次", after.towerVisits[b.idx] + " 次",
                "TOWER", predicted));
        }
        String christmasBefore = originalProfile.christmasLink;
        String christmasAfter = christmasLink();
        if (christmasBefore == null ? christmasAfter != null : !christmasBefore.equals(christmasAfter)) {
            out.add(new Change("平安夜", ChristmasChoice.answerText(rules, christmasBefore),
                ChristmasChoice.answerText(rules, christmasAfter), "CHRISTMAS", false));
        }
        return out;
    }

    /** "救出了" / "没救出"; not known: "还没" while the window runs, "—" once it has closed. */
    private static String rescueText(SimState s, int index, boolean open) {
        long bit = 1L << index;
        if ((s.rescued & bit) != 0) {
            return "救出了";
        }
        if ((s.rescueFailed & bit) != 0) {
            return "没救出";
        }
        return open ? "还没" : "—";
    }

    /** Links already unlocked in {@code s} (RANK 1 or above). */
    int established(SimState s) {
        int count = 0;
        for (Rules.Link link : rules.links) {
            if (link.idx < s.rank.length && s.rank[link.idx] > 0) {
                count++;
            }
        }
        return count;
    }

    /** "已解锁 3 个" / "都还没解锁". */
    static String establishedText(int count) {
        return count > 0 ? "已解锁 " + count + " 个" : "都还没解锁";
    }

    /**
     * What a first run saves, for the 准备好了？ page: the date, cash, the three stats, how many
     * links are unlocked (a link left at 「?」 is saved as 未解锁, like any other), the goals, the
     * 人格面具 answer, then the answers of the pages that came up for the chosen date.
     */
    private List<Change> summaryForFirstRun(SimState after) {
        List<Change> out = new ArrayList<>();
        out.add(new Change("日期", null, Zh.slot(rules, after.slot), "DATE", false));
        out.add(new Change("现金", null, cashText(after), "CASH", false));
        StringBuilder stats = new StringBuilder();
        for (int i = 0; i < Math.min(3, rules.stats.length); i++) {
            if (i > 0) {
                stats.append(" · ");
            }
            stats.append(rules.stats[i].zh).append(' ').append(Zh.lv(rules.stats[i].levelFor(after.statPts[i])));
        }
        out.add(new Change("个人能力值", null, stats.toString(), "STATS", false));
        out.add(new Change("社群", null, establishedText(established(after)), "LINKS", false));
        List<Integer> goals = LinkStatus.goalOrder(profile);
        out.add(new Change("目标", null, goals.isEmpty() ? "没选，所有社群都尽量提升" : goalSummary(goals), "GOALS", false));
        out.add(new Change("人格面具", null, personaShort(profile.effectivePersonaMode()), "PERSONA", false));
        for (int i : rescuesToAsk()) {
            out.add(new Change("失踪者 · " + rules.rescues[i].zh, null, rescueText(after, i, rescueOpen(i)), "TOWER", false));
        }
        for (int track : visibleEpisodeTracks()) {
            Rules.Episode e = rules.episodes[track];
            out.add(new Change(Outcomes.episodeName(e), null, episodesText(e, watched(rules, after, track)),
                "EPISODES", false));
        }
        for (Rules.Activity activity : dormActivities()) {
            int count = dormCount(after, activity.idx);
            if (count > 0) out.add(new Change(activity.zh, null, count + " 次", "DORM", false));
        }
        if (asksChristmas()) {
            out.add(new Change("平安夜", null, ChristmasChoice.answerText(rules, christmasLink()), "CHRISTMAS", false));
        }
        return out;
    }

    /** Short value for summaries ("只给目标社群带"). */
    static String personaShort(Profile.PersonaMode mode) {
        switch (mode) {
            case ALL:
                return "都带";
            case GOALS:
                return "只给目标社群带";
            default:
                return "不带";
        }
    }

    /** "必须 皇帝、恋爱 · 想要 太阳". */
    private String goalSummary(List<Integer> goals) {
        StringBuilder must = new StringBuilder();
        StringBuilder want = new StringBuilder();
        for (int g : goals) {
            StringBuilder target = profile.tier[g] == Profile.Tier.MUST ? must : want;
            if (target.length() > 0) {
                target.append("、");
            }
            target.append(rules.links[g].arcanaZh);
        }
        StringBuilder text = new StringBuilder();
        if (must.length() > 0) {
            text.append("必须 ").append(must);
        }
        if (want.length() > 0) {
            text.append(text.length() > 0 ? " · " : "").append("想要 ").append(want);
        }
        return text.toString();
    }

    /** "18,500 日元"; not known exactly: the lower bound "290+ 日元" (as on 今天), or 「不记得」 at 0. */
    static String cashText(SimState s) {
        return cashText(!s.cashExact, s.cash);
    }

    private static String cashText(boolean unknown, long cash) {
        if (!unknown) {
            return Zh.yen(cash);
        }
        return cash > 0 ? Zh.number(cash) + "+ 日元" : CashPad.UNKNOWN_LABEL;
    }

    /** "还没做" / "做完「再借给社长 10,000 日元」" (the last step done). */
    static String unlockText(Rules.Link link, int done) {
        if (done <= 0) {
            return "还没做";
        }
        int index = Math.min(done, link.unlock.steps.size()) - 1;
        return "做完「" + link.unlock.steps.get(index).zh + "」";
    }

    /** Carries the player's edits over to a draft rebased on a newer record (after a CAS conflict). */
    public void copyEditsFrom(StateDraft other) {
        sync = other.sync;
        slot = other.slot;
        cash = other.cash;
        cashUnknown = other.cashUnknown;
        System.arraycopy(other.statLv, 0, statLv, 0, statLv.length);
        System.arraycopy(other.rank, 0, rank, 0, rank.length);
        System.arraycopy(other.unlock, 0, unlock, 0, unlock.length);
        floor = other.floor;
        towerVisits = other.towerVisits;
        System.arraycopy(other.rescue, 0, rescue, 0, Math.min(rescue.length, other.rescue.length));
        System.arraycopy(other.episodes, 0, episodes, 0, Math.min(episodes.length, other.episodes.length));
        System.arraycopy(other.dormCounts, 0, dormCounts, 0, Math.min(dormCounts.length, other.dormCounts.length));
        dormAsked = other.dormAsked;
        if (other.christmas != null) {
            setChristmasLink(other.christmas.isEmpty() ? null : other.christmas);
        }
        step = other.step;
        returnTo = other.returnTo;
        if (mode == Mode.ONBOARDING) {
            System.arraycopy(other.profile.tier, 0, profile.tier, 0, Profile.LINKS);
            System.arraycopy(other.profile.order, 0, profile.order, 0, Profile.LINKS);
            setPersonaMode(other.profile.effectivePersonaMode());
            profile.otherLinks = other.profile.otherLinks;
        }
        cachedPrefill = null;
    }

    /** Sets the 人格面具 answer (onboarding), keeping the legacy boolean in step. */
    public void setPersonaMode(Profile.PersonaMode mode) {
        profile.personaMode = mode;
        profile.personaBonus = mode == Profile.PersonaMode.ALL;
    }

    /** True when the profile differs from the one the draft started with. */
    public boolean profileChanged() {
        try {
            return !profile.toJson().toString().equals(originalProfile.toJson().toString());
        } catch (org.json.JSONException e) {
            return true;
        }
    }
}
