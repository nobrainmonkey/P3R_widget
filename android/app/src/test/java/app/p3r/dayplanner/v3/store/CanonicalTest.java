package app.p3r.dayplanner.v3.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Canonical JSON: key order and whitespace never change the text (plain JVM). */
public class CanonicalTest {

    @Test
    public void sortsKeysRecursivelyAndDropsWhitespace() throws Exception {
        JSONObject a = new JSONObject("{ \"b\": 1, \"a\": {\"z\": [3, {\"y\": true, \"x\": null}], \"c\": \"文字\"} }");
        assertEquals("{\"a\":{\"c\":\"文字\",\"z\":[3,{\"x\":null,\"y\":true}]},\"b\":1}", Canonical.of(a));
    }

    @Test
    public void equalValuesGiveEqualTextAndHash() throws Exception {
        JSONObject one = new JSONObject().put("slot", 83).put("action", "meet:emperor");
        JSONObject two = new JSONObject().put("action", "meet:emperor").put("slot", 83L);
        assertEquals(Canonical.of(one), Canonical.of(two));
        assertEquals(Canonical.sha256(Canonical.of(one)), Canonical.sha256(Canonical.of(two)));
        assertNotEquals(Canonical.sha256("a"), Canonical.sha256("b"));
        assertEquals(64, Canonical.sha256("x").length());
    }

    @Test
    public void integralDoublesPrintAsIntegers() throws Exception {
        assertEquals("[2,2.5,-7]", Canonical.of(new JSONArray().put(2.0).put(2.5).put(-7L)));
    }
}
