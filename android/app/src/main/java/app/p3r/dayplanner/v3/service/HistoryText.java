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
import app.p3r.dayplanner.v3.store.PackLayout;
import app.p3r.dayplanner.v3.store.Replayer;
import app.p3r.dayplanner.v3.store.TowerRecord;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One-line Simplified Chinese summaries for the 记录历史 list (wording per docs/GLOSSARY_zh.md),
 * e.g. "5月12日 晚上 · 探索塔尔塔罗斯 · 到达 27F · 出塔后余额 14,750 日元" or
 * "6月2日 放学后 · 皇帝 · 小田桐秀利 · RANK 6 → 7".
 *
 * <p>Rank and stat changes are read from a replay trace, so lines describe what the record did to
 * the observed state (undone records are described as if they had been applied).
 */
final class HistoryText {
    private static final String TAG = "P3rHistory";

    /** Fallback when a row cannot be described. */
    static final String ZH_UNKNOWN_ROW = "一条记录";
    /** The story-hold answer (outcome NOT_DONE, reason "hold"), worded as on the result sheet. */
    static final String ZH_HOLD = "剧情原因暂时升不了";

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
            PackLayout.Remapped start = LedgerView.start(checkpoint, rules);
            if (start != null) {
                replayer.replay(start.state, chain, collect);
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
        item.undoable = row.checkpointId == currentCheckpointId
                && currentCheckpointId != 0
                && !row.voided
                && EventKind.isVoidable(row.kind);
        item.correctable = item.undoable && EventKind.TOWER.equals(row.kind);
        item.slot = slotOf(ctx, row);
        try {
            item.tower = towerValues(ctx, row);
        } catch (JSONException | RuntimeException e) {
            Log.w(TAG, "cannot read the tower values of row " + row.seq, e);
        }
        String body;
        try {
            body = body(ctx, row);
        } catch (JSONException | RuntimeException e) {
            Log.w(TAG, "cannot describe row " + row.seq, e);
            body = ZH_UNKNOWN_ROW;
        }
        item.summaryZh = item.slot >= 0 ? ZhText.join(ZhText.slot(ctx.rules, item.slot), body) : body;
        return item;
    }

    /**
     * Tower values for the correction form: for a tower row the values in effect (from the replay
     * trace, which applies the latest correction), for a correction row its own values.
     */
    private static PlannerApi.TowerReturn towerValues(Lookup ctx, EventRow row) throws JSONException {
        TowerRecord rec;
        if (EventKind.TOWER.equals(row.kind)) {
            Traced traced = ctx.traces.get(row.seq);
            rec = traced != null && traced.tower != null ? traced.tower : TowerRecord.fromJson(row.payload());
        } else if (EventKind.TOWER_FIX.equals(row.kind)) {
            rec = TowerRecord.fromJson(row.payload().getJSONObject("tower"));
        } else {
            return null;
        }
        return towerReturn(rec);
    }

    /** {@code rec} as the quick sheet would enter it. */
    static PlannerApi.TowerReturn towerReturn(TowerRecord rec) {
        PlannerApi.TowerReturn t = new PlannerApi.TowerReturn();
        t.slot = rec.slot;
        t.block = rec.block;
        t.floor = rec.floor;
        t.balanceMode = rec.balanceMode;
        t.amount = rec.amount;
        t.level = rec.level;
        t.rescuedId = rec.rescuedId;
        t.cashUnknown = rec.cashUnknown;
        t.sideDone = rec.sideDone;
        t.sideKeys = rec.side == null ? null : new ArrayList<>(rec.side);
        return t;
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
                return ZhText.join("修改了这次探索",
                        towerDetails(ctx.rules, TowerRecord.fromJson(row.payload().getJSONObject("tower"))));
            case EventKind.VOID: {
                EventRow target = ctx.bySeq.get(row.target());
                return target == null ? "撤销了一条记录" : "撤销了「" + body(ctx, target) + "」";
            }
            case EventKind.CHECKPOINT: {
                long id = row.payload().optLong("checkpointId", 0);
                return id == ctx.firstCheckpointId ? "设置了初始进度" : "同步了现状";
            }
            case EventKind.IMPORT:
                return "从备份恢复了进度";
            default:
                return ZH_UNKNOWN_ROW;
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
        String title = title(ctx.rules, action, traced);
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
            case INVITE:
                if (traced != null && traced.after.rank[action.target] != traced.before.rank[action.target]) {
                    return ZhText.join(title, meeting(action.target, outcome, traced));
                }
                return ZhText.join(title, outcome.readyCue ? "赴约了 · 游戏提示下次能升级" : "赴约了");
            case UNLOCK_STEP:
                return ZhText.join(title, unlocked(action.target, traced));
            case ACTIVITY:
                return ZhText.join(title, activity(ctx.rules, outcome, traced));
            case TARTARUS:
            case NOTE:
                return ZhText.join(title, "已完成");
            case EPISODE:
                return ZhText.join(title, "看完了");
            default:
                return title;
        }
    }

    /**
     * "皇帝 · 小田桐秀利", "恶魔 · 田中社长 · 再交 10,000 日元", "在宿舍房间学习", "探索塔尔塔罗斯",
     * "结缘网站的字条 · 女教皇 · 山岸风花", "羁绊剧情 · 伊织顺平（第 2 话）", "自由活动"; "其他行动"
     * when the action key is unknown to the current rule pack.
     */
    static String title(Rules rules, Action action, Traced traced) {
        if (action == null) {
            return "其他行动";
        }
        switch (action.type) {
            case MEET:
                return rules.links[action.target].labelZh;
            case INVITE:
                return ZhText.join("休息日邀约", rules.links[action.target].labelZh);
            case UNLOCK_STEP:
                return ZhText.join(rules.links[action.target].labelZh, unlockStepZh(rules, action));
            case ACTIVITY:
                return rules.activities[action.target].zh;
            case TARTARUS:
                return "探索塔尔塔罗斯";
            case NOTE:
                return ZhText.join(noteZh(rules), rules.links[action.target].labelZh);
            case EPISODE:
                return episodeTitle(rules, action.target, traced == null ? -1 : episodeNumber(traced.before, action.target));
            case LOCKED: {
                String reason = rules.lockReasonZh(action.reason);
                return reason != null ? reason : "剧情";
            }
            default:
                return "自由活动";
        }
    }

    /** The step's own words, else "解锁前的准备（第 2 步，共 3 步）". */
    static String unlockStepZh(Rules rules, Action action) {
        Rules.Link link = rules.links[action.target];
        List<Rules.Step> steps = link.unlock == null ? new ArrayList<>() : link.unlock.steps;
        if (action.step >= 0 && action.step < steps.size() && steps.get(action.step).zh != null) {
            return steps.get(action.step).zh;
        }
        if (action.step >= 0 && action.step < steps.size()) {
            return "解锁前的准备（第 " + (action.step + 1) + " 步，共 " + steps.size() + " 步）";
        }
        return "解锁前的准备";
    }

    /** "结缘网站的字条" (or the pack's own name for it). */
    static String noteZh(Rules rules) {
        return rules.datingNote != null && rules.datingNote.zh != null && !rules.datingNote.zh.isEmpty()
                ? rules.datingNote.zh
                : "结缘网站的字条";
    }

    /** "羁绊剧情 · 伊织顺平（第 2 话）"; without the number when {@code number < 1}. */
    static String episodeTitle(Rules rules, int track, int number) {
        Rules.Episode episode = track >= 0 && track < rules.episodes.length ? rules.episodes[track] : null;
        String who = episode == null ? null : episode.personZh;
        String title = ZhText.join("羁绊剧情", who);
        return number >= 1 ? title + "（第 " + number + " 话）" : title;
    }

    /** Number of the next episode of {@code track} in {@code s} (1-based), or -1. */
    static int episodeNumber(SimState s, int track) {
        return s != null && track >= 0 && track < s.episodes.length ? s.episodes[track] + 1 : -1;
    }

    private static String skipped(Action action, String reason) {
        if ("unwilling".equals(reason)) {
            return "跳过了";
        }
        String verb = missed(action, "没做成");
        String why = ZhText.reason(reason);
        return why == null ? verb : verb + "（" + why + "）";
    }

    private static String notDone(Action action, String reason) {
        if ("hold".equals(reason)) {
            // The same words as the answer the player tapped on the result sheet.
            return ZH_HOLD;
        }
        return missed(action, "没完成");
    }

    /** "没见到" / "没去成" / "没用上" / "没看成", else {@code fallback}. */
    private static String missed(Action action, String fallback) {
        if (action == null) {
            return fallback;
        }
        switch (action.type) {
            case MEET:
                return "没见到";
            case INVITE:
                return "没赴约";
            case TARTARUS:
                return "没去成";
            case NOTE:
                return "没用上";
            case EPISODE:
                return "没看成";
            default:
                return fallback;
        }
    }

    private static String meeting(int link, Outcome outcome, Traced traced) {
        if (traced != null && traced.after.rank[link] != traced.before.rank[link]) {
            return ZhText.rankChange(traced.before.rank[link], traced.after.rank[link]);
        }
        if (traced == null && outcome.code == Outcome.Code.RANK_UP) {
            return outcome.rank >= 0 ? (outcome.rank >= 10 ? "升到 MAX" : "升到 RANK " + outcome.rank) : "升级了";
        }
        String met = outcome.code == Outcome.Code.NO_RANK_UP ? ZhText.join("见面了", "没升级") : "见面了";
        return outcome.readyCue ? ZhText.join(met, "游戏提示下次能升级") : met;
    }

    private static String unlocked(int link, Traced traced) {
        boolean established = traced != null && traced.before.rank[link] == 0 && traced.after.rank[link] > 0;
        return established ? ZhText.join("已完成", "社群解锁了") : "已完成";
    }

    private static String activity(Rules rules, Outcome outcome, Traced traced) {
        List<String> parts = new ArrayList<>();
        for (int stat = 0; stat < SimState.STATS; stat++) {
            if (outcome.statLv[stat] > 0) {
                parts.add(ZhText.statName(rules, stat) + " " + ZhText.statLv(outcome.statLv[stat]));
            } else if (traced != null) {
                String gain = ZhText.statGain(rules, stat, traced.after.statPts[stat] - traced.before.statPts[stat]);
                if (gain != null) {
                    parts.add(gain);
                }
            }
        }
        if (outcome.cashBalance >= 0) {
            parts.add("现金 " + ZhText.yen(outcome.cashBalance));
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

    /** "到达 27F · 出塔后余额 14,750 日元 · 救出了北村文吉". */
    static String towerDetails(Rules rules, TowerRecord rec) {
        String cash;
        if (rec.cashUnknown) {
            cash = "不记得钱了，现金按没变记";
        } else if (rec.balanceMode) {
            cash = "出塔后余额 " + ZhText.yen(rec.amount);
        } else if (rec.amount > 0) {
            cash = "多了 " + ZhText.yen(rec.amount);
        } else if (rec.amount < 0) {
            cash = "少了 " + ZhText.yen(-rec.amount);
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
        return ZhText.join("到达 " + rec.floor + "F", cash, rescue, rec.sideDone ? null : "顺便要做的事没做");
    }
}
