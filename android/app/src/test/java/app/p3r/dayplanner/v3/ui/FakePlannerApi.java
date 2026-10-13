package app.p3r.dayplanner.v3.ui;

import java.util.ArrayList;
import java.util.List;

import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi;

/**
 * In-memory {@link PlannerApi} for UI tests. Records every write as a {@link Call}, enforces the
 * compare-and-set revision like the real store, and (optionally) advances the observed slot on
 * each successful record so multi-step flows (catch-up) behave realistically.
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

    final Rules rules;
    final Snapshot snap = new Snapshot();
    final List<Call> calls = new ArrayList<>();
    final List<HistoryItem> history = new ArrayList<>();
    final List<Listener> listeners = new ArrayList<>();
    List<Plan.Step> alternatives = new ArrayList<>();
    /** When set, returned (once) instead of a success receipt. */
    Receipt nextReceipt;
    /** Advance observed.slot by one on every successful outcome/tower record. */
    boolean advanceOnRecord = true;
    String backupJson = "{\"schema\":\"p3r.backup.v3\"}";
    int replanRequests;
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
        api.history.add(item(98, obs.slot - 1, "outcome", "10月21日 晚上 · 在宿舍房间学习 · 学力 +2", false, false));
        api.history.add(item(97, obs.slot - 3, "tower", "10月20日 晚上 · 探索塔尔塔罗斯 · 96F · 18,500 日元", false, true));
        api.history.add(item(96, obs.slot - 4, "outcome", "10月20日 放学后 · 皇帝 RANK 6", false, false));
        api.history.add(item(95, obs.slot - 6, "skip", "10月19日 放学后 · 跳过 · 对方不在", true, false));
        return api;
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

    private static HistoryItem item(long seq, int slot, String kind, String text, boolean voided, boolean correctable) {
        HistoryItem h = new HistoryItem();
        h.seq = seq;
        h.slot = slot;
        h.kind = kind;
        h.summaryZh = text;
        h.voided = voided;
        h.correctable = correctable;
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
        s.profile = snap.profile == null ? null : snap.profile.copy();
        s.plan = snap.plan;
        s.stateRevision = snap.stateRevision;
        s.profileRevision = snap.profileRevision;
        s.planCurrent = snap.planCurrent;
        s.planning = snap.planning;
        s.planErrorZh = snap.planErrorZh;
        s.lastRecordMillis = snap.lastRecordMillis;
        s.eventsSinceCheckpoint = snap.eventsSinceCheckpoint;
        return s;
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
        Receipt ok = new Receipt();
        ok.ok = true;
        ok.eventSeq = ++seq;
        if (stateWrite) {
            snap.stateRevision++;
            if (advance && advanceOnRecord && snap.observed != null) {
                snap.observed.slot++;
            }
        } else {
            snap.profileRevision++;
        }
        ok.stateRevision = snap.stateRevision;
        ok.profileRevision = snap.profileRevision;
        return ok;
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
