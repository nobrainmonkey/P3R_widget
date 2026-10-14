package app.p3r.dayplanner.v3.widget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.p3r.dayplanner.v3.engine.DialogueHint;
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
        /** Observed state exists but no usable plan yet (none installed, or the installed one starts later). */
        PLANNING,
        /** No usable plan and the last planning attempt failed, or the records could not be read. */
        ERROR,
        /** The plan has nothing left to show. */
        FINISHED,
        /** A plan item (productive step, the pending free slot, or a story-locked marker). */
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
        /** Step on to the next item (a story-locked item has nothing to record). */
        NEXT,
        /** Open onboarding. */
        ONBOARD,
        /** Open the app on 今天, where a failed or pending plan can be retried or followed (status cards). */
        OPEN_APP,
        /** Open the app on 日程 (the plan has nothing left to show, or nothing after a story-locked item). */
        OPEN_SCHEDULE
    }

    /** How long the "✓ 已记录 · 再点撤销" card stays before the widget moves on to the next item. */
    static final long RECEIPT_TTL_MS = 60_000L;
    /**
     * An undo tap this soon after the direct record is the second half of a double tap on 完成 (it
     * landed on the re-rendered button), never a deliberate undo.
     */
    static final long UNDO_ARM_MS = 700L;
    /** The catch-up button (Done on a future item); the app's detail sheet says the same. */
    static final String CATCHUP_LABEL = "已经玩到这里";
    /** The button of a story-locked item: on to the next thing to do. */
    static final String NEXT_LABEL = "下一项";

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
        if (snap == null) return new PlanView(Mode.EMPTY, null, none, -1, null, Done.ONBOARD);
        if (snap.observed == null) {
            // No records: a fresh install invites setup, but a failed load must not pose as one.
            if (snap.planErrorZh != null) return new PlanView(Mode.ERROR, snap, none, -1, null, Done.OPEN_APP);
            return new PlanView(Mode.EMPTY, snap, none, -1, null, Done.ONBOARD);
        }
        if (snap.plan == null || (!snap.planCurrent && snap.plan.fromSlot > snap.observed.slot)) {
            // A lagging plan that starts after the observed slot (e.g. right after an undo or a sync
            // to an earlier date) cannot describe the pending slot: wait for the replan.
            Mode mode = snap.planErrorZh != null && !snap.planning ? Mode.ERROR : Mode.PLANNING;
            return new PlanView(mode, snap, none, -1, null, Done.OPEN_APP);
        }
        List<PlanItems.Item> items = PlanItems.of(snap.plan, snap.observed.slot);
        if (receiptValid(receipt, snap, nowMillis)) {
            return new PlanView(Mode.RECEIPT, snap, items, -1, receipt, Done.UNDO);
        }
        if (items.isEmpty()) return new PlanView(Mode.FINISHED, snap, items, -1, null, Done.OPEN_SCHEDULE);
        int index = cursorSlot < 0 ? 0 : Math.max(0, PlanItems.indexOf(items, cursorSlot));
        return new PlanView(Mode.ITEM, snap, items, index, null, doneFor(snap, items, index));
    }

    /** The planner could not be reached (service missing or failing). */
    static PlanView unavailable() {
        return new PlanView(Mode.ERROR, null, Collections.emptyList(), -1, null, Done.OPEN_APP);
    }

    static boolean receiptValid(WidgetPrefs.Receipt receipt, PlannerApi.Snapshot snap, long nowMillis) {
        if (receipt == null || snap == null || snap.observed == null) return false;
        long age = nowMillis - receipt.createdMillis;
        return receipt.stateRevision == snap.stateRevision
                && receipt.slot < snap.observed.slot
                && age >= 0 && age < RECEIPT_TTL_MS;
    }

    /**
     * Steps whose predicted outcome is certain enough to record from the widget (docs/DESIGN_v3.md
     * §7.2): plain training and jobs. Everything else needs the result sheet: meetings, unlock steps,
     * Tartarus, the dating-site note and Link Episodes, and any step with side steps ("顺便：…"),
     * whose payment or talk the player may have skipped.
     */
    static boolean isDirect(Plan.Step step) {
        if (step == null || step.action == null || !step.action.startsWith("act:")) return false;
        if (step.side != null && !step.side.isEmpty()) return false;
        return "stat".equals(step.kind) || "job".equals(step.kind);
    }

    private static Done doneFor(PlannerApi.Snapshot snap, List<PlanItems.Item> items, int index) {
        PlanItems.Item item = items.get(index);
        // Nothing to record while the story runs: the button moves on to what comes after it.
        if (item.locked()) return index < items.size() - 1 ? Done.NEXT : Done.OPEN_SCHEDULE;
        if (item.slot > snap.observed.slot) return Done.CATCHUP;
        return isDirect(item.step) && snap.planCurrent ? Done.COMMIT : Done.RESULT;
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
        m.keepWordsWhole = true;
        switch (mode) {
            case EMPTY:
                brandPlate(m);
                m.title = "设置目标，从当前存档开始";
                m.cue = "填好进度后，每天点「完成」就行";
                m.cueMaxLines = 2;
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
            boolean unreadable = snapshot == null || snapshot.observed == null;
            m.title = unreadable ? "暂时读不到安排" : "暂时没有安排";
            m.cue = error == null ? "点开应用看看" : error;
            m.cueEssential = true; // what went wrong and what to do: the only instruction on the card
        } else {
            m.title = "后面没有要做的安排了";
            m.cue = "可以在应用里查看完整日程";
        }
        m.cueMaxLines = 2; // the cue is the card's instruction: let it wrap rather than end in "…"
        m.taskDescription = m.title + "。点击打开应用";
        // Nothing to browse or record: one button that does what the card says, never a dead 当前/完成 row.
        m.currentVisible = false;
        m.doneIcon = CardModel.DoneIcon.NONE;
        if (done == Done.OPEN_SCHEDULE) {
            m.doneLabel = "查看日程";
            m.doneDescription = "查看日程：在应用里看完整的日程";
        } else {
            m.doneLabel = "打开应用";
            m.doneDescription = mode == Mode.PLANNING ? "打开应用：查看安排的进度" : "打开应用：在应用里重试";
        }
    }

    private void itemCard(CardModel m, Rules rules) {
        Plan.Step step = item.step;
        plate(m, item.slot, rules);
        m.prevEnabled = index > 0;
        m.nextEnabled = index < items.size() - 1;
        // Already on the pending item (where ‹ is dimmed too): 当前 has nowhere to go.
        m.currentEnabled = index > 0;
        if (!m.currentEnabled) m.currentDescription = "当前：已经在显示下一项要做的安排";
        // A story-locked pending item has nothing to record either: one live button, never a dead row.
        m.currentVisible = m.currentEnabled || !item.locked();
        progress(m, item.slot, item.lastSlot, rules);
        StepCopy copy = StepCopy.of(step, rules, snapshot.observed, snapshot.plan);
        m.title = copy.title;
        m.shortTitles.addAll(copy.shortTitles);
        m.titleIcon = copy.icon;
        m.tag = copy.tag;
        m.location = copy.location;
        m.hint = copy.hint;
        m.side = copy.side;
        m.cue = cueFor(step, copy, rules);
        // What to answer: the step's own choices, the side steps' picks, then the class and exam
        // answers of the card's slots; never the app's generic hint. The full app line is read out.
        List<String> spoken = new ArrayList<>();
        DialogueHint.Answers own = ownAnswers(step, rules);
        if (!own.isEmpty()) {
            m.answers.add(answerLine(own, null));
            spoken.add("social".equals(step.kind) ? DialogueHint.appLine(own) : m.answers.get(0).text());
        }
        for (String key : step.side == null ? Collections.<String>emptyList() : step.side) {
            DialogueHint.Answers picks = DialogueHint.forUnlockStep(unlockStep(rules, key));
            if (picks.isEmpty()) continue;
            CardModel.AnswerLine line = answerLine(picks, DialogueHint.SIDE_PREFIX);
            m.answers.add(line);
            spoken.add(line.text());
        }
        List<Plan.Reminder> others = new ArrayList<>();
        for (Plan.Reminder r : item.reminders) {
            if (r.isAnswer()) m.answers.add(answerLine(r));
            else others.add(r);
            spoken.add(r.textZh);
        }
        // The reminder row shows the most important one first (stable: plan order among equals).
        others.sort((a, b) -> Integer.compare(b.priority, a.priority));
        for (Plan.Reminder r : others) m.reminders.add(new CardModel.ReminderLine(r.textZh, blankToNull(r.shortZh)));
        m.taskDescription = describe(m, spoken) + "。点击查看详情";
        switch (done) {
            case COMMIT:
                m.doneDescription = "完成：按安排记录「" + m.title + "」";
                break;
            case RESULT:
                m.doneDescription = "完成：记录「" + m.title + "」的结果";
                break;
            case CATCHUP:
                m.doneLabel = CATCHUP_LABEL;
                m.doneIcon = CardModel.DoneIcon.NONE;
                m.doneDescription = CATCHUP_LABEL + "：补记这一项前面还没记录的安排";
                break;
            case NEXT:
                m.doneLabel = NEXT_LABEL;
                m.doneIcon = CardModel.DoneIcon.NONE;
                m.doneDescription = NEXT_LABEL + "：这段时间不能自由行动，不用记录，看之后要做的安排";
                break;
            case OPEN_SCHEDULE:
                m.doneLabel = "查看日程";
                m.doneIcon = CardModel.DoneIcon.NONE;
                m.doneDescription = "查看日程：这段时间不能自由行动，不用记录，在应用里看完整的日程";
                break;
            default:
                m.doneEnabled = false;
                m.doneDescription = "这段时间不能自由行动，不用记录";
                break;
        }
    }

    private void receiptCard(CardModel m, Rules rules) {
        plate(m, receipt.slot, rules);
        m.prevEnabled = false;
        m.nextEnabled = !items.isEmpty();
        progress(m, receipt.slot, receipt.slot, rules);
        m.title = StepCopy.titleOf(receipt.title, receipt.kind);
        m.tag = StepCopy.tag(receipt.kind, receipt.subtitle, null);
        m.location = blankToNull(receipt.location);
        m.shortTitles.addAll(StepCopy.shortTitles(m.title, m.location));
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

    /** Relative-day label plus one tick per slot of the shown day (白天/放学后, 晚上). */
    private void progress(CardModel m, int shownFirst, int shownLast, Rules rules) {
        int day = Rules.dayOf(shownFirst);
        int observed = observedSlot();
        m.progressLabel = relativeDay(day, Rules.dayOf(observed));
        StringBuilder spoken = new StringBuilder(m.progressLabel);
        for (int evening = 0; evening <= 1; evening++) {
            int slot = Rules.slot(day, evening == 1);
            CardModel.Tick tick;
            if (slot >= shownFirst && slot <= shownLast) tick = CardModel.Tick.CURRENT;
            else if (slot < observed) tick = CardModel.Tick.DONE;
            else tick = slot == observed || productiveAt(slot) ? CardModel.Tick.PENDING : CardModel.Tick.LOCKED;
            m.ticks.add(tick);
            String period = GameCalendar.ofSlot(slot, rules).periodZh();
            spoken.append(evening == 0 ? "：" : "，").append(period).append(tickWord(tick));
        }
        m.progressDescription = spoken.toString();
    }

    private boolean productiveAt(int slot) {
        Plan.Step step = snapshot.plan == null ? null : snapshot.plan.at(slot);
        return PlanItems.isProductive(step);
    }

    private String cueFor(Plan.Step step, StepCopy copy, Rules rules) {
        if (item.locked()) return lockCue(step, rules);
        if (!snapshot.planCurrent && snapshot.planning) return "正在根据最新记录重新安排…";
        if (!snapshot.planCurrent && snapshot.planErrorZh != null) return "安排还没更新，点开看看";
        if (copy.instruction != null) return copy.instruction;
        if ("free".equals(StepCopy.kindOf(step))) return "这个时段想做什么都行";
        return StepCopy.glossary(blankToNull(step.whyZh));
    }

    /** "这段时间不能自由行动" (or the engine's specific note), "到11月5日都不能自由行动" for a run. */
    private String lockCue(Plan.Step step, Rules rules) {
        if (Rules.dayOf(item.lastSlot) > Rules.dayOf(item.slot)) {
            GameCalendar.Info end = GameCalendar.ofSlot(item.lastSlot, rules);
            return "到" + end.month + "月" + end.dayOfMonth + "日都不能自由行动";
        }
        String sub = blankToNull(step.subtitleZh);
        boolean generic = sub == null || sub.contains("不能自由行动") || sub.contains("剧情安排") || sub.contains("剧情推进")
                || sub.trim().equals(blankToNull(step.titleZh) == null ? "" : step.titleZh.trim());
        return generic ? "这段时间不能自由行动" : StepCopy.glossary(sub.trim());
    }

    // ------------------------------------------------------------------ text helpers

    static String relativeDay(int day, int observedDay) {
        int delta = day - observedDay;
        if (delta == 0) return "今天";
        if (delta == 1) return "明天";
        if (delta == 2) return "后天";
        if (delta == -1) return "昨天";
        return delta > 0 ? delta + "天后" : (-delta) + "天前";
    }

    private static String tickWord(CardModel.Tick tick) {
        switch (tick) {
            case DONE: return "已记录";
            case CURRENT: return "正在显示";
            case LOCKED: return "没有安排";
            default: return "待做";
        }
    }

    /** "RANK 6 → 7" → "RANK 6→7"; blank → null. */
    static String compactTag(String subtitle) {
        return StepCopy.compact(subtitle);
    }

    /** Never shows an empty title: falls back to a plain description of the step kind. */
    static String titleOf(String title, String kind) {
        return StepCopy.titleOf(title, kind);
    }

    private static String describe(CardModel m) {
        return describe(m, Collections.emptyList());
    }

    /** The card read out: title, chip, place, hint, side steps, then {@code extra} (answers, 当日提醒). */
    private static String describe(CardModel m, List<String> extra) {
        StringBuilder sb = new StringBuilder(m.title);
        if (m.tag != null) sb.append("，").append(m.tag);
        if (m.location != null) sb.append("，地点：").append(m.location);
        if (m.hint != null) sb.append("。").append(m.hint);
        if (m.side != null) sb.append("。").append(m.side);
        for (String line : extra) {
            if (blankToNull(line) != null) sb.append("。").append(line.trim());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ answers

    /**
     * The choices of the step itself (docs/DESIGN_v3.md §18): a meeting's guide answers for its scene
     * ({@link DialogueHint#forMeeting}), an unlock step's picks; none without rules.
     */
    private DialogueHint.Answers ownAnswers(Plan.Step step, Rules rules) {
        if (rules == null || step == null) return DialogueHint.Answers.NONE;
        if ("social".equals(step.kind)) return DialogueHint.forMeeting(rules, snapshot.profile, step);
        if (step.action != null && step.action.startsWith("unlock:")) {
            return DialogueHint.forUnlockStep(unlockStep(rules, step.action));
        }
        return DialogueHint.Answers.NONE;
    }

    /** The unlock step of an action key "unlock:justice:0", or null. */
    static Rules.Step unlockStep(Rules rules, String key) {
        if (rules == null || key == null || !key.startsWith("unlock:")) return null;
        String[] parts = key.split(":");
        Rules.Link link = parts.length == 3 ? rules.link(parts[1]) : null;
        if (link == null || link.unlock == null) return null;
        try {
            int k = Integer.parseInt(parts[2]);
            return k >= 0 && k < link.unlock.steps.size() ? link.unlock.steps.get(k) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The widget's line of {@code a}; {@code label} overrides the 「答：」 (a side step's 「顺便选：」). */
    static CardModel.AnswerLine answerLine(DialogueHint.Answers a, String label) {
        String shortLabel = label == null && a.conditional ? DialogueHint.CONDITIONAL_SHORT_PREFIX : null;
        String note = DialogueHint.widgetNote(a);
        List<String> choices = DialogueHint.widgetChoices(a);
        if (choices.isEmpty() && a.any) note = DialogueHint.ANY_ZH;
        return new CardModel.AnswerLine(label != null ? label : DialogueHint.widgetLabel(a), shortLabel, choices, note,
                a.conditional);
    }

    /** 「课堂：马（第 2 项）」 / 「考试：五月病」 for a class or exam 当日提醒. */
    static CardModel.AnswerLine answerLine(Plan.Reminder r) {
        String answer = blankToNull(r.answerZh);
        if (answer == null) {
            // A reminder without its answer field: its short form is the whole line.
            String whole = blankToNull(r.shortZh) != null ? r.shortZh.trim() : r.textZh;
            return new CardModel.AnswerLine("", null, Collections.emptyList(), whole, false);
        }
        String label = Plan.Reminder.CLASS.equals(r.kind) ? CardModel.CLASS_LABEL : CardModel.EXAM_LABEL;
        String choice = answer.trim() + (Plan.Reminder.CLASS.equals(r.kind) && r.option > 0 ? CardModel.AnswerLine.position(r.option) : "");
        return new CardModel.AnswerLine(label, null, Collections.singletonList(choice), null, false);
    }

    static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s;
    }
}
