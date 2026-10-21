package app.p3r.dayplanner.v3.service;

import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.engine.Engine;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds labelled {@link Plan.Step}s for the 改做别的 list from a forecast transition when a
 * {@link Forecaster} does not label them itself. The wording is the engine's own (engine Labels),
 * so the list reads the same either way: title "皇帝 · 小田桐秀利", subtitle "RANK 6 → 7" /
 * "RANK 6 · 见面" / "学力 +2" / "+2,500 日元 · 魅力 +1、勇气 +1".
 */
final class StepLabels {
    /** Location line for Tartarus visits. */
    static final String TOWER_LOCATION = "影时间 · 月光馆学园";
    /** Where the dating-site note is bought, when the pack does not say. */
    static final String NOTE_LOCATION = "桐叶购物中心 · 放纵俱乐部";
    /** The remark of an invitation or a note: it never ranks the link up by itself. */
    static final String AFFINITY_ONLY = "只加好感，不会直接升级。";

    private StepLabels() {}

    static Plan.Step step(Rules rules, Profile profile, SimState before, Action action, Engine.Applied applied) {
        Plan.Step step = new Plan.Step();
        step.slot = before.slot;
        step.action = action.key(rules);
        if (applied != null) {
            if (applied.predicted != null) {
                step.predicted = applied.predicted;
            }
            step.estimate = applied.estimate;
            if (applied.assumptions != null) {
                step.assumptions = new ArrayList<>(applied.assumptions);
            }
            if (applied.side != null) {
                step.side = new ArrayList<>(applied.side);
                for (String key : applied.side) {
                    step.sideZh.add(sideZh(rules, key));
                }
            }
        }
        switch (action.type) {
            case MEET:
                labelMeeting(rules, profile, before, action, step);
                break;
            case INVITE:
                Rules.Link invited = rules.links[action.target];
                step.kind = "invite";
                step.entity = invited.id;
                step.titleZh = invited.labelZh;
                step.locationZh = "应邀外出";
                step.subtitleZh = "休息日邀约 · 收到短信时";
                step.detailZh = AFFINITY_ONLY;
                break;
            case UNLOCK_STEP:
                labelUnlock(rules, action, step);
                break;
            case ACTIVITY:
                labelActivity(rules, before, action, step, applied != null && applied.predicted != null);
                break;
            case TARTARUS:
                labelTower(rules, before, action, step);
                break;
            case NOTE:
                labelNote(rules, action, step);
                break;
            case EPISODE:
                labelEpisode(rules, before, action, step);
                break;
            default:
                step.kind = "free";
                step.titleZh = "自由活动";
                step.subtitleZh = "";
                break;
        }
        return step;
    }

    /** "记得带上「皇帝」阿尔卡那的人格面具" when the forecast counts the Persona bonus for the link. */
    static String personaHint(Rules rules, Profile profile, int link) {
        if (profile == null || link < 0 || link >= rules.links.length || !profile.personaFor(link)) {
            return null;
        }
        String arcana = rules.links[link].arcanaZh;
        return arcana == null ? null : "记得带上「" + arcana + "」阿尔卡那的人格面具";
    }

    /** "顺便：把 20,000 日元交给穿西装的男人" for a side-step key. */
    private static String sideZh(Rules rules, String key) {
        Action side = Action.parse(rules, key);
        if (side == null || side.type != Action.Type.UNLOCK_STEP) {
            return "顺便：解锁前的准备";
        }
        return "顺便：" + HistoryText.unlockStepZh(rules, side);
    }

    /** "RANK 6 → 7" for a rank-up, else "RANK 6 · 见面" ("MAX · 见面" at MAX). */
    private static void labelMeeting(Rules rules, Profile profile, SimState before, Action action, Plan.Step step) {
        Rules.Link link = rules.links[action.target];
        int rank = before.rank[action.target];
        step.kind = "social";
        step.entity = link.id;
        step.titleZh = link.labelZh;
        step.locationZh = link.locationZh;
        if (step.predicted.rankUp) {
            step.subtitleZh = ZhText.rankChange(rank, Math.min(10, rank + 1));
        } else {
            step.subtitleZh = ZhText.join(rank >= 10 ? "MAX" : "RANK " + ZhText.rank(rank), "见面");
        }
        step.hintZh = personaHint(rules, profile, action.target);
    }

    /** The step's own words, with its fee: "把钱交给田中社长 · 花费 20,000 日元". */
    private static void labelUnlock(Rules rules, Action action, Plan.Step step) {
        Rules.Link link = rules.links[action.target];
        Rules.Step unlock = link.unlock != null && action.step >= 0 && action.step < link.unlock.steps.size()
                ? link.unlock.steps.get(action.step)
                : null;
        step.kind = "unlock";
        step.entity = link.id;
        step.titleZh = link.labelZh;
        String what = HistoryText.unlockStepZh(rules, action);
        step.subtitleZh = unlock != null && unlock.cost > 0 && !what.contains("日元")
                ? ZhText.join(what, "花费 " + ZhText.yen(unlock.cost))
                : what;
        step.locationZh = unlock != null && unlock.locationZh != null ? unlock.locationZh : link.locationZh;
        step.detailZh = unlock == null ? null : unlock.howZh;
    }

    /**
     * Like the engine: stat gains joined by "、", the pay first for a job, the fee last
     * ("魅力 +2 · 花费 1,500 日元"), the trait progress of a dorm activity last ("学力 +2 · 2/3").
     * {@code forecast}: use the forecast deltas; otherwise the activity's nominal gain and cost.
     */
    private static void labelActivity(Rules rules, SimState before, Action action, Plan.Step step, boolean forecast) {
        Rules.Activity activity = rules.activities[action.target];
        boolean job = activity.kind == Rules.ActivityKind.JOB;
        boolean dorm = activity.dormPartnerZh != null && !activity.dormPartnerZh.isEmpty();
        step.kind = dorm ? "dorm" : job ? "job" : "stat";
        step.entity = activity.id;
        step.titleZh = activity.zh;
        step.locationZh = activity.locationZh;
        long cashDelta = forecast ? step.predicted.cashDelta : -activity.cost;
        List<String> gains = new ArrayList<>();
        for (int stat = 0; stat < SimState.STATS; stat++) {
            int delta = forecast ? step.predicted.statDelta[stat] : activity.gain[stat];
            String gain = ZhText.statGain(rules, stat, delta);
            if (gain != null) {
                gains.add(gain);
            }
        }
        String stats = String.join("、", gains);
        String pay = cashDelta > 0 ? "+" + ZhText.yen(cashDelta) : null;
        String fee = cashDelta < 0 ? "花费 " + ZhText.yen(-cashDelta) : null;
        String progress = null;
        if (dorm) {
            int count = before != null && activity.idx < before.used.length ? before.used[activity.idx] : 0;
            int after = Math.min(3, count + 1);
            progress = stats.isEmpty() ? "特性进度 " + after + "/3" : after + "/3";
            step.detailZh = activity.noteZh;
        }
        step.subtitleZh = ZhText.join(pay, stats, fee, progress);
        if (step.subtitleZh.isEmpty() && !job && !dorm) {
            step.subtitleZh = "提升个人能力值";
        }
    }

    /** "世俗之庭 台伯勒（2–22F）· 本区块第 1 次". */
    private static void labelTower(Rules rules, SimState before, Action action, Plan.Step step) {
        step.kind = "tower";
        step.titleZh = "探索塔尔塔罗斯";
        step.locationZh = TOWER_LOCATION;
        if (action.target >= 0 && action.target < rules.blocks.length) {
            Rules.Block block = rules.blocks[action.target];
            int visit = action.target < before.towerVisits.length ? before.towerVisits[action.target] + 1 : 1;
            String name = block.floorsZh == null || block.floorsZh.isEmpty()
                    ? block.zh
                    : block.zh + "（" + block.floorsZh + "）";
            step.entity = block.id;
            step.subtitleZh = ZhText.join(name, "本区块第 " + visit + " 次");
        } else {
            step.subtitleZh = "";
        }
    }

    /** Title "结缘网站的字条 · 山岸风花", subtitle "好感 +10 · 花费 1,500 日元". */
    private static void labelNote(Rules rules, Action action, Plan.Step step) {
        Rules.Link link = rules.links[action.target];
        Rules.DatingNote note = rules.datingNote;
        String who = ZhText.who(link);
        step.kind = "note";
        step.entity = link.id;
        step.titleZh = ZhText.join(HistoryText.noteZh(rules), who);
        if (note != null && note.gain > 0) {
            step.subtitleZh = ZhText.join("好感 +" + note.gain, note.cost > 0 ? "花费 " + ZhText.yen(note.cost) : null);
        } else {
            step.subtitleZh = "加好感";
        }
        step.locationZh = note != null && note.locationZh != null ? note.locationZh : NOTE_LOCATION;
        String how = note != null && note.howZh != null
                ? note.howZh
                : "在放纵俱乐部买结缘网站的字条，回宿舍用公用电脑给" + who + "加好感";
        String remark = note != null && note.noteZh != null && !note.noteZh.trim().isEmpty() ? note.noteZh.trim() : AFFINITY_ONLY;
        step.detailZh = how + "\n" + remark;
    }

    /** Title "羁绊剧情 · 伊织顺平（第 2 话）", subtitle "共 5 话"; the how-to and the reward in the detail. */
    private static void labelEpisode(Rules rules, SimState before, Action action, Plan.Step step) {
        Rules.Episode episode = action.target >= 0 && action.target < rules.episodes.length
                ? rules.episodes[action.target]
                : null;
        int number = HistoryText.episodeNumber(before, action.target);
        step.kind = "episode";
        step.entity = episode == null ? null : episode.id;
        step.titleZh = HistoryText.episodeTitle(rules, action.target, number);
        step.subtitleZh = episode != null && episode.count > 0
                ? episode.hasUnplannedScenes() ? "需安排 " + episode.count + " 次邀约" : "共 " + episode.count + " 话"
                : "";
        step.locationZh = episode == null ? null : episode.locationOf(number);
        if (episode == null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        addLine(lines, episode.howOf(number));
        String part = number >= 1 ? episode.rewardOf(number) : null;
        if (part != null && !part.trim().isEmpty()) {
            lines.add("这一话的奖励：" + part.trim());
        }
        addLine(lines, episode.noteZh);
        addLine(lines, episode.rewardZh);
        step.detailZh = lines.isEmpty() ? null : String.join("\n", lines);
    }

    private static void addLine(List<String> lines, String text) {
        if (text != null && !text.trim().isEmpty()) {
            lines.add(text.trim());
        }
    }
}
