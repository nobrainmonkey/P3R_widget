package app.p3r.dayplanner.v3.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses {@code assets/p3r/p3r_rules.json} (schema {@code p3r.rules.v3}, docs/DESIGN_v3.md §4) into
 * an immutable-by-convention {@link Rules} model.
 *
 * <p>Pure Java + org.json, no Android framework classes: it runs in plain JVM tests and on any
 * background thread. Validation is strict and fails fast with an {@link IllegalArgumentException}
 * whose English message names the offending field (for example {@code links[15].unlock.steps[2].slot}),
 * so a broken pack is caught by tests instead of producing a silently wrong plan.
 *
 * <p>The loader only checks structure and internal consistency (contiguous days, 22 links in
 * arcana order, array lengths, indices and ids in range, meeting and Link Episode days on unlocked
 * slots, the optional {@code episodes} and {@code datingNote} sections) and the Reload-only source policy
 * (every source declares an {@link #EDITIONS edition}; every {@code src} list names a Reload source). Game
 * facts are verified by the builder ({@code rules/build/build_p3r_rules.py}) and by the unit tests.
 */
public final class RulesLoader {
    /** The only schema this loader accepts. */
    public static final String SCHEMA = "p3r.rules.v3";

    /** Canonical link ids in arcana order (0 Fool … 20 Judgement, 21 Aeon); index == {@link Rules.Link#idx}. */
    public static final List<String> LINK_IDS = Collections.unmodifiableList(Arrays.asList(
            "fool", "magician", "priestess", "empress", "emperor", "hierophant", "lovers", "chariot",
            "justice", "hermit", "fortune", "strength", "hanged_man", "death", "temperance", "devil",
            "tower", "star", "moon", "sun", "judgement", "aeon"));

    /** Canonical stat ids; index == {@link Rules.Stat#idx}. */
    public static final List<String> STAT_IDS = Collections.unmodifiableList(Arrays.asList("academics", "charm", "courage"));

    /** Allowed confidence labels for rule records. */
    public static final List<String> CONF_VALUES = Collections.unmodifiableList(Arrays.asList(
            "game_data", "verified", "single", "estimate", "conflict"));

    /**
     * Allowed {@code sources[].edition} values (rules/build/REPORT.md §1.1). Only {@link #RELOAD_EDITIONS}
     * describe Persona 3 Reload alone; the other two are multi-edition sources that may only corroborate.
     */
    public static final List<String> EDITIONS = Collections.unmodifiableList(Arrays.asList(
            "reload", "game_data_reload", "cn_guide_reload", "reload+portable", "mixed"));

    /** Editions that count as Reload sources: every record's {@code src} must name at least one of them. */
    public static final List<String> RELOAD_EDITIONS = Collections.unmodifiableList(Arrays.asList(
            "reload", "game_data_reload", "cn_guide_reload"));

    /** Number of hex digits of the SHA-256 that identify a pack in {@link Rules#version}. */
    static final int VERSION_HASH_DIGITS = 12;

    private RulesLoader() {}

    /**
     * Parse and validate the pack.
     *
     * @param json the complete JSON text of the pack
     * @return the model; {@link Rules#version} is {@code packVersion + "|" + first 12 hex digits of
     *     the SHA-256 of the UTF-8 text}
     * @throws IllegalArgumentException with a precise English message when the text is not a valid pack
     */
    public static Rules parse(String json) {
        if (json == null) throw new IllegalArgumentException("Rules JSON is null");
        final JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Rules JSON is not a JSON object: " + e.getMessage(), e);
        }
        final Rules rules;
        try {
            rules = new Parser(root).parse();
        } catch (JSONException e) {
            throw new IllegalArgumentException("Rules JSON has a missing or mistyped field: " + e.getMessage(), e);
        }
        rules.version = rules.packVersion + "|" + sha256Hex(json).substring(0, VERSION_HASH_DIGITS);
        return rules;
    }

    /** Lower-case hex SHA-256 of the UTF-8 bytes of {@code text}. */
    static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** "MM-DD" of a day ordinal (days since 2009-04-01; January–March fall in 2010). */
    static String monthDay(int day) {
        int[] monthLengths = {30, 31, 30, 31, 31, 30, 31, 30, 31, 31, 28, 31}; // April 2009 … March 2010
        int month = 4;
        int rest = day;
        for (int len : monthLengths) {
            if (rest < len) break;
            rest -= len;
            month = month == 12 ? 1 : month + 1;
        }
        return String.format(Locale.ROOT, "%02d-%02d", month, rest + 1);
    }

    /** ISO weekday (1 = Monday … 7 = Sunday) of a day ordinal; 2009-04-01 was a Wednesday. */
    static int isoWeekday(int day) {
        return ((day + 2) % 7) + 1;
    }

    // ------------------------------------------------------------------------------------ parser

    /** One parse run; holds the cross-reference tables built along the way. */
    private static final class Parser {
        private final JSONObject root;
        private final Rules rules = new Rules();
        private final Set<String> sourceIds = new HashSet<>();
        /** Ids of the declared sources whose edition is a Reload edition. */
        private final Set<String> reloadSourceIds = new HashSet<>();
        private final Map<String, Integer> statIndex = new HashMap<>();
        private final Map<String, Integer> linkIndex = new HashMap<>();
        private final Map<String, Integer> rescueIndex = new HashMap<>();

        Parser(JSONObject root) {
            this.root = root;
        }

        Rules parse() throws JSONException {
            String schema = reqString(root, "schema", "root");
            if (!SCHEMA.equals(schema)) {
                throw fail("Unsupported rules schema '" + schema + "' (expected " + SCHEMA + ")");
            }
            rules.schema = schema;
            rules.packVersion = reqString(root, "packVersion", "root");
            String game = optString(root, "game", "root");
            rules.game = game == null ? "" : game;
            for (int i = 0; i < LINK_IDS.size(); i++) linkIndex.put(LINK_IDS.get(i), i);

            parseSources();
            parseStats();
            parseLockReasons();
            rules.firstDay = reqInt(root, "firstDay", "root");
            rules.lastDay = reqInt(root, "lastDay", "root");
            if (rules.firstDay < 0 || rules.lastDay < rules.firstDay) {
                throw fail("root.firstDay/lastDay must satisfy 0 <= firstDay <= lastDay, got "
                        + rules.firstDay + "/" + rules.lastDay);
            }
            JSONObject tartarus = reqObject(root, "tartarus", "root");
            parseBlocks(tartarus);
            parseDays();
            parseRescues(tartarus);
            parseLinks();
            parseActivities();
            parseEpisodes();
            parseDatingNote();
            parsePolicy();
            parseNotes();
            return rules;
        }

        // -------------------------------------------------------------- sections

        private void parseSources() throws JSONException {
            JSONArray arr = reqArray(root, "sources", "root");
            for (int i = 0; i < arr.length(); i++) {
                String where = "sources[" + i + "]";
                JSONObject o = reqObject(arr, i, where);
                Rules.Source s = new Rules.Source();
                s.id = reqString(o, "id", where);
                if (!sourceIds.add(s.id)) throw fail(where + ".id '" + s.id + "' is duplicated");
                s.url = optString(o, "url", where);
                s.kind = optString(o, "kind", where);
                s.sha256 = optString(o, "sha256", where);
                s.edition = optString(o, "edition", where);
                s.note = optString(o, "note", where);
                String edition = reqString(o, "edition", where);
                if (!EDITIONS.contains(edition)) {
                    throw fail(where + ".edition '" + edition + "' must be one of " + EDITIONS);
                }
                if (RELOAD_EDITIONS.contains(edition)) reloadSourceIds.add(s.id);
                rules.sources.add(s);
            }
            if (rules.sources.isEmpty()) throw fail("root.sources must not be empty");
        }

        private void parseStats() throws JSONException {
            JSONArray arr = reqArray(root, "stats", "root");
            if (arr.length() != STAT_IDS.size()) {
                throw fail("root.stats must have " + STAT_IDS.size() + " entries, found " + arr.length());
            }
            rules.stats = new Rules.Stat[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                String where = "stats[" + i + "]";
                JSONObject o = reqObject(arr, i, where);
                Rules.Stat s = new Rules.Stat();
                s.idx = i;
                s.id = reqString(o, "id", where);
                if (!STAT_IDS.get(i).equals(s.id)) {
                    throw fail(where + ".id is '" + s.id + "', expected '" + STAT_IDS.get(i) + "'");
                }
                s.zh = reqString(o, "zh", where);
                s.thresholds = intArray(o, "thresholds", where, 7);
                for (int lv = 2; lv < 7; lv++) {
                    if (s.thresholds[lv] < s.thresholds[lv - 1]) {
                        throw fail(where + ".thresholds must be non-decreasing (Lv" + lv + ")");
                    }
                }
                checkConf(o, where);
                sources(o, where);
                rules.stats[i] = s;
                statIndex.put(s.id, i);
            }
        }

        private void parseLockReasons() throws JSONException {
            JSONObject o = reqObject(root, "lockReasons", "root");
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                String where = "lockReasons." + id;
                rules.lockReasonsZh.put(id, reqString(reqObject(o, id, where), "zh", where));
            }
        }

        private void parseBlocks(JSONObject tartarus) throws JSONException {
            JSONArray arr = reqArray(tartarus, "blocks", "tartarus");
            rules.blocks = new Rules.Block[arr.length()];
            Set<String> ids = new HashSet<>();
            int previousEnd = -1;
            for (int i = 0; i < arr.length(); i++) {
                String where = "tartarus.blocks[" + i + "]";
                JSONObject o = reqObject(arr, i, where);
                Rules.Block b = new Rules.Block();
                b.idx = i;
                b.id = reqString(o, "id", where);
                if (!ids.add(b.id)) throw fail(where + ".id '" + b.id + "' is duplicated");
                b.zh = reqString(o, "zh", where);
                b.floorsZh = reqString(o, "floorsZh", where);
                b.fromDay = dayInRange(o, "fromDay", where);
                b.toDay = dayInRange(o, "toDay", where);
                if (b.toDay < b.fromDay || b.fromDay <= previousEnd) {
                    throw fail(where + " must cover an ascending, non-overlapping day range, got "
                            + b.fromDay + ".." + b.toDay);
                }
                previousEnd = b.toDay;
                b.top = reqInt(o, "top", where);
                if (b.top <= 0) throw fail(where + ".top must be positive");
                checkConf(o, where);
                sources(o, where);
                rules.blocks[i] = b;
            }
        }

        private void parseDays() throws JSONException {
            JSONArray arr = reqArray(root, "days", "root");
            int expected = rules.lastDay - rules.firstDay + 1;
            if (arr.length() != expected) {
                throw fail("root.days must have " + expected + " entries (firstDay " + rules.firstDay
                        + " .. lastDay " + rules.lastDay + "), found " + arr.length());
            }
            rules.days = new Rules.Day[rules.lastDay + 1];
            for (int i = 0; i < arr.length(); i++) {
                String where = "days[" + i + "]";
                JSONObject o = reqObject(arr, i, where);
                Rules.Day day = new Rules.Day();
                day.d = reqInt(o, "d", where);
                int want = rules.firstDay + i;
                if (day.d != want) {
                    throw fail(where + ".d is " + day.d + " but must be " + want
                            + " (days must be contiguous from firstDay " + rules.firstDay + ")");
                }
                day.md = reqString(o, "md", where);
                if (!day.md.equals(monthDay(day.d))) {
                    throw fail(where + ".md is '" + day.md + "' but day " + day.d + " is " + monthDay(day.d));
                }
                day.wd = reqInt(o, "wd", where);
                if (day.wd != isoWeekday(day.d)) {
                    throw fail(where + ".wd is " + day.wd + " but " + day.md + " is ISO weekday " + isoWeekday(day.d));
                }
                day.type = dayType(reqString(o, "type", where), where + ".type");
                day.dayLock = lockId(o, "dayLock", where);
                day.eveLock = lockId(o, "eveLock", where);
                day.moon = reqInt(o, "moon", where);
                if (day.moon < 0 || day.moon > 7) throw fail(where + ".moon must be 0..7, got " + day.moon);
                day.fullMoon = reqBool(o, "fullMoon", where);
                day.block = reqInt(o, "block", where);
                if (day.block < -1 || day.block >= rules.blocks.length) {
                    throw fail(where + ".block " + day.block + " is out of range (-1.." + (rules.blocks.length - 1) + ")");
                }
                if (day.block >= 0) {
                    Rules.Block b = rules.blocks[day.block];
                    if (day.d < b.fromDay || day.d > b.toDay) {
                        throw fail(where + ".block " + day.block + " (" + b.id + ") does not cover day " + day.d);
                    }
                }
                day.tartarus = reqBool(o, "tartarus", where);
                if (day.tartarus && (day.block < 0 || day.eveLock != null)) {
                    throw fail(where + ".tartarus is true but the day has no block or a locked evening");
                }
                day.noteZh = optString(o, "noteZh", where);
                checkConf(o, where);
                sources(o, where);
                rules.days[day.d] = day;
            }
        }

        private void parseRescues(JSONObject tartarus) throws JSONException {
            JSONArray arr = reqArray(tartarus, "rescues", "tartarus");
            if (arr.length() > 64) throw fail("tartarus.rescues has more than 64 entries (SimState uses a long bitset)");
            rules.rescues = new Rules.Rescue[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                String where = "tartarus.rescues[" + i + "]";
                JSONObject o = reqObject(arr, i, where);
                Rules.Rescue r = new Rules.Rescue();
                r.id = reqString(o, "id", where);
                if (rescueIndex.put(r.id, i) != null) throw fail(where + ".id '" + r.id + "' is duplicated");
                r.zh = reqString(o, "zh", where);
                r.fromDay = dayInRange(o, "fromDay", where);
                r.toDay = dayInRange(o, "toDay", where);
                if (r.toDay < r.fromDay) throw fail(where + " ends before it starts");
                String link = optString(o, "link", where);
                r.link = link == null ? -1 : linkRef(link, where + ".link");
                checkConf(o, where);
                sources(o, where);
                rules.rescues[i] = r;
            }
        }

        private void parseLinks() throws JSONException {
            JSONArray arr = reqArray(root, "links", "root");
            if (arr.length() != LINK_IDS.size()) {
                throw fail("root.links must have " + LINK_IDS.size() + " entries in arcana order, found " + arr.length());
            }
            rules.links = new Rules.Link[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                rules.links[i] = parseLink(reqObject(arr, i, "links[" + i + "]"), i);
            }
        }

        private Rules.Link parseLink(JSONObject o, int i) throws JSONException {
            String where = "links[" + i + "]";
            Rules.Link l = new Rules.Link();
            l.idx = i;
            l.id = reqString(o, "id", where);
            if (!LINK_IDS.get(i).equals(l.id)) {
                throw fail(where + ".id is '" + l.id + "', expected '" + LINK_IDS.get(i) + "' (links must be in arcana order)");
            }
            l.arcanaNo = reqInt(o, "no", where);
            if (l.arcanaNo != i) throw fail(where + ".no is " + l.arcanaNo + ", expected " + i);
            l.numeral = reqString(o, "numeral", where);
            l.arcanaZh = reqString(o, "arcanaZh", where);
            l.personZh = reqString(o, "personZh", where);
            l.genderZh = reqString(o, "genderZh", where);
            l.labelZh = reqString(o, "labelZh", where);
            String kind = reqString(o, "kind", where);
            if ("manual".equals(kind)) {
                l.kind = Rules.LinkKind.MANUAL;
            } else if ("story".equals(kind)) {
                l.kind = Rules.LinkKind.STORY;
            } else {
                throw fail(where + ".kind must be manual|story, got '" + kind + "'");
            }
            String slot = optString(o, "slot", where);
            if (slot == null && l.kind == Rules.LinkKind.MANUAL) throw fail(where + ".slot is required for manual links");
            l.evening = slot != null && isEvening(slot, where + ".slot");
            l.locationZh = optString(o, "locationZh", where);
            if (l.locationZh == null && l.kind == Rules.LinkKind.MANUAL) {
                throw fail(where + ".locationZh is required for manual links");
            }
            l.avail = dayBits(o, "avail", where);
            l.invite = dayBits(o, "invite", where);
            String slotLockKey = l.evening ? "evening" : "daytime";
            for (int d = l.avail.nextSetBit(0); d >= 0; d = l.avail.nextSetBit(d + 1)) {
                String lock = l.evening ? rules.days[d].eveLock : rules.days[d].dayLock;
                if (lock != null) {
                    throw fail(where + ".avail contains day " + d + " (" + rules.days[d].md + ") whose "
                            + slotLockKey + " is locked (" + lock + ")");
                }
            }
            for (int d = l.invite.nextSetBit(0); d >= 0; d = l.invite.nextSetBit(d + 1)) {
                if (rules.days[d].dayLock != null) {
                    throw fail(where + ".invite contains day " + d + " (" + rules.days[d].md
                            + ") whose daytime is locked (" + rules.days[d].dayLock + ")");
                }
            }
            l.threshold = intArray(o, "threshold", where, 11);
            l.eventGain = intArray(o, "eventGain", where, 11);
            for (int r = 0; r <= 10; r++) {
                if (l.threshold[r] < 0 || l.eventGain[r] < 0) throw fail(where + " has a negative threshold or eventGain");
            }
            l.eventGainRomance = o.isNull("eventGainRomance") ? null : intArray(o, "eventGainRomance", where, 11);
            l.romanceable = reqBool(o, "romanceable", where);
            l.unlock = parseUnlock(reqObject(o, "unlock", where), where + ".unlock", i);
            if (!o.isNull("missing")) l.missing = parseMissing(reqObject(o, "missing", where), where + ".missing");
            parseHolds(o, l, where);
            parseDialogue(o, l, where);
            parseStoryRanks(o, l, where);
            l.noteZh = optString(o, "noteZh", where);
            l.conf = checkConf(o, where);
            l.src = sources(o, where);
            return l;
        }

        private Rules.Unlock parseUnlock(JSONObject o, String where, int self) throws JSONException {
            Rules.Unlock u = new Rules.Unlock();
            u.earliestDay = dayInRange(o, "earliestDay", where);
            JSONObject stat = reqObject(o, "stat", where);
            Iterator<String> keys = stat.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                Integer idx = statIndex.get(id);
                if (idx == null) throw fail(where + ".stat references unknown stat '" + id + "'");
                u.statLv.put(idx, intInRange(stat, id, where + ".stat", 1, 6));
            }
            JSONObject link = reqObject(o, "link", where);
            keys = link.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                int idx = linkRef(id, where + ".link");
                if (idx == self) throw fail(where + ".link must not reference the link itself");
                u.linkRank.put(idx, intInRange(link, id, where + ".link", 1, 10));
            }
            JSONArray steps = reqArray(o, "steps", where);
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < steps.length(); i++) {
                Rules.Step s = parseStep(reqObject(steps, i, where + ".steps[" + i + "]"), where + ".steps[" + i + "]");
                if (!ids.add(s.id)) throw fail(where + ".steps[" + i + "].id '" + s.id + "' is duplicated");
                boolean last = i == steps.length() - 1;
                if (s.establishes != last) {
                    throw fail(where + ".steps: exactly the last step must establish the link (step " + i + ")");
                }
                u.steps.add(s);
            }
            u.noteZh = optString(o, "noteZh", where);
            u.conf = checkConf(o, where);
            u.src = sources(o, where);
            return u;
        }

        private Rules.Step parseStep(JSONObject o, String where) throws JSONException {
            Rules.Step s = new Rules.Step();
            s.id = reqString(o, "id", where);
            s.zh = reqString(o, "zh", where);
            s.howZh = optString(o, "howZh", where);
            s.locationZh = optString(o, "locationZh", where);
            s.evening = isEvening(reqString(o, "slot", where), where + ".slot");
            s.consumesSlot = o.isNull("consumesSlot") || reqBool(o, "consumesSlot", where);
            String days = reqString(o, "days", where);
            switch (days) {
                case "avail":
                    s.dayRule = Rules.StepDays.LINK_AVAIL;
                    break;
                case "any":
                    s.dayRule = Rules.StepDays.ANY_FREE;
                    break;
                case "weekdays":
                    s.dayRule = Rules.StepDays.WEEKDAYS;
                    s.weekdays = weekdays(o, "weekdays", where);
                    break;
                case "fixed":
                    s.dayRule = Rules.StepDays.FIXED;
                    s.fixedDays = sortedDays(o, "fixedDays", where);
                    if (s.fixedDays.length == 0) throw fail(where + ".fixedDays must not be empty");
                    break;
                default:
                    throw fail(where + ".days must be avail|any|weekdays|fixed, got '" + days + "'");
            }
            s.fromDay = dayInRange(o, "fromDay", where);
            s.cost = nonNegative(o, "cost", where);
            s.minGapDays = nonNegative(o, "minGapDays", where);
            s.establishes = reqBool(o, "establishes", where);
            return s;
        }

        private Rules.Missing parseMissing(JSONObject o, String where) throws JSONException {
            Rules.Missing m = new Rules.Missing();
            m.fromDay = dayInRange(o, "fromDay", where);
            m.toDay = dayInRange(o, "toDay", where);
            if (m.toDay < m.fromDay) throw fail(where + " ends before it starts");
            m.rescueId = reqString(o, "rescueId", where);
            if (!rescueIndex.containsKey(m.rescueId)) throw fail(where + ".rescueId '" + m.rescueId + "' is not a known rescue");
            checkConf(o, where);
            sources(o, where);
            return m;
        }

        private void parseHolds(JSONObject o, Rules.Link l, String where) throws JSONException {
            JSONArray arr = reqArray(o, "holds", where);
            for (int i = 0; i < arr.length(); i++) {
                String w = where + ".holds[" + i + "]";
                JSONObject h = reqObject(arr, i, w);
                Rules.Hold hold = new Rules.Hold();
                hold.rank = intInRange(h, "rank", w, 1, 10);
                hold.untilDay = reqInt(h, "untilDay", w);
                if (hold.untilDay != -1 && (hold.untilDay < rules.firstDay || hold.untilDay > rules.lastDay)) {
                    throw fail(w + ".untilDay " + hold.untilDay + " must be -1 or a calendar day");
                }
                hold.zh = reqString(h, "zh", w);
                l.holds.add(hold);
            }
        }

        private void parseDialogue(JSONObject o, Rules.Link l, String where) throws JSONException {
            JSONObject dialogue = reqObject(o, "dialogue", where);
            Iterator<String> keys = dialogue.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                final int rank;
                try {
                    rank = Integer.parseInt(key);
                } catch (NumberFormatException e) {
                    throw fail(where + ".dialogue key '" + key + "' is not a rank number");
                }
                if (rank < 1 || rank > 10) throw fail(where + ".dialogue rank " + rank + " is out of range 1..10");
                JSONArray lines = reqArray(dialogue, key, where + ".dialogue");
                List<String> texts = new ArrayList<>();
                for (int i = 0; i < lines.length(); i++) {
                    Object v = lines.get(i);
                    if (!(v instanceof String) || ((String) v).isEmpty()) {
                        throw fail(where + ".dialogue." + key + "[" + i + "] must be a non-empty string");
                    }
                    texts.add((String) v);
                }
                l.dialogue.put(rank, Collections.unmodifiableList(texts));
            }
        }

        private void parseStoryRanks(JSONObject o, Rules.Link l, String where) throws JSONException {
            if (l.kind == Rules.LinkKind.MANUAL) {
                if (!o.isNull("storyRanks")) throw fail(where + ".storyRanks must be null for manual links");
                return;
            }
            JSONArray arr = reqArray(o, "storyRanks", where);
            if (arr.length() == 0) throw fail(where + ".storyRanks must not be empty for story links");
            int[] firstDay = new int[11];
            Arrays.fill(firstDay, -1);
            for (int i = 0; i < arr.length(); i++) {
                String w = where + ".storyRanks[" + i + "]";
                JSONObject e = reqObject(arr, i, w);
                int rank = intInRange(e, "rank", w, 1, 10);
                int day = dayInRange(e, "day", w);
                if (firstDay[rank] < 0 || day < firstDay[rank]) firstDay[rank] = day;
            }
            // storyRankDay[r] = first day on which rank >= r (skipped ranks take the next event's day).
            l.storyRankDay = new int[11];
            Arrays.fill(l.storyRankDay, -1);
            int best = -1;
            for (int r = 10; r >= 1; r--) {
                if (firstDay[r] >= 0 && (best < 0 || firstDay[r] < best)) best = firstDay[r];
                l.storyRankDay[r] = best;
            }
            for (int r = 2; r <= 10; r++) {
                if (l.storyRankDay[r] >= 0 && l.storyRankDay[r - 1] > l.storyRankDay[r]) {
                    throw fail(where + ".storyRanks are not chronological at rank " + r);
                }
            }
        }

        private void parseActivities() throws JSONException {
            JSONArray arr = reqArray(root, "activities", "root");
            rules.activities = new Rules.Activity[arr.length()];
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                String where = "activities[" + i + "]";
                JSONObject o = reqObject(arr, i, where);
                Rules.Activity a = new Rules.Activity();
                a.idx = i;
                a.id = reqString(o, "id", where);
                if (!ids.add(a.id)) throw fail(where + ".id '" + a.id + "' is duplicated");
                a.zh = reqString(o, "zh", where);
                a.locationZh = reqString(o, "locationZh", where);
                String kind = reqString(o, "kind", where);
                if ("stat".equals(kind)) {
                    a.kind = Rules.ActivityKind.STAT;
                } else if ("job".equals(kind)) {
                    a.kind = Rules.ActivityKind.JOB;
                } else {
                    throw fail(where + ".kind must be stat|job, got '" + kind + "'");
                }
                JSONArray slots = reqArray(o, "slots", where);
                if (slots.length() == 0) throw fail(where + ".slots must not be empty");
                for (int s = 0; s < slots.length(); s++) {
                    if (isEvening(slots.getString(s), where + ".slots[" + s + "]")) a.eve = true; else a.day = true;
                }
                a.weekdayMask = 0;
                for (int wd : weekdays(o, "weekdays", where)) a.weekdayMask |= 1 << (wd - 1);
                JSONArray types = reqArray(o, "dayTypes", where);
                for (int t = 0; t < types.length(); t++) {
                    a.dayTypes.add(dayType(types.getString(t), where + ".dayTypes[" + t + "]"));
                }
                a.fromDay = dayInRange(o, "fromDay", where);
                a.toDay = dayInRange(o, "toDay", where);
                if (a.toDay < a.fromDay) throw fail(where + " ends before it starts");
                a.cost = nonNegative(o, "cost", where);
                JSONObject gain = reqObject(o, "gain", where);
                Iterator<String> keys = gain.keys();
                while (keys.hasNext()) {
                    String id = keys.next();
                    Integer idx = statIndex.get(id);
                    if (idx == null) throw fail(where + ".gain references unknown stat '" + id + "'");
                    a.gain[idx] = nonNegative(gain, id, where + ".gain");
                }
                JSONObject requires = reqObject(o, "requires", where);
                keys = requires.keys();
                while (keys.hasNext()) {
                    String id = keys.next();
                    Integer idx = statIndex.get(id);
                    if (idx == null) throw fail(where + ".requires references unknown stat '" + id + "'");
                    a.requiresLv.put(idx, intInRange(requires, id, where + ".requires", 1, 6));
                }
                a.pay = payTiers(o, where);
                if (a.kind == Rules.ActivityKind.JOB && a.pay.length == 0) throw fail(where + ".pay must not be empty for a job");
                a.once = reqBool(o, "once", where);
                a.noteZh = optString(o, "noteZh", where);
                a.conf = checkConf(o, where);
                a.src = sources(o, where);
                rules.activities[i] = a;
            }
        }

        /** Optional Link Episode tracks ({@code episodes}); absent = none. */
        private void parseEpisodes() throws JSONException {
            if (root.isNull("episodes")) return;
            JSONArray arr = reqArray(root, "episodes", "root");
            rules.episodes = new Rules.Episode[arr.length()];
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                String where = "episodes[" + i + "]";
                JSONObject o = reqObject(arr, i, where);
                Rules.Episode e = new Rules.Episode();
                e.idx = i;
                e.id = reqString(o, "id", where);
                if (!e.id.matches("[a-z_]+")) throw fail(where + ".id '" + e.id + "' must be lower-case letters or _");
                if (!ids.add(e.id)) throw fail(where + ".id '" + e.id + "' is duplicated");
                e.personZh = reqString(o, "personZh", where);
                e.titleZh = reqString(o, "titleZh", where);
                e.locationZh = reqString(o, "locationZh", where);
                e.howZh = optString(o, "howZh", where);
                e.rewardZh = optString(o, "rewardZh", where);
                e.noteZh = optString(o, "noteZh", where);
                e.evening = reqBool(o, "evening", where);
                e.count = intInRange(o, "count", where, 1, 31);
                JSONArray parts = reqArray(o, "parts", where);
                if (parts.length() != e.count) {
                    throw fail(where + ".parts must have count = " + e.count + " entries, found " + parts.length());
                }
                e.dayMask = new int[rules.lastDay + 1];
                e.gain = new int[e.count][3];
                e.partRewardZh = new String[e.count];
                for (int n = 1; n <= e.count; n++) {
                    String w = where + ".parts[" + (n - 1) + "]";
                    JSONObject part = reqObject(parts, n - 1, w);
                    int no = reqInt(part, "no", w);
                    if (no != n) throw fail(w + ".no is " + no + ", expected " + n + " (episodes must be in order)");
                    int[] days = sortedDays(part, "days", w);
                    if (days.length == 0) throw fail(w + ".days must not be empty");
                    for (int d : days) {
                        String lock = e.evening ? rules.days[d].eveLock : rules.days[d].dayLock;
                        if (lock != null) {
                            throw fail(w + ".days contains day " + d + " (" + rules.days[d].md + ") whose "
                                    + (e.evening ? "evening" : "daytime") + " is locked (" + lock + ")");
                        }
                        e.dayMask[d] |= 1 << (n - 1);
                    }
                    JSONObject gain = reqObject(part, "gain", w);
                    Iterator<String> keys = gain.keys();
                    while (keys.hasNext()) {
                        String id = keys.next();
                        Integer idx = statIndex.get(id);
                        if (idx == null) throw fail(w + ".gain references unknown stat '" + id + "'");
                        e.gain[n - 1][idx] = nonNegative(gain, id, w + ".gain");
                    }
                    e.partRewardZh[n - 1] = optString(part, "rewardZh", w);
                }
                e.conf = checkConf(o, where);
                e.src = sources(o, where);
                rules.episodes[i] = e;
            }
        }

        /** Optional dating-site note ({@code datingNote}); absent or null = not modelled. */
        private void parseDatingNote() throws JSONException {
            if (root.isNull("datingNote")) return;
            String where = "datingNote";
            JSONObject o = reqObject(root, "datingNote", "root");
            Rules.DatingNote n = new Rules.DatingNote();
            n.zh = reqString(o, "zh", where);
            n.howZh = reqString(o, "howZh", where);
            n.locationZh = reqString(o, "locationZh", where);
            n.noteZh = optString(o, "noteZh", where);
            if (!isEvening(reqString(o, "slot", where), where + ".slot")) throw fail(where + ".slot must be eve");
            n.fromDay = dayInRange(o, "fromDay", where);
            if (n.fromDay < rules.firstDay) throw fail(where + ".fromDay " + n.fromDay + " is before the calendar");
            n.cost = nonNegative(o, "cost", where);
            n.gain = nonNegative(o, "gain", where);
            if (n.gain == 0) throw fail(where + ".gain must be positive");
            // Missing key = the conservative single use; explicit null = no limit (sources say it can be re-bought).
            if (!o.has("maxUses")) {
                n.maxUses = 1;
            } else {
                n.maxUses = o.isNull("maxUses") ? Rules.DatingNote.UNLIMITED : intInRange(o, "maxUses", where, 1, 1000);
            }
            if (!o.isNull("requires")) {
                JSONObject requires = reqObject(o, "requires", where);
                Iterator<String> keys = requires.keys();
                while (keys.hasNext()) {
                    String id = keys.next();
                    Integer idx = statIndex.get(id);
                    if (idx == null) throw fail(where + ".requires references unknown stat '" + id + "'");
                    n.requiresLv.put(idx, intInRange(requires, id, where + ".requires", 1, 6));
                }
            }
            n.conf = checkConf(o, where);
            n.src = sources(o, where);
            rules.datingNote = n;
        }

        private void parsePolicy() throws JSONException {
            JSONObject o = reqObject(root, "policy", "root");
            rules.policy.ordinaryGain = nonNegative(o, "ordinaryGain", "policy");
            rules.policy.personaMult = reqPositiveDouble(o, "personaMult", "policy");
            rules.policy.charmMult = reqPositiveDouble(o, "charmMult", "policy");
            checkConf(o, "policy");
            sources(o, "policy");
        }

        private void parseNotes() throws JSONException {
            JSONArray arr = reqArray(root, "notes", "root");
            for (int i = 0; i < arr.length(); i++) {
                Object v = arr.get(i);
                if (!(v instanceof String)) throw fail("notes[" + i + "] must be a string");
                rules.notes.add((String) v);
            }
        }

        // -------------------------------------------------------------- field helpers

        private int[][] payTiers(JSONObject o, String where) throws JSONException {
            JSONArray arr = reqArray(o, "pay", where);
            int[][] tiers = new int[arr.length()][];
            int previous = 0;
            for (int i = 0; i < arr.length(); i++) {
                String w = where + ".pay[" + i + "]";
                JSONArray tier = arr.optJSONArray(i);
                if (tier == null || tier.length() != 2) throw fail(w + " must be [minShiftNumber, pay]");
                int shift = integral(tier.get(0), w + "[0]");
                int pay = integral(tier.get(1), w + "[1]");
                if (shift <= previous || pay < 0) throw fail(w + " must have an ascending shift number >= 1 and pay >= 0");
                previous = shift;
                tiers[i] = new int[] {shift, pay};
            }
            return tiers;
        }

        private List<String> sources(JSONObject o, String where) throws JSONException {
            JSONArray arr = reqArray(o, "src", where);
            if (arr.length() == 0) throw fail(where + ".src must not be empty");
            List<String> out = new ArrayList<>();
            boolean reload = false;
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.getString(i);
                if (!sourceIds.contains(id)) throw fail(where + ".src[" + i + "] '" + id + "' is not declared in root.sources");
                reload |= reloadSourceIds.contains(id);
                out.add(id);
            }
            // Reload-only source policy: a value may not rest on multi-edition sources alone.
            if (!reload) throw fail(where + ".src " + out + " names no Reload source (edition " + RELOAD_EDITIONS + ")");
            return out;
        }

        private String checkConf(JSONObject o, String where) throws JSONException {
            String conf = reqString(o, "conf", where);
            if (!CONF_VALUES.contains(conf)) throw fail(where + ".conf '" + conf + "' must be one of " + CONF_VALUES);
            return conf;
        }

        private String lockId(JSONObject o, String key, String where) throws JSONException {
            String id = optString(o, key, where);
            if (id != null && !rules.lockReasonsZh.containsKey(id)) {
                throw fail(where + "." + key + " '" + id + "' is not declared in root.lockReasons");
            }
            return id;
        }

        private int linkRef(String id, String where) {
            Integer idx = linkIndex.get(id);
            if (idx == null) throw fail(where + " references unknown link '" + id + "'");
            return idx;
        }

        private BitSet dayBits(JSONObject o, String key, String where) throws JSONException {
            BitSet bits = new BitSet(rules.lastDay + 1);
            for (int d : sortedDays(o, key, where)) bits.set(d);
            return bits;
        }

        private int[] sortedDays(JSONObject o, String key, String where) throws JSONException {
            JSONArray arr = reqArray(o, key, where);
            int[] out = new int[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                int d = integral(arr.get(i), where + "." + key + "[" + i + "]");
                if (d < rules.firstDay || d > rules.lastDay) {
                    throw fail(where + "." + key + "[" + i + "] = " + d + " is outside the calendar "
                            + rules.firstDay + ".." + rules.lastDay);
                }
                if (i > 0 && d <= out[i - 1]) throw fail(where + "." + key + " must be strictly ascending at index " + i);
                out[i] = d;
            }
            return out;
        }

        private int[] weekdays(JSONObject o, String key, String where) throws JSONException {
            JSONArray arr = reqArray(o, key, where);
            if (arr.length() == 0) throw fail(where + "." + key + " must not be empty");
            int[] out = new int[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                out[i] = integral(arr.get(i), where + "." + key + "[" + i + "]");
                if (out[i] < 1 || out[i] > 7) throw fail(where + "." + key + "[" + i + "] = " + out[i] + " is not an ISO weekday 1..7");
            }
            return out;
        }

        private int dayInRange(JSONObject o, String key, String where) throws JSONException {
            int d = reqInt(o, key, where);
            if (d < 0 || d > rules.lastDay) {
                throw fail(where + "." + key + " = " + d + " is outside 0.." + rules.lastDay);
            }
            return d;
        }

        private int intInRange(JSONObject o, String key, String where, int min, int max) throws JSONException {
            int v = reqInt(o, key, where);
            if (v < min || v > max) throw fail(where + "." + key + " = " + v + " is outside " + min + ".." + max);
            return v;
        }

        private int nonNegative(JSONObject o, String key, String where) throws JSONException {
            int v = reqInt(o, key, where);
            if (v < 0) throw fail(where + "." + key + " must not be negative, got " + v);
            return v;
        }

        private int[] intArray(JSONObject o, String key, String where, int length) throws JSONException {
            JSONArray arr = reqArray(o, key, where);
            if (arr.length() != length) {
                throw fail(where + "." + key + " must have " + length + " entries, found " + arr.length());
            }
            int[] out = new int[length];
            for (int i = 0; i < length; i++) out[i] = integral(arr.get(i), where + "." + key + "[" + i + "]");
            return out;
        }

        private boolean isEvening(String slot, String where) {
            if ("day".equals(slot)) return false;
            if ("eve".equals(slot)) return true;
            throw fail(where + " must be day|eve, got '" + slot + "'");
        }

        private Rules.DayType dayType(String value, String where) {
            switch (value) {
                case "school": return Rules.DayType.SCHOOL;
                case "sunday": return Rules.DayType.SUNDAY;
                case "holiday": return Rules.DayType.HOLIDAY;
                case "vacation": return Rules.DayType.VACATION;
                case "exam": return Rules.DayType.EXAM;
                case "story": return Rules.DayType.STORY;
                default: throw fail(where + " must be school|sunday|holiday|vacation|exam|story, got '" + value + "'");
            }
        }
    }

    // ------------------------------------------------------------------------------------ JSON helpers
    // org.json differs between Android (optString(null) -> "null") and the JVM artifact; these helpers
    // behave identically on both.

    private static IllegalArgumentException fail(String message) {
        return new IllegalArgumentException(message);
    }

    private static String reqString(JSONObject o, String key, String where) throws JSONException {
        if (o.isNull(key)) throw fail(where + "." + key + " is required");
        Object v = o.get(key);
        if (!(v instanceof String) || ((String) v).isEmpty()) throw fail(where + "." + key + " must be a non-empty string");
        return (String) v;
    }

    private static String optString(JSONObject o, String key, String where) throws JSONException {
        if (o.isNull(key)) return null;
        Object v = o.get(key);
        if (!(v instanceof String)) throw fail(where + "." + key + " must be a string or null");
        return (String) v;
    }

    private static int reqInt(JSONObject o, String key, String where) throws JSONException {
        if (o.isNull(key)) throw fail(where + "." + key + " is required");
        return integral(o.get(key), where + "." + key);
    }

    private static int integral(Object v, String where) {
        if (!(v instanceof Number)) throw fail(where + " must be an integer");
        double d = ((Number) v).doubleValue();
        if (d != Math.rint(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) throw fail(where + " must be an integer");
        return (int) d;
    }

    private static double reqPositiveDouble(JSONObject o, String key, String where) throws JSONException {
        if (o.isNull(key)) throw fail(where + "." + key + " is required");
        Object v = o.get(key);
        if (!(v instanceof Number) || ((Number) v).doubleValue() <= 0) throw fail(where + "." + key + " must be a positive number");
        return ((Number) v).doubleValue();
    }

    private static boolean reqBool(JSONObject o, String key, String where) throws JSONException {
        if (o.isNull(key)) throw fail(where + "." + key + " is required");
        Object v = o.get(key);
        if (!(v instanceof Boolean)) throw fail(where + "." + key + " must be true or false");
        return (Boolean) v;
    }

    private static JSONObject reqObject(JSONObject o, String key, String where) throws JSONException {
        if (o.isNull(key)) throw fail(where + "." + key + " is required");
        Object v = o.get(key);
        if (!(v instanceof JSONObject)) throw fail(where + "." + key + " must be an object");
        return (JSONObject) v;
    }

    private static JSONObject reqObject(JSONArray arr, int index, String where) throws JSONException {
        Object v = arr.get(index);
        if (!(v instanceof JSONObject)) throw fail(where + " must be an object");
        return (JSONObject) v;
    }

    private static JSONArray reqArray(JSONObject o, String key, String where) throws JSONException {
        if (o.isNull(key)) throw fail(where + "." + key + " is required");
        Object v = o.get(key);
        if (!(v instanceof JSONArray)) throw fail(where + "." + key + " must be an array");
        return (JSONArray) v;
    }
}
