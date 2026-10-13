package app.p3r.dayplanner.v3.widget;

import java.util.Collections;
import java.util.List;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.service.PlannerApi;

/**
 * What one plan-widget instance shows, resolved from a planner snapshot plus the widget's own UI
 * state (cursor, last direct record). Pure: no Android or I/O, so it is cheap to test.
 */
final class PlanView {

    enum Mode {
        /** No observed state yet: invite the player to set up. */
        EMPTY,
        /** Observed state exists but no plan has been installed yet. */
        PLANNING,
        /** No plan and the last planning attempt failed. */
        ERROR,
        /** The plan has nothing left to show. */
        FINISHED,
        /** A plan item (productive step or story-locked marker). */
        ITEM,
        /** The step the widget just recorded, offering undo. */
        RECEIPT
    }

    /** What the primary button does. */
    enum Done {
        NONE,
        /** Record AS_PREDICTED directly (training, job). */
        COMMIT,
        /** Open the quick result sheet for the current slot. */
        RESULT,
        /** Open the quick result sheet in catch-up mode (Done on a future item). */
        CATCHUP,
        /** Void the widget's last direct record. */
        UNDO,
        /** Open onboarding. */
        ONBOARD
    }

    /** How long the "✓ 已记录 · 再点撤销" card stays before the widget moves on to the next item. */
    static final long RECEIPT_TTL_MS = 60_000L;

    final Mode mode;
    final PlannerApi.Snapshot snapshot;
    final List<PlanItems.Item> items;
    /** Index of the shown item in {@link #items}; -1 when no item is shown. */
    final int index;
    final PlanItems.Item item;
    final WidgetPrefs.Receipt receipt;
    final Done done;

    private PlanView(Mode mode, PlannerApi.Snapshot snapshot, List<PlanItems.Item> items, int index,
                     WidgetPrefs.Receipt receipt, Done done) {
        this.mode = mode;
        this.snapshot = snapshot;
        this.items = items;
        this.index = index;
        this.item = index >= 0 ? items.get(index) : null;
        this.receipt = receipt;
        this.done = done;
    }

    /**
     * @param cursorSlot slot the widget is browsing in the current plan, or -1 for the pending item
     * @param receipt    the widget's last direct record, or null
     */
    static PlanView resolve(PlannerApi.Snapshot snap, int cursorSlot, WidgetPrefs.Receipt receipt, long nowMillis) {
        List<PlanItems.Item> none = Collections.emptyList();
        if (snap == null || snap.observed == null) return new PlanView(Mode.EMPTY, snap, none, -1, null, Done.ONBOARD);
        if (snap.plan == null) {
            Mode mode = snap.planErrorZh != null && !snap.planning ? Mode.ERROR : Mode.PLANNING;
            return new PlanView(mode, snap, none, -1, null, Done.NONE);
        }
        List<PlanItems.Item> items = PlanItems.of(snap.plan, snap.observed.slot);
        if (receiptValid(receipt, snap, nowMillis)) {
            return new PlanView(Mode.RECEIPT, snap, items, -1, receipt, Done.UNDO);
        }
        if (items.isEmpty()) return new PlanView(Mode.FINISHED, snap, items, -1, null, Done.NONE);
        int index = cursorSlot < 0 ? 0 : Math.max(0, PlanItems.indexOf(items, cursorSlot));
        return new PlanView(Mode.ITEM, snap, items, index, null, doneFor(snap, items.get(index)));
    }

    /** The planner could not be reached (service missing or failing). */
    static PlanView unavailable() {
        return new PlanView(Mode.ERROR, null, Collections.emptyList(), -1, null, Done.NONE);
    }

    static boolean receiptValid(WidgetPrefs.Receipt receipt, PlannerApi.Snapshot snap, long nowMillis) {
        if (receipt == null || snap == null || snap.observed == null) return false;
        long age = nowMillis - receipt.createdMillis;
        return receipt.stateRevision == snap.stateRevision
                && receipt.slot < snap.observed.slot
                && age >= 0 && age < RECEIPT_TTL_MS;
    }

    /** Kinds whose predicted outcome is certain enough to record from the widget (docs/DESIGN_v3.md §7.2). */
    static boolean isDirect(String kind) {
        return "stat".equals(kind) || "job".equals(kind);
    }

    private static Done doneFor(PlannerApi.Snapshot snap, PlanItems.Item item) {
        if (item.locked()) return Done.NONE;
        if (item.slot > snap.observed.slot) return Done.CATCHUP;
        boolean direct = isDirect(item.step.kind) && snap.planCurrent;
        return direct ? Done.COMMIT : Done.RESULT;
    }

    // ------------------------------------------------------------------ derived facts

    String planId() {
        return snapshot != null && snapshot.plan != null ? snapshot.plan.planId : "";
    }

    long stateRevision() {
        return snapshot == null ? -1 : snapshot.stateRevision;
    }

    int observedSlot() {
        return snapshot != null && snapshot.observed != null ? snapshot.observed.slot : -1;
    }

    /** Slot whose date the plate shows, or -1 for none. */
    int shownSlot() {
        if (mode == Mode.ITEM) return item.slot;
        if (mode == Mode.RECEIPT) return receipt.slot;
        return observedSlot();
    }

    /** Evening slots use the night palette. */
    boolean night() {
        int slot = shownSlot();
        return mode != Mode.EMPTY && slot >= 0 && Rules.isEvening(slot);
    }

    /** Step shown on the card (item step or the recorded step), or null. */
    String shownAction() {
        if (mode == Mode.ITEM) return item.step.action;
        if (mode == Mode.RECEIPT) return receipt.action;
        return null;
    }

    // ------------------------------------------------------------------ card

    /** Card content in natural Simplified Chinese; {@code rules} (optional) refines the plate. */
    CardModel toCard(Rules rules) {
        CardModel m = new CardModel();
        m.night = night();
        switch (mode) {
            case EMPTY:
                brandPlate(m);
                m.title = "设置目标，从当前存档开始";
                m.cue = "填好进度后，每天点「完成」就行";
                m.taskDescription = "还没有安排。点击开始设置";
                m.currentVisible = false;
                m.doneLabel = "开始设置";
                m.doneIcon = CardModel.DoneIcon.NONE;
                m.doneDescription = "开始设置目标和当前进度";
                return m;
            case PLANNING:
            case ERROR:
            case FINISHED:
                statusCard(m, rules);
                return m;
            case RECEIPT:
                receiptCard(m, rules);
                return m;
            default:
                itemCard(m, rules);
                return m;
        }
    }

    private void brandPlate(CardModel m) {
        m.dateText = "P3R";
        m.dateDescription = "P3R 规划";
        m.arrowsVisible = false;
    }

    private void statusCard(CardModel m, Rules rules) {
        if (observedSlot() >= 0) plate(m, observedSlot(), rules);
        else brandPlate(m);
        m.arrowsVisible = false;
        m.ticks.clear();
        m.progressLabel = null;
        if (mode == Mode.PLANNING) {
            m.title = "正在生成安排…";
            m.cue = "排好后会自动显示在这里";
        } else if (mode == Mode.ERROR) {
            String error = snapshot == null ? null : blankToNull(snapshot.planErrorZh);
            m.title = snapshot == null ? "暂时读不到安排" : "安排没能生成";
            m.cue = error == null ? "点开规划看看" : error;
        } else {
            m.title = "之后没有需要安排的行动了";
            m.cue = "可以在应用里查看完整日程";
        }
        m.taskDescription = m.title + "。点击打开规划";
        m.currentEnabled = false;
        m.doneEnabled = false;
        m.doneDescription = "现在没有可以记录的项目";
    }

    private void itemCard(CardModel m, Rules rules) {
        Plan.Step step = item.step;
        plate(m, item.slot, rules);
        m.prevEnabled = index > 0;
        m.nextEnabled = index < items.size() - 1;
        progress(m, item.slot, item.lastSlot);
        m.title = titleOf(step.titleZh, step.kind);
        m.tag = compactTag(step.subtitleZh);
        m.location = blankToNull(step.locationZh);
        m.cue = cueFor(step, rules);
        if (m.tag != null && m.cue != null && m.cue.contains(m.tag)) m.tag = null; // e.g. 剧情推进 twice
        m.taskDescription = describe(m) + "。点击查看详情";
        switch (done) {
            case COMMIT:
                m.doneDescription = "完成：按计划记录「" + m.title + "」";
                break;
            case RESULT:
                m.doneDescription = "完成：记录「" + m.title + "」的结果";
                break;
            case CATCHUP:
                m.doneLabel = "做到这里";
                m.doneDescription = "做到这里：补记这一项之前的安排";
                break;
            default:
                m.doneEnabled = false;
                m.doneDescription = "剧情时段，无需记录";
                break;
        }
    }

    private void receiptCard(CardModel m, Rules rules) {
        plate(m, receipt.slot, rules);
        m.prevEnabled = false;
        m.nextEnabled = !items.isEmpty();
        progress(m, receipt.slot, receipt.slot);
        m.title = titleOf(receipt.title, receipt.kind);
        m.tag = compactTag(receipt.subtitle);
        m.location = blankToNull(receipt.location);
        m.cue = "✓ 已记录 · 再点撤销";
        m.cueEssential = true; // explains why 完成 now reads 撤销
        m.taskDescription = describe(m) + "，已记录";
        m.doneLabel = "撤销";
        m.doneIcon = CardModel.DoneIcon.UNDO;
        m.doneDescription = "撤销刚才的记录";
    }

    private void plate(CardModel m, int slot, Rules rules) {
        GameCalendar.Info info = GameCalendar.ofSlot(slot, rules);
        m.dateText = info.numerals();
        m.weekdayZh = info.weekdayZh();
        m.moonPhase = info.moonPhase;
        m.periodZh = info.periodZh();
        m.dateDescription = info.spokenZh();
        m.watermarkDay = info.dayOfMonth;
        m.motif = WidgetMotif.forDay(info.day);
    }

    /** Relative-day label plus one tick per slot of the shown day (白天, 晚上). */
    private void progress(CardModel m, int shownFirst, int shownLast) {
        int day = Rules.dayOf(shownFirst);
        int observed = observedSlot();
        m.progressLabel = relativeDay(day, Rules.dayOf(observed));
        StringBuilder spoken = new StringBuilder(m.progressLabel);
        for (int evening = 0; evening <= 1; evening++) {
            int slot = Rules.slot(day, evening == 1);
            CardModel.Tick tick;
            if (slot >= shownFirst && slot <= shownLast) tick = CardModel.Tick.CURRENT;
            else if (slot < observed) tick = CardModel.Tick.DONE;
            else tick = productiveAt(slot) ? CardModel.Tick.PENDING : CardModel.Tick.LOCKED;
            m.ticks.add(tick);
            spoken.append(evening == 0 ? "：白天" : "，晚上").append(tickWord(tick));
        }
        m.progressDescription = spoken.toString();
    }

    private boolean productiveAt(int slot) {
        Plan.Step step = snapshot.plan == null ? null : snapshot.plan.at(slot);
        return step != null && step.productive() && !PlanItems.isLocked(step);
    }

    private String cueFor(Plan.Step step, Rules rules) {
        if (item.locked()) {
            if (Rules.dayOf(item.lastSlot) > Rules.dayOf(item.slot)) {
                GameCalendar.Info end = GameCalendar.ofSlot(item.lastSlot, rules);
                return "剧情推进，无需选择 · 持续到" + end.month + "月" + end.dayOfMonth + "日";
            }
            return "剧情推进，无需选择";
        }
        if (!snapshot.planCurrent && snapshot.planning) return "正在根据最新记录调整安排…";
        if (!snapshot.planCurrent && snapshot.planErrorZh != null) return "安排暂未更新，点开查看";
        return blankToNull(step.whyZh);
    }

    // ------------------------------------------------------------------ text helpers

    static String relativeDay(int day, int observedDay) {
        int delta = day - observedDay;
        if (delta == 0) return "今日";
        if (delta == 1) return "明天";
        if (delta == 2) return "后天";
        if (delta == -1) return "昨天";
        return delta > 0 ? delta + "天后" : (-delta) + "天前";
    }

    private static String tickWord(CardModel.Tick tick) {
        switch (tick) {
            case DONE: return "已记录";
            case CURRENT: return "正在显示";
            case LOCKED: return "无需安排";
            default: return "待做";
        }
    }

    /** "RANK 6 → 7" → "RANK 6→7"; blank → null. */
    static String compactTag(String subtitle) {
        String s = blankToNull(subtitle);
        return s == null ? null : s.trim().replaceAll("\\s*→\\s*", "→");
    }

    /** Never shows an empty title: falls back to a plain description of the step kind. */
    static String titleOf(String title, String kind) {
        String t = blankToNull(title);
        if (t != null) return t.trim();
        switch (kind == null ? "" : kind) {
            case "social": return "社群";
            case "unlock": return "解锁社群";
            case "stat": return "提升个人能力值";
            case "job": return "打工";
            case "tower": return "探索塔尔塔罗斯";
            case "locked": return "剧情时段";
            default: return "自由行动";
        }
    }

    private static String describe(CardModel m) {
        StringBuilder sb = new StringBuilder(m.title);
        if (m.tag != null) sb.append("，").append(m.tag);
        if (m.location != null) sb.append("，地点：").append(m.location);
        return sb.toString();
    }

    static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s;
    }
}
