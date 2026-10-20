package app.p3r.dayplanner.v3.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.p3r.dayplanner.v3.engine.DialogueHint;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.model.RulesLoader;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class TipsCatalogueTest {
    @Test public void lookupIncludesEveryManualRankAndAllSchoolAnswersWithoutAStateOrPlan() throws Exception {
        Rules rules = realRules();
        PeriodTips.Period all = TipsCatalogue.all(rules, new Profile(), null);
        List<Plan.Reminder> dialogues = TipsCatalogue.filter(all.items, rules, "", TipsCatalogue.Content.DIALOGUE);
        assertEquals("all 19 manual links, opening and nine rank-up scenes each", 190, dialogues.size());
        for (Rules.Link link : rules.links) {
            if (link.kind != Rules.LinkKind.MANUAL) continue;
            Plan.Reminder opening = find(dialogues, "dialogue." + link.id + ".1");
            assertNotNull(link.id, opening);
            assertEquals(link.labelZh + " · 解锁", opening.textZh);
            assertEquals(-1, opening.slot);
            assertSame(link, TipsCatalogue.dialogueLink(rules, opening));
            assertEquals(1, TipsCatalogue.dialogueRank(opening));
            assertNull(find(all.items, "unlock." + link.id));
            for (int scene = 2; scene <= 10; scene++) {
                Plan.Reminder item = find(dialogues, "dialogue." + link.id + "." + scene);
                assertNotNull(item);
                assertTrue(item.textZh.contains(TipsCatalogue.rankStep(scene)));
                assertTrue("RANK 10 is MAX", scene < 10 || item.textZh.contains("RANK 9 → MAX"));
                assertFalse("the app says 回答：, the widget 答：", item.textZh.contains("\n答："));
                assertSame(link, TipsCatalogue.dialogueLink(rules, item));
                assertEquals(scene, TipsCatalogue.dialogueRank(item));
                assertEquals("a lookup is not a calendar appointment", -1, item.slot);
                assertFalse(item.textZh.contains(DialogueHint.FALLBACK));
            }
        }
        List<Plan.Reminder> answers = TipsCatalogue.filter(all.items, rules, "", TipsCatalogue.Content.CLASS_EXAM);
        assertEquals(53, answers.size()); // 36 class + 17 exam answers, not just the observed block.
        assertNotNull(find(answers, "class.04-08"));
        assertNotNull(find(answers, "class.01-08"));
    }

    @Test public void searchFindsCharacterRankRepeatedAnswersFullSequenceAndDates() throws Exception {
        Rules rules = realRules();
        PeriodTips.Period all = TipsCatalogue.all(rules, new Profile(), null);
        List<Plan.Reminder> exact = TipsCatalogue.filter(all.items, rules, "风花 RANK 1", TipsCatalogue.Content.ALL);
        assertEquals(1, exact.size());
        assertEquals("dialogue.priestess.2", exact.get(0).id);
        assertTrue(exact.get(0).textZh.contains("\n回答：没问题（首问） / 没问题（末问）"));
        assertTrue(exact.get(0).detailZh.contains("第 1 问：没问题\n第 2 问：随便选\n第 3 问：没问题"));
        assertFalse(exact.get(0).detailZh.contains("注意："));
        assertEquals("dialogue.priestess.2", TipsCatalogue.filter(all.items, rules, "女教皇1", TipsCatalogue.Content.ALL).get(0).id);
        assertEquals(1, TipsCatalogue.filter(all.items, rules, "风花 ｒ１", TipsCatalogue.Content.DIALOGUE).size());
        assertEquals(1, TipsCatalogue.filter(all.items, rules, "女教皇 1级", TipsCatalogue.Content.DIALOGUE).size());
        assertEquals("dialogue.priestess.3", TipsCatalogue.filter(all.items, rules, "女教皇2级",
                TipsCatalogue.Content.DIALOGUE).get(0).id);
        assertEquals("dialogue.priestess.3", TipsCatalogue.filter(all.items, rules, "风花R2",
                TipsCatalogue.Content.DIALOGUE).get(0).id);
        assertEquals(1, TipsCatalogue.filter(all.items, rules, "女教皇 2", TipsCatalogue.Content.DIALOGUE).size());
        for (String query : new String[]{"风花 升到RANK2", "女教皇 升至2级", "风花 到达R2"}) {
            List<Plan.Reminder> result = TipsCatalogue.filter(all.items, rules, query, TipsCatalogue.Content.ALL);
            assertEquals(query, 1, result.size());
            assertEquals(query, "dialogue.priestess.2", result.get(0).id);
        }
        assertEquals("dialogue.empress.10", TipsCatalogue.filter(all.items, rules, "美鹤 rank 10",
                TipsCatalogue.Content.DIALOGUE).get(0).id);
        assertEquals("class.04-08", TipsCatalogue.filter(all.items, rules, "4/8 鲤鱼旗",
                TipsCatalogue.Content.CLASS_EXAM).get(0).id);
        assertTrue(TipsCatalogue.filter(all.items, rules, "完全不存在的剧情选项", TipsCatalogue.Content.ALL).isEmpty());
        assertTrue(TipsCatalogue.filter(all.items, rules, "鲤鱼旗", TipsCatalogue.Content.DIALOGUE).isEmpty());
    }

    @Test public void openingLookupIncludesFullRequirementsStepsAndCurrentRankZeroAliases() throws Exception {
        Rules rules = realRules();
        PeriodTips.Period all = TipsCatalogue.all(rules, new Profile(), null);
        for (String query : new String[]{"风花 解锁", "女教皇 前置", "女教皇 RANK0", "风花 r0"}) {
            List<Plan.Reminder> results = TipsCatalogue.filter(all.items, rules, query, TipsCatalogue.Content.DIALOGUE);
            assertEquals(query, 1, results.size());
            Plan.Reminder opening = results.get(0);
            assertEquals(query, "dialogue.priestess.1", opening.id);
            assertTrue(opening.detailZh.contains("勇气 MAX"));
            assertTrue(opening.detailZh.contains("命运社群 RANK 1"));
            assertTrue(opening.detailZh.contains("教室前的走廊"));
        }
        Plan.Reminder justice = find(all.items, "dialogue.justice.1");
        assertNotNull(justice);
        for (Rules.Step step : rules.link("justice").unlock.steps) assertTrue(justice.detailZh.contains(step.howZh));
        assertNull(TipsCatalogue.dialogueLink(rules, find(all.items, "unlock.fool")));
        assertEquals(0, TipsCatalogue.dialogueRank(new Plan.Reminder()));
    }

    @Test public void currentRouteChangesLookupWithoutCachingOrMutatingThePreviousCatalogue() throws Exception {
        Rules rules = realRules();
        Profile profile = new Profile();
        int index = rules.linkIndex("priestess");
        profile.route[index] = Profile.Route.FRIEND;
        SimState state = new SimState();
        state.slot = rules.firstSlot();
        PeriodTips.Period friend = TipsCatalogue.all(rules, profile, state);
        Plan.Reminder first = find(friend.items, "dialogue.priestess.7");
        assertTrue(first.textZh.contains("一定是这样"));
        assertFalse(first.textZh.contains("真的是这样吗"));
        assertFalse(find(friend.items, "dialogue.priestess.8").detailZh.contains("译意"));
        profile.route[index] = Profile.Route.ROMANCE;
        PeriodTips.Period romance = TipsCatalogue.all(rules, profile, state);
        Plan.Reminder second = find(romance.items, "dialogue.priestess.7");
        assertTrue(second.textZh.contains("真的是这样吗"));
        assertFalse(second.textZh.contains("一定是这样"));
        assertTrue(first.textZh.contains("一定是这样"));
        assertEquals(rules.firstSlot(), state.slot);
        assertEquals(Profile.Route.ROMANCE, profile.route[index]);
    }

    @Test public void lookupRejectsInvalidAndAutomaticStoryScenesInsteadOfInventingAMeeting() throws Exception {
        Rules rules = realRules();
        int priestess = rules.linkIndex("priestess");
        assertSame(DialogueHint.Answers.NONE, DialogueHint.lookupScene(null, null, priestess, 2));
        assertSame(DialogueHint.Answers.NONE, DialogueHint.lookupScene(rules, null, -1, 2));
        assertSame(DialogueHint.Answers.NONE, DialogueHint.lookupScene(rules, null, priestess, 1));
        assertSame(DialogueHint.Answers.NONE, DialogueHint.lookupScene(rules, null, priestess, 11));
        assertSame(DialogueHint.Answers.NONE, DialogueHint.lookupScene(rules, null, rules.linkIndex("fool"), 2));
    }

    @Test public void requestNumberAliasesFindOneNamedRequestInsteadOfDatesOtherNumbersOrBatchNotices() {
        Rules rules = requestRules();
        Rules.Reminder batch = new Rules.Reminder();
        batch.id = "request.10-06";
        batch.kind = Plan.Reminder.REQUEST;
        batch.zh = "第 75 号和第 76 号有新委托";
        rules.reminders.items.add(batch);
        PeriodTips.Period all = TipsCatalogue.all(rules, new Profile(), null);
        assertEquals(3, TipsCatalogue.filter(all.items, rules, "", TipsCatalogue.Content.REQUEST).size());
        assertFalse(all.items.stream().anyMatch(item -> batch.id.equals(item.id)));

        Plan.Reminder wrongNumber = new Plan.Reminder();
        wrongNumber.id = "request.catalogue.175";
        wrongNumber.kind = Plan.Reminder.REQUEST;
        wrongNumber.textZh = "第 175 号 · 不应误匹配";
        all.items.add(wrongNumber);
        Plan.Reminder date = new Plan.Reminder();
        date.id = "calendar.75";
        date.textZh = "7/5 日期不能被当作委托75";
        date.slot = Rules.slot(75, false);
        all.items.add(date);
        for (String query : new String[]{"75", "075", "委托75", "请求75", "No.75", "#75", "第75号", "75号",
                "伊丽莎白 委托75", "伊丽莎白75", "伊莉莎白#75", "request #75"}) {
            List<Plan.Reminder> result = TipsCatalogue.filter(all.items, rules, query, TipsCatalogue.Content.ALL);
            assertEquals(query, 1, result.size());
            assertEquals(query, "request.catalogue.75", result.get(0).id);
        }
        assertEquals("request.catalogue.75", TipsCatalogue.filter(all.items, rules, "战国头盔",
                TipsCatalogue.Content.REQUEST).get(0).id);
        assertEquals("request.catalogue.75", TipsCatalogue.filter(all.items, rules, "Sengoku-era helm",
                TipsCatalogue.Content.REQUEST).get(0).id);
        assertTrue(TipsCatalogue.filter(all.items, rules, "75", TipsCatalogue.Content.DIALOGUE).isEmpty());
        assertTrue(TipsCatalogue.filter(all.items, rules, "No.999", TipsCatalogue.Content.REQUEST).isEmpty());
    }

    @Test public void realRequestCatalogueFindsAll101NumbersAndChineseNamesWithTheirCompleteInstructions() throws Exception {
        Rules rules = realRules();
        PeriodTips.Period all = TipsCatalogue.all(rules, new Profile(), null);
        assertEquals(101, rules.reminders.requestTips.size());
        assertEquals(101, TipsCatalogue.filter(all.items, rules, "", TipsCatalogue.Content.REQUEST).size());
        for (int number = 1; number <= 101; number++) {
            Rules.RequestTip source = null;
            for (Rules.RequestTip tip : rules.reminders.requestTips) if (tip.number == number) source = tip;
            assertNotNull("request #" + number, source);
            List<Plan.Reminder> byNumber = TipsCatalogue.filter(all.items, rules, "委托" + number,
                    TipsCatalogue.Content.ALL);
            assertEquals("request #" + number, 1, byNumber.size());
            Plan.Reminder item = byNumber.get(0);
            assertEquals("request.catalogue." + number, item.id);
            assertEquals("第 " + number + " 号 · " + source.titleZh, item.textZh);
            assertEquals(source.detailZh, item.detailZh);
            assertFalse(source.titleZh.trim().isEmpty());
            assertTrue("request #" + number + " has its instructions", source.detailZh.trim().length() > 10);
            String id = item.id;
            assertTrue("Chinese name of request #" + number + ": " + source.titleZh,
                    TipsCatalogue.filter(all.items, rules, source.titleZh, TipsCatalogue.Content.REQUEST)
                            .stream().anyMatch(result -> id.equals(result.id)));
        }
    }

    @Test public void currentRequestWindowRetainsPrerequisitesWithoutInventingAcceptedOrCompletedState() {
        Rules rules = requestRules();
        Rules.Block first = new Rules.Block();
        first.fromDay = 0;
        Rules.Block second = new Rules.Block();
        second.fromDay = 180;
        Rules.Block third = new Rules.Block();
        third.fromDay = 210;
        rules.blocks = new Rules.Block[]{first, second, third};
        SimState observed = new SimState();
        observed.slot = Rules.slot(190, false);
        PeriodTips.Period current = TipsCatalogue.current(rules, observed, null);
        List<Plan.Reminder> requests = TipsCatalogue.filter(current.items, rules, "", TipsCatalogue.Content.REQUEST);
        assertEquals(1, requests.size());
        Plan.Reminder helm = requests.get(0);
        assertEquals("request.catalogue.75", helm.id);
        assertTrue(helm.detailZh.contains("分 8 天"));
        assertEquals(rules.reminders.requestTips.get(1).detailZh, helm.detailZh);
        assertTrue(current.ongoingIds.contains(helm.id));
        assertEquals(Rules.slot(190, false), observed.slot);
        assertEquals(0, observed.statPts[0]);
        assertEquals(-1, rules.reminders.requestTips.get(1).untilDay);
    }

    @Test public void currentRequestListKeepsRealDeadlineWindowsButDoesNotRepeatOldUntimedRequests() {
        Rules rules = requestRules();
        Rules.Block first = new Rules.Block(); first.fromDay = 0;
        Rules.Block second = new Rules.Block(); second.fromDay = 180;
        Rules.Block third = new Rules.Block(); third.fromDay = 210;
        rules.blocks = new Rules.Block[]{first, second, third};
        Rules.RequestTip timed = request(90, "有期限的委托", "期限", 200, 220);
        rules.reminders.requestTips.add(timed);
        SimState observed = new SimState(); observed.slot = Rules.slot(215, false);
        PeriodTips.Period current = TipsCatalogue.current(rules, observed, null);
        assertNull(find(current.items, "request.catalogue.75"));
        assertNotNull(find(current.items, "request.catalogue.76"));
        assertNotNull(find(current.items, "request.catalogue.90"));
        assertNotNull(find(TipsCatalogue.all(rules, new Profile(), observed).items, "request.catalogue.75"));
    }

    @Test public void realLatePeriodExcludesMayPermanentOpeningsAndKeepsCompleteLookup() throws Exception {
        Rules rules = realRules();
        SimState observed = new SimState(); observed.slot = Rules.slot(190, false);
        PeriodTips.Period current = TipsCatalogue.current(rules, observed, null);
        assertNull(find(current.items, "fragment.05-25.club"));
        assertNull(find(current.items, "request.05-29.outings"));
        assertNull(find(current.items, "request.catalogue.77"));
        assertNotNull(find(current.items, "request.catalogue.75"));
        assertNotNull("the faculty-office days are dated nudges, not batch notices",
                find(current.items, "request.10-06.faculty"));
        assertNull("the named requests replace the batch notice", find(current.items, "request.10-06"));
        PeriodTips.Period all = TipsCatalogue.all(rules, new Profile(), observed);
        assertNotNull(find(all.items, "fragment.05-25.club"));
        assertNotNull(find(all.items, "request.catalogue.77"));
        assertNotNull(find(all.items, "request.catalogue.78"));
    }

    @Test public void combatLookupMatchesExactFloorsNamesAndMonthsAndKeepsBareRequestNumbersDistinct() {
        Rules rules = combatRules();
        PeriodTips.Period all = TipsCatalogue.all(rules, new Profile(), null);
        assertEquals(4, TipsCatalogue.filter(all.items, rules, "", TipsCatalogue.Content.COMBAT).size());
        for (String query : new String[]{"36F", "36 F", "36层", "第36层"}) {
            List<Plan.Reminder> result = TipsCatalogue.filter(all.items, rules, query, TipsCatalogue.Content.ALL);
            assertEquals(query, 1, result.size());
            assertEquals(query, "combat.catalogue.guardian.36", result.get(0).id);
        }
        assertEquals("combat.catalogue.guardian.36", TipsCatalogue.filter(all.items, rules, "36",
                TipsCatalogue.Content.COMBAT).get(0).id);
        assertEquals("combat.catalogue.guardian.36", TipsCatalogue.filter(all.items, rules, "天平",
                TipsCatalogue.Content.COMBAT).get(0).id);
        assertEquals("combat.catalogue.fullmoon.hermit", TipsCatalogue.filter(all.items, rules, "10月 满月",
                TipsCatalogue.Content.COMBAT).get(0).id);
        assertEquals("combat.catalogue.fullmoon.hermit", TipsCatalogue.filter(all.items, rules, "10月满月",
                TipsCatalogue.Content.COMBAT).get(0).id);
        assertEquals("request.catalogue.75", TipsCatalogue.filter(all.items, rules, "75",
                TipsCatalogue.Content.ALL).get(0).id);
        assertEquals("combat.catalogue.guardian.75", TipsCatalogue.filter(all.items, rules, "75",
                TipsCatalogue.Content.COMBAT).get(0).id);
        assertTrue(TipsCatalogue.filter(all.items, rules, "999层", TipsCatalogue.Content.COMBAT).isEmpty());
    }

    @Test public void currentCombatListUsesBlockOpeningsAndFullMoonDatesWithoutSchedulingFights() {
        Rules rules = combatRules();
        Rules.Block first = new Rules.Block();
        first.fromDay = 0;
        Rules.Block second = new Rules.Block();
        second.fromDay = 180;
        Rules.Block third = new Rules.Block();
        third.fromDay = 210;
        rules.blocks = new Rules.Block[]{first, second, third};
        SimState observed = new SimState();
        observed.slot = Rules.slot(190, false);
        Plan plan = new Plan();
        PeriodTips.Period current = TipsCatalogue.current(rules, observed, plan);
        List<Plan.Reminder> combat = TipsCatalogue.filter(current.items, rules, "", TipsCatalogue.Content.COMBAT);
        assertEquals(2, combat.size());
        assertNotNull(find(combat, "combat.catalogue.guardian.136"));
        assertNotNull(find(combat, "combat.catalogue.fullmoon.hermit"));
        assertTrue(plan.reminders.isEmpty());
        assertTrue(plan.steps.isEmpty());
        assertEquals(Rules.slot(190, false), observed.slot);
        assertEquals(0, observed.floor);
    }

    @Test public void everyMonadEncounterMapsToItsOfficialOpeningBlockAndFloorRange() throws Exception {
        Rules rules = realRules();
        PeriodTips.Period all = TipsCatalogue.all(rules, new Profile(), null);
        Set<String> blockIds = new LinkedHashSet<>();
        int doors = 0;
        int passages = 0;
        for (Plan.Reminder item : all.items) {
            Rules.CombatTip combat = TipsCatalogue.combatTip(rules, item);
            if (combat == null || !"monad".equals(combat.kind)) {
                assertNull(item.id, TipsCatalogue.monadBlock(rules, item));
                continue;
            }
            Rules.Block block = TipsCatalogue.monadBlock(rules, item);
            assertNotNull(combat.id, block);
            blockIds.add(block.id);
            assertEquals(combat.id, combat.fromDay, block.fromDay);
            assertTrue(combat.id, combat.id.startsWith("combat." + block.id + "."));
            assertFalse(block.zh.isEmpty());
            assertFalse(block.floorsZh.isEmpty());
            if ("door".equals(combat.encounterType)) {
                doors++;
                assertEquals("random doors must not be assigned a fake floor", 0, combat.floor);
            } else {
                passages++;
                assertTrue(combat.id, combat.floor <= block.top);
            }
        }
        assertEquals(Arrays.asList("yabbashah_1", "yabbashah_2", "tziah_1", "tziah_2",
                "harabah_1", "harabah_2", "adamah"), new ArrayList<>(blockIds));
        assertEquals(21, doors);
        assertEquals(18, passages);
    }

    @Test public void combatTitlesCanMentionRequestsWithoutTurningTheSearchIntoARequestNumberFilter() {
        Rules rules = combatRules();
        Rules.CombatTip challenge = combat("challenge.elizabeth", "optional", "255F隐藏挑战：伊莉莎白（委托101）", 255, 280);
        rules.reminders.combatTips.add(challenge);
        Rules.RequestTip request = request(101, "打倒最强之人", "最强之人", 280, -1);
        request.detailZh = "到255F挑战伊莉莎白。";
        rules.reminders.requestTips.add(request);
        PeriodTips.Period all = TipsCatalogue.all(rules, new Profile(), null);
        for (String query : new String[]{challenge.titleZh, "255F 委托101", "伊莉莎白 委托101"}) {
            List<Plan.Reminder> combat = TipsCatalogue.filter(all.items, rules, query, TipsCatalogue.Content.COMBAT);
            assertEquals(query, 1, combat.size());
            assertEquals("combat.catalogue.challenge.elizabeth", combat.get(0).id);
        }
        assertEquals("combat.catalogue.challenge.elizabeth", TipsCatalogue.filter(all.items, rules,
                challenge.titleZh, TipsCatalogue.Content.ALL).get(0).id);
        assertEquals("request.catalogue.101", TipsCatalogue.filter(all.items, rules, "委托101",
                TipsCatalogue.Content.ALL).get(0).id);
        assertEquals("request.catalogue.101", TipsCatalogue.filter(all.items, rules, "委托101 255F",
                TipsCatalogue.Content.REQUEST).get(0).id);
    }

    @Test public void realCombatCatalogueKeepsEveryEncounterPhaseAndTheFixedPassageBattleOrderSearchable() throws Exception {
        Rules rules = realRules();
        PeriodTips.Period all = TipsCatalogue.all(rules, new Profile(), null);
        assertEquals(97, rules.reminders.combatTips.size());
        assertEquals(97, TipsCatalogue.filter(all.items, rules, "", TipsCatalogue.Content.COMBAT).size());
        java.util.Set<Integer> operationDays = new java.util.HashSet<>();
        int operations = 0;
        for (Rules.CombatTip source : rules.reminders.combatTips) {
            Plan.Reminder item = find(all.items, "combat.catalogue." + source.id);
            assertNotNull(source.id, item);
            assertEquals(source.detailZh, item.detailZh);
            String id = item.id;
            assertTrue(source.id + " is searchable by its title",
                    TipsCatalogue.filter(all.items, rules, source.titleZh, TipsCatalogue.Content.COMBAT)
                            .stream().anyMatch(result -> id.equals(result.id)));
            for (Rules.CombatEnemy enemy : source.enemies) {
                assertEquals(9, enemy.affinities.length);
                assertTrue(source.id + " enemy/phase " + enemy.nameZh,
                        TipsCatalogue.filter(all.items, rules, enemy.nameZh, TipsCatalogue.Content.COMBAT)
                                .stream().anyMatch(result -> id.equals(result.id)));
            }
            if ("full_moon".equals(source.kind)) { operations++; operationDays.add(source.fromDay); }
            if ("door".equals(source.encounterType)) {
                assertEquals("a random door is not a fixed floor", 0, source.floor);
                assertEquals(0, source.battleOrder);
            }
        }
        assertEquals("seven operation nights contain eight separate encounters", 8, operations);
        assertEquals(7, operationDays.size());
        assertTrue(rules.reminders.combatTips.stream().anyMatch(combat -> "story".equals(combat.kind)
                && "04-09".equals(rules.day(combat.fromDay).md)));
        int[] floors = {91, 117, 143, 171, 197, 225, 255};
        int[] battles = {2, 3, 3, 3, 2, 2, 3};
        for (int i = 0; i < floors.length; i++) {
            List<Plan.Reminder> results = TipsCatalogue.filter(all.items, rules, floors[i] + "F", TipsCatalogue.Content.COMBAT);
            int expectedOrder = 1;
            for (Plan.Reminder item : results) {
                Rules.CombatTip combat = TipsCatalogue.combatTip(rules, item);
                assertEquals(floors[i], combat.floor);
                if (!"passage".equals(combat.encounterType)) continue;
                assertEquals("passage at " + floors[i] + "F", expectedOrder++, combat.battleOrder);
            }
            assertEquals("all passage battles at " + floors[i] + "F are returned", battles[i] + 1, expectedOrder);
        }
        Rules.CombatTip reaper = rules.reminders.combatTips.stream()
                .filter(combat -> "combat.adamah.p4".equals(combat.id)).findFirst().orElse(null);
        assertNotNull(reaper);
        assertEquals("roaming Reaper is not a fourth passage battle", "optional", reaper.kind);
        assertEquals(0, reaper.floor);
        assertEquals(0, reaper.battleOrder);
    }

    @Test public void visibleCategoriesPartitionTheWholeCatalogueWithoutLosingAnyReminderKind() throws Exception {
        Rules rules = realRules();
        List<Plan.Reminder> items = TipsCatalogue.all(rules, new Profile(), null).items;
        List<String> categories = TipsCatalogue.categories(items, rules);
        Set<String> visibleLabels = new LinkedHashSet<>();
        Set<String> expectedIds = new HashSet<>();
        for (Plan.Reminder item : items) {
            visibleLabels.add(TipsCatalogue.categoryLabel(rules, item));
            expectedIds.add(item.id);
        }
        assertEquals(new ArrayList<>(visibleLabels), categories);
        assertTrue(categories.contains("社群对话"));
        assertTrue(categories.contains("委托"));
        assertTrue(categories.contains("蒙纳多之门"));
        assertTrue(categories.contains("蒙纳多通道"));
        assertTrue(categories.contains("课堂提问"));
        assertTrue(categories.contains("考试"));
        Set<String> actualIds = new HashSet<>();
        int categorized = 0;
        for (String category : categories) {
            List<Plan.Reminder> matching = TipsCatalogue.filter(items, rules, "", TipsCatalogue.Content.ALL, category);
            assertFalse(category, matching.isEmpty());
            for (Plan.Reminder item : matching) {
                assertEquals(category, TipsCatalogue.categoryLabel(rules, item));
                assertTrue("each reminder appears under one visible tag: " + item.id, actualIds.add(item.id));
                categorized++;
            }
        }
        assertEquals(items.size(), categorized);
        assertEquals(expectedIds, actualIds);
    }

    @Test public void combatReminderTagsUseTheCanonicalEncounterCategoryRatherThanLegacyLabels() throws Exception {
        Rules rules = realRules();
        List<Plan.Reminder> items = TipsCatalogue.all(rules, new Profile(), null).items;
        for (Rules.CombatTip combat : rules.reminders.combatTips) {
            String expected;
            switch (combat.kind) {
                case "guardian": expected = "守关首领"; break;
                case "full_moon": expected = "满月作战"; break;
                case "monad": expected = "passage".equals(combat.encounterType) ? "蒙纳多通道" : "蒙纳多之门"; break;
                case "story": expected = "剧情作战"; break;
                case "final": expected = "最终战"; break;
                case "optional": expected = "特殊挑战"; break;
                default: throw new AssertionError("unhandled source category " + combat.kind);
            }
            Plan.Reminder item = find(items, "combat.catalogue." + combat.id);
            assertNotNull(combat.id, item);
            assertEquals(combat.id, expected, item.labelZh);
            assertEquals(combat.id, expected, TipsCatalogue.categoryLabel(combat));
            assertEquals(combat.id, expected, TipsCatalogue.categoryLabel(rules, item));
        }
    }

    @Test public void exactVisibleTagsInferNumberSemanticsWithoutMixingFloorsRequestsOrExamAnswers() {
        Rules rules = combatRules();
        Rules.CombatTip passage = combat("monad.75", "monad", "75层通道", 75, 80);
        passage.encounterType = "passage";
        rules.reminders.combatTips.add(passage);
        List<Plan.Reminder> items = TipsCatalogue.all(rules, new Profile(), null).items;
        Plan.Reminder exam = new Plan.Reminder();
        exam.id = "exam.sample";
        exam.kind = Plan.Reminder.EXAM;
        exam.labelZh = "考试";
        exam.textZh = "第75题参考答案";
        items.add(exam);
        assertEquals(TipsCatalogue.Content.COMBAT, TipsCatalogue.contentForCategory(items, rules, "守关首领"));
        assertEquals(TipsCatalogue.Content.CLASS_EXAM, TipsCatalogue.contentForCategory(items, rules, "考试"));
        assertEquals(TipsCatalogue.Content.REQUEST, TipsCatalogue.contentForCategory(items, rules, "委托"));
        for (String query : new String[]{"75", "75F", "第75层"}) {
            List<Plan.Reminder> guardians = TipsCatalogue.filter(items, rules, query, TipsCatalogue.Content.ALL, "守关首领");
            assertEquals(query, 1, guardians.size());
            assertEquals("combat.catalogue.guardian.75", guardians.get(0).id);
        }
        assertEquals("combat.catalogue.monad.75", TipsCatalogue.filter(items, rules, "75",
                TipsCatalogue.Content.ALL, "蒙纳多通道").get(0).id);
        assertEquals("request.catalogue.75", TipsCatalogue.filter(items, rules, "75",
                TipsCatalogue.Content.ALL, "委托").get(0).id);
        assertEquals("exam.sample", TipsCatalogue.filter(items, rules, "75",
                TipsCatalogue.Content.ALL, "考试").get(0).id);
        assertTrue(TipsCatalogue.filter(items, rules, "75", TipsCatalogue.Content.REQUEST, "守关首领").isEmpty());
        assertTrue(TipsCatalogue.filter(items, rules, "", TipsCatalogue.Content.ALL, "守关").isEmpty());
    }

    @Test public void categoriesReflectSuppliedScopeAndCurrentRulesWithoutKeepingAStaleLabelIndex() {
        Rules rules = new Rules();
        Rules.CombatTip combat = combat("guardian.sample", "guardian", "一场战斗", 36, 70);
        rules.reminders.combatTips.add(combat);
        Plan.Reminder item = new Plan.Reminder();
        item.id = "combat.catalogue.guardian.sample";
        item.kind = TipsCatalogue.COMBAT;
        item.labelZh = "旧显示名";
        List<Plan.Reminder> items = new ArrayList<>(Arrays.asList(item));
        List<String> first = TipsCatalogue.categories(items, rules);
        assertEquals(Arrays.asList("守关首领"), first);
        combat.kind = "optional";
        assertEquals(Arrays.asList("特殊挑战"), TipsCatalogue.categories(items, rules));
        assertEquals(Arrays.asList("守关首领"), first);
        assertTrue(TipsCatalogue.filter(items, rules, "", TipsCatalogue.Content.ALL, "守关首领").isEmpty());
        assertEquals("旧显示名", item.labelZh);
        Plan.Reminder ordinary = new Plan.Reminder();
        ordinary.labelZh = "  免费自习  ";
        items.add(ordinary);
        assertEquals(Arrays.asList("特殊挑战", "免费自习"), TipsCatalogue.categories(items, rules));
        ordinary.labelZh = " ";
        assertEquals("提醒", TipsCatalogue.categoryLabel(rules, ordinary));
        ordinary.labelZh = "特殊挑战";
        assertEquals("one visible label may contain unlike reminder kinds", TipsCatalogue.Content.ALL,
                TipsCatalogue.contentForCategory(items, rules, "特殊挑战"));
        assertEquals(TipsCatalogue.Content.ALL, TipsCatalogue.contentForCategory(items, rules, "不存在"));
        assertEquals(TipsCatalogue.Content.ALL, TipsCatalogue.contentForCategory(items, rules, null));
        assertTrue(TipsCatalogue.categories(new ArrayList<>(), rules).isEmpty());
    }

    @Test public void routePreviewReadsTheOtherRouteWithoutTouchingTheProfile() throws Exception {
        Rules rules = realRules();
        Profile profile = new Profile();
        int index = rules.linkIndex("priestess");
        Plan.Reminder friend = TipsCatalogue.scene(rules, profile, index, 7, null);
        Plan.Reminder romance = TipsCatalogue.scene(rules, profile, index, 7, Boolean.TRUE);
        assertTrue(friend.textZh.contains("一定是这样"));
        assertTrue(romance.textZh.contains("真的是这样吗"));
        assertEquals(Profile.Route.UNDECIDED, profile.route[index]);
        assertNull(TipsCatalogue.scene(rules, profile, rules.linkIndex("fool"), 2, null));
    }

    @Test public void displayedCatalogueTextHasNoJapaneseOrRawPointValues() throws Exception {
        Rules rules = realRules();
        Profile romance = new Profile();
        java.util.Arrays.fill(romance.route, Profile.Route.ROMANCE);
        java.util.regex.Pattern shorthand = java.util.regex.Pattern.compile("[\\u3040-\\u30ff]|♪|为\\s*[0-9]|均\\s*0|同分");
        for (Profile profile : new Profile[]{new Profile(), romance}) {
            for (Plan.Reminder item : TipsCatalogue.all(rules, profile, null).items) {
                if (!TipsCatalogue.DIALOGUE.equals(item.kind)) continue;
                for (String text : new String[]{item.textZh, item.detailZh}) {
                    if (text != null) assertFalse(item.id + ": " + text, shorthand.matcher(text).find());
                }
            }
        }
        Plan.Reminder justice = TipsCatalogue.scene(rules, romance, rules.linkIndex("justice"), 9, null);
        assertTrue(justice.detailZh, justice.detailZh.contains("这一场第 2 问只有「千寻是清白的」加好感"));
        assertFalse(justice.detailZh, justice.detailZh.contains("门槛"));
    }

    @Test public void sceneNotesKeepTheirSentenceBreaksAndDropTheResearchWords() throws Exception {
        // The 「no next rank」 clause goes; between two sentences its full stop stays.
        assertEquals("甲。乙。", TipsCatalogue.playerNote("甲；已到MAX，无后续升级门槛。乙。"));
        assertEquals("甲。乙。", TipsCatalogue.playerNote("甲。MAX后没有下一级好感门槛。乙"));
        assertEquals("甲。", TipsCatalogue.playerNote("已到终级，没有后续升级门槛；甲。"));
        assertEquals("甲。", TipsCatalogue.playerNote("甲；已到终级，无后续升级门槛。"));
        assertEquals("", TipsCatalogue.playerNote("已到终级，没有后续升级门槛。"));
        Rules rules = realRules();
        int aeon = rules.linkIndex("aeon");
        Plan.Reminder friend = TipsCatalogue.scene(rules, new Profile(), aeon, 10, Boolean.FALSE);
        assertTrue(friend.detailZh, friend.detailZh.contains(
                "友情终级，全程按喜好选。问题依次是：拍照时的欢呼回应、是否喜欢拍照、她能做到什么、回宿舍后解释刚才在做什么。"));
        assertFalse(friend.detailZh, friend.detailZh.contains("同伴询问"));
        Plan.Reminder romance = TipsCatalogue.scene(rules, new Profile(), aeon, 10, Boolean.TRUE);
        assertTrue(romance.detailZh, romance.detailZh.contains("恋人终级，全程按喜好选。问题依次是：询问她想说的话、回应她的注视。"));
        Plan.Reminder strength = TipsCatalogue.scene(rules, new Profile(), rules.linkIndex("strength"), 10, Boolean.FALSE);
        assertTrue(strength.detailZh, strength.detailZh.contains("\nRANK 9 拒绝过告白的话，后两问说法不一样，按意思选就行。"));
        // Every scene of every link, both routes: no guide-author wording, no two sentences run together.
        java.util.regex.Pattern research = java.util.regex.Pattern.compile(
                "门槛|亦已计入|仍给出|按相同含义对应|完整顺序|完整友情推荐|选完整|选问题");
        int scenes = 0;
        for (Boolean route : new Boolean[]{Boolean.FALSE, Boolean.TRUE}) {
            for (int link = 0; link < rules.links.length; link++) {
                for (int scene = 2; scene <= 10; scene++) {
                    Plan.Reminder item = TipsCatalogue.scene(rules, new Profile(), link, scene, route);
                    if (item == null) continue;
                    scenes++;
                    assertFalse(item.id + ": " + item.detailZh, research.matcher(item.detailZh).find());
                    assertFalse(item.id + ": " + item.detailZh, item.detailZh.contains("。。"));
                }
            }
        }
        assertTrue(scenes > 100);
    }

    @Test public void officialElementNamesReplaceTheOlderSpellingsButNotNames() {
        assertEquals("无情冰鸦弱火焰", TipsCatalogue.officialTerms("无情冰鸦弱火炎"));
        assertEquals("弱点：无；无效：神圣、暗黑", TipsCatalogue.officialTerms("弱点：无；无效：光、暗"));
        assertEquals("杀戮双子弱电击、神圣", TipsCatalogue.officialTerms("杀戮双子弱电击、光"));
        assertEquals("两只都反弹神圣和暗黑", TipsCatalogue.officialTerms("两只都反弹光和暗"));
        assertEquals("优先击杀光之天秤", TipsCatalogue.officialTerms("优先击杀光之天秤"));
        assertEquals("神圣炮台无弱点", TipsCatalogue.officialTerms("神圣炮台无弱点"));
    }

    private static Rules combatRules() {
        Rules rules = requestRules();
        rules.days = new Rules.Day[301];
        for (int day = 0; day <= 300; day++) {
            java.time.LocalDate date = java.time.LocalDate.of(2009, 4, 1).plusDays(day);
            Rules.Day calendar = new Rules.Day();
            calendar.md = String.format("%02d-%02d", date.getMonthValue(), date.getDayOfMonth());
            rules.days[day] = calendar;
        }
        Rules.CombatTip first = combat("guardian.36", "guardian", "守关战斗", 36, 70);
        first.aliases.add("天平");
        rules.reminders.combatTips.add(first);
        rules.reminders.combatTips.add(combat("guardian.75", "guardian", "另一场守关战斗", 75, 80));
        rules.reminders.combatTips.add(combat("guardian.136", "guardian", "后期守关战斗", 136, 181));
        rules.reminders.combatTips.add(combat("fullmoon.hermit", "full_moon", "隐者", 0, 189));
        return rules;
    }

    private static Rules.CombatTip combat(String id, String kind, String title, int floor, int from) {
        Rules.CombatTip combat = new Rules.CombatTip();
        combat.id = id;
        combat.kind = kind;
        combat.titleZh = title;
        combat.shortZh = "弱点：冰";
        combat.detailZh = "弱点：冰；火无效。\n先处理召唤的小怪。";
        combat.floor = floor;
        combat.fromDay = from;
        return combat;
    }

    private static Rules requestRules() {
        Rules rules = new Rules();
        rules.firstDay = 0;
        rules.lastDay = 300;
        rules.reminders.requestTips.add(request(7, "带给我松树树脂", "松树树脂", 10, 30));
        Rules.RequestTip helm = request(75, "带给我战国时代的头盔", "战国头盔", 188, -1);
        helm.aliases.add("Sengoku-era helm");
        helm.detailZh = "先接委托，再到教职员室分 8 天触发对话；拿到物品后交付。";
        rules.reminders.requestTips.add(helm);
        rules.reminders.requestTips.add(request(76, "带给我眼镜布", "眼镜布", 211, 220));
        return rules;
    }

    private static Rules.RequestTip request(int number, String title, String alias, int from, int until) {
        Rules.RequestTip request = new Rules.RequestTip();
        request.number = number;
        request.titleZh = title;
        request.aliases.add(alias);
        request.detailZh = "先接委托，再取得物品后交付。";
        request.fromDay = from;
        request.untilDay = until;
        return request;
    }

    private static Plan.Reminder find(List<Plan.Reminder> items, String id) {
        for (Plan.Reminder item : items) if (id.equals(item.id)) return item;
        return null;
    }

    private static Rules realRules() throws Exception {
        for (String name : new String[]{"src/main/assets/p3r/p3r_rules.json", "app/src/main/assets/p3r/p3r_rules.json",
                "android/app/src/main/assets/p3r/p3r_rules.json"}) {
            Path path = Path.of(name);
            if (Files.isRegularFile(path)) return RulesLoader.parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
        }
        throw new IllegalStateException("rule pack not found");
    }
}
