package app.p3r.dayplanner.v3.store;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Payload of a {@link EventKind#TOWER} row: one Tartarus visit as the player reported it.
 * Cash is given in exactly one way: the new balance ({@link #balanceMode}) or the net change.
 *
 * <pre>{"slot":201,"block":1,"floor":27,"cashMode":"balance","amount":14750,"level":21,"rescued":"bunkichi"}</pre>
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

    public TowerRecord copy() {
        TowerRecord r = new TowerRecord();
        r.slot = slot;
        r.block = block;
        r.floor = floor;
        r.balanceMode = balanceMode;
        r.amount = amount;
        r.level = level;
        r.rescuedId = rescuedId;
        return r;
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
        return r;
    }
}
