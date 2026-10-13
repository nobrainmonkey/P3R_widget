package app.p3r.dayplanner.v3.service;

import android.util.Log;

import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.engine.Outcome;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.store.CheckpointRow;
import app.p3r.dayplanner.v3.store.EventKind;
import app.p3r.dayplanner.v3.store.EventRow;
import app.p3r.dayplanner.v3.store.OutcomeApplier;
import app.p3r.dayplanner.v3.store.OutcomeRecord;
import app.p3r.dayplanner.v3.store.Replayer;
import app.p3r.dayplanner.v3.store.TowerRecord;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One-line Simplified Chinese summaries for the 记录历史 list, e.g.
 * "5月12日 晚上 · 探索塔尔塔罗斯 · 到达 27F · 余额 14,750 日元" or
 * "6月2日 放学后 · 皇帝 · 小田桐秀利 · RANK 6 → 7".
 *
 * <p>Rank and stat changes are read from a replay trace, so lines describe what the record did to
 * the observed state (undone records are described as if they had been applied).
 */
final class HistoryText {
    private static final String TAG = "P3rHistory";

    private HistoryText() {}

    /** States around one replayed row. */
    static final class Traced {
        final SimState before;
        final SimState after;
        final TowerRecord tower;

        Traced(SimState before, SimState after, TowerRecord tower) {
            this.before = before;
            this.after = after;
            this.tower = tower;
        }
    }

    /** Replays every checkpoint and returns the trace of each observed row by sequence number. */
    static Map<Long, Traced> trace(Rules rules, Profile profile, OutcomeApplier applier,
                                   List<CheckpointRow> checkpoints, List<EventRow> rows) {
        Map<Long, Traced> traces = new HashMap<>();
        Replayer replayer = new Replayer(rules, profile, applier);
        Replayer.Trace collect = (row, before, after, tower) ->
                traces.put(row.seq, new Traced(before, after, tower));
        for (CheckpointRow checkpoint : checkpoints) {
            List<EventRow> chain = new ArrayList<>();
            for (EventRow row : rows) {
                if (row.checkpointId == checkpoint.id) {
                    chain.add(row);
                }
            }
            try {
                replayer.replay(checkpoint.state(), chain, collect);
            } catch (JSONException e) {
                Log.w(TAG, "checkpoint " + checkpoint.id + " is unreadable", e);
            }
        }
        return traces;
    }

    /** History items, newest first; profile journal rows are not listed. {@code limit <= 0}: all. */
    static List<PlannerApi.HistoryItem> items(Rules rules, List<CheckpointRow> checkpoints,
                                              List<EventRow> rows, Map<Long, Traced> traces,
                                              long currentCheckpointId, int limit) {
        Map<Long, EventRow> bySeq = new HashMap<>();
        for (EventRow row : rows) {
            bySeq.put(row.seq, row);
        }
        Map<Long, CheckpointRow> checkpointsById = new HashMap<>();
        long firstCheckpointId = Long.MAX_VALUE;
        for (CheckpointRow c : checkpoints) {
            checkpointsById.put(c.id, c);
            firstCheckpointId = Math.min(firstCheckpointId, c.id);
        }
        Lookup ctx = new Lookup(rules, bySeq, checkpointsById, traces, firstCheckpointId);
        List<PlannerApi.HistoryItem> out = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            EventRow row = rows.get(i);
            if (EventKind.PROFILE.equals(row.kind)) {
                continue;
            }
            out.add(item(ctx, row, currentCheckpointId));
            if (limit > 0 && out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    /** Lookup tables shared while building one history list. */
    private static final class Lookup {
        final Rules rules;
        final Map<Long, EventRow> bySeq;
        final Map<Long, CheckpointRow> checkpoints;
        final Map<Long, Traced> traces;
        final long firstCheckpointId;

        Lookup(Rules rules, Map<Long, EventRow> bySeq, Map<Long, CheckpointRow> checkpoints,
                Map<Long, Traced> traces, long firstCheckpointId) {
            this.rules = rules;
            this.bySeq = bySeq;
            this.checkpoints = checkpoints;
            this.traces = traces;
            this.firstCheckpointId = firstCheckpointId;
        }
    }

    private static PlannerApi.HistoryItem item(Lookup ctx, EventRow row, long currentCheckpointId) {
        PlannerApi.HistoryItem item = new PlannerApi.HistoryItem();
        item.seq = row.seq;
        item.createdMillis = row.created;
        item.kind = row.kind;
        item.voided = row.voided;
        item.correctable = row.checkpointId == currentCheckpointId
                && currentCheckpointId != 0
                && !row.voided
                && EventKind.isVoidable(row.kind);
        item.slot = slotOf(ctx, row);
        String body;
        try {
            body = body(ctx, row);
        } catch (JSONException | RuntimeException e) {
            Log.w(TAG, "cannot describe row " + row.seq, e);
            body = "一条记录";
        }
        item.summaryZh = item.slot >= 0 ? ZhText.join(ZhText.slot(ctx.rules, item.slot), body) : body;
        return item;
    }

    /** Slot the row is about: its own, its target's, or its checkpoint's; -1 if unknown. */
    private static int slotOf(Lookup ctx, EventRow row) {
        switch (row.kind) {
            case EventKind.OUTCOME:
            case EventKind.SKIP:
            case EventKind.TOWER:
                return row.slot();
            case EventKind.TOWER_FIX:
            case EventKind.VOID: {
                EventRow target = ctx.bySeq.get(row.target());
                return target == null ? -1 : slotOf(ctx, target);
            }
            case EventKind.CHECKPOINT:
            case EventKind.IMPORT:
                return checkpointSlot(ctx, row);
            default:
                return -1;
        }
    }

    private static int checkpointSlot(Lookup ctx, EventRow row) {
        try {
            CheckpointRow c = ctx.checkpoints.get(row.payload().optLong("checkpointId", 0));
            return c == null ? -1 : c.state().slot;
        } catch (JSONException e) {
            return -1;
        }
    }

    /** Text after the date. */
    private static String body(Lookup ctx, EventRow row) throws JSONException {
        switch (row.kind) {
            case EventKind.OUTCOME:
            case EventKind.SKIP:
                return outcomeBody(ctx, row);
            case EventKind.TOWER:
                return ZhText.join("探索塔尔塔罗斯", towerDetails(ctx.rules, TowerRecord.fromJson(row.payload())));
            case EventKind.TOWER_FIX:
                return ZhText.join("修改了塔尔塔罗斯记录",
                        towerDetails(ctx.rules, TowerRecord.fromJson(row.payload().getJSONObject("tower"))));
            case EventKind.VOID: {
                EventRow target = ctx.bySeq.get(row.target());
                return target == null ? "撤销了一条记录" : "撤销了「" + body(ctx, target) + "」";
            }
            case EventKind.CHECKPOINT: {
                long id = row.payload().optLong("checkpointId", 0);
                return id == ctx.firstCheckpointId ? "设置了初始进度" : "同步了游戏现状";
            }
            case EventKind.IMPORT:
                return "从备份恢复了进度";
            default:
                return "一条记录";
        }
    }

    private static String outcomeBody(Lookup ctx, EventRow row) throws JSONException {
        OutcomeRecord record = OutcomeRecord.fromJson(row.payload());
        Outcome outcome = record.outcome;
        if (EventKind.SKIP.equals(row.kind)) {
            outcome.code = Outcome.Code.SKIPPED;
        }
        Action action = Action.parse(ctx.rules, record.actionKey);
        Traced traced = ctx.traces.get(row.seq);
        String title = title(ctx.rules, action);
        if (outcome.code == Outcome.Code.SKIPPED) {
            return ZhText.join(title, skipped(action, outcome.reason));
        }
        if (outcome.code == Outcome.Code.NOT_DONE) {
            return ZhText.join(title, notDone(action, outcome.reason));
        }
        if (action == null) {
            return title;
        }
        switch (action.type) {
            case MEET:
                return ZhText.join(title, meeting(action.target, outcome, traced));
            case UNLOCK_STEP:
                return ZhText.join(title, unlocked(action.target, traced));
            case ACTIVITY:
                return ZhText.join(title, activity(ctx.rules, outcome, traced));
            case TARTARUS:
                return ZhText.join(title, "已完成");
            default:
                return title;
        }
    }

    /**
     * "皇帝 · 小田桐秀利", "在宿舍房间学习", "与友近健二对话", "探索塔尔塔罗斯", "自由安排"; "其他行动" when
     * the action key is unknown to the current rule pack.
     */
    private static String title(Rules rules, Action action) {
        if (action == null) {
            return "其他行动";
        }
        switch (action.type) {
            case MEET:
                return rules.links[action.target].labelZh;
            case UNLOCK_STEP: {
                Rules.Link link = rules.links[action.target];
                List<Rules.Step> steps = link.unlock.steps;
                boolean known = action.step >= 0 && action.step < steps.size() && steps.get(action.step).zh != null;
                return known ? steps.get(action.step).zh : ZhText.join(link.labelZh, "解锁准备");
            }
            case ACTIVITY:
                return rules.activities[action.target].zh;
            case TARTARUS:
                return "探索塔尔塔罗斯";
            default:
                return "自由安排";
        }
    }

    private static String skipped(Action action, String reason) {
        if ("unwilling".equals(reason)) {
            return "跳过了";
        }
        String verb = "没做成";
        if (action != null && action.type == Action.Type.MEET) {
            verb = "没见到";
        } else if (action != null && action.type == Action.Type.TARTARUS) {
            verb = "没去成";
        }
        String why = ZhText.reason(reason);
        return why == null ? verb : verb + "（" + why + "）";
    }

    private static String notDone(Action action, String reason) {
        if ("hold".equals(reason)) {
            return "游戏提示暂时无法升级";
        }
        if (action != null && action.type == Action.Type.MEET) {
            return "没见到";
        }
        if (action != null && action.type == Action.Type.TARTARUS) {
            return "没去成";
        }
        return "没完成";
    }

    private static String meeting(int link, Outcome outcome, Traced traced) {
        if (traced != null && traced.after.rank[link] != traced.before.rank[link]) {
            return "RANK " + ZhText.rank(traced.before.rank[link]) + " → " + ZhText.rank(traced.after.rank[link]);
        }
        if (traced == null && outcome.code == Outcome.Code.RANK_UP) {
            return outcome.rank >= 0 ? "升到 RANK " + ZhText.rank(outcome.rank) : "升级了";
        }
        String met = outcome.code == Outcome.Code.NO_RANK_UP ? "见了面，没升级" : "见了面";
        return outcome.readyCue ? met + "，游戏提示下次可升级" : met;
    }

    private static String unlocked(int link, Traced traced) {
        boolean established = traced != null && traced.before.rank[link] == 0 && traced.after.rank[link] > 0;
        return established ? "已完成，社群建立了" : "已完成";
    }

    private static String activity(Rules rules, Outcome outcome, Traced traced) {
        List<String> parts = new ArrayList<>();
        for (int stat = 0; stat < SimState.STATS; stat++) {
            if (outcome.statLv[stat] > 0) {
                parts.add(ZhText.statName(rules, stat) + " Lv." + outcome.statLv[stat]);
            } else if (traced != null) {
                String gain = ZhText.statGain(rules, stat, traced.after.statPts[stat] - traced.before.statPts[stat]);
                if (gain != null) {
                    parts.add(gain);
                }
            }
        }
        if (outcome.cashBalance >= 0) {
            parts.add("余额 " + ZhText.yen(outcome.cashBalance));
        } else if (traced != null) {
            long delta = traced.after.cash - traced.before.cash;
            if (delta > 0) {
                parts.add("收入 " + ZhText.yen(delta));
            } else if (delta < 0) {
                parts.add("花费 " + ZhText.yen(-delta));
            }
        }
        if (parts.isEmpty()) {
            return "已完成";
        }
        return ZhText.join(parts.toArray(new String[0]));
    }

    /** "到达 27F · 余额 14,750 日元 · 救出了北村文吉". */
    static String towerDetails(Rules rules, TowerRecord rec) {
        String cash;
        if (rec.balanceMode) {
            cash = "余额 " + ZhText.yen(rec.amount);
        } else if (rec.amount > 0) {
            cash = "收入 " + ZhText.yen(rec.amount);
        } else if (rec.amount < 0) {
            cash = "支出 " + ZhText.yen(-rec.amount);
        } else {
            cash = "现金没变";
        }
        String rescue = null;
        if (rec.rescuedId != null) {
            for (Rules.Rescue r : rules.rescues) {
                if (rec.rescuedId.equals(r.id)) {
                    rescue = "救出了" + r.zh;
                }
            }
        }
        return ZhText.join("到达 " + rec.floor + "F", cash, rescue);
    }
}
