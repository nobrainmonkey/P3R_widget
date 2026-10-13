package app.p3r.dayplanner.v3.store;

import app.p3r.dayplanner.v3.model.Rules;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Payload of a {@link EventKind#TOWER} row: one Tartarus visit as the player reported it.
 * Cash is given in exactly one way: the new balance ({@link #balanceMode}) or the net change.
 *
 * <pre>{"slot":201,"block":1,"floor":27,"cashMode":"balance","amount":14750,"level":21,"rescued":"bunkichi"}</pre>
 *
 * {@code "sideDone":false} is written only when the slot's side steps (the plan's "顺便：…" lines)
 * were not done; older rows never have it. {@link #block} is the index under the pack the row was
 * written with; replay re-derives the block from the day (see {@code Replayer}).
 *
 * <p>Side steps: {@code sideKeys} is the list the sheet passed, if any (part of the command);
 * {@code side} / {@code sideStepIds} are the side steps done as resolved when the row was written
 * (see {@link OutcomeRecord}; not part of the command's fingerprint). Replay applies exactly
 * {@code side}; rows without it (older rows) let the engine work the side steps out. Corrections
 * never carry them: the visit's own row decides.
 */
public final class TowerRecord {
    public static final String MODE_BALANCE = "balance";
    public static final String MODE_NET = "net";

    public int slot;
    /** Block index in {@code Rules.blocks}. */
    public int block;
    /** Highest floor reached on this visit. */
    public int floor;
    /** True: {@link #amount} is the balance after the visit. False: net change (may be negative). */
    public boolean balanceMode;
    public long amount;
    /** Protagonist level, or null when not given. */
    public Integer level;
    /** Rescue id confirmed during the visit, or null. */
    public String rescuedId;
    /** False when the player said the slot's side steps were not done (default true). */
    public boolean sideDone = true;
    /** Side-step keys the sheet passed with the command, or null. */
    public List<String> sideKeys;
    /** Side steps done as resolved when recording (keys, in order), or null when not stored. */
    public List<String> side;
    /** Unlock step id per key of {@link #side}; never null. */
    public Map<String, String> sideStepIds = new LinkedHashMap<>();

    public TowerRecord copy() {
        TowerRecord r = new TowerRecord();
        r.slot = slot;
        r.block = block;
        r.floor = floor;
        r.balanceMode = balanceMode;
        r.amount = amount;
        r.level = level;
        r.rescuedId = rescuedId;
        r.sideDone = sideDone;
        r.sideKeys = sideKeys == null ? null : new ArrayList<>(sideKeys);
        r.side = side == null ? null : new ArrayList<>(side);
        r.sideStepIds = new LinkedHashMap<>(sideStepIds);
        return r;
    }

    /** Stores the side steps done ({@code done}, null for none stored), with their step ids under {@code rules}. */
    public void resolveSide(Rules rules, List<String> done) {
        this.side = done == null ? null : new ArrayList<>(done);
        this.sideStepIds = OutcomeRecord.stepIdsOf(rules, done);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("slot", slot);
        o.put("block", block);
        o.put("floor", floor);
        o.put("cashMode", balanceMode ? MODE_BALANCE : MODE_NET);
        o.put("amount", amount);
        if (level != null) {
            o.put("level", level.intValue());
        }
        if (rescuedId != null) {
            o.put("rescued", rescuedId);
        }
        if (!sideDone) {
            o.put("sideDone", false);
        }
        if (sideKeys != null) {
            o.put("sideKeys", new JSONArray(sideKeys));
        }
        OutcomeRecord.putSide(o, side, sideStepIds);
        return o;
    }

    /** Parses a payload; rejects anything but exactly one well-formed cash mode. */
    public static TowerRecord fromJson(JSONObject o) throws JSONException {
        TowerRecord r = new TowerRecord();
        r.slot = o.getInt("slot");
        r.block = o.getInt("block");
        r.floor = o.getInt("floor");
        String mode = o.getString("cashMode");
        if (MODE_BALANCE.equals(mode)) {
            r.balanceMode = true;
        } else if (MODE_NET.equals(mode)) {
            r.balanceMode = false;
        } else {
            throw new JSONException("unknown cashMode " + mode);
        }
        r.amount = o.getLong("amount");
        r.level = o.has("level") ? o.getInt("level") : null;
        r.rescuedId = o.has("rescued") ? o.getString("rescued") : null;
        r.sideDone = o.optBoolean("sideDone", true);
        JSONArray keys = o.optJSONArray("sideKeys");
        if (keys != null) {
            r.sideKeys = new ArrayList<>(keys.length());
            for (int i = 0; i < keys.length(); i++) {
                r.sideKeys.add(keys.getString(i));
            }
        }
        r.side = OutcomeRecord.sideOf(o);
        r.sideStepIds = OutcomeRecord.sideStepIdsOf(o);
        return r;
    }
}
