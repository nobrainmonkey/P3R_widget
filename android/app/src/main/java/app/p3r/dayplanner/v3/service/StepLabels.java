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
 * Builds labelled {@link Plan.Step}s for the 改做别的 list from a forecast transition, with the
 * wording of docs/GLOSSARY_zh.md: title "皇帝 · 小田桐秀利", subtitle "RANK 6 → 7" /
 * "RANK 6 · 见面（预计不升级）" / "学力 +2" / "+2,500 日元".
 */
final class StepLabels {
    /** Location line for Tartarus visits. */
    static final String TOWER_LOCATION = "影时间 · 月光馆学园";
    /** Where the dating-site note is bought, when the pack does not say. */
    static final String NOTE_LOCATION = "桐叶购物中心 · 放纵俱乐部";

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
                step.subtitleZh = "休息日邀约 · 收到短信才选 · 只加好感";
                step.detailZh = "收到短信后赴约；只加好感，不直接升级。";
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
                labelNote(rules, before, action, step);
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

    private static void labelMeeting(Rules rules, Profile profile, SimState before, Action action, Plan.Step step) {
        Rules.Link link = rules.links[action.target];
        int rank = before.rank[action.target];
        step.kind = "social";
        step.entity = link.id;
        step.titleZh = link.labelZh;
        step.locationZh = link.locationZh;
        step.subtitleZh = step.predicted.rankUp
                ? ZhText.rankChange(rank, Math.min(10, rank + 1))
                : ZhText.join("RANK " + ZhText.rank(rank), "见面（预计不升级）");
        step.hintZh = personaHint(rules, profile, action.target);
    }

    private static void labelUnlock(Rules rules, Action action, Plan.Step step) {
        Rules.Link link = rules.links[action.target];
        Rules.Step unlock = link.unlock != null && action.step >= 0 && action.step < link.unlock.steps.size()
                ? link.unlock.steps.get(action.step)
                : null;
        step.kind = "unlock";
        step.entity = link.id;
        step.titleZh = link.labelZh;
        step.subtitleZh = HistoryText.unlockStepZh(rules, action);
        step.locationZh = unlock != null && unlock.locationZh != null ? unlock.locationZh : link.locationZh;
        step.detailZh = unlock == null ? null : unlock.howZh;
    }

    /** {@code forecast}: use the forecast deltas; otherwise the activity's nominal gain and cost. */
    private static void labelActivity(Rules rules, SimState before, Action action, Plan.Step step, boolean forecast) {
        Rules.Activity activity = rules.activities[action.target];
        boolean job = activity.kind == Rules.ActivityKind.JOB;
        boolean dorm = activity.dormPartnerZh != null && !activity.dormPartnerZh.isEmpty();
        step.kind = dorm ? "dorm" : job ? "job" : "stat";
        step.entity = activity.id;
        step.titleZh = activity.zh;
        step.locationZh = activity.locationZh;
        long cashDelta = forecast ? step.predicted.cashDelta : -activity.cost;
        List<String> parts = new ArrayList<>();
        if (cashDelta > 0) {
            parts.add("+" + ZhText.yen(cashDelta));
        }
        for (int stat = 0; stat < SimState.STATS; stat++) {
            int delta = forecast ? step.predicted.statDelta[stat] : activity.gain[stat];
            String gain = ZhText.statGain(rules, stat, delta);
            if (gain != null) {
                parts.add(gain);
            }
        }
        if (cashDelta < 0) {
            parts.add("花费 " + ZhText.yen(-cashDelta));
        }
        if (dorm) {
            int count = before != null && activity.idx < before.used.length ? before.used[activity.idx] : 0;
            parts.add("特性进度 " + Math.min(3, count + 1) + "/3");
            step.detailZh = activity.noteZh;
        }
        step.subtitleZh = ZhText.join(parts.toArray(new String[0]));
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

    /** Title "结缘网站的字条", subtitle "给山岸风花加好感 · RANK 6". */
    private static void labelNote(Rules rules, SimState before, Action action, Plan.Step step) {
        Rules.Link link = rules.links[action.target];
        Rules.DatingNote note = rules.datingNote;
        step.kind = "note";
        step.entity = link.id;
        step.titleZh = HistoryText.noteZh(rules);
        String who = link.personZh != null ? link.personZh : link.labelZh;
        step.subtitleZh = ZhText.join("给" + who + "加好感", "RANK " + ZhText.rank(before.rank[action.target]));
        step.locationZh = note != null && note.locationZh != null ? note.locationZh : NOTE_LOCATION;
        step.detailZh = note != null && note.howZh != null
                ? note.howZh
                : "在放纵俱乐部买结缘网站的字条，回宿舍用公用电脑给" + who + "加好感";
    }

    /** Title "羁绊剧情 · 伊织顺平（第 2 话）", subtitle: what finishing the track gives. */
    private static void labelEpisode(Rules rules, SimState before, Action action, Plan.Step step) {
        Rules.Episode episode = action.target >= 0 && action.target < rules.episodes.length
                ? rules.episodes[action.target]
                : null;
        step.kind = "episode";
        step.entity = episode == null ? null : episode.id;
        step.titleZh = HistoryText.episodeTitle(rules, action.target, HistoryText.episodeNumber(before, action.target));
        step.subtitleZh = episode != null && episode.rewardZh != null ? episode.rewardZh : "不加社群点数";
        step.locationZh = episode == null ? null : episode.locationZh;
        step.detailZh = episode == null ? null : episode.noteZh;
    }
}
