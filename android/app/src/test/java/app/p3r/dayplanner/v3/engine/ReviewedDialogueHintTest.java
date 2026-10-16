package app.p3r.dayplanner.v3.engine;

import static org.junit.Assert.*;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Approved guidance keeps authored order, compact wording and explicit route boundaries. */
public class ReviewedDialogueHintTest {
    private static Rules fixture() {
        Rules r = new Rules();
        Rules.Link link = new Rules.Link();
        link.idx = 0;
        link.id = "priestess";
        link.romanceable = true;
        r.links = new Rules.Link[]{link};
        return r;
    }

    private static Rules.DialogueVariant variant(String... choices) {
        Rules.DialogueVariant v = new Rules.DialogueVariant();
        v.choices = Arrays.asList(choices);
        v.sequence = Arrays.asList(choices);
        return v;
    }

    private static Rules.DialogueHintEntry hint(Rules r, int scene) {
        Rules.DialogueHintEntry h = new Rules.DialogueHintEntry();
        r.links[0].dialogueHints.put(scene, h);
        return h;
    }

    private static Profile love() {
        Profile p = new Profile();
        p.route[0] = Profile.Route.ROMANCE;
        return p;
    }

    private static Plan.Predicted pred(int scene, boolean up, boolean certain) {
        Plan.Predicted p = new Plan.Predicted();
        p.rankFrom = scene - 1;
        p.rankTo = up ? scene : scene - 1;
        p.rankUp = up;
        p.certain = certain;
        return p;
    }

    private static Rules pack() throws Exception {
        for (String candidate : new String[]{"src/main/assets/p3r/p3r_rules.json", "app/src/main/assets/p3r/p3r_rules.json",
                "android/app/src/main/assets/p3r/p3r_rules.json"}) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) return RulesLoader.parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
        }
        throw new AssertionError("shipped pack missing");
    }

    @Test
    public void reviewedCommonGuidanceBypassesUnreviewedHighRankWithholding() {
        Rules r = fixture();
        r.links[0].dialogue.put(8, List.of("旧攻略告白"));
        hint(r, 8).common = variant("男孩或女孩");
        DialogueHint.Answers answers = DialogueHint.forScene(r, new Profile(), 0, 8, false);
        assertTrue(answers.reviewed);
        assertEquals("参考回答：男孩或女孩", DialogueHint.appLine(answers));
        assertEquals("答：男孩或女孩", DialogueHint.widgetLine(answers));
    }

    @Test
    public void routeOverrideWinsAndMissingReviewedRouteCannotReadRawRomanceAnswers() {
        Rules r = fixture();
        r.links[0].dialogue.put(9, List.of("共同问题/我喜欢你，恋人线"));
        Rules.DialogueHintEntry h = hint(r, 9);
        h.romance = variant("我喜欢你");
        DialogueHint.Answers friend = DialogueHint.forScene(r, new Profile(), 0, 9, false);
        assertTrue(friend.reviewed);
        assertTrue(friend.choices.isEmpty());
        assertTrue(friend.sequence.isEmpty());
        assertFalse(DialogueHint.appLine(friend).contains("共同问题"));
        assertFalse(DialogueHint.appLine(friend).contains("我喜欢你"));
        h.common = variant("共同回答");
        assertEquals(List.of("共同回答"), DialogueHint.forScene(r, new Profile(), 0, 9, false).choices);
        assertEquals(List.of("我喜欢你"), DialogueHint.forScene(r, love(), 0, 9, false).choices);
        h.friend = variant("友情回答");
        assertEquals(List.of("友情回答"), DialogueHint.forScene(r, new Profile(), 0, 9, false).choices);
    }

    @Test
    public void repeatedAnswersAndHumanCompactWordingKeepTheirPromptIdentity() {
        Rules r = fixture();
        Rules.DialogueVariant v = variant("加油", "加油");
        v.shortChoices = List.of("加油", "再选加油");
        v.sequence = List.of("任意", "加油", "加油", "任意");
        hint(r, 4).common = v;
        DialogueHint.Answers a = DialogueHint.forScene(r, null, 0, 4, false);
        assertEquals("参考回答：加油 / 加油", DialogueHint.appLine(a));
        assertEquals("答：加油 / 再选加油", DialogueHint.widgetLine(a));
        assertEquals(v.sequence, a.sequence);
    }

    @Test
    public void avoidOnlyGuidanceNamesTheAnswerInTheAppAndWidget() {
        Rules r = fixture();
        Rules.DialogueVariant v = variant();
        v.avoid = List.of("我喜欢风花");
        v.sequence = List.of("告白时别选「我喜欢风花」");
        hint(r, 9).friend = v;
        DialogueHint.Answers a = DialogueHint.forScene(r, null, 0, 9, false);
        assertEquals("参考回答：别选「我喜欢风花」", DialogueHint.appLine(a));
        assertEquals("答：别选「我喜欢风花」", DialogueHint.widgetLine(a));
        assertTrue(a.choices.isEmpty());
        v.noteZh = "友情路线其余问题仍待核对";
        v.shortNoteZh = "";
        DialogueHint.Answers detailed = DialogueHint.forScene(r, null, 0, 9, false);
        assertTrue(DialogueHint.appLine(detailed).contains("其余问题仍待核对"));
        assertEquals("答：别选「我喜欢风花」", DialogueHint.widgetLine(detailed));
    }

    @Test
    public void optionalRomancePrerequisiteIsANoteForFriendAndAnEssentialChoiceForRomance() {
        Rules r = fixture();
        Rules.DialogueHintEntry h = hint(r, 7);
        h.common = variant("太过分了");
        h.common.noteZh = "想保留恋爱分支，再答「真的是这样吗」";
        h.common.shortNoteZh = h.common.noteZh;
        h.romance = variant("太过分了", "真的是这样吗");
        h.romance.noteZh = "末句是保留恋爱分支的前置，不等于确定关系";
        DialogueHint.Answers friend = DialogueHint.forScene(r, new Profile(), 0, 7, false);
        assertEquals(List.of("太过分了"), friend.choices);
        assertTrue(DialogueHint.widgetNote(friend).contains("想保留恋爱分支"));
        DialogueHint.Answers romance = DialogueHint.forScene(r, love(), 0, 7, false);
        assertEquals(List.of("太过分了", "真的是这样吗"), DialogueHint.widgetChoices(romance));
        assertFalse(DialogueHint.widgetNote(romance).contains("想保留"));
    }

    @Test
    public void uncertainMeetingsKeepReviewedNotesButCertainAndHeldMeetingsHaveNoScene() {
        Rules r = fixture();
        Rules.DialogueVariant v = variant("好");
        v.noteZh = "仅出现该问题时选";
        hint(r, 4).common = v;
        DialogueHint.Answers maybe = DialogueHint.forMeeting(r, null, 0, pred(4, false, false), 10);
        assertTrue(maybe.conditional);
        assertEquals("要是升级了，答：好；仅出现该问题时选", DialogueHint.appLine(maybe));
        assertTrue(DialogueHint.widgetLine(maybe).startsWith(DialogueHint.CONDITIONAL_PREFIX));
        assertSame(DialogueHint.Answers.NONE, DialogueHint.forMeeting(r, null, 0, pred(4, false, true), 10));
        Rules.Hold hold = new Rules.Hold();
        hold.rank = 4;
        hold.untilDay = 11;
        r.links[0].holds.add(hold);
        assertSame(DialogueHint.Answers.NONE, DialogueHint.forMeeting(r, null, 0, pred(4, false, false), 10));
    }

    @Test
    public void legacyUnreviewedScenesStillUseTheirExistingPolicy() {
        Rules r = fixture();
        r.links[0].dialogue.put(3, List.of("任意", "第2项"));
        r.links[0].dialogue.put(8, List.of("男孩或者女孩"));
        assertEquals(DialogueHint.FALLBACK, DialogueHint.appLine(DialogueHint.forScene(r, null, 0, 3, false)));
        assertEquals(DialogueHint.FRIEND_FALLBACK, DialogueHint.appLine(DialogueHint.forScene(r, null, 0, 8, false)));
    }

    @Test
    public void completedPackRestoresThePreviouslyHeldScenesAndBothRoutes() throws Exception {
        Rules r = pack();
        Rules.Link priestess = r.link("priestess");
        DialogueHint.Answers three = DialogueHint.forScene(r, null, priestess.idx, 3, false);
        assertTrue(three.reviewed);
        assertEquals(List.of("就啪的一撒", "不用心急"), three.choices);
        assertEquals(List.of("就啪的一撒", "不用心急", "任意"), three.sequence);
        for (String[] completed : new String[][]{{"hierophant", "9"}, {"star", "3"}, {"fortune", "5"}, {"tower", "9"},
                {"empress", "6"}, {"hierophant", "7"}, {"justice", "9"}, {"strength", "7"}}) {
            Rules.Link link = r.link(completed[0]);
            int rank = Integer.parseInt(completed[1]);
            assertTrue(Arrays.toString(completed), link.dialogueHints.containsKey(rank));
            DialogueHint.Answers a = DialogueHint.forScene(r, new Profile(), link.idx, rank, false);
            assertTrue(Arrays.toString(completed), a.reviewed);
            assertFalse(Arrays.toString(completed), a.isEmpty());
            assertNotNull(Arrays.toString(completed), DialogueHint.widgetLine(a));
        }
        for (String[] completed : new String[][]{{"empress", "8"}, {"aeon", "9"}}) {
            Rules.Link link = r.link(completed[0]);
            DialogueHint.Answers a = DialogueHint.forScene(r, new Profile(), link.idx, Integer.parseInt(completed[1]), false);
            assertTrue(a.reviewed);
            assertFalse(a.choices.isEmpty());
            assertFalse(a.sequence.isEmpty());
        }
    }

    @Test
    public void appDetailsPreserveArbitraryPositionsWithoutPuttingThemOnTheCompactCard() throws Exception {
        Rules r = pack();
        Profile p = new Profile();
        Model m = new Model(r, p, null);
        SimState start = new SimState(r.blocks.length, r.activities.length);
        start.slot = Rules.slot(100, false);
        Labels labels = new Labels(m, new Heuristics(m, start, null));
        Kernel.Effect effect = new Kernel.Effect();
        effect.rankUp = true;
        effect.rankFrom = 4;
        effect.rankTo = 5;
        Rules.Link lovers = r.link("lovers");
        String detail = labels.meetHow(start.slot, lovers.idx, effect);
        assertTrue(detail, detail.contains("任意 / 任意 / 任意 / 我是她朋友 / 抱歉"));
        DialogueHint.Answers a = DialogueHint.forScene(r, p, lovers.idx, 5, false);
        assertTrue(DialogueHint.widgetLine(a), DialogueHint.widgetLine(a).contains("最后一问"));
        assertFalse(DialogueHint.widgetLine(a), DialogueHint.widgetLine(a).contains("任意"));
    }
}
