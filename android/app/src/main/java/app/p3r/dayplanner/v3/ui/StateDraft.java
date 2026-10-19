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

    /** Answer to "这段时间基本照着安排玩了吗？". */
    public enum SyncAnswer { YES, PARTLY, NO }

    /** Answer to "北村文吉救出来了吗？" (stored per rescue index). */
    public static final int RESCUE_UNSURE = 0;
    public static final int RESCUE_DONE = 1;
    public static final int RESCUE_FAILED = 2;

    /** RANK value meaning "?" (not sure — keep what was stored). */
    public static final int UNKNOWN = -1;

    /** How the first-run summary lists a link left at "?" (saved as 未解锁). */
    public static final String UNSURE_RANK = "不确定（先按未解锁安排）";
    /** Shown under a rescue the record says failed, while that answer is preselected. */
    public static final String RECORDED_FAILURE_NOTE = "上次记录的结果，如果其实救出了请改。";

    /** One line of the confirmation diff ("皇帝 RANK 6 → 7"). */
    public static final class Change {
        public final String labelZh;
        public final String beforeZh;
        public final String afterZh;

        Change(String labelZh, String beforeZh, String afterZh) {
            this.labelZh = labelZh;
            this.beforeZh = beforeZh;
            this.afterZh = afterZh;
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
    final Boolean[] ready = new Boolean[Profile.LINKS];
    final Integer[] unlock = new Integer[Profile.LINKS];
    Integer floor;
    Integer towerVisits;
    /** Rescue answers per index into {@code rules.rescues} ({@link #RESCUE_DONE} …), null = untouched. */
    final Integer[] rescue;
    /** 羁绊剧情 watched per track (index into {@code rules.episodes}), null = untouched. */
    final Integer[] episodes;
    /** 平安夜 answer: null = untouched, "" = 都婉拒了, else the id of the link whose invitation was accepted. */
    String christmas;
    String step;

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

    /** True when the player said "不确定" (or, on first run, has not entered the cash yet). */
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

    /** True when the link is marked ready to rank up on the next meeting. */
    public boolean ready(int link) {
        if (ready[link] != null) {
            return ready[link];
        }
        int r = rank(link);
        if (r == UNKNOWN || r == base.rank[link]) {
            return base.ready[link];
        }
        return r == prefill().rank[link] && prefill().ready[link];
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
     * "没救出" when the record says so (its window has closed), else "不确定". A recorded result is
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
        if (watched <= 0) {
            return "还没看";
        }
        return watched >= track.count ? "全部看完" : "已看 " + watched + " 话";
    }

    public void setRescue(int index, int answer) {
        rescue[index] = Math.max(RESCUE_UNSURE, Math.min(RESCUE_FAILED, answer));
    }

    // ------------------------------------------------------------------ 平安夜

    /** The wizard asks about 12/24 from 12/20 on, and always in 修改进度. */
    public boolean asksChristmas() {
        return mode == Mode.EDIT || Rules.dayOf(slot()) >= ChristmasChoice.askFromDay();
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
        int clamped = Math.max(UNKNOWN, Math.min(10, value));
        if (clamped <= 0 || clamped >= 10 || (rank[link] != null && rank[link] != clamped)) {
            ready[link] = null;
        }
        rank[link] = clamped;
    }

    /** Marks whether the game shows that this link will rank up on the next meeting. */
    public void setReady(int link, boolean value) {
        ready[link] = value;
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
            if (r != p.rank[i]) {
                if (r == UNKNOWN || r == base.rank[i]) {
                    // "?" or the recorded RANK keeps the confirmed record (points, cue, unlock
                    // progress), never the forecast shown after "是".
                    s.rank[i] = base.rank[i];
                    s.pts[i] = base.pts[i];
                    s.ready[i] = base.ready[i];
                    s.unlockStep[i] = base.unlockStep[i];
                    s.lastStepDay[i] = base.lastStepDay[i];
                } else {
                    s.rank[i] = r;
                    s.pts[i] = 0;
                    s.ready[i] = false;
                    if (r >= 1) {
                        s.unlockStep[i] = Math.max(s.unlockStep[i], link.unlock.steps.size());
                    }
                }
            }
            if (ready[i] != null && s.rank[i] >= 1 && s.rank[i] < 10 && link.kind == Rules.LinkKind.MANUAL
                    && s.rank[i] + 1 < link.threshold.length && link.threshold[s.rank[i] + 1] > 0) {
                s.ready[i] = ready[i];
                if (!ready[i] && s.pts[i] >= link.threshold[s.rank[i] + 1]) {
                    s.pts[i] = 0;
                }
            }
        }
        for (int i = 0; i < Profile.LINKS; i++) {
            if (unlock[i] != null && s.rank[i] == 0) {
                s.unlockStep[i] = unlock[i];
                s.lastStepDay[i] = -1;
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
        if (before.slot != after.slot) {
            // 「10月25日 白天」, as in the rest of the app's text (M/D is for date plates only).
            out.add(new Change("日期", Zh.slot(rules, before.slot), Zh.slot(rules, after.slot)));
        }
        if (before.cash != after.cash || before.cashExact != after.cashExact) {
            out.add(new Change("现金", cashText(before), cashText(after)));
        }
        for (int i = 0; i < Math.min(3, rules.stats.length); i++) {
            int a = rules.stats[i].levelFor(before.statPts[i]);
            int b = rules.stats[i].levelFor(after.statPts[i]);
            if (a != b) {
                out.add(new Change(rules.stats[i].zh, Zh.lv(a), Zh.lv(b)));
            }
        }
        for (Rules.Link link : rules.links) {
            int a = before.rank[link.idx];
            int b = after.rank[link.idx];
            if (a != b) {
                out.add(new Change(link.arcanaZh, Zh.rank(a), Zh.rank(b)));
            } else if (b == 0 && before.unlockStep[link.idx] != after.unlockStep[link.idx]) {
                out.add(new Change(link.arcanaZh + " 解锁进度", unlockText(link, before.unlockStep[link.idx]),
                    unlockText(link, after.unlockStep[link.idx])));
            } else if (b >= 1 && b < 10 && before.ready[link.idx] != after.ready[link.idx]) {
                out.add(new Change(link.arcanaZh + " 下次见面",
                    before.ready[link.idx] ? "可直接升级" : "还需累积好感",
                    after.ready[link.idx] ? "可直接升级" : "还需累积好感"));
            }
        }
        if (before.floor != after.floor) {
            out.add(new Change("最高到达层", before.floor + "F", after.floor + "F"));
        }
        for (int i = 0; i < rules.rescues.length && i < 63; i++) {
            String a = rescueText(before, i);
            String c = rescueText(after, i);
            if (!a.equals(c)) {
                out.add(new Change("救出" + rules.rescues[i].zh, a, c));
            }
        }
        for (Rules.Episode track : rules.episodes) {
            int a = watched(rules, before, track.idx);
            int e = watched(rules, after, track.idx);
            if (a != e) {
                out.add(new Change(Outcomes.episodeName(track), episodesText(track, a), episodesText(track, e)));
            }
        }
        Rules.Block b = block();
        if (b != null && b.idx < before.towerVisits.length && before.towerVisits[b.idx] != after.towerVisits[b.idx]) {
            out.add(new Change(b.zh + " 已去", before.towerVisits[b.idx] + " 次", after.towerVisits[b.idx] + " 次"));
        }
        String christmasBefore = originalProfile.christmasLink;
        String christmasAfter = christmasLink();
        if (christmasBefore == null ? christmasAfter != null : !christmasBefore.equals(christmasAfter)) {
            out.add(new Change("平安夜", ChristmasChoice.answerText(rules, christmasBefore),
                ChristmasChoice.answerText(rules, christmasAfter)));
        }
        return out;
    }

    private static String rescueText(SimState s, int index) {
        long bit = 1L << index;
        if ((s.rescued & bit) != 0) {
            return "救出了";
        }
        return (s.rescueFailed & bit) != 0 ? "没救出" : "不确定";
    }

    private List<Change> summaryForFirstRun(SimState after) {
        List<Change> out = new ArrayList<>();
        out.add(new Change("日期", null, Zh.slot(rules, after.slot)));
        out.add(new Change("现金", null, cashText(after)));
        StringBuilder stats = new StringBuilder();
        for (int i = 0; i < Math.min(3, rules.stats.length); i++) {
            if (i > 0) {
                stats.append("  ");
            }
            stats.append(rules.stats[i].zh).append(' ').append(Zh.lv(rules.stats[i].levelFor(after.statPts[i])));
        }
        out.add(new Change("个人能力值", null, stats.toString()));
        int established = 0;
        List<Integer> unsure = new ArrayList<>();
        for (Rules.Link link : rules.links) {
            if (rank(link.idx) == UNKNOWN) {
                unsure.add(link.idx);
            } else if (link.idx < after.rank.length && after.rank[link.idx] > 0) {
                established++;
            }
        }
        String linksText = established > 0 ? "已解锁 " + established + " 个"
            : unsure.isEmpty() ? "都还没解锁" : "没有确定已解锁的";
        out.add(new Change("社群", null, linksText));
        // "?" is saved as 未解锁 for now: say so for every such link, never silently.
        for (int link : unsure) {
            out.add(new Change(rules.links[link].arcanaZh, null, UNSURE_RANK));
        }
        List<Integer> goals = LinkStatus.goalOrder(profile);
        out.add(new Change("目标", null, goals.isEmpty() ? "没有指定，所有社群尽量提升" : goalSummary(goals)));
        out.add(new Change("人格面具", null, personaShort(profile.effectivePersonaMode())));
        for (int i : rescuesToAsk()) {
            String answer = rescueText(after, i);
            out.add(new Change("救出" + rules.rescues[i].zh, null, answer));
        }
        for (int track : episodeTracks()) {
            Rules.Episode e = rules.episodes[track];
            out.add(new Change(Outcomes.episodeName(e), null, episodesText(e, watched(rules, after, track))));
        }
        if (asksChristmas()) {
            out.add(new Change("平安夜", null, ChristmasChoice.answerText(rules, christmasLink())));
        }
        return out;
    }

    /** Short value for the confirm page ("只给目标社群带"). */
    static String personaShort(Profile.PersonaMode mode) {
        switch (mode) {
            case ALL:
                return "基本都带";
            case GOALS:
                return "只给目标社群带";
            default:
                return "不带/不一定";
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

    private static String cashText(SimState s) {
        return s.cashExact ? Zh.yen(s.cash) : "不确定";
    }

    static String unlockText(Rules.Link link, int done) {
        if (done <= 0) {
            return "还没开始";
        }
        int index = Math.min(done, link.unlock.steps.size()) - 1;
        return "已完成「" + link.unlock.steps.get(index).zh + "」";
    }

    /** Carries the player's edits over to a draft rebased on a newer record (after a CAS conflict). */
    public void copyEditsFrom(StateDraft other) {
        sync = other.sync;
        slot = other.slot;
        cash = other.cash;
        cashUnknown = other.cashUnknown;
        System.arraycopy(other.statLv, 0, statLv, 0, statLv.length);
        System.arraycopy(other.rank, 0, rank, 0, rank.length);
        System.arraycopy(other.ready, 0, ready, 0, ready.length);
        System.arraycopy(other.unlock, 0, unlock, 0, unlock.length);
        floor = other.floor;
        towerVisits = other.towerVisits;
        System.arraycopy(other.rescue, 0, rescue, 0, Math.min(rescue.length, other.rescue.length));
        System.arraycopy(other.episodes, 0, episodes, 0, Math.min(episodes.length, other.episodes.length));
        if (other.christmas != null) {
            setChristmasLink(other.christmas.isEmpty() ? null : other.christmas);
        }
        step = other.step;
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
