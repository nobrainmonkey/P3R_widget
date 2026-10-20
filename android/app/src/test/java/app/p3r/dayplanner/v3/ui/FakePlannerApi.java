package app.p3r.dayplanner.v3.ui;

import java.util.ArrayList;
import java.util.List;

import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi;
import app.p3r.dayplanner.v3.service.StoryDisplay;

/**
 * In-memory {@link PlannerApi} for UI tests. Records every write as a {@link Call}, enforces the
 * compare-and-set revision like the real store and, like the real store, only accepts a record for
 * exactly the observed slot (an earlier slot is "already recorded", a later one a gap), refuses
 * {@code locked:} keys, and after each record moves the observed slot past calendar-locked slots.
 */
final class FakePlannerApi implements PlannerApi {
    /** One recorded write. */
    static final class Call {
        final String method;
        final String commandId;
        final long expectedRevision;
        final int slot;
        final String actionKey;
        final Outcome outcome;
        final TowerReturn tower;
        final long targetSeq;
        final SimState state;
        final Profile profile;

        Call(String method, String commandId, long expectedRevision, int slot, String actionKey, Outcome outcome,
             TowerReturn tower, long targetSeq, SimState state, Profile profile) {
            this.method = method;
            this.commandId = commandId;
            this.expectedRevision = expectedRevision;
            this.slot = slot;
            this.actionKey = actionKey;
            this.outcome = outcome;
            this.tower = tower;
            this.targetSeq = targetSeq;
            this.state = state;
            this.profile = profile;
        }

        @Override
        public String toString() {
            return method + "(slot=" + slot + ", action=" + actionKey + ", rev=" + expectedRevision + ")";
        }
    }

    /** The rule pack; null simulates a pack or database that failed to open. */
    Rules rules;
    final Snapshot snap = new Snapshot();
    final List<Call> calls = new ArrayList<>();
    final List<HistoryItem> history = new ArrayList<>();
    final java.util.Map<Long, OutcomeCorrection> corrections = new java.util.HashMap<>();
    final List<Listener> listeners = new ArrayList<>();
    List<Plan.Step> alternatives = new ArrayList<>();
    /** When set, returned (once) instead of a success receipt. */
    Receipt nextReceipt;
    /** When set, returned (once) by the next {@link #saveProfile} instead of saving. */
    Receipt nextProfileReceipt;
    /** Advance observed.slot to the next open slot on every successful outcome/tower record. */
    boolean advanceOnRecord = true;
    /** Check slots and action keys like the store (off only for screens the store never reaches). */
    boolean strictSlots = true;
    private final java.util.Set<String> importIds = new java.util.HashSet<>();
    String backupJson = "{\"schema\":\"p3r.backup.v3\"}";
    int replanRequests;
    /** Calls of {@link #requestFullReplan} / {@link #cancelFullReplan}. */
    int fullReplanRequests;
    int deepReplanRequests;
    int fullReplanCancels;
    final List<Integer> acknowledgedStorySlots = new ArrayList<>();
    private long seq = 100;

    FakePlannerApi(Rules rules) {
        this.rules = rules;
    }

    /** The standard mid-game fixture: 10/22 放学后, goals 皇帝/恋爱 必须, a current plan. */
    static FakePlannerApi midGame() {
        Rules r = Fixtures.rules();
        FakePlannerApi api = new FakePlannerApi(r);
        SimState obs = Fixtures.observedMid(r);
        api.snap.observed = obs;
        api.snap.profile = Fixtures.profileMid();
        api.snap.plan = Fixtures.planMid(r, obs);
        api.snap.stateRevision = Fixtures.STATE_REV;
        api.snap.profileRevision = Fixtures.PROFILE_REV;
        api.snap.planCurrent = true;
        api.snap.lastRecordMillis = System.currentTimeMillis() - 2 * 60 * 60 * 1000L;
        api.snap.eventsSinceCheckpoint = 9;
        api.alternatives = Fixtures.alternatives(r, obs.slot);
        // Flags as the real service sets them (HistoryText): undoable = a record after the latest
        // checkpoint (seq 94) that is not voided; action outcomes and tower visits are correctable.
        api.history.add(item(98, obs.slot - 1, "outcome", "10月21日 晚上 · 在宿舍房间学习 · 学力 +2", false, true));
        HistoryItem tower = item(97, obs.slot - 3, "tower", "10月20日 晚上 · 探索塔尔塔罗斯 · 到达 96F · 余额 18,500 日元",
            false, true);
        tower.tower = towerValues(obs.slot - 3, 3, 96, true, 18_500, null);
        api.history.add(tower);
        api.history.add(item(96, obs.slot - 4, "outcome", "10月20日 放学后 · 皇帝 RANK 6", false, true));
        api.history.add(item(95, obs.slot - 6, "skip", "10月19日 放学后 · 跳过 · 对方不在", true, true));
        api.history.add(item(94, obs.slot - 8, "checkpoint", "10月18日 · 同步现状", false, false));
        api.history.add(item(93, obs.slot - 9, "outcome", "10月18日 放学后 · 女教皇 RANK 5", false, false));
        return api;
    }

    static TowerReturn towerValues(int slot, int block, int floor, boolean balanceMode, long amount, Integer level) {
        TowerReturn t = new TowerReturn();
        t.slot = slot;
        t.block = block;
        t.floor = floor;
        t.balanceMode = balanceMode;
        t.amount = amount;
        t.level = level;
        return t;
    }

    /** First run: no observed state yet. */
    static FakePlannerApi firstRun() {
        Rules r = Fixtures.rules();
        FakePlannerApi api = new FakePlannerApi(r);
        api.snap.profile = new Profile();
        api.snap.stateRevision = 0;
        api.snap.profileRevision = 0;
        return api;
    }

    /**
     * A history row; {@code afterCheckpoint} = recorded after the latest 同步现状. The flags follow
     * the real service: non-voided records after the checkpoint are undoable; action outcomes,
     * their corrections and tower visits are correctable.
     */
    static HistoryItem item(long seq, int slot, String kind, String text, boolean voided, boolean afterCheckpoint) {
        HistoryItem h = new HistoryItem();
        h.seq = seq;
        h.originalSeq = "outcome".equals(kind) || "skip".equals(kind) || "tower".equals(kind) ? seq : -1;
        h.slot = slot;
        h.kind = kind;
        h.summaryZh = text;
        h.voided = voided;
        boolean record = "outcome".equals(kind) || "skip".equals(kind) || "outcome_fix".equals(kind)
                || "tower".equals(kind) || "tower_fix".equals(kind);
        h.undoable = record && afterCheckpoint && !voided;
        h.correctable = h.undoable && ("tower".equals(kind) || "outcome".equals(kind)
                || "skip".equals(kind) || "outcome_fix".equals(kind));
        h.createdMillis = System.currentTimeMillis() - (100 - seq) * 3_600_000L;
        return h;
    }

    List<Call> callsOf(String method) {
        List<Call> out = new ArrayList<>();
        for (Call c : calls) {
            if (c.method.equals(method)) {
                out.add(c);
            }
        }
        return out;
    }

    Call last() {
        return calls.isEmpty() ? null : calls.get(calls.size() - 1);
    }

    void fireChanged() {
        for (Listener l : new ArrayList<>(listeners)) {
            l.onChanged(snapshot());
        }
    }

    // ------------------------------------------------------------------ PlannerApi

    @Override
    public Rules rules() {
        return rules;
    }

    @Override
    public synchronized Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.observed = snap.observed == null ? null : snap.observed.copy();
        s.storyDisplay = snap.storyDisplay;
        s.profile = snap.profile == null ? null : snap.profile.copy();
        s.plan = snap.plan;
        s.stateRevision = snap.stateRevision;
        s.profileRevision = snap.profileRevision;
        s.planCurrent = snap.planCurrent;
        s.planning = snap.planning;
        s.planErrorZh = snap.planErrorZh;
        s.lastRecordMillis = snap.lastRecordMillis;
        s.eventsSinceCheckpoint = snap.eventsSinceCheckpoint;
        s.fullReplanning = snap.fullReplanning;
        s.fullReplanDeep = snap.fullReplanDeep;
        s.fullReplanTicket = snap.fullReplanTicket;
        s.fullReplanDoneTicket = snap.fullReplanDoneTicket;
        s.fullReplanResult = snap.fullReplanResult;
        return s;
    }

    @Override
    public synchronized boolean acknowledgeStory(String source, int slot) {
        StoryDisplay story = snap.storyDisplay;
        if (story == null || !story.key.equals(source) || story.cursorSlot != slot) return false;
        acknowledgedStorySlots.add(slot);
        snap.storyDisplay = StoryDisplay.create(rules, snap.profile, source, story.fromSlot, story.toSlot,
                story.currentLastSlot() + 1);
        return true;
    }

    private synchronized Receipt write(Call call, boolean stateWrite, boolean advance) {
        calls.add(call);
        if (nextReceipt != null) {
            Receipt r = nextReceipt;
            nextReceipt = null;
            return r;
        }
        long current = stateWrite ? snap.stateRevision : snap.profileRevision;
        if (call.expectedRevision != current) {
            return stale();
        }
        if (advance && strictSlots && snap.observed != null) {
            Receipt rejected = checkSlot(call);
            if (rejected != null) {
                return rejected;
            }
        }
        Receipt ok = new Receipt();
        ok.ok = true;
        ok.eventSeq = ++seq;
        if (stateWrite) {
            snap.stateRevision++;
            if (advance && advanceOnRecord && snap.observed != null) {
                snap.observed.slot = Slots.firstOpen(rules, snap.observed.slot + 1);
            }
        } else {
            snap.profileRevision++;
        }
        ok.stateRevision = snap.stateRevision;
        ok.profileRevision = snap.profileRevision;
        return ok;
    }

    /** The store's slot rules: exactly the observed slot, never a lock. */
    private Receipt checkSlot(Call call) {
        if (call.slot < snap.observed.slot) {
            return invalid("这个时段已经记录过了");
        }
        if (call.slot > snap.observed.slot) {
            Receipt gap = invalid("前面还有时段没有记录");
            gap.errorCode = Receipt.CODE_GAP;
            gap.missingSlots = Slots.openBetween(rules, snap.observed.slot, call.slot);
            return gap;
        }
        if (call.actionKey != null && call.actionKey.startsWith("locked:")) {
            return invalid("这个时段由剧情安排，不能记录");
        }
        return null;
    }

    static Receipt invalid(String zh) {
        Receipt r = new Receipt();
        r.ok = false;
        r.errorCode = Receipt.CODE_INVALID;
        r.errorZh = zh;
        return r;
    }

    static Receipt stale() {
        Receipt r = new Receipt();
        r.ok = false;
        r.errorCode = "stale";
        r.errorZh = "记录已在别处更新";
        return r;
    }

    @Override
    public Receipt recordOutcome(String commandId, long expectedStateRevision, int slot, String actionKey, Outcome outcome) {
        return write(new Call("recordOutcome", commandId, expectedStateRevision, slot, actionKey, outcome, null, -1, null, null),
            true, true);
    }

    @Override
    public Receipt recordTower(String commandId, long expectedStateRevision, TowerReturn tower) {
        return write(new Call("recordTower", commandId, expectedStateRevision, tower.slot, "tower:" + tower.block, null, tower,
            -1, null, null), true, true);
    }

    @Override
    public Receipt correctTower(String commandId, long expectedStateRevision, long targetSeq, TowerReturn corrected) {
        return write(new Call("correctTower", commandId, expectedStateRevision, corrected.slot, null, null, corrected,
            targetSeq, null, null), true, false);
    }

    @Override
    public OutcomeCorrection outcomeCorrection(long targetSeq) {
        OutcomeCorrection correction = corrections.get(targetSeq);
        if (correction != null) correction.stateRevision = snap.stateRevision;
        return correction;
    }

    @Override
    public Receipt correctOutcome(String commandId, long expectedStateRevision, long targetSeq, Outcome replacement) {
        OutcomeCorrection correction = corrections.get(targetSeq);
        if (correction == null) return invalid("这条记录不能修改");
        Receipt receipt = write(new Call("correctOutcome", commandId, expectedStateRevision, correction.slot,
                correction.actionKey, replacement, null, targetSeq, null, null), true, false);
        if (receipt.ok) correction.outcome = replacement;
        return receipt;
    }

    @Override
    public Receipt voidEvent(String commandId, long expectedStateRevision, long targetSeq) {
        return write(new Call("voidEvent", commandId, expectedStateRevision, -1, null, null, null, targetSeq, null, null),
            true, false);
    }

    @Override
    public Receipt saveCheckpoint(String commandId, long expectedStateRevision, SimState observed, Profile profileOrNull) {
        Receipt r = write(new Call("saveCheckpoint", commandId, expectedStateRevision, observed.slot, null, null, null, -1,
            observed, profileOrNull), true, false);
        if (r.ok) {
            synchronized (this) {
                snap.observed = observed.copy();
                if (profileOrNull != null) {
                    snap.profile = profileOrNull.copy();
                }
            }
        }
        return r;
    }

    @Override
    public Receipt saveProfile(String commandId, long expectedProfileRevision, Profile profile) {
        if (nextProfileReceipt != null) {
            Receipt failed = nextProfileReceipt;
            nextProfileReceipt = null;
            calls.add(new Call("saveProfile", commandId, expectedProfileRevision, -1, null, null, null, -1, null, profile));
            return failed;
        }
        Receipt r = write(new Call("saveProfile", commandId, expectedProfileRevision, -1, null, null, null, -1, null, profile),
            false, false);
        if (r.ok) {
            synchronized (this) {
                snap.profile = profile.copy();
            }
        }
        return r;
    }

    @Override
    public List<HistoryItem> history(int limit) {
        return new ArrayList<>(history.subList(0, Math.min(limit, history.size())));
    }

    @Override
    public void requestReplan() {
        replanRequests++;
    }

    /** Like the service: a ticket per request, the same one while it is pending, 0 before onboarding. */
    @Override
    public synchronized long requestFullReplan() {
        fullReplanRequests++;
        return requestManualReplan(false);
    }

    @Override
    public synchronized long requestDeepReplan() {
        deepReplanRequests++;
        return requestManualReplan(true);
    }

    private long requestManualReplan(boolean deep) {
        if (snap.observed == null) {
            return 0;
        }
        if (!snap.fullReplanning) {
            snap.fullReplanTicket++;
            snap.fullReplanning = true;
            snap.fullReplanDeep = deep;
        }
        return snap.fullReplanTicket;
    }

    @Override
    public synchronized void cancelFullReplan() {
        fullReplanCancels++;
    }

    /** Ends the pending full request with {@code result} (the plan is whatever the test installed). */
    synchronized void endFullReplan(FullReplanResult result) {
        snap.fullReplanning = false;
        snap.fullReplanDoneTicket = snap.fullReplanTicket;
        snap.fullReplanResult = result;
    }

    @Override
    public List<Plan.Step> alternatives(int slot) {
        return new ArrayList<>(alternatives);
    }

    @Override
    public String exportBackup() {
        calls.add(new Call("exportBackup", null, -1, -1, null, null, null, -1, null, null));
        return backupJson;
    }

    @Override
    public Receipt importBackup(String commandId, String json) {
        Receipt r = new Receipt();
        calls.add(new Call("importBackup", commandId, -1, -1, json, null, null, -1, null, null));
        r.ok = true;
        r.duplicate = !importIds.add(commandId);
        if (!r.duplicate) {
            synchronized (this) {
                snap.stateRevision++;
                snap.profileRevision++;
            }
        }
        r.stateRevision = snap.stateRevision;
        r.profileRevision = snap.profileRevision;
        return r;
    }

    @Override
    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }
}
