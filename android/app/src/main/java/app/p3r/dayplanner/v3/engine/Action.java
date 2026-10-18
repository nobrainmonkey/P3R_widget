package app.p3r.dayplanner.v3.engine;

/**
 * One slot-consuming choice (docs/DESIGN_v3.md §5.2). Value object; {@link #key()} is the stable
 * string form used in plans, widget intents and event payloads.
 *
 * <pre>
 *   meet:&lt;linkId&gt;            MEET
 *   invite:&lt;linkId&gt;          INVITE (record an actual received holiday invitation; never planned)
 *   unlock:&lt;linkId&gt;:&lt;stepIdx&gt; UNLOCK_STEP
 *   act:&lt;activityId&gt;         ACTIVITY
 *   tower:&lt;blockIdx&gt;         TARTARUS
 *   note:&lt;linkId&gt;            NOTE (dating-site note for that link, evening)
 *   episode:&lt;trackId&gt;        EPISODE (next Link Episode of that track)
 *   free                     FREE (nothing productive is legal)
 *   locked:&lt;reasonId&gt;        LOCKED (not a choice; emitted for locked slots)
 * </pre>
 */
public final class Action {
    public enum Type { MEET, UNLOCK_STEP, ACTIVITY, TARTARUS, FREE, LOCKED, NOTE, EPISODE, INVITE }

    public final Type type;
    /** Link index (MEET/INVITE/UNLOCK_STEP/NOTE), activity index (ACTIVITY), block index (TARTARUS), else -1. */
    public final int target;
    /** Unlock step index for UNLOCK_STEP, else -1. */
    public final int step;
    /** Lock reason id for LOCKED, else null. */
    public final String reason;

    private Action(Type type, int target, int step, String reason) {
        this.type = type;
        this.target = target;
        this.step = step;
        this.reason = reason;
    }

    public static Action meet(int link) { return new Action(Type.MEET, link, -1, null); }
    public static Action invite(int link) { return new Action(Type.INVITE, link, -1, null); }
    public static Action unlock(int link, int step) { return new Action(Type.UNLOCK_STEP, link, step, null); }
    public static Action activity(int activity) { return new Action(Type.ACTIVITY, activity, -1, null); }
    public static Action tower(int block) { return new Action(Type.TARTARUS, block, -1, null); }
    public static Action note(int link) { return new Action(Type.NOTE, link, -1, null); }
    public static Action episode(int track) { return new Action(Type.EPISODE, track, -1, null); }
    public static Action free() { return new Action(Type.FREE, -1, -1, null); }
    public static Action locked(String reason) { return new Action(Type.LOCKED, -1, -1, reason); }

    public boolean isProductive() { return type != Type.FREE && type != Type.LOCKED; }

    /** Stable key; needs the rules to resolve ids. */
    public String key(app.p3r.dayplanner.v3.model.Rules rules) {
        switch (type) {
            case MEET: return "meet:" + rules.links[target].id;
            case INVITE: return "invite:" + rules.links[target].id;
            case UNLOCK_STEP: return "unlock:" + rules.links[target].id + ":" + step;
            case ACTIVITY: return "act:" + rules.activities[target].id;
            case TARTARUS: return "tower:" + target;
            case NOTE: return "note:" + rules.links[target].id;
            case EPISODE: return "episode:" + rules.episodes[target].id;
            case LOCKED: return "locked:" + reason;
            default: return "free";
        }
    }

    /** Inverse of {@link #key}. Returns null for malformed or unknown keys. */
    public static Action parse(app.p3r.dayplanner.v3.model.Rules rules, String key) {
        if (key == null) return null;
        try {
            if (key.equals("free")) return free();
            if (key.startsWith("locked:")) return locked(key.substring(7));
            if (key.startsWith("tower:")) return tower(Integer.parseInt(key.substring(6)));
            if (key.startsWith("note:")) {
                app.p3r.dayplanner.v3.model.Rules.Link l = rules.link(key.substring(5));
                return l == null ? null : note(l.idx);
            }
            if (key.startsWith("episode:")) {
                String id = key.substring(8);
                for (app.p3r.dayplanner.v3.model.Rules.Episode e : rules.episodes) if (e.id.equals(id)) return episode(e.idx);
                return null;
            }
            if (key.startsWith("act:")) {
                app.p3r.dayplanner.v3.model.Rules.Activity a = rules.activity(key.substring(4));
                return a == null ? null : activity(a.idx);
            }
            if (key.startsWith("meet:")) {
                app.p3r.dayplanner.v3.model.Rules.Link l = rules.link(key.substring(5));
                return l == null ? null : meet(l.idx);
            }
            if (key.startsWith("invite:")) {
                app.p3r.dayplanner.v3.model.Rules.Link l = rules.link(key.substring(7));
                return l == null ? null : invite(l.idx);
            }
            if (key.startsWith("unlock:")) {
                String[] p = key.split(":");
                app.p3r.dayplanner.v3.model.Rules.Link l = rules.link(p[1]);
                return l == null ? null : unlock(l.idx, Integer.parseInt(p[2]));
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof Action)) return false;
        Action a = (Action) o;
        return a.type == type && a.target == target && a.step == step
                && (reason == null ? a.reason == null : reason.equals(a.reason));
    }

    @Override public int hashCode() {
        return ((type.ordinal() * 31 + target) * 31 + step) * 31 + (reason == null ? 0 : reason.hashCode());
    }

    @Override public String toString() { return type + "(" + target + (step >= 0 ? "," + step : "") + (reason != null ? "," + reason : "") + ")"; }
}
