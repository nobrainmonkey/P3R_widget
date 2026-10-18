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
    public enum Content { ALL, DIALOGUE, CLASS_EXAM, REQUEST, COMBAT }
    public enum CombatGroup { GUARDIAN, MONAD, STORY, OPTIONAL }

    private TipsCatalogue() {}

    /** Current reminders plus catalogue requests whose acceptance windows overlap the opening period. */
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
            for (int scene = 2; scene <= 10; scene++) {
                DialogueHint.Answers answers = DialogueHint.lookupScene(rules, profile, index, scene);
                if (answers.isEmpty()) continue;
                Plan.Reminder item = new Plan.Reminder();
                item.id = "dialogue." + link.id + "." + scene;
                item.kind = DIALOGUE;
                item.labelZh = "社群对话";
                String name = name(link);
                String route = link.romanceable
                        ? DialogueHint.romanceRoute(profile, index) ? "恋人路线" : "朋友路线" : "";
                String compact = DialogueHint.widgetLine(answers);
                item.textZh = name + " · 升到 RANK " + scene
                        + (route.isEmpty() ? "" : "（" + route + "）")
                        + "\n" + (compact == null ? DialogueHint.appLine(answers) : compact);
                List<String> detail = new ArrayList<>();
                if (!answers.sequence.isEmpty()) {
                    detail.add("完整问答顺序：\n" + String.join("\n", answers.sequence));
                } else detail.add(DialogueHint.appLine(answers));
                if (!answers.avoid.isEmpty()) detail.add("别选：" + String.join(" / ", answers.avoid));
                if (answers.noteZh != null && !answers.noteZh.trim().isEmpty()) detail.add(answers.noteZh.trim());
                if (link.noteZh != null && !link.noteZh.trim().isEmpty()) detail.add("注意：" + link.noteZh.trim());
                item.detailZh = String.join("\n\n", detail);
                // An undated scene must not look like a forecasted calendar appointment.
                item.slot = -1;
                dialogues.add(item);
            }
        }
        dialogues.addAll(catalogue.items);
        catalogue.items = dialogues;
        return catalogue;
    }

    private static void addRequests(PeriodTips.Period period, Rules rules, boolean all) {
        if (rules == null || rules.reminders == null || rules.reminders.requestTips.isEmpty()) return;
        if (all) {
            // Dated batch notices and repeated faculty-office reminders stay on the calendar. The
            // lookup instead has one independently named entry per request, not 26 partial notices.
            period.items.removeIf(item -> Plan.Reminder.REQUEST.equals(item.kind));
        }
        for (Rules.RequestTip request : rules.reminders.requestTips) {
            int until = request.untilDay < 0 ? rules.lastDay : request.untilDay;
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
        normalized = normalized.replaceAll("(?:rank|r)\\s*(10|[2-9])", " rank$1 ")
                .replaceAll("(10|[2-9])\\s*级", " rank$1 ").trim();
        String[] words = normalized.isEmpty() ? new String[0] : normalized.split("\\s+");
        for (Plan.Reminder item : items) {
            if (!selected.isEmpty() && !selected.equals(categoryLabel(rules, item))) continue;
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
                boolean explicitRank = word.matches("rank(?:10|[2-9])");
                boolean bareRank = words.length > 1 && word.matches("10|[2-9]");
                if (DIALOGUE.equals(item.kind) && (explicitRank || bareRank)
                        && !item.id.endsWith("." + (explicitRank ? word.substring(4) : word))) {
                    matches = false;
                    break;
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
                add(text, link.arcanaZh + scene);
                add(text, link.personZh + scene);
                add(text, "rank" + scene);
            }
        } else if (rules != null && item.slot >= 0) {
            Rules.Day day = rules.day(Rules.dayOf(item.slot));
            if (day != null) add(text, day.md + " " + day.month() + "/" + day.dayOfMonth()
                    + " " + day.month() + "月" + day.dayOfMonth() + "日");
        }
        return normalize(text.toString()).replaceAll("rank\\s+(10|[2-9])", "rank$1");
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
