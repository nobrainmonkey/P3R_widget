package app.p3r.dayplanner.v3.model;

import static app.p3r.dayplanner.v3.model.PackFixture.day;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Game facts and planning invariants of the shipped pack. Every expectation here is traceable to
 * the evidence listed in rules/build/REPORT.md (game-data tables first).
 */
public class RulesPackFactsTest {
    private static final int ACADEMICS = 0;
    private static final int CHARM = 1;
    private static final int COURAGE = 2;

    private final Rules r = PackFixture.rules();

    // ------------------------------------------------------------------ links and names

    @Test
    public void linksUseOfficialSimplifiedChineseNames() {
        String[] arcana = {"愚者", "魔术师", "女教皇", "女皇", "皇帝", "教皇", "恋爱", "战车", "正义", "隐者", "命运",
                "力量", "倒悬者", "死神", "节制", "恶魔", "塔", "星", "月亮", "太阳", "审判", "永劫"};
        for (int i = 0; i < arcana.length; i++) {
            assertEquals(arcana[i], r.links[i].arcanaZh);
            assertEquals(r.links[i].arcanaZh + " · " + r.links[i].personZh, r.links[i].labelZh);
            assertFalse(r.links[i].genderZh.isEmpty());
        }
        assertEquals("皇帝 · 小田桐秀利", link("emperor").labelZh);
        assertEquals("贝培", link("temperance").personZh);
        assertEquals("舞子", link("hanged_man").personZh);
        assertEquals("Ｙ子", link("hermit").personZh);
        assertEquals("北村文吉、光子", link("hierophant").personZh);
        assertEquals("特别课外活动部", link("fool").personZh);
        assertEquals("伙伴们", link("judgement").personZh); // spoiler-safe label
        assertEquals("XIII", link("death").numeral);
        assertEquals("女", link("hanged_man").genderZh);
        assertEquals("夫妇", link("hierophant").genderZh);
    }

    @Test
    public void packNeverUsesOutdatedNames() {
        String json = PackFixture.json();
        for (String wrong : new String[] {"贝贝", "大桥舞子", "伊丽莎白", "女帝", "倒吊人"}) {
            assertFalse("pack must not contain " + wrong, json.contains(wrong));
        }
        for (String right : new String[] {"贝培", "舞子", "伊莉莎白", "Ｙ子", "倒悬者", "月亮"}) {
            assertTrue("pack should contain " + right, json.contains(right));
        }
    }

    @Test
    public void playerFacingTextHasNoRawEnglishOrIds() {
        Pattern latin = Pattern.compile("[A-Za-z]+");
        Set<String> allowed = new HashSet<>(List.of("Lv", "RANK", "MAX", "F", "OK", "G"));
        List<String> texts = new ArrayList<>();
        for (Rules.Link l : r.links) {
            add(texts, l.arcanaZh, l.personZh, l.genderZh, l.labelZh, l.locationZh, l.noteZh, l.unlock.noteZh);
            for (Rules.Step s : l.unlock.steps) add(texts, s.zh, s.howZh, s.locationZh);
            for (Rules.Hold h : l.holds) add(texts, h.zh);
        }
        for (Rules.Activity a : r.activities) add(texts, a.zh, a.locationZh, a.noteZh);
        for (Rules.Day d : r.days) if (d != null) add(texts, d.noteZh);
        for (Rules.Block b : r.blocks) add(texts, b.zh, b.floorsZh);
        for (Rules.Rescue x : r.rescues) add(texts, x.zh);
        for (Rules.Stat s : r.stats) add(texts, s.zh);
        texts.addAll(r.lockReasonsZh.values());
        texts.addAll(r.notes);
        for (String text : texts) {
            Matcher m = latin.matcher(text);
            while (m.find()) {
                assertTrue("Latin word '" + m.group() + "' in player text: " + text, allowed.contains(m.group()));
            }
        }
    }

    // ------------------------------------------------------------------ points and stats

    @Test
    public void thresholdsComeFromGameData() {
        for (Rules.Link l : r.links) {
            assertEquals(l.id + " rank 1", 0, l.threshold[1]);
            assertEquals(l.id + " rank 2", 0, l.threshold[2]);
            assertEquals(l.id + " rank 3", 0, l.threshold[3]);
        }
        assertArrayEquals(new int[] {0, 0, 0, 0, 15, 15, 15, 15, 22, 22, 22}, link("magician").threshold);
        assertEquals(22, link("lovers").threshold[6]);
        assertEquals(0, link("emperor").threshold[4]);
        for (int rank = 4; rank <= 9; rank++) assertEquals(0, link("aeon").threshold[rank]);
        assertEquals(30, link("aeon").threshold[10]);
        for (String id : new String[] {"fool", "death", "devil", "sun", "judgement"}) {
            assertArrayEquals(id, new int[11], link(id).threshold);
        }
    }

    @Test
    public void eventGainsAreBestAnswerScenePoints() {
        assertEquals(20, link("magician").eventGain[3]);
        assertEquals(0, link("moon").eventGain[9]);
        assertEquals(15, link("aeon").eventGain[9]);
        assertEquals(10, link("fortune").eventGain[4]); // lowest of 10 (aqiu) and 20 (model)
        for (Rules.Link l : r.links) {
            assertEquals(l.id, 0, l.eventGain[10]);
            assertNull(l.id, l.eventGainRomance);
        }
    }

    @Test
    public void statThresholdsComeFromGameData() {
        assertArrayEquals(new int[] {0, 0, 20, 55, 100, 155, 230}, r.stats[ACADEMICS].thresholds);
        assertArrayEquals(new int[] {0, 0, 15, 30, 45, 70, 100}, r.stats[CHARM].thresholds);
        assertArrayEquals(new int[] {0, 0, 15, 30, 45, 60, 80}, r.stats[COURAGE].thresholds);
        assertEquals("学力", r.stats[ACADEMICS].zh);
        assertEquals(6, r.stats[ACADEMICS].levelFor(230));
        assertEquals(5, r.stats[ACADEMICS].levelFor(229));
    }

    // ------------------------------------------------------------------ availability

    @Test
    public void devilAndTowerMeetOnlyOnTheirEvenings() {
        Rules.Link devil = link("devil");
        assertTrue(devil.evening);
        assertTrue(devil.avail.cardinality() > 50);
        for (int d = devil.avail.nextSetBit(0); d >= 0; d = devil.avail.nextSetBit(d + 1)) {
            int wd = r.day(d).wd;
            assertTrue("Devil on " + r.day(d).md, wd == 2 || wd == 6);
        }
        Rules.Link tower = link("tower");
        assertTrue(tower.evening);
        for (int d = tower.avail.nextSetBit(0); d >= 0; d = tower.avail.nextSetBit(d + 1)) {
            assertTrue("Tower on " + r.day(d).md, r.day(d).wd >= 4);
        }
        for (Rules.Link l : r.links) {
            if (!l.id.equals("devil") && !l.id.equals("tower")) assertFalse(l.id, l.evening);
        }
    }

    @Test
    public void meetingsNeverFallOnLockedSlots() {
        for (Rules.Link l : r.links) {
            for (int d = l.avail.nextSetBit(0); d >= 0; d = l.avail.nextSetBit(d + 1)) {
                String lock = l.evening ? r.day(d).eveLock : r.day(d).dayLock;
                assertNull(l.id + " meets on locked " + r.day(d).md, lock);
            }
            for (int d = l.invite.nextSetBit(0); d >= 0; d = l.invite.nextSetBit(d + 1)) {
                assertNull(l.id + " invitation on locked " + r.day(d).md, r.day(d).dayLock);
            }
            if (l.kind == Rules.LinkKind.STORY) {
                assertTrue(l.id, l.avail.isEmpty() && l.invite.isEmpty());
            }
        }
    }

    @Test
    public void empressMeetsMondaysAndFridaysOnlyInThePreExamWeek() {
        Rules.Link empress = link("empress");
        assertTrue(empress.avail.get(day(12, 7)));  // Monday of the pre-exam week
        assertTrue(empress.avail.get(day(12, 11))); // Friday of the pre-exam week
        for (int d = empress.avail.nextSetBit(0); d >= 0; d = empress.avail.nextSetBit(d + 1)) {
            int wd = r.day(d).wd;
            if (wd == 1 || wd == 5) assertTrue(r.day(d).md, d >= day(12, 7) && d <= day(12, 12));
        }
        assertEquals(day(11, 21), empress.avail.nextSetBit(0));
    }

    @Test
    public void emperorIsAwayInNovember() {
        Rules.Link emperor = link("emperor");
        for (int d = day(11, 3); d <= day(11, 29); d++) assertFalse(r.day(d).md, emperor.avail.get(d));
        assertTrue(emperor.avail.get(day(11, 30)));
    }

    @Test
    public void holidayInvitationsFollowTheGameTable() {
        TreeSet<Integer> dates = new TreeSet<>();
        int total = 0;
        for (Rules.Link l : r.links) {
            for (int d = l.invite.nextSetBit(0); d >= 0; d = l.invite.nextSetBit(d + 1)) {
                dates.add(d);
                total++;
                Rules.DayType type = r.day(d).type;
                assertTrue(l.id + " invitation on a school day " + r.day(d).md,
                        type == Rules.DayType.SUNDAY || type == Rules.DayType.HOLIDAY || type == Rules.DayType.VACATION);
            }
        }
        // HolidayScheduleDataAsset: 35 dates and 77 link invitations (the analysis report's "34" was a miscount).
        assertEquals(35, dates.size());
        assertEquals(77, total);
        assertTrue(link("lovers").invite.get(day(8, 3)));
        assertTrue(link("lovers").invite.get(day(8, 9)));
        assertTrue(link("justice").invite.get(day(8, 9)));
        assertTrue(link("magician").invite.get(day(4, 26)));
        assertTrue(link("empress").invite.get(day(1, 24)));
    }

    // ------------------------------------------------------------------ calendar

    @Test
    public void prologueIsLockedAndFreeTimeStartsOnApril21() {
        for (int d = r.firstDay; d < day(4, 21); d++) {
            assertEquals(r.day(d).md, "prologue", r.day(d).dayLock);
            assertEquals(r.day(d).md, "prologue", r.day(d).eveLock);
            assertEquals(-1, r.day(d).block);
        }
        Rules.Day apr21 = r.day(day(4, 21));
        assertNull(apr21.dayLock);
        assertNull(apr21.eveLock);
        assertTrue(apr21.tartarus);
    }

    @Test
    public void april22DaytimeIsTheForcedMagicianIntroduction() {
        Rules.Day apr22 = r.day(day(4, 22));
        assertEquals("forced_event", apr22.dayLock);
        assertNull(apr22.eveLock);
        assertTrue(apr22.tartarus);
        assertEquals(day(4, 22), link("magician").unlock.earliestDay);
        assertTrue(link("magician").unlock.steps.isEmpty());
    }

    @Test
    public void fullMoonOperationsLockTheWholeDay() {
        int[][] fullMoons = {{5, 9}, {6, 8}, {7, 7}, {8, 6}, {9, 5}, {10, 4}, {11, 3}};
        for (int[] md : fullMoons) {
            Rules.Day d = r.day(day(md[0], md[1]));
            assertEquals(d.md, "full_moon", d.dayLock);
            assertEquals(d.md, "full_moon", d.eveLock);
            assertEquals(d.md, 4, d.moon);
            assertTrue(d.md, d.fullMoon);
            assertFalse(d.md, d.tartarus);
        }
        assertEquals(4, r.day(day(1, 31)).moon);
        assertTrue(r.day(day(1, 31)).fullMoon);
        assertNull("the day after a full moon is free when the calendar shows meetings", r.day(day(5, 10)).dayLock);
        assertFalse(r.day(day(5, 10)).fullMoon);
        assertEquals(5, r.day(day(5, 10)).moon);
        assertEquals("estimated full moon: phase only", 4, r.day(day(12, 2)).moon);
        assertFalse(r.day(day(12, 2)).fullMoon);
        int flagged = 0;
        for (Rules.Day d : r.days) if (d != null && d.fullMoon) flagged++;
        assertEquals(9, flagged); // 4/9 … 11/3 and the finale
    }

    @Test
    public void examCoresAreLockedButTheLastExamDayIsNot() {
        int[][] ranges = {{5, 18, 5, 22}, {7, 14, 7, 17}, {10, 13, 10, 16}, {12, 14, 12, 18}};
        for (int[] range : ranges) {
            for (int d = day(range[0], range[1]); d <= day(range[2], range[3]); d++) {
                assertEquals(r.day(d).md, "exam", r.day(d).dayLock);
                assertEquals(r.day(d).md, "exam", r.day(d).eveLock);
                assertEquals(Rules.DayType.EXAM, r.day(d).type);
            }
        }
        assertNull(r.day(day(5, 23)).dayLock);
        assertNull(r.day(day(10, 17)).dayLock);
        assertNull(r.day(day(12, 19)).dayLock);
        assertNull(r.day(day(7, 18)).eveLock);
    }

    @Test
    public void storyDaysAreLockedConservatively() {
        assertWholeDay("yakushima", 7, 20, 7, 22);
        assertEquals("yakushima", r.day(day(7, 23)).dayLock);
        assertNull("7/23 evening has witnesses", r.day(day(7, 23)).eveLock);
        assertWholeDay("summer_classes", 8, 10, 8, 14);
        assertWholeDay("festival", 8, 16, 8, 16);
        assertWholeDay("typhoon_illness", 9, 18, 9, 20);
        assertWholeDay("school_trip", 11, 17, 11, 19);
        assertWholeDay("work_experience", 11, 24, 11, 26);
        assertWholeDay("story", 11, 4, 11, 4);
        for (int d = day(7, 27); d <= day(8, 2); d++) assertEquals("track_training", r.day(d).dayLock);
        assertEquals("new_year", r.day(day(1, 1)).dayLock);
        assertNull("New Year's evening is free", r.day(day(1, 1)).eveLock);
        assertEquals("reserved", r.day(day(1, 21)).dayLock);
        assertNull(r.day(day(1, 21)).eveLock);
        assertEquals("story", r.day(day(12, 31)).eveLock);
        assertNull(r.day(day(12, 31)).dayLock);
    }

    @Test
    public void christmasEveIsFreeByDefault() {
        Rules.Day eve = r.day(day(12, 24));
        assertNull(eve.dayLock);
        assertNull(eve.eveLock);
        assertNotNull(eve.noteZh);
    }

    @Test
    public void finaleLocksOnlyTheLastEvening() {
        Rules.Day jan31 = r.day(day(1, 31));
        assertEquals("finale", jan31.eveLock);
        assertNull(jan31.dayLock);
        assertFalse(jan31.tartarus);
        assertTrue(r.day(day(1, 30)).tartarus);
        assertEquals(r.lastSlot(), Rules.slot(305, true));
        assertEquals("finale", r.lockOf(r.lastSlot()));
        assertNull(r.lockOf(Rules.slot(305, false)));
    }

    @Test
    public void vacationsAndHolidaysFollowTheCalendar() {
        assertEquals(Rules.DayType.SCHOOL, r.day(day(7, 25)).type);
        assertEquals(Rules.DayType.VACATION, r.day(day(7, 26)).type);
        assertEquals(Rules.DayType.VACATION, r.day(day(8, 31)).type);
        assertEquals(Rules.DayType.SCHOOL, r.day(day(9, 1)).type);
        assertEquals(Rules.DayType.SCHOOL, r.day(day(12, 26)).type);
        assertEquals(Rules.DayType.VACATION, r.day(day(1, 7)).type);
        assertEquals(Rules.DayType.SCHOOL, r.day(day(1, 8)).type);
        assertEquals(Rules.DayType.HOLIDAY, r.day(day(4, 29)).type);
        assertEquals(Rules.DayType.SCHOOL, r.day(day(5, 6)).type); // substitute holiday not observed in-game
        assertEquals(Rules.DayType.SUNDAY, r.day(day(5, 10)).type);
    }

    // ------------------------------------------------------------------ Tartarus

    @Test
    public void tartarusBlocksOpenOnGameDataDates() {
        int[][] starts = {{4, 21}, {5, 10}, {6, 13}, {7, 9}, {8, 8}, {9, 10}, {10, 6}, {11, 6}, {12, 3}, {1, 1}};
        int[] tops = {22, 43, 69, 92, 118, 144, 172, 198, 226, 256};
        for (int i = 0; i < starts.length; i++) {
            assertEquals(r.blocks[i].id, day(starts[i][0], starts[i][1]), r.blocks[i].fromDay);
            assertEquals(r.blocks[i].id, tops[i], r.blocks[i].top);
            assertEquals(i, r.day(r.blocks[i].fromDay).block);
        }
        assertEquals(day(1, 30), r.blocks[9].toDay);
        assertEquals("世俗之庭 台伯勒", r.blocks[0].zh);
        assertEquals("忧郁之庭 亚达玛", r.blocks[9].zh);
    }

    @Test
    public void tartarusEntryRespectsClosuresAndLocks() {
        int[][] closed = {{7, 11}, {7, 12}, {7, 19}, {7, 23}, {7, 29}, {8, 2}, {8, 7}, {8, 15}, {11, 20}, {11, 22},
                {11, 30}, {12, 2}, {12, 3}, {12, 4}, {12, 5}, {12, 6}, {12, 7}, {12, 8}, {12, 9}, {12, 13}};
        for (int[] md : closed) assertFalse(r.day(day(md[0], md[1])).tartarus);
        int open = 0;
        for (Rules.Day d : r.days) {
            if (d == null || !d.tartarus) continue;
            open++;
            assertTrue(d.md, d.block >= 0);
            assertNull(d.md, d.eveLock);
            assertNotNull(r.blockOn(d.d));
        }
        assertTrue(open > 200);
    }

    @Test
    public void rescueWindowsGateHierophantAndHangedMan() {
        Rules.Rescue bunkichi = r.rescues[0];
        assertEquals("bunkichi", bunkichi.id);
        assertEquals("北村文吉", bunkichi.zh);
        assertEquals(day(9, 12), bunkichi.fromDay);
        assertEquals(day(10, 3), bunkichi.toDay);
        assertEquals(r.linkIndex("hierophant"), bunkichi.link);
        Rules.Rescue maiko = r.rescues[1];
        assertEquals("maiko", maiko.id);
        assertEquals(day(10, 21), maiko.fromDay);
        assertEquals(day(11, 2), maiko.toDay);
        assertEquals(r.linkIndex("hanged_man"), maiko.link);

        Rules.Missing hierophant = link("hierophant").missing;
        assertEquals("bunkichi", hierophant.rescueId);
        assertEquals(bunkichi.fromDay, hierophant.fromDay);
        assertEquals(bunkichi.toDay, hierophant.toDay);
        assertEquals("maiko", link("hanged_man").missing.rescueId);
        int withMissing = 0;
        for (Rules.Link l : r.links) if (l.missing != null) withMissing++;
        assertEquals(2, withMissing);
        assertTrue("at least one open evening in each rescue window", openEvenings(bunkichi) > 5 && openEvenings(maiko) > 5);
    }

    // ------------------------------------------------------------------ unlocks

    @Test
    public void unlockRequirementsUseTheStrictestClaims() {
        assertRequires("devil", Map.of(CHARM, 4), Map.of("hermit", 4));
        assertRequires("tower", Map.of(COURAGE, 4), Map.of("strength", 4));
        assertRequires("empress", Map.of(ACADEMICS, 6), Map.of());
        assertRequires("sun", Map.of(ACADEMICS, 4), Map.of("hanged_man", 3));
        assertRequires("priestess", Map.of(COURAGE, 6), Map.of("fortune", 1));
        assertRequires("lovers", Map.of(CHARM, 6), Map.of());
        assertRequires("star", Map.of(COURAGE, 4), Map.of());
        assertRequires("temperance", Map.of(ACADEMICS, 2), Map.of("hierophant", 3));
        assertRequires("moon", Map.of(CHARM, 2), Map.of("magician", 3));
        assertRequires("justice", Map.of(), Map.of("emperor", 1));
        assertRequires("strength", Map.of(), Map.of("chariot", 2));
        assertEquals(day(8, 9), link("sun").unlock.earliestDay);
        assertEquals(day(11, 21), link("empress").unlock.earliestDay);
        assertNotNull(link("empress").unlock.noteZh);
    }

    @Test
    public void devilIsOpenedWithThreePaymentsOnSeparateEvenings() {
        List<Rules.Step> steps = link("devil").unlock.steps;
        assertEquals(3, steps.size());
        int[] costs = {20000, 10000, 10000};
        for (int i = 0; i < 3; i++) {
            Rules.Step s = steps.get(i);
            assertTrue(s.evening);
            assertEquals(Rules.StepDays.LINK_AVAIL, s.dayRule);
            assertEquals(costs[i], s.cost);
            assertEquals("only the last payment takes the evening", i == 2, s.consumesSlot);
            assertEquals(i == 2, s.establishes);
            if (i > 0) assertTrue(s.minGapDays >= 1);
        }
    }

    @Test
    public void multiStepOpeningsAreOrderedAndConsumeOnlyTheFinalSlot() {
        assertEquals(4, link("justice").unlock.steps.size());
        assertEquals(day(5, 7), link("justice").unlock.steps.get(3).fromDay);
        assertEquals(510, link("hanged_man").unlock.steps.get(0).cost);
        assertEquals(Rules.StepDays.FIXED, link("moon").unlock.steps.get(0).dayRule);
        assertEquals(Rules.StepDays.FIXED, link("tower").unlock.steps.get(0).dayRule);
        assertEquals(Rules.StepDays.ANY_FREE, link("sun").unlock.steps.get(1).dayRule);
        for (Rules.Link l : r.links) {
            List<Rules.Step> steps = l.unlock.steps;
            for (int i = 0; i < steps.size(); i++) {
                boolean last = i == steps.size() - 1;
                assertEquals(l.id + " step " + i, last, steps.get(i).establishes);
                if (last) assertTrue(l.id + " final step takes a slot", steps.get(i).consumesSlot);
                assertFalse(steps.get(i).zh.isEmpty());
                assertNotNull(steps.get(i).howZh);
            }
        }
    }

    @Test
    public void everyManualLinkHasAReachableUnlockPath() {
        for (Rules.Link l : r.links) {
            if (l.kind != Rules.LinkKind.MANUAL) continue;
            assertNoCycle(l, new HashSet<>());
            int established = establishDay(l);
            assertTrue(l.id + " cannot be opened before the end of the calendar", established >= 0);
            int nextMeeting = -1;
            for (int d = established + 1; d <= r.lastDay && nextMeeting < 0; d++) {
                if (l.meetsOn(d)) nextMeeting = d;
            }
            assertTrue(l.id + " has no meeting day after its opening on " + r.day(established).md, nextMeeting > 0);
        }
    }

    @Test
    public void storyLinksRankUpByDate() {
        int[] fool = link("fool").storyRankDay;
        assertEquals(day(4, 18), fool[1]);
        assertEquals(day(11, 28), fool[8]); // rank 8 is skipped: reached with rank 9
        assertEquals(day(11, 28), fool[9]);
        assertEquals(day(12, 31), fool[10]);
        int[] death = link("death").storyRankDay;
        assertEquals(day(6, 12), death[1]);
        assertEquals(day(7, 12), death[2]);
        assertEquals(day(7, 12), death[3]);
        assertEquals(day(8, 7), death[5]);
        assertEquals(day(9, 12), death[6]);
        assertEquals(day(10, 6), death[8]);
        assertEquals(day(11, 4), death[10]);
        int[] judgement = link("judgement").storyRankDay;
        assertEquals(day(12, 31), judgement[1]);
        assertEquals(day(1, 30), judgement[10]);
        for (Rules.Link l : r.links) {
            if (l.kind == Rules.LinkKind.STORY) {
                for (int rank = 2; rank <= 10; rank++) assertTrue(l.storyRankDay[rank] >= l.storyRankDay[rank - 1]);
            } else {
                assertNull(l.storyRankDay);
            }
        }
    }

    @Test
    public void dialogueComesFromTheChineseGuide() {
        assertEquals(List.of("对人生？（3）", "加油（2）"), link("magician").dialogue.get(3));
        assertTrue(link("tower").dialogue.get(1).contains("基尔酒"));
        for (Rules.Link l : r.links) {
            for (Map.Entry<Integer, List<String>> e : l.dialogue.entrySet()) {
                assertTrue(e.getKey() >= 1 && e.getKey() <= 10);
                assertFalse(e.getValue().isEmpty());
            }
        }
    }

    // ------------------------------------------------------------------ activities

    @Test
    public void everyActivityHasChineseNameLocationAndSaneNumbers() {
        Set<String> ids = new HashSet<>();
        for (Rules.Activity a : r.activities) {
            assertTrue(a.id, ids.add(a.id));
            assertFalse(a.id, a.zh == null || a.zh.isEmpty());
            assertFalse(a.id, a.locationZh == null || a.locationZh.isEmpty());
            assertTrue(a.id, a.day || a.eve);
            assertTrue(a.id, a.weekdayMask != 0);
            int gain = a.gain[0] + a.gain[1] + a.gain[2];
            assertTrue(a.id + " must raise a stat", gain > 0);
            if (a.kind == Rules.ActivityKind.JOB) {
                assertEquals(a.id, 0, a.cost);
                assertTrue(a.id, a.pay.length > 0 && a.payFor(1) > 0);
                for (int i = 1; i < a.pay.length; i++) assertTrue(a.pay[i][1] >= a.pay[i - 1][1]);
            } else {
                assertEquals(a.id, 0, a.pay.length);
            }
        }
    }

    @Test
    public void everyFreeSlotOffersAFreeProductiveActivity() {
        // A plan never has to idle: some zero-cost stat activity fits every unlocked slot.
        for (Rules.Day d : r.days) {
            if (d == null) continue;
            for (int evening = 0; evening <= 1; evening++) {
                boolean eve = evening == 1;
                if ((eve ? d.eveLock : d.dayLock) != null) continue;
                boolean found = false;
                for (Rules.Activity a : r.activities) {
                    boolean slotOk = eve ? a.eve : a.day;
                    boolean typeOk = a.dayTypes.isEmpty() || a.dayTypes.contains(d.type);
                    if (a.cost == 0 && a.kind == Rules.ActivityKind.STAT && slotOk && a.allowsWeekday(d.wd)
                            && d.d >= a.fromDay && d.d <= a.toDay && typeOk) {
                        found = true;
                        break;
                    }
                }
                assertTrue(d.md + (eve ? " evening" : " daytime") + " has no free activity", found);
            }
        }
    }

    @Test
    public void keyActivitiesMatchTheEvidence() {
        Rules.Activity study = r.activity("study_room");
        assertTrue(study.eve);
        assertFalse(study.day);
        assertEquals(2, study.gain[ACADEMICS]);
        for (int wd = 1; wd <= 7; wd++) assertTrue(study.allowsWeekday(wd));

        Rules.Activity library = r.activity("library");
        assertEquals(2, library.gain[ACADEMICS]);
        assertEquals(List.of(Rules.DayType.SCHOOL), library.dayTypes);
        assertEquals("conflict", library.conf);

        Rules.Activity chagall = r.activity("job_chagall");
        assertEquals(Rules.ActivityKind.JOB, chagall.kind);
        assertTrue(chagall.eve);
        assertEquals(2500, chagall.payFor(1));
        assertEquals(3000, chagall.payFor(5));
        assertEquals(5000, chagall.payFor(30));
        assertEquals(1, chagall.gain[CHARM]);
        assertEquals(1, chagall.gain[COURAGE]);
        assertTrue(chagall.allowsWeekday(1) && chagall.allowsWeekday(2) && chagall.allowsWeekday(3));
        assertFalse(chagall.allowsWeekday(4));

        Rules.Activity accessory = r.activity("job_accessory");
        assertTrue(accessory.day);
        assertEquals(day(7, 24), accessory.fromDay);
        assertEquals(3500, accessory.payFor(1));

        Rules.Activity cinema = r.activity("job_cinema");
        assertTrue(cinema.allowsWeekday(7));
        assertFalse(cinema.allowsWeekday(6));
        assertEquals(5000, cinema.payFor(1));
    }

    // ------------------------------------------------------------------ helpers

    private Rules.Link link(String id) {
        Rules.Link l = r.link(id);
        assertNotNull(id, l);
        return l;
    }

    private static void add(List<String> out, String... texts) {
        for (String t : texts) if (t != null) out.add(t);
    }

    private void assertWholeDay(String reason, int m1, int d1, int m2, int d2) {
        for (int d = day(m1, d1); d <= day(m2, d2); d++) {
            assertEquals(r.day(d).md, reason, r.day(d).dayLock);
            assertEquals(r.day(d).md, reason, r.day(d).eveLock);
        }
    }

    private int openEvenings(Rules.Rescue rescue) {
        int n = 0;
        for (int d = rescue.fromDay; d <= rescue.toDay; d++) if (r.day(d).tartarus) n++;
        return n;
    }

    private void assertRequires(String id, Map<Integer, Integer> stats, Map<String, Integer> links) {
        Rules.Unlock u = link(id).unlock;
        assertEquals(id + " stat requirements", stats, u.statLv);
        assertEquals(id + " link requirements", links.size(), u.linkRank.size());
        for (Map.Entry<String, Integer> e : links.entrySet()) {
            assertEquals(id + " needs " + e.getKey(), e.getValue(), u.linkRank.get(r.linkIndex(e.getKey())));
        }
    }

    private void assertNoCycle(Rules.Link l, Set<Integer> path) {
        if (!path.add(l.idx)) fail("unlock requirement cycle through " + l.id);
        for (int required : l.unlock.linkRank.keySet()) assertNoCycle(r.links[required], new HashSet<>(path));
    }

    /**
     * Earliest day on which every unlock step can be performed in order (slot free, day rule and
     * gap respected), or the earliest day for automatic openings; -1 if impossible.
     */
    private int establishDay(Rules.Link l) {
        int previous = -1;
        if (l.unlock.steps.isEmpty()) return l.unlock.earliestDay;
        for (Rules.Step s : l.unlock.steps) {
            int from = Math.max(l.unlock.earliestDay, s.fromDay);
            if (previous >= 0) from = Math.max(from, previous + s.minGapDays);
            int found = -1;
            for (int d = Math.max(from, r.firstDay); d <= r.lastDay && found < 0; d++) {
                Rules.Day day = r.day(d);
                String lock = s.evening ? day.eveLock : day.dayLock;
                if (lock == null && stepAllows(l, s, day)) found = d;
            }
            if (found < 0) return -1;
            previous = found;
        }
        return previous;
    }

    private static boolean stepAllows(Rules.Link l, Rules.Step s, Rules.Day day) {
        switch (s.dayRule) {
            case LINK_AVAIL:
                return l.avail.get(day.d);
            case WEEKDAYS:
                for (int wd : s.weekdays) if (wd == day.wd) return true;
                return false;
            case FIXED:
                for (int d : s.fixedDays) if (d == day.d) return true;
                return false;
            case ANY_FREE:
            default:
                return true;
        }
    }
}
