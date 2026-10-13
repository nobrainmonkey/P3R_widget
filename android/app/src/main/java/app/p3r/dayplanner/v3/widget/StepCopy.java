package app.p3r.dayplanner.v3.widget;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * The widget's wording of one plan step (docs/GLOSSARY_zh.md): the engine's copy cut down to what
 * fits a card. The tag chip keeps only the short fact (RANK 6→7, 学力 +2, +2,500 日元, 本区块第 1 次);
 * long unlock instructions move to the cue line; the dating-site note and Link Episodes get their own
 * titles. Reads both the current and the earlier engine wording. Pure Java so it can be tested
 * without Android; {@code rules} may be null.
 */
final class StepCopy {
    static final String NOTE_TITLE = "结缘网站的字条";
    static final String EPISODE_TITLE = "羁绊剧情";
    static final String FREE_TITLE = "自由活动";
    static final String TOWER_TITLE = "探索塔尔塔罗斯";
    /**
     * How the note is used, for the cue line (the title and chip already say who and what for, the
     * location row where it is sold). The full name, as the glossary writes it, and short enough for
     * one line of the 250-wide card.
     */
    static final String NOTE_HOW = "买结缘网站的字条，当晚回宿舍用公用电脑";

    /** "RANK 6 → 7", "RANK 9 → MAX", and the older "RANK 9 → RANK MAX". */
    private static final Pattern RANK_STEP = Pattern.compile("RANK\\s*(\\d+|MAX)\\s*→\\s*(?:RANK\\s*)?(\\d+|MAX)");
    /** "RANK MAX", which the glossary writes as plain "MAX" (「升到 MAX」「已经 MAX」). */
    private static final Pattern RANK_MAX = Pattern.compile("RANK\\s*MAX");
    /** Chip of a meeting with a link already at MAX (like "RANK 4 · 见面" below it). */
    private static final String MAX_MEETING = "MAX · 见面";
    private static final Pattern EPISODE_NO = Pattern.compile("第\\s*(\\d+)\\s*话");
    private static final Pattern VISIT = Pattern.compile("^第\\s*(\\d+)\\s*次$");
    private static final Pattern LEVEL_UP = Pattern.compile("\\s*[（(]\\s*升到\\s*(Lv\\.?\\s*\\d|MAX)\\s*[）)]");
    private static final String SEP = "\\s*·\\s*";
    private static final Pattern LV6 = Pattern.compile("Lv\\.?\\s*6(?!\\d)");

    /** Card title (never blank). */
    String title = "";
    /** The title for a card too small for it ({@link #shortTitle(String)}), or null. */
    String shortTitle;
    CardModel.TitleIcon icon = CardModel.TitleIcon.NONE;
    /** Chip text, or null. */
    String tag;
    String location;
    /** Replaces the why-line on the cue (an unlock step's instruction), or null. */
    String instruction;
    /** Reminder line ({@link Plan.Step#hintZh}), or null. */
    String hint;
    /** Side steps joined into one line ("顺便：A；B"), or null. */
    String side;

    private StepCopy() {}

    /**
     * @param observed the observed state (for the next Link Episode number), may be null
     * @param plan     the plan {@code step} belongs to (same reason), may be null
     */
    static StepCopy of(Plan.Step step, Rules rules, SimState observed, Plan plan) {
        StepCopy c = new StepCopy();
        String kind = kindOf(step);
        c.location = blankToNull(step.locationZh);
        c.hint = glossary(blankToNull(step.hintZh));
        c.side = glossary(sideLine(step.sideZh));
        switch (kind) {
            case "note":
                noteCopy(c, step, rules);
                break;
            case "episode":
                episodeCopy(c, step, rules, observed, plan);
                break;
            case "free":
                c.title = FREE_TITLE;
                break;
            case "locked":
                c.title = titleOf(step.titleZh, kind);
                break;
            case "unlock":
                c.title = titleOf(step.titleZh, kind);
                unlockCopy(c, step.subtitleZh);
                break;
            case "tower":
                towerCopy(c, step);
                break;
            default:
                c.title = titleOf(step.titleZh, kind);
                c.tag = tag(kind, step.subtitleZh, step.predicted);
                break;
        }
        c.shortTitle = shortTitle(c.title);
        return c;
    }

    /**
     * The part of a title that must not be cut, for a card too small for all of it: the person
     * (「倒悬者 · 舞子」 → 「舞子」, 「羁绊剧情 · 伊织顺平（第 2 话）」 → 「伊织顺平（第 2 话）」,
     * 「结缘网站的字条 · 岳羽由加莉」 → 「岳羽由加莉」). The kind mark beside the title still tells a note
     * or an episode apart, and the glossary never shortens the note's name to 「字条」. Null for a
     * title without parts.
     */
    static String shortTitle(String title) {
        String t = blankToNull(title);
        if (t == null) return null;
        t = t.trim();
        int at = t.lastIndexOf(" · ");
        if (at < 0) return null;
        return blankToNull(t.substring(at + 3).trim());
    }

    /** Coarse kind with the new action types recognised by their keys ("note:…", "episode:…"). */
    static String kindOf(Plan.Step step) {
        String action = step.action == null ? "" : step.action;
        if (action.startsWith("note:") || "note".equals(step.kind)) return "note";
        if (action.startsWith("episode:") || "episode".equals(step.kind)) return "episode";
        if (action.startsWith("locked:") || "locked".equals(step.kind)) return "locked";
        if ("free".equals(action) || "free".equals(step.kind)) return "free";
        return step.kind == null ? "" : step.kind;
    }

    // ------------------------------------------------------------------ kinds

    private static void noteCopy(StepCopy c, Plan.Step step, Rules rules) {
        c.icon = CardModel.TitleIcon.NOTE;
        String person = null;
        Rules.Link link = rules == null ? null : rules.link(idAfter(step.action, "note:"));
        if (link != null) person = blankToNull(link.personZh);
        String engineTitle = blankToNull(step.titleZh);
        if (person == null && engineTitle != null) {
            if (engineTitle.contains(NOTE_TITLE)) {
                c.title = engineTitle.trim();
            } else {
                String[] parts = engineTitle.trim().split(SEP);
                person = blankToNull(parts[parts.length - 1]);
            }
        }
        if (c.title.isEmpty()) c.title = person == null ? NOTE_TITLE : NOTE_TITLE + " · " + person;
        int rank = step.predicted == null ? -1 : step.predicted.rankFrom;
        if (rank >= 1 && rank <= 9) {
            c.tag = "RANK " + rank + " · 加好感";
        } else {
            String sub = compact(step.subtitleZh);
            c.tag = sub == null || c.title.contains(sub) ? "加好感" : sub;
        }
        if (c.location == null && rules != null && rules.datingNote != null) c.location = blankToNull(rules.datingNote.locationZh);
        boolean hintSaysHow = c.hint != null && c.hint.contains("电脑");
        if (!hintSaysHow) c.instruction = NOTE_HOW;
    }

    private static void episodeCopy(StepCopy c, Plan.Step step, Rules rules, SimState observed, Plan plan) {
        c.icon = CardModel.TitleIcon.EPISODE;
        String trackId = idAfter(step.action, "episode:");
        Rules.Episode track = null;
        if (rules != null) {
            for (Rules.Episode e : rules.episodes) {
                if (e != null && e.id != null && e.id.equals(trackId)) track = e;
            }
        }
        String engineText = (step.titleZh == null ? "" : step.titleZh) + " " + (step.subtitleZh == null ? "" : step.subtitleZh);
        int number = -1;
        Matcher m = EPISODE_NO.matcher(engineText);
        if (m.find()) number = Integer.parseInt(m.group(1));
        else if (track != null) number = nextEpisode(track, step, observed, plan);
        String person = track == null ? null : blankToNull(track.personZh);
        if (person == null) person = personFromTitle(step.titleZh);
        c.title = episodeTitle(person, number);
        String sub = compact(step.subtitleZh);
        boolean redundant = sub == null || EPISODE_NO.matcher(sub).find() || c.title.contains(sub)
                || sub.contains(EPISODE_TITLE) || sub.contains("羁绊剧场");
        c.tag = redundant ? null : sub;
        if (c.location == null && track != null) c.location = blankToNull(track.locationZh);
    }

    /**
     * 「羁绊剧情 · 伊织顺平（第 2 话）」, the glossary's form that 记录历史 and 改做别的 also use; the
     * person and the number are each left out when unknown ({@code number < 1}).
     */
    static String episodeTitle(String person, int number) {
        StringBuilder title = new StringBuilder(EPISODE_TITLE);
        if (person != null) title.append(" · ").append(person.trim());
        if (number > 0) title.append("（第 ").append(number).append(" 话）");
        return title.toString();
    }

    /** Episodes watched so far plus the same track's earlier steps in the plan, plus one. */
    private static int nextEpisode(Rules.Episode track, Plan.Step step, SimState observed, Plan plan) {
        int watched = observed != null && track.idx >= 0 && track.idx < observed.episodes.length ? observed.episodes[track.idx] : 0;
        int from = observed != null ? observed.slot : (plan != null ? plan.fromSlot : step.slot);
        int earlier = 0;
        if (plan != null && plan.steps != null) {
            for (Plan.Step s : plan.steps) {
                if (s != null && s.slot >= from && s.slot < step.slot && step.action.equals(s.action)) earlier++;
            }
        }
        int n = watched + earlier + 1;
        return track.count > 0 && n > track.count ? -1 : n;
    }

    /** "羁绊剧情 · 伊织顺平（第 2 话）" / "羁绊剧场 · 伊织顺平" → "伊织顺平"; null when unclear. */
    static String personFromTitle(String title) {
        String t = blankToNull(title);
        if (t == null) return null;
        t = EPISODE_NO.matcher(t).replaceAll("").replaceAll("[（(）)]", " ");
        for (String part : t.split(SEP)) {
            String p = part.trim();
            if (p.isEmpty() || p.contains(EPISODE_TITLE) || p.contains("羁绊剧场")) continue;
            return p;
        }
        return null;
    }

    private static void unlockCopy(StepCopy c, String subtitle) {
        c.tag = "解锁社群";
        String s = compact(subtitle);
        if (s == null) return;
        String[] parts = s.split(SEP, 2);
        String what = parts[0].trim();
        // A bare "开启社群"/"解锁社群" says nothing the chip does not; a cost or an instruction does.
        boolean plain = what.isEmpty() || what.endsWith("社群") && what.length() <= 4;
        if (!plain) c.instruction = glossary(s);
        else if (parts.length > 1 && !parts[1].trim().isEmpty()) c.instruction = glossary(parts[1].trim());
    }

    /**
     * Title 「探索塔尔塔罗斯」, chip 「本区块第 1 次」 or 「救出舞子」, and the block on the location line
     * (the title already says Tartarus). Earlier engine wording put the block in the title and
     * 「探索塔尔塔罗斯 · 第 1 次」 in the subtitle; the current one puts both in the subtitle.
     */
    private static void towerCopy(StepCopy c, Plan.Step step) {
        c.title = TOWER_TITLE;
        List<String> where = new ArrayList<>();
        String title = blankToNull(step.titleZh);
        if (title != null && !title.contains("塔尔塔罗斯")) where.add(title.trim());
        List<String> parts = new ArrayList<>();
        String s = compact(step.subtitleZh);
        if (s != null) {
            for (String part : s.split(SEP)) {
                String p = part.trim();
                if (!p.isEmpty() && !p.equals(TOWER_TITLE)) parts.add(p);
            }
        }
        if (!parts.isEmpty()) {
            String last = parts.remove(parts.size() - 1);
            Matcher visit = VISIT.matcher(last);
            c.tag = visit.matches() ? "本区块第 " + visit.group(1) + " 次" : last;
        }
        for (String p : parts) {
            if (!where.contains(p)) where.add(p);
        }
        if (!where.isEmpty()) c.location = String.join(" · ", where);
    }

    // ------------------------------------------------------------------ chip text

    /**
     * Short chip text for a step kind: the RANK change of a meeting, the stat gain of a training
     * (cost dropped), the pay of a job. Null for locked and free steps.
     */
    static String tag(String kind, String subtitle, Plan.Predicted predicted) {
        String s = compact(subtitle);
        switch (kind == null ? "" : kind) {
            case "locked":
            case "free":
                return null;
            case "social":
                return socialTag(s, predicted);
            case "stat":
                return s == null ? null : statTag(s);
            case "job":
                return s == null ? null : jobTag(s);
            default:
                return s;
        }
    }

    /** "RANK 6→7", "RANK 9→MAX", "RANK 4 · 见面"; a link already at MAX reads "MAX · 见面". */
    private static String socialTag(String s, Plan.Predicted p) {
        if (p != null && p.rankFrom >= 1 && p.rankTo >= p.rankFrom) {
            if (p.rankTo > p.rankFrom) return "RANK " + rank(p.rankFrom) + "→" + rank(p.rankTo);
            return p.rankFrom >= 10 ? MAX_MEETING : "RANK " + p.rankFrom + " · 见面";
        }
        if (s == null) return null;
        Matcher m = RANK_STEP.matcher(s);
        if (m.find()) return "RANK " + m.group(1) + "→" + m.group(2);
        if (RANK_MAX.matcher(s).find()) return MAX_MEETING;
        String[] parts = s.split(SEP);
        String last = parts[parts.length - 1].trim();
        if (last.startsWith("加深关系") || last.startsWith("见面")) return "见面";
        return last.isEmpty() ? null : last;
    }

    /** "魅力 +4（升到 Lv.2） · 1,500 日元" → "魅力 +4 → Lv.2"; Lv.6 reads MAX. */
    private static String statTag(String s) {
        String gains = s.split(SEP)[0].trim();
        Matcher m = LEVEL_UP.matcher(gains);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(gains, last, m.start());
            String digits = m.group(1).replaceAll("\\D", "");
            boolean max = digits.isEmpty() || "6".equals(digits); // "MAX", or Lv.6 which the game calls MAX
            out.append(" → ").append(max ? "MAX" : "Lv." + digits);
            last = m.end();
        }
        out.append(gains.substring(last));
        String t = out.toString().trim();
        return t.isEmpty() ? null : t;
    }

    /** "打工 · 预计 +5,000 日元" or "预计 +2,500 日元 · 魅力 +1" → "+5,000 日元". */
    private static String jobTag(String s) {
        for (String part : s.split(SEP)) {
            String p = part.trim();
            if (p.contains("日元")) return p.replaceFirst("^(预计|至少)\\s*", "").trim();
        }
        for (String part : s.split(SEP)) {
            String p = part.trim();
            if (!p.isEmpty() && !p.equals("打工")) return p;
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    /** "顺便：A", "顺便：B" → "顺便：A；B". */
    static String sideLine(List<String> sideZh) {
        if (sideZh == null) return null;
        StringBuilder sb = new StringBuilder();
        for (String raw : sideZh) {
            String line = blankToNull(raw);
            if (line == null) continue;
            line = line.trim();
            if (sb.length() == 0) {
                sb.append(line.startsWith("顺便") ? line : "顺便：" + line);
            } else {
                sb.append('；').append(line.replaceFirst("^顺便[：:]\\s*", ""));
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * Engine sentences in the words of docs/GLOSSARY_zh.md, for plans saved before the engine used
     * them: the sixth stat level is MAX, a link reaches MAX (not 练满, not RANK MAX: 「升到 MAX」
     * 「RANK 9 → MAX」) and is 解锁 (not 开启 / 正式开始).
     */
    static String glossary(String text) {
        if (text == null) return null;
        String t = LV6.matcher(text).replaceAll("MAX");
        t = RANK_MAX.matcher(t).replaceAll("MAX");
        return t.replace("练满", "升到 MAX").replace("就正式开始了", "就解锁了").replace("开启", "解锁");
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
            case "note": return NOTE_TITLE;
            case "episode": return EPISODE_TITLE;
            default: return FREE_TITLE;
        }
    }

    /** "RANK 6 → 7" → "RANK 6→7"; blank → null. */
    static String compact(String subtitle) {
        String s = blankToNull(subtitle);
        return s == null ? null : s.trim().replaceAll("\\s*→\\s*", "→");
    }

    private static String rank(int r) {
        return r >= 10 ? "MAX" : String.valueOf(r);
    }

    private static String idAfter(String action, String prefix) {
        return action != null && action.startsWith(prefix) ? action.substring(prefix.length()) : "";
    }

    static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s;
    }
}
