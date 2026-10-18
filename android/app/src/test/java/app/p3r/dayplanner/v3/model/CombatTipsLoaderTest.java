package app.p3r.dayplanner.v3.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/** Combat references load independently from the forecast and retain honest resistance descriptions. */
public class CombatTipsLoaderTest {
    @Test public void shippedCatalogueCoversAllGuardianFloorsOperationsAndFinalPhases() {
        Rules rules = PackFixture.rules();
        assertEquals(97, rules.reminders.combatTips.size());
        Set<Integer> floors = new HashSet<>();
        int operations = 0;
        int monad = 0;
        for (Rules.CombatTip tip : rules.reminders.combatTips) {
            assertFalse(tip.titleZh.isEmpty());
            assertFalse(tip.shortZh.isEmpty());
            assertFalse(tip.detailZh.isEmpty());
            assertFalse(tip.aliases.isEmpty());
            assertTrue(tip.src.contains(tip.id.equals("combat.prologue.awakening")
                    ? "aqiu_walkthrough" : "aqiu_tartarus"));
            if (tip.kind.equals("guardian")) floors.add(tip.floor);
            if (tip.kind.equals("full_moon")) operations++;
            if (tip.kind.equals("monad")) monad++;
        }
        assertEquals(43, floors.size());
        assertTrue(floors.contains(5));
        assertTrue(floors.contains(255));
        assertEquals(8, operations);
        assertEquals(39, monad);
        assertTrue(find(rules, "combat.thebel.full_moon").detailZh.contains("火炎伤害×2（不会因此倒地）"));
        String nyx = find(rules, "combat.adamah_final.264").detailZh;
        assertTrue(nyx.contains("倪克斯化身·魔术师｜弱点：无；无效：火炎"));
        assertTrue(nyx.contains("倪克斯化身·死神｜弱点：无\n"));
    }

    @Test public void lineupsExposeCountsAndExactNineAttributeSymbols() {
        Rules rules = PackFixture.rules();
        Rules.CombatEnemy raven = find(rules, "combat.thebel.5").enemies.get(0);
        assertEquals(2, raven.count);
        assertEquals(9, raven.affinities.length);
        assertEquals("w", raven.affinities[3]);
        assertEquals("s", raven.affinities[4]);
        assertEquals("*", raven.affinities[7]);
        assertEquals("door", find(rules, "combat.yabbashah_1.d1").encounterType);
        assertEquals("passage", find(rules, "combat.yabbashah_1.p1").encounterType);
        Rules.CombatTip awakening = find(rules, "combat.prologue.awakening");
        assertTrue(awakening.enemies.isEmpty());
        assertEquals("按剧情提示推进，觉醒后继续。", awakening.tacticsZh);
        for (Rules.CombatTip tip : rules.reminders.combatTips) {
            assertFalse(tip.tacticsZh.contains("源表"));
            assertFalse(tip.detailZh.contains("部分敌名译意"));
        }
    }

    @Test public void fixedPassagesExposeFloorAndOrderWhileHiddenChallengesRemainOptional() {
        Rules rules = PackFixture.rules();
        Rules.CombatTip first = find(rules, "combat.yabbashah_1.p1");
        Rules.CombatTip second = find(rules, "combat.yabbashah_1.p2");
        assertEquals(91, first.floor);
        assertEquals(91, second.floor);
        assertEquals(1, first.battleOrder);
        assertEquals(2, second.battleOrder);
        Rules.CombatTip reaper = find(rules, "combat.adamah.p4");
        assertEquals("optional", reaper.kind);
        assertEquals(0, reaper.floor);
        assertEquals(0, reaper.battleOrder);
        assertEquals("06-13", rules.day(reaper.fromDay).md);
        Rules.CombatTip elizabeth = find(rules, "combat.ultimate.4p");
        assertEquals("optional", elizabeth.kind);
        assertEquals(255, elizabeth.floor);
        assertEquals(0, elizabeth.battleOrder);
    }

    @Test public void malformedStructuredLineupsFailInsteadOfRenderingWrongAffinities() throws JSONException {
        JSONObject tree = PackFixture.tree();
        firstEnemy(tree).put("count", 0);
        rejects(tree, ".count");

        tree = PackFixture.tree();
        firstEnemy(tree).put("affinities", new JSONArray().put("w"));
        rejects(tree, ".affinities must have 9 entries");

        tree = PackFixture.tree();
        firstEnemy(tree).getJSONArray("affinities").put(0, "unknown");
        rejects(tree, ".affinities[0] has an unknown resistance symbol");

        tree = PackFixture.tree();
        tips(tree).getJSONObject(0).put("encounterType", "unknown");
        rejects(tree, ".encounterType must be door, passage or empty");

        tree = PackFixture.tree();
        tips(tree).getJSONObject(0).put("battleOrder", 1);
        rejects(tree, ".battleOrder is only valid for a fixed passage battle");
    }

    @Test public void oldCombatRecordsWithoutStructuredTablesStillLoad() throws JSONException {
        JSONObject tree = PackFixture.tree();
        JSONObject first = tips(tree).getJSONObject(0);
        first.remove("tacticsZh");
        first.remove("enemies");
        first.remove("encounterType");
        Rules.CombatTip parsed = RulesLoader.parse(tree.toString()).reminders.combatTips.get(0);
        assertTrue(parsed.enemies.isEmpty());
        assertEquals(parsed.detailZh, parsed.tacticsZh);
    }

    @Test public void combatSectionIsOptionalForOldPacksAndHasNoSimulationSideEffects() throws JSONException {
        JSONObject tree = PackFixture.tree();
        Rules with = RulesLoader.parse(tree.toString());
        tree.getJSONObject("reminders").remove("combatTips");
        Rules without = RulesLoader.parse(tree.toString());
        assertTrue(without.reminders.combatTips.isEmpty());
        assertEquals(without.activities.length, with.activities.length);
        assertEquals(without.reminders.items.size(), with.reminders.items.size());
        assertEquals(without.reminders.requestTips.size(), with.reminders.requestTips.size());
        for (int day = with.firstDay; day <= with.lastDay; day++) {
            assertEquals(without.days[day].dayLock, with.days[day].dayLock);
            assertEquals(without.days[day].eveLock, with.days[day].eveLock);
        }
    }

    @Test public void duplicateIdentitiesUnknownKindsAndBadFloorDatesAreRejected() throws JSONException {
        JSONObject tree = PackFixture.tree();
        tips(tree).getJSONObject(1).put("id", tips(tree).getJSONObject(0).getString("id"));
        rejects(tree, ".id is malformed or duplicated");

        tree = PackFixture.tree();
        tips(tree).getJSONObject(0).put("kind", "unknown");
        rejects(tree, ".kind is unknown");

        tree = PackFixture.tree();
        tips(tree).getJSONObject(0).put("floor", 0);
        rejects(tree, ".floor must identify the fixed floor");

        tree = PackFixture.tree();
        tips(tree).getJSONObject(0).put("fromDay", 2);
        rejects(tree, ".fromDay");
    }

    @Test public void emptyAndDuplicateAliasesAreRejected() throws JSONException {
        JSONObject tree = PackFixture.tree();
        tips(tree).getJSONObject(0).put("aliases", new JSONArray().put(""));
        rejects(tree, ".aliases[0]");

        tree = PackFixture.tree();
        tips(tree).getJSONObject(0).put("aliases", new JSONArray().put("boss").put("boss"));
        rejects(tree, ".aliases[1] is duplicated");
    }

    private static Rules.CombatTip find(Rules rules, String id) {
        for (Rules.CombatTip tip : rules.reminders.combatTips) if (tip.id.equals(id)) return tip;
        throw new AssertionError(id);
    }

    private static JSONObject firstEnemy(JSONObject tree) throws JSONException {
        return tips(tree).getJSONObject(0).getJSONArray("enemies").getJSONObject(0);
    }

    private static JSONArray tips(JSONObject tree) throws JSONException {
        return tree.getJSONObject("reminders").getJSONArray("combatTips");
    }

    private static void rejects(JSONObject tree, String message) {
        try {
            RulesLoader.parse(tree.toString());
            fail("expected validation failure: " + message);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(message));
        }
    }
}
