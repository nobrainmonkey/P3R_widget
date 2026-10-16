package app.p3r.dayplanner.v3.model;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Reviewed dialogue is optional, source-bound data; malformed guidance must fail before display. */
public class DialogueHintsLoaderTest {
    private static JSONObject variant(String... choices) throws Exception {
        return new JSONObject().put("choices", new JSONArray(choices)).put("sequence", new JSONArray(choices));
    }

    private static JSONObject entry(JSONObject common) throws Exception {
        return new JSONObject().put("common", common).put("conf", "verified")
                .put("src", new JSONArray().put("cn_guide"));
    }

    private static JSONObject tree(String rank, JSONObject hint) throws Exception {
        JSONObject tree = PackFixture.tree();
        tree.getJSONArray("links").getJSONObject(2).put("dialogueHints", new JSONObject().put(rank, hint));
        return tree;
    }

    private static void rejected(JSONObject tree, String text) {
        try {
            RulesLoader.parse(tree.toString());
            fail("accepted malformed dialogue guidance");
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage(), error.getMessage().contains(text));
        }
    }

    @Test
    public void optionalHintsKeepOlderPacksLoadable() throws Exception {
        JSONObject tree = PackFixture.tree();
        JSONArray links = tree.getJSONArray("links");
        for (int i = 0; i < links.length(); i++) links.getJSONObject(i).remove("dialogueHints");
        for (Rules.Link link : RulesLoader.parse(tree.toString()).links) assertTrue(link.dialogueHints.isEmpty());
    }

    @Test
    public void orderedDuplicatesAndCompactWordingSurviveLoading() throws Exception {
        JSONObject v = variant("加油", "加油").put("shortChoices", new JSONArray().put("加油").put("再选加油"))
                .put("sequence", new JSONArray().put("任意").put("加油").put("加油").put("任意"))
                .put("noteZh", "仅这道题出现时选择").put("shortNoteZh", "出现时选");
        Rules.DialogueVariant loaded = RulesLoader.parse(tree("3", entry(v)).toString()).links[2].dialogueHints.get(3).common;
        assertEquals(java.util.List.of("加油", "加油"), loaded.choices);
        assertEquals(java.util.List.of("任意", "加油", "加油", "任意"), loaded.sequence);
        assertEquals(java.util.List.of("加油", "再选加油"), loaded.shortChoices);
        assertEquals("仅这道题出现时选择", loaded.noteZh);
        try {
            loaded.sequence.add("不应可变");
            fail("loaded sequence must be immutable");
        } catch (UnsupportedOperationException expected) {
            // An accidental display mutation cannot corrupt the pack's ordering.
        }
    }

    @Test
    public void sourceBoundAvoidOnlyAndAnyVariantsAreValid() throws Exception {
        JSONObject friend = variant().put("avoid", new JSONArray().put("我喜欢你"));
        JSONObject love = variant("我喜欢你");
        JSONObject hint = new JSONObject().put("friend", friend).put("romance", love).put("conf", "verified")
                .put("src", new JSONArray().put("cn_guide"));
        Rules.DialogueHintEntry loaded = RulesLoader.parse(tree("9", hint).toString()).links[2].dialogueHints.get(9);
        assertNull(loaded.common);
        assertEquals(java.util.List.of("我喜欢你"), loaded.friend.avoid);
        JSONObject any = variant().put("any", true).put("sequence", new JSONArray().put("任意").put("任意"));
        assertTrue(RulesLoader.parse(tree("3", entry(any)).toString()).links[2].dialogueHints.get(3).common.any);
        JSONObject detailOnly = variant("好").put("noteZh", "第二问可能不出现").put("shortNoteZh", "");
        assertEquals("", RulesLoader.parse(tree("4", entry(detailOnly)).toString()).links[2].dialogueHints.get(4).common.shortNoteZh);
    }

    @Test
    public void invalidRanksAndDuplicateNumericRanksAreRejected() throws Exception {
        rejected(tree("11", entry(variant("好"))), "out of range 1..10");
        rejected(tree("next", entry(variant("好"))), "not a rank number");
        JSONObject t = tree("3", entry(variant("好")));
        t.getJSONArray("links").getJSONObject(2).getJSONObject("dialogueHints").put("03", entry(variant("好")));
        rejected(t, "duplicates rank 3");
    }

    @Test
    public void incompatibleOrIncompleteVariantsAreRejected() throws Exception {
        rejected(tree("3", entry(variant("好").put("any", true))), "any cannot accompany explicit choices");
        rejected(tree("3", entry(variant("好", "加油").put("shortChoices", new JSONArray().put("好")))),
                "shortChoices must have one entry per choice");
        rejected(tree("3", entry(variant())), "must have choices, any, avoid or noteZh");
        JSONObject noSequence = variant("好");
        noSequence.remove("sequence");
        rejected(tree("3", entry(noSequence)), "sequence");
        rejected(tree("3", entry(variant(" "))), "must be a non-empty string");
        rejected(tree("3", entry(variant("好").put("noteZh", " "))), "must be a non-empty string");
    }

    @Test
    public void missingVariantConfidenceAndUnknownSourcesAreRejected() throws Exception {
        JSONObject noVariants = new JSONObject().put("conf", "verified").put("src", new JSONArray().put("cn_guide"));
        rejected(tree("3", noVariants), "must have at least one dialogue variant");
        rejected(tree("3", entry(variant("好")).put("conf", "certainly")), ".conf");
        rejected(tree("3", entry(variant("好")).put("src", new JSONArray().put("no_such_source"))), "not declared");
        rejected(tree("3", entry(variant("好")).put("src", new JSONArray())), ".src must not be empty");
    }
}
