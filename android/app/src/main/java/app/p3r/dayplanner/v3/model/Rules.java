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
    /**
     * Lock reason of both 12/24 slots when the player accepted a Christmas Eve invitation
     * ({@link Profile#christmasLink}). Profile-dependent, so never stored in {@link Day} locks.
     */
    public static final String LOCK_CHRISTMAS = "christmas_date";
    /** Label of {@link #LOCK_CHRISTMAS} when the pack does not declare one. */
    public static final String LOCK_CHRISTMAS_ZH = "平安夜约会";
    /** Month-day of Christmas Eve in {@link Day#md}. */
    public static final String CHRISTMAS_EVE_MD = "12-24";

    public String schema = "p3r.rules.v3";
    public String packVersion = "0";
    /** Game edition the pack describes (developer-facing English, never shown to players). */
    public String game = "";
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
    /** Optional Link Episode tracks (male SEES members etc.), in a stable order. */
    public Episode[] episodes = new Episode[0];
    /** Dating-site note (evening points for an established link), or null when not modelled. */
    public DatingNote datingNote;
    /** 当日提醒 data (static lines, 上课犯困 days, the 保健室 rule, missing-person groups); empty when the pack has none. */
    public Reminders reminders = new Reminders();
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

    /** Index of the link with this id, or -1. */
    public int linkIndex(String id) {
        Link l = link(id);
        return l == null ? -1 : l.idx;
    }

    /** Index of the activity with this id, or -1. */
    public int activityIndex(String id) {
        Activity a = activity(id);
        return a == null ? -1 : a.idx;
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

    /**
     * Chinese label of a lock reason: the pack's {@code lockReasons}, else a built-in label for
     * reasons the engine adds itself ({@link #LOCK_CHRISTMAS} → 「平安夜约会」), else null.
     */
    public String lockReasonZh(String id) {
        if (id == null) return null;
        String zh = lockReasonsZh.get(id);
        if (zh != null && !zh.trim().isEmpty()) return zh;
        return LOCK_CHRISTMAS.equals(id) ? LOCK_CHRISTMAS_ZH : null;
    }

    /** Day ordinal of 12/24 in this pack, or -1 when the calendar has no such day. */
    public int christmasEveDay() {
        for (int d = Math.max(0, firstDay); d <= lastDay && d < days.length; d++) {
            if (days[d] != null && CHRISTMAS_EVE_MD.equals(days[d].md)) return d;
        }
        return -1;
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
        /** Operation full moon (4/9 … 11/3) or the finale on 1/31; estimated full moons only set {@link #moon} = 4. */
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
        /** Roman numeral printed on the arcana card ("0", "I" … "XX"; Aeon shows "XX" too). */
        public String numeral;
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
        /**
         * Missing-person window during which the link is unavailable unless the matching rescue already
         * happened; not rescued by {@code toDay} = link lost. Null if none.
         */
        public Missing missing;
        /** Rank-ups blocked until a day/flag (mid-chain story holds). */
        public List<Hold> holds = new ArrayList<>();
        /** CN guide dialogue fragments per target rank (verbatim; display only). */
        public Map<Integer, List<String>> dialogue = new HashMap<>();
        /**
         * Story links only (null otherwise): storyRankDay[r] = first day on which the story has granted
         * rank r or higher (skipped ranks share the next event's day), -1 if never. Length 11; [0] = -1.
         */
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
        /**
         * Ordered steps; the last step ({@code establishes=true}) sets rank 1. Empty = automatic: rank 1
         * once {@link #earliestDay} is reached and the stat/link requirements hold (Magician on 4/22,
         * Strength when Chariot reaches rank 2).
         */
        public List<Step> steps = new ArrayList<>();
        /** Extra player-facing hint about the opening (Chinese), or null. */
        public String noteZh;
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
        /**
         * Whether the step consumes the slot. A step that does not (e.g. the first two Devil payments)
         * still happens in a free slot of its kind on an allowed day and still respects
         * {@link #minGapDays}, but leaves that slot usable for another action.
         */
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
        /** Player-facing remark (Chinese), e.g. why a conservative gain is used; may be null. */
        public String noteZh;
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

    /**
     * A Link Episode track (P3R replaces the male SEES Social Links with these). Episodes are watched
     * in order; each consumes one slot of its kind on a day whose cell allows that episode number.
     * They give items/characteristics, never Social Link points.
     */
    public static final class Episode {
        public int idx;
        /** Stable id, e.g. "junpei". */
        public String id;
        /** Character name, e.g. "伊织顺平". */
        public String personZh;
        /** Display title, e.g. "羁绊剧情 · 伊织顺平" (the UI appends "（第 2 话）"). */
        public String titleZh;
        public boolean evening;
        /** Number of episodes in the track (1..31). */
        public int count;
        /** dayMask[day] bit (n-1) set → episode n can be watched that day (0 = none). Sized lastDay+1. */
        public int[] dayMask = new int[0];
        public String locationZh;
        /** How to start an episode (where to go / accept the phone invitation), in plain Chinese. */
        public String howZh;
        /** What the track gives overall, in plain Chinese (optional). */
        public String rewardZh;
        public String noteZh;
        /**
         * Stat points (academics, charm, courage — index = {@link Stat#idx}) of episode n at
         * {@code gain[n - 1]}; [count][3], zeros when the episode gives none. Never Social Link points.
         */
        public int[][] gain = new int[0][];
        /** Short reward line of episode n at {@code partRewardZh[n - 1]} (e.g. "魅力 +2"), or null. */
        public String[] partRewardZh = new String[0];
        public String conf;
        public List<String> src = new ArrayList<>();

        public boolean allows(int day, int episodeNumber) {
            return day >= 0 && day < dayMask.length && episodeNumber >= 1 && (dayMask[day] & (1 << (episodeNumber - 1))) != 0;
        }

        /** Stat points of episode n (length 3), or zeros when n is out of range. */
        public int[] gainOf(int episodeNumber) {
            return episodeNumber >= 1 && episodeNumber <= gain.length ? gain[episodeNumber - 1] : new int[3];
        }

        /** Reward line of episode n, or null. */
        public String rewardOf(int episodeNumber) {
            return episodeNumber >= 1 && episodeNumber <= partRewardZh.length ? partRewardZh[episodeNumber - 1] : null;
        }

        /** First day on which episode n can be watched, or -1. */
        public int firstDay(int episodeNumber) {
            for (int d = 0; d < dayMask.length; d++) if (allows(d, episodeNumber)) return d;
            return -1;
        }

        /** Last day on which episode n can be watched, or -1. */
        public int lastDay(int episodeNumber) {
            for (int d = dayMask.length - 1; d >= 0; d--) if (allows(d, episodeNumber)) return d;
            return -1;
        }
    }

    /**
     * Dating-site note: buy at the club and use on the dorm PC in the same evening to add points to
     * one established, non-MAX link without meeting them. Never ranks the link up by itself.
     */
    public static final class DatingNote {
        /** Sentinel for {@link #maxUses}: the note can be bought and used any number of times. */
        public static final int UNLIMITED = Integer.MAX_VALUE;

        public String zh;
        public String howZh;
        public String locationZh;
        /** Player-facing remark (Chinese): who can be chosen, that it never ranks up by itself; may be null. */
        public String noteZh;
        public int fromDay;
        public int cost;
        /** Points added (estimate; multipliers are NOT applied). */
        public int gain;
        /**
         * How many times the note can be used in one playthrough; {@link #UNLIMITED} when the sources say it
         * can be bought again (each use still takes an evening). Always >= 1.
         */
        public int maxUses = 1;
        /** Stat index → minimum Lv needed to get into the club at night (Courage Lv2). */
        public Map<Integer, Integer> requiresLv = new HashMap<>();
        public String conf;
        public List<String> src = new ArrayList<>();

        public boolean unlimited() { return maxUses == UNLIMITED; }
    }

    /**
     * One static 当日提醒 line of the pack ({@code reminders.items}): something slot-free to answer or do on a
     * game day. The text is final Chinese; the engine puts it on a plan when its day is in the plan and its stat
     * requirement is met (engine Reminders, docs/DESIGN_v3.md §17.1).
     */
    public static final class Reminder {
        /** Stable id, e.g. "class.07-08" or "request.06-06.deadline". */
        public String id;
        /** class | exam | tv | request | fragment | trip | feature. */
        public String kind;
        /** Chip label, e.g. "课堂提问". */
        public String labelZh;
        /** Line for the card and the 今天 page. */
        public String zh;
        /** Small-widget form (at most 16 characters). */
        public String shortZh;
        /** Class/exam only: the answer exactly as the zh guide writes it; otherwise null. */
        public String answerZh;
        /** Longer text for the day sheet, or null. */
        public String detailZh;
        /** What it gives, e.g. "魅力 +2", or null. */
        public String gainZh;
        public String conf;
        public int day;
        /** Deadline day, or -1. With {@link #firstEligible}: the last day to look at (-1 = the end of the calendar). */
        public int untilDay = -1;
        /** Class only: the answer's position 1..4 in the zh guide; 0 when unknown. */
        public int option;
        /** 1 nice to know, 2 a deadline coming up, 3 missable for good. */
        public int priority = 1;
        /** Yen it costs (TV shopping), 0 otherwise. */
        public int costYen;
        public boolean evening;
        /**
         * Shown on the first slot of its kind from {@link #day} (to {@link #untilDay}) on whose forecast meets
         * {@link #requiresLv}, instead of on {@link #day} itself.
         */
        public boolean firstEligible;
        /** Stat index → minimum visible Lv (1..6) the forecast must reach; empty = none. */
        public Map<Integer, Integer> requiresLv = new HashMap<>();
        public List<String> src = new ArrayList<>();
    }

    /** Missing people who went missing on the same day with the same limit ({@code reminders.rescueGroups}). */
    public static final class RescueGroup {
        /** "rescue.MM-DD" of {@link #fromDay}. */
        public String id;
        public int fromDay;
        /** Last day they can be rescued (whether the day itself counts is not verified). */
        public int toDay;
        /** Number of people in the group (≥ 1). */
        public int count;
        /** Ids of {@link Rules#rescues} in this group (the people the engine models), possibly empty. */
        public List<String> rescueIds = new ArrayList<>();
        /** What failing this group's rescue locks (「救不出来的话，伊莉莎白的第 97 号委托就做不了。」), or null. */
        public String locksZh;
    }

    /** The {@code reminders} section of the pack; the engine words 上课犯困, 保健室 and 失踪者 against the plan. */
    public static final class Reminders {
        /** Static lines, sorted by (day, slot, id). */
        public List<Reminder> items = new ArrayList<>();
        /** School days on which class makes the protagonist drowsy (stay awake or close the eyes), ascending. */
        public int[] dozeDays = new int[0];
        /** Academics points for staying awake, Courage points for closing the eyes. */
        public int dozeAcademics = 2;
        public int dozeCourage = 2;
        /** Courage points of the nurse's office the school day after a Tartarus or full-moon night; 0 = not modelled. */
        public int nurseCourage;
        /**
         * Days whose evening the story spends in Tartarus (4/20, a prologue lock): the nurse's office
         * counts them like a Tartarus night. Ascending.
         */
        public int[] nurseStoryDays = new int[0];
        public List<RescueGroup> rescueGroups = new ArrayList<>();

        public boolean isEmpty() {
            return items.isEmpty() && dozeDays.length == 0 && nurseCourage == 0 && rescueGroups.isEmpty();
        }
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
        /**
         * Edition the source describes ({@code sources[].edition}: reload, game_data_reload,
         * cn_guide_reload, reload+portable or mixed; rules/build/REPORT.md §1), or null in packs
         * built before the field existed.
         */
        public String edition;
    }

    public static <T> List<T> ro(List<T> l) { return Collections.unmodifiableList(l); }
}
