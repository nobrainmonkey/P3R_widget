package app.p3r.dayplanner.v3.service;

import app.p3r.dayplanner.v3.engine.DialogueHint;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** On-demand, read-only guide lookup. No plan, current rank or recorded date limits its answers. */
public final class TipsCatalogue {
    public static final String DIALOGUE = "dialogue";
    public static final String COMBAT = "combat";
    private static final String REQUEST_PREFIX = "request.catalogue.";
    private static final String COMBAT_PREFIX = "combat.catalogue.";
    private static final Pattern REQUEST_NUMBER = Pattern.compile(
            "(?:委托|请求|任务|伊莉莎白|elizabeth|request\\s*#?|no\\.?|#)\\s*0*(\\d{1,3})(?!\\d)(?:\\s*号)?"
                    + "|(?<!\\d)(?:第\\s*)?0*(\\d{1,3})\\s*号");
    private static final Pattern FLOOR_NUMBER = Pattern.compile("(?<!\\d)(?:第\\s*)?0*(\\d{1,3})\\s*(?:f|层)(?!\\d)");
    private static final Pattern TARGET_RANK = Pattern.compile("(?:升到|升至|到达|目标)\\s*(?:rank|r)?\\s*(10|[1-9])(?!\\d)(?:\\s*级)?");
    public enum Content { ALL, DIALOGUE, CLASS_EXAM, REQUEST, COMBAT }
    public enum CombatGroup { GUARDIAN, MONAD, STORY, OPTIONAL }

    private TipsCatalogue() {}

    /** Current openings and dated windows that overlap this Tartarus-block period. */
    public static PeriodTips.Period current(Rules rules, SimState observed, Plan plan) {
        PeriodTips.Period period = PeriodTips.collect(rules, observed, plan);
        addRequests(period, rules, false);
        addCombat(period, rules, false);
        return period;
    }

    /** Rebuild for the supplied rules/profile so a changed friendship/romance route cannot go stale. */
    public static PeriodTips.Period all(Rules rules, Profile profile, SimState observed) {
        PeriodTips.Period catalogue = PeriodTips.collectAll(rules, observed);
        if (rules == null) return catalogue;
        addRequests(catalogue, rules, true);
        addCombat(catalogue, rules, true);
        List<Plan.Reminder> dialogues = new ArrayList<>();
        for (int index = 0; index < rules.links.length; index++) {
            Rules.Link link = rules.links[index];
            if (link == null || link.kind != Rules.LinkKind.MANUAL) continue;
            if (link.unlock != null) {
                Plan.Reminder opening = PeriodTips.unlockTip(rules, observed, link);
                catalogue.items.removeIf(item -> opening.id.equals(item.id));
                catalogue.ongoingIds.remove(opening.id);
                opening.id = "dialogue." + link.id + ".1";
                opening.kind = DIALOGUE;
                opening.labelZh = "社群对话";
                opening.textZh = name(link) + " · 解锁";
                opening.slot = -1;
                dialogues.add(opening);
            }
            for (int scene = 2; scene <= 10; scene++) {
                Plan.Reminder item = scene(rules, profile, index, scene, null);
                if (item != null) dialogues.add(item);
            }
        }
        dialogues.addAll(catalogue.items);
        catalogue.items = dialogues;
        return catalogue;
    }

    /** 「RANK 3 → 4」, 「RANK 9 → MAX」 for the scene that ranks a link up into {@code scene}. */
    public static String rankStep(int scene) {
        return "RANK " + (scene - 1) + " → " + (scene >= 10 ? "MAX" : String.valueOf(scene));
    }

    /**
     * One rank-up scene as 提示 shows it: 「女教皇 · 山岸风花 · RANK 1 → 2」 with the app's
     * 「回答：…」 line, and the full answers as numbered questions (「第 1 问：没问题」) in the
     * details. {@code romance} previews a route without touching the profile (null: the profile's
     * route). Null when the guide has nothing on the scene.
     */
    public static Plan.Reminder scene(Rules rules, Profile profile, int index, int scene, Boolean romance) {
        if (rules == null || index < 0 || index >= rules.links.length || rules.links[index] == null) return null;
        Rules.Link link = rules.links[index];
        Profile shown = profile;
        if (romance != null) {
            shown = new Profile();
            if (index < shown.route.length) {
                shown.route[index] = romance ? Profile.Route.ROMANCE : Profile.Route.FRIEND;
            }
        }
        DialogueHint.Answers answers = DialogueHint.lookupScene(rules, shown, index, scene);
        if (answers.isEmpty()) return null;
        Plan.Reminder item = new Plan.Reminder();
        item.id = "dialogue." + link.id + "." + scene;
        item.kind = DIALOGUE;
        item.labelZh = "社群对话";
        String line = answerLine(answers);
        item.textZh = name(link) + " · " + rankStep(scene) + (line == null ? "" : "\n" + line);
        List<String> detail = new ArrayList<>();
        String questions = questions(answers.sequence);
        if (!questions.isEmpty()) detail.add(questions);
        else if (line != null) detail.add(line);
        if (!answers.avoid.isEmpty()) {
            List<String> avoid = new ArrayList<>();
            for (String answer : answers.avoid) {
                String bare = DialogueHint.withoutCounts(answer);
                if (!bare.isEmpty()) avoid.add("「" + bare + "」");
            }
            if (!avoid.isEmpty()) detail.add("别选" + String.join("", avoid));
        }
        String note = playerNote(answers.noteZh);
        if (!note.isEmpty()) detail.add(note);
        if (link.noteZh != null && !link.noteZh.trim().isEmpty()) detail.add(link.noteZh.trim());
        item.detailZh = String.join("\n\n", detail);
        // An undated scene must not look like a forecasted calendar appointment.
        item.slot = -1;
        return item;
    }

    /**
     * The scene's answers in one line: 「回答：没问题（首问） / 没问题（末问）」, 「回答：怎么回答都可以」,
     * or the guide's short note (「观看剧情即可」); null when there is nothing to say.
     */
    public static String answerLine(DialogueHint.Answers a) {
        if (a == null || a.isEmpty()) return null;
        List<String> choices = DialogueHint.widgetChoices(a);
        if (!choices.isEmpty()) return DialogueHint.PREFIX + String.join(" / ", choices);
        if (a.any) return DialogueHint.ANY;
        if (a.shortNoteZh != null && !a.shortNoteZh.trim().isEmpty()) return a.shortNoteZh.trim();
        if (a.friendCaution) return "只想做朋友？别选表白的回答";
        return null;
    }

    /** Lines of a dialogue detail that are the numbered questions: 「第 2 问：随便选」. */
    public static final Pattern QUESTION = Pattern.compile("^第 (\\d+) 问：(.*)$");

    /**
     * The guide's ordered answers as numbered questions; a 【…】 heading of another route or an
     * earlier choice becomes its own line ending in 「：」 and restarts the count, and an authored
     * sentence (ending in 「。」) stays a plain line.
     */
    static String questions(List<String> sequence) {
        List<String> lines = new ArrayList<>();
        int number = 0;
        for (String raw : sequence) {
            String entry = raw == null ? "" : raw.trim();
            if (entry.isEmpty()) continue;
            if (entry.startsWith("【") && entry.endsWith("】")) {
                lines.add(heading(entry.substring(1, entry.length() - 1)) + "：");
                number = 0;
            } else if (entry.endsWith("。")) {
                lines.add(playerNote(entry));
            } else {
                String answer = entry.startsWith("任意") ? "随便选" + entry.substring(2) : entry;
                lines.add("第 " + (++number) + " 问：" + answer);
            }
        }
        return String.join("\n", lines);
    }

    private static String heading(String text) {
        switch (text) {
            case "按友情目标的推荐流程": return "只做朋友";
            case "按恋爱目标的推荐流程": return "走恋爱线";
            default: return text;
        }
    }

    /** Guide-author shorthand in the notes, as 提示 says it (the widget keeps its own short notes). */
    private static final String[][] NOTE_WORDS = {
        {"两句都是最高收益之一", "两句都加好感"},
        {"此答也是最高收益之一", "这句也加好感"},
        {"好感收益相同", "加的好感一样"},
        {"收益相同", "加的好感一样"},
        {"同收益", "加的好感一样"},
        {"收益较低", "加的好感少"},
        {"主序列适用于", "上面的回答适用于"},
    };
    private static final Pattern NO_NEXT_RANK = Pattern.compile(
            "[；，]?(?:已到(?:终级|MAX)，)?(?:MAX后)?(?:没有|无)(?:后续|下一级)(?:升级|好感)?(?:升级)?门槛[。；]?");

    /** An authored note without the guide's modelling words; "" when nothing is left. */
    static String playerNote(String note) {
        if (note == null) return "";
        String out = note.trim();
        for (String[] words : NOTE_WORDS) out = out.replace(words[0], words[1]);
        out = NO_NEXT_RANK.matcher(out).replaceAll("");
        out = out.trim();
        if (out.startsWith("；") || out.startsWith("，")) out = out.substring(1).trim();
        if (!out.isEmpty() && !out.endsWith("。") && !out.endsWith("！") && !out.endsWith("？")) out += "。";
        return out.equals("。") ? "" : out;
    }

    /**
     * Element names as the official Simplified Chinese game writes them (火焰、神圣、暗黑), for combat
     * text the rule pack still words with the older 火炎 / 光 / 暗.
     */
    public static String officialTerms(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text.replace("火炎", "火焰").replace("光属性", "神圣属性").replace("暗属性", "暗黑属性")
                .replace("光和暗", "神圣和暗黑");
        out = ELEMENT_LIGHT.matcher(out).replaceAll("神圣");
        return ELEMENT_DARK.matcher(out).replaceAll("暗黑");
    }

    private static final Pattern ELEMENT_LIGHT = Pattern.compile("(?<=[：、弱])光(?=[、；。，\\n]|$)");
    private static final Pattern ELEMENT_DARK = Pattern.compile("(?<=[：、弱])暗(?=[、；。，\\n]|$)");

    /** Dated batch notices (「伊莉莎白有新的委托」, 「第 12、13 号委托 6月6日截止」): the named entries say it all. */
    private static final Pattern BATCH_NOTICE = Pattern.compile("request\\.\\d\\d-\\d\\d(?:\\.deadline|\\.outings)?");

    private static void addRequests(PeriodTips.Period period, Rules rules, boolean all) {
        if (rules == null || rules.reminders == null || rules.reminders.requestTips.isEmpty()) return;
        if (all) {
            // Dated batch notices and repeated faculty-office reminders stay on the calendar. The
            // lookup instead has one independently named entry per request, not 26 partial notices.
            period.items.removeIf(item -> Plan.Reminder.REQUEST.equals(item.kind));
        }
        int before = period.items.size();
        for (Rules.RequestTip request : rules.reminders.requestTips) {
            int until = request.untilDay < 0 ? request.fromDay : request.untilDay;
            if (!all && (request.fromDay > period.toDay || until < period.fromDay)) continue;
            Plan.Reminder item = new Plan.Reminder();
            item.id = REQUEST_PREFIX + request.number;
            item.kind = Plan.Reminder.REQUEST;
            item.labelZh = "委托";
            item.textZh = "第 " + request.number + " 号 · " + request.titleZh;
            item.detailZh = request.detailZh;
            item.slot = Rules.slot(request.fromDay, false);
            item.untilDay = request.untilDay;
            if (request.untilDay >= 0) item.priority = Plan.Reminder.MISSABLE;
            else period.ongoingIds.add(item.id);
            period.items.add(item);
        }
        if (!all && period.items.size() > before) {
            // 本期 lists every named request of the period; the batch notices would repeat them.
            period.items.removeIf(item -> Plan.Reminder.REQUEST.equals(item.kind) && item.id != null
                    && BATCH_NOTICE.matcher(item.id).matches());
        }
    }

    public static boolean isRequestEntry(Plan.Reminder item) {
        return item != null && item.id != null && item.id.startsWith(REQUEST_PREFIX)
                && item.id.substring(REQUEST_PREFIX.length()).matches("[1-9][0-9]{0,2}");
    }

    private static void addCombat(PeriodTips.Period period, Rules rules, boolean all) {
        if (rules == null || rules.reminders == null) return;
        for (Rules.CombatTip combat : rules.reminders.combatTips) {
            if (!all && (combat.fromDay < period.fromDay || combat.fromDay > period.toDay)) continue;
            Plan.Reminder item = new Plan.Reminder();
            item.id = COMBAT_PREFIX + combat.id;
            item.kind = COMBAT;
            item.labelZh = categoryLabel(combat);
            String title = combat.titleZh;
            if (combat.floor > 0 && !title.contains(combat.floor + "F") && !title.contains(combat.floor + "层")) {
                title = combat.floor + "F · " + title;
            }
            item.textZh = title + (combat.shortZh == null || combat.shortZh.isEmpty() ? "" : "\n" + combat.shortZh);
            item.detailZh = combat.detailZh;
            item.slot = Rules.slot(combat.fromDay, "full_moon".equals(combat.kind));
            period.items.add(item);
            if ("guardian".equals(combat.kind) || "monad".equals(combat.kind) || "optional".equals(combat.kind)) {
                period.ongoingIds.add(item.id);
            }
        }
    }

    /** The same visible tag is used on cards, detail sheets and the category filter. */
    public static String categoryLabel(Rules.CombatTip combat) {
        if (combat == null) return "提醒";
        switch (combat.kind) {
            case "guardian": return "守关首领";
            case "full_moon": return "满月作战";
            case "monad": return "passage".equals(combat.encounterType) ? "蒙纳多通道"
                    : "door".equals(combat.encounterType) ? "蒙纳多之门" : "蒙纳多";
            case "final": return "最终战";
            case "story": return "剧情作战";
            case "optional": return "特殊挑战";
            default: return "剧情作战";
        }
    }

    public static String categoryLabel(Rules rules, Plan.Reminder item) {
        Rules.CombatTip combat = combatTip(rules, item);
        if (combat != null) return categoryLabel(combat);
        return item == null || item.labelZh == null || item.labelZh.trim().isEmpty()
                ? "提醒" : item.labelZh.trim();
    }

    /** First occurrence order, rebuilt from this scope rather than a static list of known tags. */
    public static List<String> categories(List<Plan.Reminder> items, Rules rules) {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        for (Plan.Reminder item : items) labels.add(categoryLabel(rules, item));
        return new ArrayList<>(labels);
    }

    /** A shared label can cover different reminder kinds; those categories keep general search. */
    public static Content contentForCategory(List<Plan.Reminder> items, Rules rules, String label) {
        if (label == null || label.trim().isEmpty()) return Content.ALL;
        String selected = label.trim();
        Content content = null;
        for (Plan.Reminder item : items) {
            if (!selected.equals(categoryLabel(rules, item))) continue;
            Content candidate = contentOf(item);
            if (content != null && content != candidate) return Content.ALL;
            content = candidate;
        }
        return content == null ? Content.ALL : content;
    }

    private static Content contentOf(Plan.Reminder item) {
        if (item == null) return Content.ALL;
        if (DIALOGUE.equals(item.kind)) return Content.DIALOGUE;
        if (COMBAT.equals(item.kind)) return Content.COMBAT;
        if (Plan.Reminder.REQUEST.equals(item.kind)) return Content.REQUEST;
        if (item.isAnswer()) return Content.CLASS_EXAM;
        return Content.ALL;
    }

    public static Rules.CombatTip combatTip(Rules rules, Plan.Reminder item) {
        if (rules == null || rules.reminders == null || item == null || item.id == null
                || !item.id.startsWith(COMBAT_PREFIX)) return null;
        String id = item.id.substring(COMBAT_PREFIX.length());
        for (Rules.CombatTip combat : rules.reminders.combatTips) if (id.equals(combat.id)) return combat;
        return null;
    }

    /** Rank-scene identifiers stay in the rules' target-rank convention (1 means opening). */
    public static int dialogueRank(Plan.Reminder item) {
        if (item == null || item.id == null) return 0;
        if (item.id.startsWith("unlock.")) return 1;
        if (!DIALOGUE.equals(item.kind) || !item.id.startsWith("dialogue.")) return 0;
        int split = item.id.lastIndexOf('.');
        if (split <= "dialogue.".length()) return 0;
        try {
            int rank = Integer.parseInt(item.id.substring(split + 1));
            return rank >= 1 && rank <= 10 ? rank : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** Shared identity for social-link opening instructions and rank scenes. */
    public static Rules.Link dialogueLink(Rules rules, Plan.Reminder item) {
        if (rules == null || item == null || item.id == null || dialogueRank(item) == 0) return null;
        String id = item.id.startsWith("unlock.") ? item.id.substring("unlock.".length())
                : item.id.substring("dialogue.".length(), item.id.lastIndexOf('.'));
        Rules.Link link = rules.link(id);
        return link != null && link.kind == Rules.LinkKind.MANUAL ? link : null;
    }

    /** Monad doors have no fixed floor; their source block supplies the real floor range. */
    public static Rules.Block monadBlock(Rules rules, Plan.Reminder item) {
        Rules.CombatTip combat = combatTip(rules, item);
        if (combat == null || !"monad".equals(combat.kind)) return null;
        for (Rules.Block block : rules.blocks) {
            if (block.id != null && combat.id.startsWith("combat." + block.id + ".")) return block;
        }
        Rules.Block current = null;
        for (Rules.Block block : rules.blocks) {
            if (block.fromDay <= combat.fromDay && (current == null || block.fromDay > current.fromDay)) current = block;
        }
        return current;
    }

    public static CombatGroup combatGroup(Rules.CombatTip combat) {
        if (combat == null) return CombatGroup.OPTIONAL;
        if ("guardian".equals(combat.kind)) return CombatGroup.GUARDIAN;
        if ("monad".equals(combat.kind)) return CombatGroup.MONAD;
        if ("optional".equals(combat.kind)) return CombatGroup.OPTIONAL;
        return CombatGroup.STORY;
    }

    /** Match all query words against titles, full answers/conditions and the event's date aliases. */
    public static List<Plan.Reminder> filter(List<Plan.Reminder> items, Rules rules, String query, Content content) {
        return filter(items, rules, query, content, null);
    }

    /** Search within an exact visible tag; its content kind determines bare-number interpretation. */
    public static List<Plan.Reminder> filter(List<Plan.Reminder> items, Rules rules, String query,
                                           Content content, String category) {
        String selected = category == null ? "" : category.trim();
        if (content == Content.ALL && !selected.isEmpty()) content = contentForCategory(items, rules, selected);
        List<Plan.Reminder> out = new ArrayList<>();
        String normalized = normalize(query);
        int targetRank = -1;
        Matcher targetMatch = TARGET_RANK.matcher(normalized);
        if (targetMatch.find()) {
            targetRank = Integer.parseInt(targetMatch.group(1));
            normalized = normalized.substring(0, targetMatch.start()) + " " + normalized.substring(targetMatch.end());
            normalized = normalized.trim();
        }
        int requestNumber = -1;
        int floorNumber = -1;
        if (normalized.matches("\\d{1,3}") && (content == Content.ALL
                || content == Content.REQUEST || content == Content.COMBAT)) {
            if (content == Content.COMBAT) floorNumber = Integer.parseInt(normalized);
            else requestNumber = Integer.parseInt(normalized);
            normalized = "";
        } else {
            if (content != Content.REQUEST) {
                Matcher floor = FLOOR_NUMBER.matcher(normalized);
                if (floor.find()) {
                    floorNumber = Integer.parseInt(floor.group(1));
                    normalized = normalized.substring(0, floor.start()) + " " + normalized.substring(floor.end());
                }
            }
            if (content != Content.COMBAT && floorNumber < 0) {
                Matcher number = REQUEST_NUMBER.matcher(normalized);
                if (number.find()) {
                    requestNumber = Integer.parseInt(number.group(1) == null ? number.group(2) : number.group(1));
                    normalized = normalized.substring(0, number.start()) + " " + normalized.substring(number.end());
                }
            }
        }
        normalized = normalized.replaceAll("(?:rank|r)\\s*(10|[0-9])", " rank$1 ")
                .replaceAll("(10|[0-9])\\s*级", " rank$1 ").trim();
        String[] words = normalized.isEmpty() ? new String[0] : normalized.split("\\s+");
        for (Plan.Reminder item : items) {
            if (!selected.isEmpty() && !selected.equals(categoryLabel(rules, item))) continue;
            if (targetRank >= 0 && (!DIALOGUE.equals(item.kind) || dialogueRank(item) != targetRank)) continue;
            if (content == Content.DIALOGUE && !DIALOGUE.equals(item.kind)) continue;
            if (content == Content.CLASS_EXAM && !item.isAnswer()) continue;
            if (content == Content.REQUEST && !Plan.Reminder.REQUEST.equals(item.kind)) continue;
            if (content == Content.COMBAT && !COMBAT.equals(item.kind)) continue;
            if (requestNumber >= 0 && !item.id.equals(REQUEST_PREFIX + requestNumber)) continue;
            if (floorNumber >= 0) {
                Rules.CombatTip combat = combatTip(rules, item);
                if (combat == null || combat.floor <= 0 || combat.floor != floorNumber) continue;
            }
            String searchable = searchable(item, rules);
            boolean matches = true;
            for (String word : words) {
                boolean explicitRank = word.matches("rank(?:10|[0-9])");
                boolean bareRank = words.length > 1 && word.matches("10|[0-9]");
                if (DIALOGUE.equals(item.kind) && (explicitRank || bareRank)) {
                    int requested = Integer.parseInt(explicitRank ? word.substring(4) : word);
                    int targetScene = requested == 10 ? 10 : requested + 1;
                    if (dialogueRank(item) != targetScene) { matches = false; break; }
                    continue;
                }
                if (!searchable.contains(word)) { matches = false; break; }
            }
            if (matches) out.add(item);
        }
        if (content == Content.COMBAT) out.sort((first, second) -> {
            Rules.CombatTip a = combatTip(rules, first);
            Rules.CombatTip b = combatTip(rules, second);
            int group = combatGroup(a).compareTo(combatGroup(b));
            if (group != 0 || a == null || b == null) return group;
            if (combatGroup(a) != CombatGroup.STORY) {
                int floor = Integer.compare(a.floor, b.floor);
                if (floor != 0) return floor;
                int order = Integer.compare(a.battleOrder, b.battleOrder);
                if (order != 0) return order;
            }
            // Stable ordering retains the source's order for multiple fights on one story date.
            return Integer.compare(a.fromDay, b.fromDay);
        });
        return out;
    }

    private static String searchable(Plan.Reminder item, Rules rules) {
        StringBuilder text = new StringBuilder();
        add(text, item.labelZh);
        add(text, item.textZh);
        add(text, item.shortZh);
        add(text, item.answerZh);
        add(text, item.detailZh);
        add(text, item.gainZh);
        Rules.CombatTip combat = combatTip(rules, item);
        if (combat != null) {
            add(text, combat.tacticsZh);
            // The cards say 火焰 / 神圣 / 暗黑 (officialTerms); either spelling finds the fight.
            add(text, officialTerms(combat.tacticsZh));
            add(text, officialTerms(item.textZh));
            for (Rules.CombatEnemy enemy : combat.enemies) add(text, enemy.nameZh);
            if ("monad".equals(combat.kind)) add(text, "passage".equals(combat.encounterType) ? "蒙纳多通道" : "蒙纳多之门");
            if (combatGroup(combat) == CombatGroup.STORY) add(text, "剧情作战");
            for (String alias : combat.aliases) add(text, alias);
            if (combat.floor > 0) add(text, combat.floor + "f " + combat.floor + "层 第" + combat.floor + "层");
            if ("full_moon".equals(combat.kind)) add(text, "满月 满月作战");
            Rules.Day day = rules.day(combat.fromDay);
            if (day != null) {
                add(text, day.month() + "月boss " + day.month() + "月战斗");
                if ("full_moon".equals(combat.kind)) add(text, day.month() + "月满月 " + day.month() + "月满月作战");
            }
        }
        if (Plan.Reminder.REQUEST.equals(item.kind)) {
            add(text, "伊莉莎白 伊丽莎白 Elizabeth 委托 请求");
            if (rules != null && rules.reminders != null && isRequestEntry(item)) {
                int number = Integer.parseInt(item.id.substring(REQUEST_PREFIX.length()));
                for (Rules.RequestTip request : rules.reminders.requestTips) {
                    if (request.number != number) continue;
                    for (String alias : request.aliases) add(text, alias);
                    break;
                }
            }
        }
        if (DIALOGUE.equals(item.kind)) {
            int split = item.id.lastIndexOf('.');
            String id = item.id.substring("dialogue.".length(), split);
            String scene = item.id.substring(split + 1);
            Rules.Link link = rules == null ? null : rules.link(id);
            if (link != null) {
                int currentRank = Integer.parseInt(scene) - 1;
                add(text, link.arcanaZh + currentRank);
                add(text, link.personZh + currentRank);
                add(text, "rank" + currentRank);
                if (currentRank == 0) add(text, "解锁 前置 开启");
                if (currentRank == 9) {
                    add(text, "rank10 max");
                    add(text, link.arcanaZh + "10");
                    add(text, link.personZh + "10");
                }
            }
        } else if (rules != null && item.slot >= 0) {
            Rules.Day day = rules.day(Rules.dayOf(item.slot));
            if (day != null) add(text, day.md + " " + day.month() + "/" + day.dayOfMonth()
                    + " " + day.month() + "月" + day.dayOfMonth() + "日");
        }
        return normalize(text.toString()).replaceAll("rank\\s+(10|[0-9])", "rank$1");
    }

    private static String name(Rules.Link link) {
        if (link.labelZh != null && !link.labelZh.trim().isEmpty()) return link.labelZh;
        return (link.arcanaZh == null ? "社群" : link.arcanaZh)
                + (link.personZh == null || link.personZh.isEmpty() ? "" : " · " + link.personZh);
    }

    private static void add(StringBuilder text, String value) {
        if (value != null && !value.isEmpty()) text.append(' ').append(value);
    }

    private static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replace("伊丽莎白", "伊莉莎白")
                .replace('\u2060', ' ').trim().replaceAll("\\s+", " ");
    }
}
