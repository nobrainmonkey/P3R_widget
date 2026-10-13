package app.p3r.dayplanner.v3.ui;

import java.util.ArrayList;
import java.util.List;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * Editing model behind the state wizard (onboarding / 同步现状 / 完整修改).
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

    /** Answer to "这段时间基本按计划了吗？". */
    public enum SyncAnswer { YES, PARTLY, NO }

    /** RANK value meaning "?" (not sure — keep what was stored). */
    public static final int UNKNOWN = -1;

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
    final Integer[] unlock = new Integer[Profile.LINKS];
    Integer floor;
    Integer towerVisits;
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

    public int unlockStep(int link) {
        return unlock[link] != null ? unlock[link] : prefill().unlockStep[link];
    }

    /** Block active on the chosen date, or null. */
    public Rules.Block block() {
        return rules.blockOn(Rules.dayOf(slot()));
    }

    public int floor() {
        return floor != null ? floor : prefill().floor;
    }

    public int towerVisits() {
        if (towerVisits != null) {
            return towerVisits;
        }
        Rules.Block b = block();
        SimState p = prefill();
        return b == null || b.idx >= p.towerVisits.length ? 0 : p.towerVisits[b.idx];
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

    /** Cycles a goal tier —→想要→必须→— and appends to the end of the new tier's order. */
    public Profile.Tier cycleTier(int link) {
        Profile.Tier next;
        switch (profile.tier[link]) {
            case NONE:
                next = Profile.Tier.WANT;
                break;
            case WANT:
                next = Profile.Tier.MUST;
                break;
            default:
                next = Profile.Tier.NONE;
                break;
        }
        setTier(link, next);
        return next;
    }

    /** Sets a tier, placing the link last within it. */
    public void setTier(int link, Profile.Tier tier) {
        int maxOrder = -1;
        for (int i = 0; i < Profile.LINKS; i++) {
            if (i != link && profile.tier[i] == tier) {
                maxOrder = Math.max(maxOrder, profile.order[i]);
            }
        }
        profile.tier[link] = tier;
        profile.order[link] = tier == Profile.Tier.NONE ? 0 : maxOrder + 1;
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
                s.statPts[i] = rules.stats[i].thresholds[lv];
            }
        }
        for (Rules.Link link : rules.links) {
            int i = link.idx;
            int r = rank(i);
            if (r == UNKNOWN || r == p.rank[i]) {
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
        return s;
    }

    /** The profile to save with the checkpoint (onboarding only), else null. */
    public Profile profileToSave() {
        return mode == Mode.ONBOARDING ? profile : null;
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
            }
        }
        if (before.floor != after.floor) {
            out.add(new Change("塔尔塔罗斯 最高层", before.floor + "F", after.floor + "F"));
        }
        Rules.Block b = block();
        if (b != null && b.idx < before.towerVisits.length && before.towerVisits[b.idx] != after.towerVisits[b.idx]) {
            out.add(new Change(b.zh + " 已去", before.towerVisits[b.idx] + " 次", after.towerVisits[b.idx] + " 次"));
        }
        return out;
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
        for (int r : after.rank) {
            if (r > 0) {
                established++;
            }
        }
        out.add(new Change("社群", null, established == 0 ? "都还没建立" : "已建立 " + established + " 个"));
        List<Integer> goals = LinkStatus.goalOrder(profile);
        StringBuilder goalText = new StringBuilder();
        for (int g : goals) {
            if (goalText.length() > 0) {
                goalText.append("、");
            }
            goalText.append(rules.links[g].arcanaZh);
        }
        out.add(new Change("目标", null, goals.isEmpty() ? "没有指定，所有社群尽量提升" : goalText.toString()));
        out.add(new Change("人格面具", null, profile.personaBonus ? "约会时通常带着同阿尔卡那的" : "不计加成"));
        return out;
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

    /** True when the profile differs from the one the draft started with. */
    public boolean profileChanged() {
        try {
            return !profile.toJson().toString().equals(originalProfile.toJson().toString());
        } catch (org.json.JSONException e) {
            return true;
        }
    }
}
