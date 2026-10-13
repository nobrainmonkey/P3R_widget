package app.p3r.dayplanner.v3.widget;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The plan widget's 「参考回答」 line (audit: the adaptive widget never showed dialogue). It must say
 * exactly what the app says: compared with the app's {@code ui.Dialogue} on every guide fragment of
 * the shipped pack, except that the widget shows nothing where the app shows its generic fallback.
 */
public class GuideDialogueTest {

    private static Rules pack() throws Exception {
        for (String candidate : new String[]{"src/main/assets/p3r/p3r_rules.json", "app/src/main/assets/p3r/p3r_rules.json",
                "android/app/src/main/assets/p3r/p3r_rules.json"}) {
            File f = new File(candidate);
            if (f.isFile()) return RulesLoader.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        }
        return null;
    }

    private static Plan.Step meeting(String linkId, int from, int to) {
        Plan.Step step = new Plan.Step();
        step.action = "meet:" + linkId;
        step.kind = "social";
        step.entity = linkId;
        step.predicted.rankFrom = from;
        step.predicted.rankTo = to;
        step.predicted.rankUp = to > from;
        return step;
    }

    @Test
    public void saysWhatTheAppSaysForEveryGuideFragmentOfThePack() throws Exception {
        Rules rules = pack();
        Assume.assumeTrue("rule pack not available", rules != null);
        Class<?> app = Class.forName("app.p3r.dayplanner.v3.ui.Dialogue");
        Method ofFragments = app.getDeclaredMethod("line", List.class);
        Method ofStep = app.getDeclaredMethod("line", Rules.class, Plan.Step.class);
        Field fallbackField = app.getDeclaredField("FALLBACK");
        ofFragments.setAccessible(true);
        ofStep.setAccessible(true);
        fallbackField.setAccessible(true);
        String fallback = (String) fallbackField.get(null);
        int lines = 0;
        for (Rules.Link link : rules.links) {
            for (int rank = 1; rank <= 10; rank++) {
                List<String> fragments = link.dialogue.get(rank);
                assertEquals(link.id + " RANK " + rank, ofFragments.invoke(null, fragments), GuideDialogue.line(fragments));
                for (Plan.Step step : Arrays.asList(meeting(link.id, rank - 1, rank), meeting(link.id, rank, rank))) {
                    String shown = (String) ofStep.invoke(null, rules, step);
                    String expected = fallback.equals(shown) ? null : shown;
                    String widget = GuideDialogue.line(rules, step);
                    assertEquals(link.id + " " + step.predicted.rankFrom + "→" + step.predicted.rankTo, expected, widget);
                    if (widget != null) lines++;
                }
            }
        }
        assertTrue("the pack has guide answers", lines > 0);
    }

    @Test
    public void onlyMeetingsWithGuideTextGetALine() {
        Rules rules = FakePlannerApi.miniRules();
        Rules.Link emperor = rules.link("emperor");
        emperor.dialogue.put(7, Arrays.asList("我会帮你的", "这样的规矩没有意义"));
        emperor.dialogue.put(8, Collections.singletonList("任意"));
        emperor.dialogue.put(9, Arrays.asList("第1项", "选1进入恋人线，选2做朋友"));
        assertEquals("参考回答：我会帮你的 / 这样的规矩没有意义", GuideDialogue.line(rules, meeting("emperor", 6, 7)));
        assertEquals("a meeting without a rank-up uses its current rank", null, GuideDialogue.line(rules, meeting("emperor", 6, 6)));
        assertEquals(GuideDialogue.ANY, GuideDialogue.line(rules, meeting("emperor", 7, 8)));
        assertNull("ordinals and route notes are never shown", GuideDialogue.line(rules, meeting("emperor", 8, 9)));
        assertNull("no guide text for this rank", GuideDialogue.line(rules, meeting("emperor", 3, 4)));
        assertNull("no rule pack", GuideDialogue.line(null, meeting("emperor", 6, 7)));
        Plan.Step note = meeting("emperor", 6, 7);
        note.action = "note:emperor";
        assertNull("the dating-site note is no meeting", GuideDialogue.line(rules, note));
        Plan.Step training = meeting("emperor", 6, 7);
        training.kind = "stat";
        assertNull(GuideDialogue.line(rules, training));
        assertEquals("参考回答：A / B / C", GuideDialogue.line(Arrays.asList("对话选择：A/B", "C（前面选择第一个选项才有这个对话）")));
    }
}
