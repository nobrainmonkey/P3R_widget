package app.p3r.dayplanner.v3.service;

import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.store.EventKind;
import app.p3r.dayplanner.v3.store.EventRow;
import app.p3r.dayplanner.v3.store.StateShape;
import app.p3r.dayplanner.v3.store.TowerRecord;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Validation of new commands against the current ledger view. Every method returns null when the
 * command is acceptable, otherwise a {@link Rejection} with a natural Chinese message.
 *
 * <p>Observations are trusted over the rule pack: a record is refused only when it is impossible
 * to store consistently (wrong slot, negative cash, a floor above the block's top…), never because
 * the rules think the action was unavailable.
 */
final class CommandCheck {
    /** Sanity bound for typed yen amounts. */
    static final long MAX_CASH = 99_999_999L;
    /** Side steps one slot can carry (one per link at most). */
    static final int MAX_SIDE_KEYS = 32;

    static final String ZH_NO_STATE = "还没有填写当前进度，请先完成初始设置";
    static final String ZH_ALREADY_RECORDED = "这个时段已经记录过了";
    static final String ZH_CALENDAR_OVER = "日程已经全部结束了，不能再记录";
    static final String ZH_OUT_OF_CALENDAR = "这个时段不在游戏日程内";
    static final String ZH_UNKNOWN_ACTION = "没认出这一项，请重新选一下";
    static final String ZH_SIDE_UNKNOWN = "没认出顺便要做的事，请重新选一下";
    static final String ZH_RANK_RANGE = "社群 RANK 不对，请重新选一下";
    static final String ZH_RANK_NOT_HIGHER = "升级后的 RANK 应该比现在高";
    static final String ZH_RANK_UP_NOT_MEETING = "只有和社群对象见面才能记录升级";
    static final String ZH_STAT_RANGE = "学力、魅力、勇气只能是 Lv.1 到 MAX";
    static final String ZH_CASH_NEGATIVE = "现金不能是负数";
    static final String ZH_CASH_TOO_LARGE = "金额太大了，请检查一下";
    static final String ZH_NET_NEGATIVE = "按这次的增减算，现金会变成负数，请检查一下";
    static final String ZH_REASON = "没认出原因，请重新选一下";
    static final String ZH_TOWER_EVENING = "塔尔塔罗斯只能在晚上去";
    static final String ZH_TOWER_CLOSED = "这一天还进不了塔尔塔罗斯";
    static final String ZH_TOWER_BLOCK = "区块和日期对不上，请重新选择";
    static final String ZH_FLOOR_NEGATIVE = "楼层不能是负数";
    static final String ZH_CASH_UNKNOWN_AMOUNT = "选了「不记得钱了」就不用再填金额";
    static final String ZH_LEVEL = "主角等级要在 1 到 99 之间";
    static final String ZH_RESCUE = "没有找到这位失踪者";
    static final String ZH_FIX_MISSING = "找不到要修改的探索记录";
    static final String ZH_FIX_OLD = "这条记录早于最近一次同步现状，已经不能修改了";
    static final String ZH_FIX_VOIDED = "这条记录已经撤销了，不能再修改";
    static final String ZH_VOID_MISSING = "找不到要撤销的记录";
    static final String ZH_VOID_KIND = "这条记录不能撤销";
    static final String ZH_VOID_OLD = "这条记录早于最近一次同步现状，已经不能撤销了";
    static final String ZH_VOID_TWICE = "这条记录已经撤销过了";
    static final String ZH_STATE_MISSING = "请先填写当前进度";
    static final String ZH_STATE_INCOMPLETE = "当前进度不完整，请重新填写";
    static final String ZH_STATE_DATE = "这个日期不在游戏日程里（4月～次年1月31日）";
    static final String ZH_STAT_NEGATIVE = "学力、魅力、勇气不能是负数";
    static final String ZH_LINK_PROGRESS_NEGATIVE = "社群进度不能是负数";
    static final String ZH_VISITS_NEGATIVE = "探索次数不能是负数";
    static final String ZH_PROFILE_MISSING = "规划设置不完整，请重新填写";

    private static final Pattern REASON_ID = Pattern.compile("[a-z_]{1,32}");

    private CommandCheck() {}

    /** The slot must be exactly the observed next slot. */
    static Rejection nextSlot(LedgerView view, int slot) {
        if (view.observed == null) {
            return Rejection.invalid(ZH_NO_STATE);
        }
        int observedSlot = view.observed.slot;
        if (slot < observedSlot) {
            return Rejection.invalid(ZH_ALREADY_RECORDED);
        }
        if (observedSlot > view.rules.lastSlot()) {
            return Rejection.invalid(ZH_CALENDAR_OVER);
        }
        if (slot > view.rules.lastSlot()) {
            return Rejection.invalid(ZH_OUT_OF_CALENDAR);
        }
        if (slot > observedSlot) {
            return Rejection.gap(StateShape.openSlotsBetween(view.rules, view.profile, observedSlot, slot));
        }
        return null;
    }

    static Rejection outcome(LedgerView view, int slot, String actionKey, Outcome outcome) {
        Rejection r = nextSlot(view, slot);
        if (r != null) {
            return r;
        }
        return outcomeValues(view.rules, view.observed, actionKey, outcome);
    }

    /** Visible values of an outcome, checked against the state at its own historical slot. */
    static Rejection outcomeValues(Rules rules, SimState before, String actionKey, Outcome outcome) {
        Action action = Action.parse(rules, actionKey);
        if (!knownAction(rules, action)) {
            return Rejection.invalid(ZH_UNKNOWN_ACTION);
        }
        if (outcome.rank < -1 || outcome.rank > 10) {
            return Rejection.invalid(ZH_RANK_RANGE);
        }
        if (outcome.statLv == null || outcome.statLv.length != SimState.STATS) {
            return Rejection.invalid(ZH_STAT_RANGE);
        }
        for (int lv : outcome.statLv) {
            if (lv != -1 && (lv < 1 || lv > 6)) {
                return Rejection.invalid(ZH_STAT_RANGE);
            }
        }
        Rejection r = cashBalance(outcome.cashBalance);
        if (r != null) {
            return r;
        }
        if (outcome.reason != null && !REASON_ID.matcher(outcome.reason).matches()) {
            return Rejection.invalid(ZH_REASON);
        }
        r = sideKeys(rules, outcome.sideKeys);
        if (r != null) {
            return r;
        }
        if (outcome.code == Outcome.Code.RANK_UP) {
            if (action.type != Action.Type.MEET) {
                return Rejection.invalid(ZH_RANK_UP_NOT_MEETING);
            }
            if (outcome.rank >= 0 && outcome.rank <= before.rank[action.target]) {
                return Rejection.invalid(ZH_RANK_NOT_HIGHER);
            }
        }
        return null;
    }

    static Rejection outcomeTarget(LedgerView view, EventRow target, SimState before) {
        if (target == null || !(EventKind.OUTCOME.equals(target.kind) || EventKind.SKIP.equals(target.kind)))
            return Rejection.invalid("找不到要修改的行动记录");
        if (target.checkpointId != view.checkpointId) return Rejection.invalid(ZH_FIX_OLD);
        if (target.voided) return Rejection.invalid(ZH_FIX_VOIDED);
        if (before == null || target.slot() >= view.observed.slot)
            return Rejection.invalid("这条记录暂时不能修改，请先核对当前进度");
        return null;
    }

    /** A new tower visit at the observed slot. */
    static Rejection tower(LedgerView view, TowerRecord rec) {
        Rejection r = nextSlot(view, rec.slot);
        if (r != null) {
            return r;
        }
        if (!Rules.isEvening(rec.slot)) {
            return Rejection.invalid(ZH_TOWER_EVENING);
        }
        Rules.Block block = view.rules.blockOn(Rules.dayOf(rec.slot));
        if (block == null) {
            return Rejection.invalid(ZH_TOWER_CLOSED);
        }
        if (rec.block != block.idx) {
            return Rejection.invalid(ZH_TOWER_BLOCK);
        }
        r = sideKeys(view.rules, rec.sideKeys);
        if (r != null) {
            return r;
        }
        return towerValues(view.rules, block, rec, view.observed);
    }

    /**
     * Side-step keys passed with a record (null: none passed) must name unlock steps of this pack.
     * Whether they can be applied is decided when the record is stored, never refused.
     */
    static Rejection sideKeys(Rules rules, List<String> keys) {
        if (keys == null) {
            return null;
        }
        if (keys.size() > MAX_SIDE_KEYS) {
            return Rejection.invalid(ZH_SIDE_UNKNOWN);
        }
        for (String key : keys) {
            Action side = Action.parse(rules, key);
            if (side == null || side.type != Action.Type.UNLOCK_STEP || !knownAction(rules, side)) {
                return Rejection.invalid(ZH_SIDE_UNKNOWN);
            }
        }
        return null;
    }

    /** A correction of {@code target}; {@code before} is the replayed state just before it. */
    static Rejection correction(LedgerView view, EventRow target, TowerRecord rec, SimState before) {
        if (target == null || !EventKind.TOWER.equals(target.kind)) {
            return Rejection.invalid(ZH_FIX_MISSING);
        }
        if (target.checkpointId != view.checkpointId) {
            return Rejection.invalid(ZH_FIX_OLD);
        }
        if (target.voided) {
            return Rejection.invalid(ZH_FIX_VOIDED);
        }
        // The block of the visit's day under the loaded pack (as replay counts it), else the
        // stored index when the day has none.
        Rules.Block block = view.rules.blockOn(Rules.dayOf(rec.slot));
        if (block == null && rec.block >= 0 && rec.block < view.rules.blocks.length) {
            block = view.rules.blocks[rec.block];
        }
        if (block == null) {
            return Rejection.invalid(ZH_FIX_MISSING);
        }
        return towerValues(view.rules, block, rec, before);
    }

    static Rejection voidTarget(LedgerView view, EventRow target) {
        if (target == null) {
            return Rejection.invalid(ZH_VOID_MISSING);
        }
        if (!EventKind.isVoidable(target.kind)) {
            return Rejection.invalid(ZH_VOID_KIND);
        }
        if (target.checkpointId != view.checkpointId) {
            return Rejection.invalid(ZH_VOID_OLD);
        }
        if (target.voided) {
            return Rejection.invalid(ZH_VOID_TWICE);
        }
        return null;
    }

    /** A full observed state typed by the player. */
    static Rejection checkpoint(Rules rules, SimState s) {
        if (s == null) {
            return Rejection.invalid(ZH_STATE_MISSING);
        }
        if (s.statPts == null || s.rank == null || s.pts == null || s.ready == null
                || s.unlockStep == null || s.lastStepDay == null || s.towerVisits == null
                || s.shifts == null || s.used == null) {
            return Rejection.invalid(ZH_STATE_INCOMPLETE);
        }
        if (s.slot < rules.firstSlot() || s.slot > rules.lastSlot()) {
            return Rejection.invalid(ZH_STATE_DATE);
        }
        if (s.cash < 0) {
            return Rejection.invalid(ZH_CASH_NEGATIVE);
        }
        if (s.cash > MAX_CASH) {
            return Rejection.invalid(ZH_CASH_TOO_LARGE);
        }
        for (int rank : s.rank) {
            if (rank < 0 || rank > 10) {
                return Rejection.invalid(ZH_RANK_RANGE);
            }
        }
        if (anyNegative(s.statPts)) {
            return Rejection.invalid(ZH_STAT_NEGATIVE);
        }
        if (anyNegative(s.pts) || anyNegative(s.unlockStep)) {
            return Rejection.invalid(ZH_LINK_PROGRESS_NEGATIVE);
        }
        if (anyNegative(s.towerVisits) || anyNegative(s.shifts) || anyNegative(s.used)) {
            return Rejection.invalid(ZH_VISITS_NEGATIVE);
        }
        if (s.floor < 0) {
            return Rejection.invalid(ZH_FLOOR_NEGATIVE);
        }
        Rules.Block block = rules.blockOn(Rules.dayOf(s.slot));
        if (block != null && block.top > 0 && s.floor > block.top) {
            return Rejection.invalid(floorTooHigh(block));
        }
        return null;
    }

    /** Floor, cash, level and rescue of a tower record; {@code before} may be null. */
    private static Rejection towerValues(Rules rules, Rules.Block block, TowerRecord rec, SimState before) {
        if (rec.floor < 0) {
            return Rejection.invalid(ZH_FLOOR_NEGATIVE);
        }
        if (block.top > 0 && rec.floor > block.top) {
            return Rejection.invalid(floorTooHigh(block));
        }
        if (Math.abs(rec.amount) > MAX_CASH) {
            return Rejection.invalid(ZH_CASH_TOO_LARGE);
        }
        if (rec.cashUnknown && (rec.balanceMode || rec.amount != 0)) {
            return Rejection.invalid(ZH_CASH_UNKNOWN_AMOUNT);
        }
        if (rec.balanceMode && rec.amount < 0) {
            return Rejection.invalid(ZH_CASH_NEGATIVE);
        }
        if (!rec.balanceMode && before != null && before.cashExact && before.cash + rec.amount < 0) {
            return Rejection.invalid(ZH_NET_NEGATIVE);
        }
        if (rec.level != null && (rec.level < 1 || rec.level > 99)) {
            return Rejection.invalid(ZH_LEVEL);
        }
        if (rec.rescuedId != null && !hasRescue(rules, rec.rescuedId)) {
            return Rejection.invalid(ZH_RESCUE);
        }
        return null;
    }

    /** "楼层不能超过本区块的最高层 43F". */
    static String floorTooHigh(Rules.Block block) {
        return "楼层不能超过本区块的最高层 " + block.top + "F";
    }

    /** -1 means "not given"; anything else must be a plausible balance. */
    private static Rejection cashBalance(long cash) {
        if (cash == -1) {
            return null;
        }
        if (cash < 0) {
            return Rejection.invalid(ZH_CASH_NEGATIVE);
        }
        if (cash > MAX_CASH) {
            return Rejection.invalid(ZH_CASH_TOO_LARGE);
        }
        return null;
    }

    /** Parsed, not a lock, and every index it carries exists in the rules. */
    private static boolean knownAction(Rules rules, Action action) {
        if (action == null) {
            return false;
        }
        switch (action.type) {
            case MEET:
            case INVITE:
                return true;
            case UNLOCK_STEP:
                return action.step >= 0 && action.step < rules.links[action.target].unlock.steps.size();
            case ACTIVITY:
            case FREE:
                return true;
            case TARTARUS:
                return action.target >= 0 && action.target < rules.blocks.length;
            case NOTE:
                return action.target >= 0 && action.target < rules.links.length;
            case EPISODE:
                return action.target >= 0 && action.target < rules.episodes.length;
            default:
                return false;
        }
    }

    private static boolean hasRescue(Rules rules, String id) {
        for (Rules.Rescue rescue : rules.rescues) {
            if (id.equals(rescue.id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyNegative(int[] values) {
        for (int v : values) {
            if (v < 0) {
                return true;
            }
        }
        return false;
    }
}
