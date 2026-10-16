package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.*;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;

/** Completion is usable guidance for every scene, with branch history retained in details. */
public class CompletedDialogueHintTest {
    private static Rules rules;
    private static final String TRANSLATION_NOTE = "部分选项为译意，按含义对应。";

    @BeforeClass
    public static void loadShippedPack() throws Exception {
        for (String candidate : new String[]{"src/main/assets/p3r/p3r_rules.json", "app/src/main/assets/p3r/p3r_rules.json",
                "android/app/src/main/assets/p3r/p3r_rules.json"}) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                rules = RulesLoader.parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
                return;
            }
        }
        throw new AssertionError("shipped pack missing");
    }

    private static Profile profile(String id, Profile.Route route) {
        Profile p = new Profile();
        p.route[rules.link(id).idx] = route;
        return p;
    }

    private static DialogueHint.Answers answers(String id, int rank, Profile.Route route) {
        return DialogueHint.forScene(rules, profile(id, route), rules.link(id).idx, rank, false);
    }

    private static String detail(String id, int rank, Profile.Route route) {
        Profile p = profile(id, route);
        Model model = new Model(rules, p, null);
        SimState start = new SimState(rules.blocks.length, rules.activities.length);
        start.slot = rules.firstSlot();
        Labels labels = new Labels(model, new Heuristics(model, start, null));
        Kernel.Effect effect = new Kernel.Effect();
        effect.rankUp = true;
        effect.rankFrom = rank - 1;
        effect.rankTo = rank;
        return labels.meetHow(start.slot, rules.link(id).idx, effect);
    }

    private static void assertSequenceContains(DialogueHint.Answers a, String... sequence) {
        String actual = String.join(" / ", a.sequence);
        assertTrue(actual, actual.contains(String.join(" / ", sequence)));
    }

    @Test
    public void everyManualRankUpHasUsableGuidanceForBothRelationshipTargets() {
        int scenes = 0;
        int routes = 0;
        for (Rules.Link link : rules.links) {
            if (link.kind != Rules.LinkKind.MANUAL) continue;
            for (int rank = 2; rank <= 10; rank++) {
                String scene = link.id + " R" + rank;
                assertTrue(scene + " has a bound source sequence", link.dialogue.containsKey(rank));
                scenes++;
                for (Profile.Route route : new Profile.Route[]{Profile.Route.FRIEND, Profile.Route.ROMANCE}) {
                    DialogueHint.Answers a = answers(link.id, rank, route);
                    String label = scene + " " + route;
                    assertFalse(label, a.isEmpty());
                    assertEquals(label, rank, a.scene);
                    String app = DialogueHint.appLine(a);
                    String widget = DialogueHint.widgetLine(a);
                    assertNotNull(label, widget);
                    assertTrue(label + ": " + widget, widget.startsWith(DialogueHint.WIDGET_PREFIX));
                    assertTrue(label + " has concrete answers, a verified free choice, or an authored tip",
                            !a.choices.isEmpty() || a.any || (a.noteZh != null && !a.noteZh.isEmpty()));
                    assertFalse(label + ": " + app, app.contains(DialogueHint.FALLBACK));
                    assertFalse(label + ": " + widget, widget.contains(DialogueHint.FRIEND_AVOID_ALONE));
                    routes++;
                }
            }
        }
        assertEquals("19 manual links, each with R2 through R10", 171, scenes);
        assertEquals("both profile routes of every manual scene", 342, routes);
    }

    @Test
    public void devilMaxIsANoteOnlySceneWithoutInventedAnswerOptions() {
        DialogueHint.Answers a = answers("devil", 10, Profile.Route.FRIEND);
        assertTrue(a.reviewed);
        assertFalse(a.isEmpty());
        assertFalse("no menu is not a free-choice menu", a.any);
        assertTrue(a.choices.isEmpty());
        assertTrue(DialogueHint.widgetChoices(a).isEmpty());
        assertEquals("答：观看剧情即可", DialogueHint.widgetLine(a));
        String full = detail("devil", 10, Profile.Route.FRIEND);
        assertTrue(full, full.contains("没有回答选项") || full.contains("无须回答"));
        assertFalse(full, full.contains(DialogueHint.FALLBACK));
    }

    @Test
    public void priestessTwoKeepsTheSameAnswerAtTwoDifferentQuestions() {
        DialogueHint.Answers a = answers("priestess", 2, Profile.Route.FRIEND);
        assertEquals(List.of("没问题", "没问题"), a.choices);
        assertEquals(List.of("没问题", "任意", "没问题"), a.sequence);
        List<String> compact = DialogueHint.widgetChoices(a);
        assertEquals("shortening cannot merge repeated responses", 2, compact.size());
        assertTrue(compact.toString(), compact.get(0).contains("没问题"));
        assertTrue(compact.toString(), compact.get(1).contains("没问题"));
        assertTrue(detail("priestess", 2, Profile.Route.FRIEND).contains("没问题 / 任意 / 没问题"));
    }

    @Test
    public void justiceFiveFriendRetainsAllFourQuestionsUntilTheLaterRelationshipDecision() {
        List<String> normal = List.of("随便问吧", "产生恋爱感情了", "随时欢迎千寻找我倾诉", "握住她的手");
        DialogueHint.Answers friend = answers("justice", 5, Profile.Route.FRIEND);
        DialogueHint.Answers romance = answers("justice", 5, Profile.Route.ROMANCE);
        assertEquals(normal, friend.choices);
        assertEquals(normal, romance.choices);
        assertSequenceContains(friend, normal.toArray(new String[0]));
        assertTrue("a novel discussion must not be marked as a confession", friend.avoid.isEmpty());
        assertEquals(4, DialogueHint.widgetChoices(friend).size());
        assertFalse(DialogueHint.widgetLine(friend).contains("别选"));
        String full = detail("justice", 5, Profile.Route.FRIEND);
        assertTrue(full, full.contains("不等于确定交往") || full.contains("不等于确定关系"));
        assertTrue("the historical three-question alternative remains documented", full.contains("太草率"));
        assertFalse("the lower-gain historical alternative is not mixed into the primary card",
                friend.choices.contains("太草率了"));
    }

    @Test
    public void justiceNineProfileSelectsAcceptOrDeclineWithoutDiscardingTheScoringQuestions() {
        DialogueHint.Answers friend = answers("justice", 9, Profile.Route.FRIEND);
        DialogueHint.Answers romance = answers("justice", 9, Profile.Route.ROMANCE);
        assertEquals(List.of("她会帮你的", "千寻是清白的", "突然这是怎么了？", "千寻是我重要的学妹"), friend.choices);
        assertEquals(List.of("她会帮你的", "千寻是清白的", "突然这是怎么了？", "我的心意和千寻一样"), romance.choices);
        assertSequenceContains(friend, "她会帮你的", "千寻是清白的", "任意", "突然这是怎么了？", "千寻是我重要的学妹", "任意");
        assertSequenceContains(romance, "她会帮你的", "千寻是清白的", "任意", "突然这是怎么了？", "我的心意和千寻一样", "任意");
        assertFalse(DialogueHint.widgetLine(friend).contains("我的心意"));
        assertFalse(DialogueHint.widgetLine(romance).contains("重要的学妹"));
        assertEquals("the default profile also chooses friendship guidance", friend.choices,
                DialogueHint.forScene(rules, null, rules.link("justice").idx, 9, false).choices);
    }

    @Test
    public void justiceNineEarlyFriendshipIsAnOrderedAlternativeInDetails() {
        DialogueHint.Answers a = answers("justice", 9, Profile.Route.FRIEND);
        assertSequenceContains(a, "她会帮你的", "千寻是清白的", "任意", "我都看到了");
        assertFalse(a.choices.contains("我都看到了"));
        assertFalse(DialogueHint.widgetLine(a).contains("我都看到了"));
        String full = detail("justice", 9, Profile.Route.FRIEND);
        assertTrue(full, full.contains("我都看到了"));
        assertTrue("the alternative must be conditional on the earlier history", full.contains("若R5") || full.contains("此前"));
        assertTrue("all positions including arbitrary prompts remain visible", full.contains("完整问答顺序"));
    }

    @Test
    public void translationDisclosureAppearsInDetailsButDoesNotLengthenTheWidget() {
        for (Profile.Route route : new Profile.Route[]{Profile.Route.FRIEND, Profile.Route.ROMANCE}) {
            DialogueHint.Answers a = answers("justice", 9, route);
            assertTrue(DialogueHint.appLine(a), DialogueHint.appLine(a).contains(TRANSLATION_NOTE));
            assertTrue(detail("justice", 9, route).contains(TRANSLATION_NOTE));
            assertFalse(DialogueHint.widgetLine(a).contains("译意"));
            assertFalse(DialogueHint.widgetLine(a).contains("按含义对应"));
            List<String> compact = DialogueHint.widgetChoices(a);
            assertEquals(a.choices.size(), compact.size());
            assertTrue("the first answer remains readable without forcing expansion", compact.get(0).length() <= 6);
        }
    }

    @Test
    public void aeonMaxPreservesTheActualQuestionCountsOfEachRoute() {
        DialogueHint.Answers friend = answers("aeon", 10, Profile.Route.FRIEND);
        DialogueHint.Answers romance = answers("aeon", 10, Profile.Route.ROMANCE);
        assertTrue(friend.any);
        assertTrue(romance.any);
        assertEquals(Arrays.asList("任意", "任意", "任意", "任意"), friend.sequence);
        assertEquals(Arrays.asList("任意", "任意"), romance.sequence);
        assertEquals("答：怎么回答都可以", DialogueHint.widgetLine(friend));
        assertEquals("答：怎么回答都可以", DialogueHint.widgetLine(romance));
        assertTrue(detail("aeon", 10, Profile.Route.FRIEND).contains("任意 / 任意 / 任意 / 任意"));
    }
}
