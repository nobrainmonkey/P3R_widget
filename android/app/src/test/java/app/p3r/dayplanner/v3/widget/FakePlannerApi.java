package app.p3r.dayplanner.v3.widget;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi;

/**
 * In-memory {@link PlannerApi} with a canned plan around 11/1–11/5 (one item of every kind, worded
 * exactly as the engine's Labels word them) and the store's contract for writes: compare-and-set on
 * the state revision and idempotency per command id. Recording AS_PREDICTED advances the observed
 * slot and keeps the plan current (§10.6).
 */
final class FakePlannerApi implements PlannerApi {
    static final String PLAN_ID = "plan-A";
    /** 11/1 (Sunday) daytime. */
    static final int FIRST_SLOT = Rules.slot(GameCalendar.ordinal(2009, 11, 1), false);

    /** One recorded call to {@link #recordOutcome}. */
    static final class Call {
        final String commandId;
        final long expectedRevision;
        final int slot;
        final String action;
        final Outcome outcome;
        /**
         * The side-step keys ("顺便") the store persists with this record: like the real store, taken
         * from the installed plan's step for that slot and action when the outcome says they were
         * done; empty otherwise.
         */
        final List<String> sideKeys;

        Call(String commandId, long expectedRevision, int slot, String action, Outcome outcome, List<String> sideKeys) {
            this.commandId = commandId;
            this.expectedRevision = expectedRevision;
            this.slot = slot;
            this.action = action;
            this.outcome = outcome;
            this.sideKeys = sideKeys;
        }
    }

    private static final class Event {
        long seq;
        int slot;
        boolean voided;
    }

    final List<Call> recordCalls = new ArrayList<>();
    final List<String> voidCalls = new ArrayList<>();
    private final Map<String, Receipt> receipts = new HashMap<>();
    private final Map<Long, Event> events = new HashMap<>();
    private long nextSeq = 1;

    SimState observed;
    Plan plan;
    long stateRevision = 7;
    long profileRevision = 3;
    boolean planCurrent = true;
    boolean planning;
    String planErrorZh;
    Rules rules;
    RuntimeException snapshotFailure;

    FakePlannerApi() {
        observed = new SimState(6, 4);
        observed.slot = FIRST_SLOT;
        plan = cannedPlan(PLAN_ID);
    }

    static Plan cannedPlan(String planId) {
        Plan p = new Plan();
        p.planId = planId;
        p.stateRevision = 7;
        p.profileRevision = 3;
        p.fromSlot = FIRST_SLOT;
        int s = FIRST_SLOT;
        p.steps.add(rank(step(s, "meet:hanged", "social", "倒悬者 · 舞子", "RANK 4 → 5", "长鸣神社", "舞子只在周日白天出现"), 4, 5));
        p.steps.add(step(s + 1, "act:study_room", "stat", "在宿舍房间学习", "学力 +2 · 免费", "宿舍 · 自己房间", null));
        p.steps.add(rank(step(s + 2, "meet:emperor", "social", "皇帝 · 小田桐秀利", "RANK 6 → 7", "月光馆学园 3楼 学生会室", null), 6, 7));
        p.steps.add(step(s + 3, "act:job_chagall", "job", "咖啡店打工", "打工 · 预计 +3,000 日元", "桐叶购物中心 · 夏卡尔咖啡店", null));
        p.steps.add(step(s + 4, "unlock:temperance:0", "unlock", "节制 · 贝培", "开启社群", "月光馆学园 1楼 家政教室", null));
        p.steps.add(step(s + 5, "locked:full_moon", "locked", "满月作战", "剧情安排，不能自由行动", null, null));
        p.steps.add(step(s + 6, "locked:story", "locked", "剧情事件", "剧情安排，不能自由行动", null, null));
        p.steps.add(step(s + 7, "locked:story", "locked", "剧情事件", "剧情安排，不能自由行动", null, null));
        p.steps.add(step(s + 8, "free", "free", "自由时间", "没有能推进目标的安排", null, "这个时段没有可做的事，可以自由安排"));
        p.steps.add(step(s + 9, "tower:4", "tower", "奢豪之庭 茨尔阿（145–172F）", "探索塔尔塔罗斯 · 第 2 次", "塔尔塔罗斯", null));
        p.toSlot = s + 9;
        return p;
    }

    static Plan.Step step(int slot, String action, String kind, String title, String subtitle, String location, String why) {
        Plan.Step st = new Plan.Step();
        st.slot = slot;
        st.action = action;
        st.kind = kind;
        st.titleZh = title;
        st.subtitleZh = subtitle;
        st.locationZh = location;
        st.whyZh = why;
        return st;
    }

    static Plan.Step rank(Plan.Step st, int from, int to) {
        st.predicted.rankFrom = from;
        st.predicted.rankTo = to;
        st.predicted.rankUp = to > from;
        return st;
    }

    /** Adds a side step ("顺便：…") done in the same slot, as the engine attaches them. */
    static Plan.Step side(Plan.Step st, String key, String zh) {
        st.side.add(key);
        st.sideZh.add(zh);
        return st;
    }

    /** Dating-site note for 山岸风花 (女教皇, RANK 6) on an evening slot. */
    static Plan.Step noteStep(int slot) {
        Plan.Step st = rank(step(slot, "note:priestess", "note", "结缘网站的字条 · 山岸风花", "加好感（不会升级）",
                "桐叶购物中心 · 放纵俱乐部", "风花的见面机会不多了，用字条补上好感"), 6, 6);
        st.hintZh = "当晚回宿舍用公用电脑给山岸风花加好感";
        return st;
    }

    /** Second Link Episode of 伊织顺平 on a daytime slot. */
    static Plan.Step episodeStep(int slot) {
        return step(slot, "episode:junpei", "episode", "羁绊剧场 · 伊织顺平", "第 2 话", "月光馆学园 2楼 教室", null);
    }

    /** A small rule pack with the people the note/episode steps refer to (no calendar). */
    static Rules miniRules() {
        Rules r = new Rules();
        Rules.Link priestess = new Rules.Link();
        priestess.id = "priestess";
        priestess.idx = 0;
        priestess.arcanaZh = "女教皇";
        priestess.personZh = "山岸风花";
        Rules.Link emperor = new Rules.Link();
        emperor.id = "emperor";
        emperor.idx = 1;
        emperor.arcanaZh = "皇帝";
        emperor.personZh = "小田桐秀利";
        r.links = new Rules.Link[]{priestess, emperor};
        Rules.Episode junpei = new Rules.Episode();
        junpei.idx = 0;
        junpei.id = "junpei";
        junpei.personZh = "伊织顺平";
        junpei.titleZh = "羁绊剧场 · 伊织顺平";
        junpei.count = 4;
        junpei.locationZh = "月光馆学园 2楼 教室";
        r.episodes = new Rules.Episode[]{junpei};
        Rules.DatingNote note = new Rules.DatingNote();
        note.zh = "结缘网站的字条";
        note.locationZh = "桐叶购物中心 · 放纵俱乐部";
        note.cost = 3000;
        note.gain = 10;
        r.datingNote = note;
        return r;
    }

    /** Puts the observed state on the step with this action (it becomes the current item). */
    void observeAt(String action) {
        for (Plan.Step st : plan.steps) {
            if (st.action.equals(action)) {
                observed.slot = st.slot;
                return;
            }
        }
        throw new IllegalArgumentException(action);
    }

    /** Installs a replan with the same steps under a new plan id. */
    void installPlan(String planId) {
        plan = cannedPlan(planId);
        planCurrent = true;
    }

    @Override public Rules rules() { return rules; }

    @Override
    public Snapshot snapshot() {
        if (snapshotFailure != null) throw snapshotFailure;
        Snapshot s = new Snapshot();
        s.observed = observed == null ? null : observed.copy();
        s.plan = plan;
        s.stateRevision = stateRevision;
        s.profileRevision = profileRevision;
        s.planCurrent = planCurrent;
        s.planning = planning;
        s.planErrorZh = planErrorZh;
        s.profile = new Profile();
        return s;
    }

    @Override
    public Receipt recordOutcome(String commandId, long expectedStateRevision, int slot, String actionKey, Outcome outcome) {
        Plan.Step planned = plan == null ? null : plan.at(slot);
        List<String> sideKeys = new ArrayList<>();
        if (outcome.sideDone && planned != null && planned.action.equals(actionKey)) sideKeys.addAll(planned.side);
        recordCalls.add(new Call(commandId, expectedStateRevision, slot, actionKey, outcome, sideKeys));
        Receipt previous = receipts.get(commandId);
        if (previous != null) return duplicateOf(previous);
        if (expectedStateRevision != stateRevision) return rejected("stale");
        if (observed == null || slot != observed.slot) return rejected("invalid");
        Event e = new Event();
        e.seq = nextSeq++;
        e.slot = slot;
        events.put(e.seq, e);
        stateRevision++;
        observed.slot = slot + 1;
        Receipt r = ok(e.seq);
        receipts.put(commandId, r);
        return r;
    }

    @Override
    public Receipt voidEvent(String commandId, long expectedStateRevision, long targetSeq) {
        voidCalls.add(commandId + "#" + targetSeq);
        Receipt previous = receipts.get(commandId);
        if (previous != null) return duplicateOf(previous);
        if (expectedStateRevision != stateRevision) return rejected("stale");
        Event e = events.get(targetSeq);
        if (e == null || e.voided) return rejected("invalid");
        e.voided = true;
        stateRevision++;
        observed.slot = e.slot;
        Receipt r = ok(targetSeq);
        receipts.put(commandId, r);
        return r;
    }

    private Receipt ok(long seq) {
        Receipt r = new Receipt();
        r.ok = true;
        r.stateRevision = stateRevision;
        r.profileRevision = profileRevision;
        r.eventSeq = seq;
        return r;
    }

    private static Receipt duplicateOf(Receipt original) {
        Receipt r = new Receipt();
        r.ok = original.ok;
        r.duplicate = true;
        r.stateRevision = original.stateRevision;
        r.profileRevision = original.profileRevision;
        r.eventSeq = original.eventSeq;
        return r;
    }

    private Receipt rejected(String code) {
        Receipt r = new Receipt();
        r.ok = false;
        r.errorCode = code;
        r.errorZh = "安排已更新";
        r.stateRevision = stateRevision;
        return r;
    }

    // ---- not used by the widget

    @Override public Receipt recordTower(String commandId, long expectedStateRevision, TowerReturn tower) { throw new UnsupportedOperationException(); }
    @Override public Receipt correctTower(String commandId, long expectedStateRevision, long targetSeq, TowerReturn corrected) { throw new UnsupportedOperationException(); }
    @Override public Receipt saveCheckpoint(String commandId, long expectedStateRevision, SimState observed, Profile profileOrNull) { throw new UnsupportedOperationException(); }
    @Override public Receipt saveProfile(String commandId, long expectedProfileRevision, Profile profile) { throw new UnsupportedOperationException(); }
    @Override public List<HistoryItem> history(int limit) { return new ArrayList<>(); }
    @Override public void requestReplan() {}
    @Override public List<Plan.Step> alternatives(int slot) { return new ArrayList<>(); }
    @Override public String exportBackup() { throw new UnsupportedOperationException(); }
    @Override public Receipt importBackup(String commandId, String json) { throw new UnsupportedOperationException(); }
    @Override public void addListener(Listener listener) {}
    @Override public void removeListener(Listener listener) {}
}
