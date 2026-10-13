package app.p3r.dayplanner.v3.model;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable-by-convention P3R rule model, compiled from {@code assets/p3r/p3r_rules.json}
 * (schema {@code p3r.rules.v3}, see docs/DESIGN_v3.md §4).
 *
 * <p>Calendar axis: {@code day} = days since 2009-04-01, {@code slot = day*2 + (evening?1:0)}.
 * Arrays indexed by day are sized {@code lastDay+1}; entries before {@code firstDay} are null.
 *
 * <p>Fields are public for cheap access from the planner's hot loops. Code outside
 * {@link RulesLoader} and test fixtures must treat them as read-only.
 */
public final class Rules {
    public static final int DAYTIME = 0;
    public static final int EVENING = 1;

    public String schema = "p3r.rules.v3";
    public String packVersion = "0";
    /** Stable identity of the loaded pack: packVersion + "|" + sha256 prefix of the JSON. */
    public String version = "0";
    public int firstDay;
    public int lastDay;
    /** Indexed by day ordinal; null outside [firstDay, lastDay]. */
    public Day[] days = new Day[0];
    /** 22 links in arcana order (0 Fool … 20 Judgement, 21 Aeon). Index == {@link Link#idx}. */
    public Link[] links = new Link[0];
    public Activity[] activities = new Activity[0];
    /** academics, charm, courage — index == {@link Stat#idx}. */
    public Stat[] stats = new Stat[0];
    public Block[] blocks = new Block[0];
    public Rescue[] rescues = new Rescue[0];
    public Map<String, String> lockReasonsZh = new HashMap<>();
    public Policy policy = new Policy();
    public List<Source> sources = new ArrayList<>();
    public List<String> notes = new ArrayList<>();

    public static int slot(int day, boolean evening) { return day * 2 + (evening ? 1 : 0); }
    public static int dayOf(int slot) { return slot >> 1; }
    public static boolean isEvening(int slot) { return (slot & 1) == 1; }

    public int firstSlot() { return slot(firstDay, false); }
    public int lastSlot() { return slot(lastDay, true); }

    public Day day(int d) { return d >= 0 && d < days.length ? days[d] : null; }

    public Link link(String id) {
        for (Link l : links) if (l.id.equals(id)) return l;
        return null;
    }

    public Activity activity(String id) {
        for (Activity a : activities) if (a.id.equals(id)) return a;
        return null;
    }

    public Stat stat(String id) {
        for (Stat s : stats) if (s.id.equals(id)) return s;
        return null;
    }

    /** Lock reason id for a slot, or null when the slot is free. Outside the calendar → "out_of_range". */
    public String lockOf(int slot) {
        Day d = day(dayOf(slot));
        if (d == null) return "out_of_range";
        return isEvening(slot) ? d.eveLock : d.dayLock;
    }

    /** Block active on a day, or null. */
    public Block blockOn(int day) {
        Day d = day(day);
        if (d == null || d.block < 0 || d.block >= blocks.length) return null;
        return blocks[d.block];
    }

    // ---------------------------------------------------------------- nested model types

    public enum DayType { SCHOOL, SUNDAY, HOLIDAY, VACATION, EXAM, STORY }

    public static final class Day {
        public int d;
        /** "MM-DD". */
        public String md;
        /** ISO weekday 1=Mon … 7=Sun. */
        public int wd;
        public DayType type = DayType.SCHOOL;
        /** Lock reason id or null (free). */
        public String dayLock;
        public String eveLock;
        /** 0..7, 4 = full moon. Decorative. */
        public int moon;
        public boolean fullMoon;
        /** Index into {@link Rules#blocks} or -1. */
        public int block = -1;
        /** Tartarus entry possible this evening (ignoring the evening lock, which is checked separately). */
        public boolean tartarus;
        public String noteZh;

        public int month() { return Integer.parseInt(md.substring(0, 2)); }
        public int dayOfMonth() { return Integer.parseInt(md.substring(3, 5)); }
    }

    public enum LinkKind { MANUAL, STORY }

    public static final class Link {
        public int idx;
        public String id;
        public int arcanaNo;
        public String arcanaZh;
        public String personZh;
        public String genderZh;
        /** Selector label, e.g. "皇帝 · 小田桐秀利". Spoiler-safe. */
        public String labelZh;
        public LinkKind kind = LinkKind.MANUAL;
        public boolean evening;
        public String locationZh;
        /** Days with an ordinary-meeting (O) cell. */
        public BitSet avail = new BitSet();
        /** Days with a holiday invitation (D cell); also meeting days. */
        public BitSet invite = new BitSet();
        /** threshold[r] = points needed while at rank r-1 to reach rank r. Length 11. */
        public int[] threshold = new int[11];
        /** eventGain[r] = best-answer points earned in the rank-r scene (count toward r+1). Length 11. */
        public int[] eventGain = new int[11];
        /** Same as eventGain for the romance route, or null. */
        public int[] eventGainRomance;
        public boolean romanceable;
        public Unlock unlock = new Unlock();
        /** Missing-person window during which the link is unavailable; null if none. */
        public Missing missing;
        /** Rank-ups blocked until a day/flag (mid-chain story holds). */
        public List<Hold> holds = new ArrayList<>();
        /** CN guide dialogue fragments per target rank (verbatim; display only). */
        public Map<Integer, List<String>> dialogue = new HashMap<>();
        /** Story links only: storyRankDay[r] = day the story grants rank r, or -1. Length 11. */
        public int[] storyRankDay;
        public String noteZh;
        public String conf;
        public List<String> src = new ArrayList<>();

        /** True if a meeting is possible on this day (O or D cell). */
        public boolean meetsOn(int day) { return avail.get(day) || invite.get(day); }
    }

    public static final class Unlock {
        public int earliestDay;
        /** Stat index → minimum visible Lv (1..6). */
        public Map<Integer, Integer> statLv = new HashMap<>();
        /** Link index → minimum rank. */
        public Map<Integer, Integer> linkRank = new HashMap<>();
        /** Ordered steps; the step with {@code establishes=true} sets rank 1. Empty = auto by date. */
        public List<Step> steps = new ArrayList<>();
        public String conf;
        public List<String> src = new ArrayList<>();
    }

    public enum StepDays { LINK_AVAIL, WEEKDAYS, FIXED, ANY_FREE }

    public static final class Step {
        public String id;
        public String zh;
        /** Short how-to shown in the detail sheet. */
        public String howZh;
        public boolean evening;
        /** Whether the step consumes the slot. Steps that do not are folded into the next one. */
        public boolean consumesSlot = true;
        public StepDays dayRule = StepDays.LINK_AVAIL;
        /** ISO weekdays when dayRule == WEEKDAYS. */
        public int[] weekdays = new int[0];
        /** Explicit days when dayRule == FIXED. */
        public int[] fixedDays = new int[0];
        public int fromDay;
        public int cost;
        /** Minimum calendar days after the previous step (1 = a later day). */
        public int minGapDays;
        public boolean establishes;
        public String locationZh;
    }

    public static final class Hold {
        /** The rank-up INTO this rank is held. */
        public int rank;
        /** Held until (exclusive) this day, or -1 when held until the player reports progress. */
        public int untilDay = -1;
        public String zh;
    }

    public static final class Missing {
        public int fromDay;
        public int toDay;
        public String rescueId;
    }

    public enum ActivityKind { STAT, JOB }

    public static final class Activity {
        public int idx;
        public String id;
        public String zh;
        public String locationZh;
        public ActivityKind kind = ActivityKind.STAT;
        public boolean day;
        public boolean eve;
        /** Bit (wd-1) set for allowed ISO weekdays. */
        public int weekdayMask = 0x7f;
        /** Allowed day types (empty = all). */
        public List<DayType> dayTypes = new ArrayList<>();
        public int fromDay;
        public int toDay = Integer.MAX_VALUE;
        public int cost;
        /** Points per stat index (length 3). */
        public int[] gain = new int[3];
        /** Stat index → minimum Lv required to use the activity. */
        public Map<Integer, Integer> requiresLv = new HashMap<>();
        /** Jobs: pay tiers as {minShiftNumber, pay} pairs, ascending. */
        public int[][] pay = new int[0][];
        /** Use at most once in the whole game (e.g. a challenge menu). */
        public boolean once;
        public String conf;
        public List<String> src = new ArrayList<>();

        public boolean allowsWeekday(int wd) { return (weekdayMask & (1 << (wd - 1))) != 0; }

        /** Minimum pay for the given 1-based shift number. */
        public int payFor(int shiftNumber) {
            int p = 0;
            for (int[] tier : pay) if (shiftNumber >= tier[0]) p = tier[1];
            return p;
        }
    }

    public static final class Stat {
        public int idx;
        public String id;
        public String zh;
        /** thresholds[lv] = cumulative points needed for Lv (index 1..6; [0] unused). */
        public int[] thresholds = new int[7];

        public int levelFor(int pts) {
            int lv = 1;
            for (int l = 2; l <= 6; l++) if (pts >= thresholds[l]) lv = l;
            return lv;
        }
    }

    public static final class Block {
        public int idx;
        public String id;
        public String zh;
        public String floorsZh;
        public int fromDay;
        public int toDay;
        public int top;
    }

    public static final class Rescue {
        public String id;
        public String zh;
        public int fromDay;
        public int toDay;
        /** Link that is lost if this rescue fails, or -1. */
        public int link = -1;
    }

    public static final class Policy {
        public int ordinaryGain = 10;
        public double personaMult = 1.51;
        public double charmMult = 1.51;
    }

    public static final class Source {
        public String id;
        public String url;
        public String kind;
        public String sha256;
        public String note;
    }

    public static <T> List<T> ro(List<T> l) { return Collections.unmodifiableList(l); }
}
