package app.p3r.dayplanner.v3.widget;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Where card text may wrap. Chinese has a line-break opportunity between any two characters, so a
 * TextView happily splits 「小田桐/秀利」 or leaves 「（1）」 alone on the next line. Words that must
 * stay whole get U+2060 WORD JOINER between their characters; spaces beside a number become
 * no-break spaces. Both are invisible and take no width, so only the line breaks change.
 *
 * <p>Display only: the card model keeps plain text (tests, accessibility, records), and
 * {@link CardRenderer} shapes it right before measuring and drawing. {@link #plain} undoes it.
 */
public final class CardText {
    /** U+2060 WORD JOINER: no line break on either side; invisible and zero-width. */
    public static final char WJ = '\u2060';
    public static final char NBSP = '\u00A0';
    /** Separator between the parts of a title (「皇帝 · 小田桐秀利」). */
    static final String SEPARATOR = " · ";
    /**
     * The separator as a shaped title carries it: a line may break before the dot, never after it,
     * so no line ends in 「·」 (and {@link CardRenderer} wraps a title there without the dot).
     */
    static final String SHAPED_SEPARATOR = " ·" + NBSP;
    /** What a separator becomes where a drawn title wraps at it: the dot goes with the break. */
    public static final char TITLE_BREAK = '\n';
    /** Separator between dialogue choices (「A / B」). */
    static final String CHOICE_SEPARATOR = " / ";
    /** The prefix of the plan widget's dialogue line, as the app writes it. */
    static final String ANSWER_PREFIX = "参考回答：";
    /** Joins two alternatives inside one dialogue choice (「我也去看看吧或者我在这里等她」). */
    private static final String ALTERNATIVES = "或者";
    /** Longest title part (characters) still treated as one name. */
    static final int MAX_NAME = 12;

    /** A line may start with these: a break stays allowed before them (「伊织顺平」/「（第 2 话）」). */
    private static final String OPENING = "（(「『【〔《〈“‘";
    /** A line may end with these: a break stays allowed after them. */
    private static final String CLOSING = "）)」』】〕》〉”’，、。；：！？…";
    /**
     * Words that turn up inside titles without a separator around them: names, and the words of the
     * widget's own status titles (「设置目标，从当前存档开始」 must not wrap as 「存/档」).
     */
    private static final List<String> WORDS = Collections.unmodifiableList(Arrays.asList(
            "费洛蒙咖啡", "塔尔塔罗斯", "人格面具", "阿尔卡那", "羁绊剧情", "结缘网站的字条", "自由活动",
            "设置", "目标", "当前", "存档", "开始", "生成", "安排", "暂时", "后面", "没有"));
    /** A space beside a number, except the one before a separator's dot (「RANK2 · 友近」 may break there). */
    private static final Pattern NUMBER_SPACE = Pattern.compile("(?<=\\d) (?=[^\\s·])|(?<=\\S) (?=\\d)");
    /** A title separator however its spaces are shaped (a space beside a number is already no-break). */
    private static final Pattern ANY_SEPARATOR = Pattern.compile("[ " + NBSP + "]·[ " + NBSP + "]");
    /** Location parts: 「桐叶购物中心 · 夏卡尔咖啡店」, 「教室／田径社」. */
    private static final Pattern LOCATION_PARTS = Pattern.compile("\\s*[·/／、，,；;]\\s*|\\s+");

    private CardText() {}

    /** The spaces beside a number made non-breaking: 「第 2 话」 and 「10,000 日元」 never split. */
    public static String keepNumbersTogether(String text) {
        return text == null ? null : NUMBER_SPACE.matcher(text).replaceAll(String.valueOf(NBSP));
    }

    /** {@code word} with a joiner between every two characters, so it never wraps inside. */
    public static String keep(String word) {
        if (word == null) return null;
        String s = strip(word);
        boolean[] join = new boolean[s.length() + 1];
        joinRange(s, join, 0, s.length());
        return build(s, join);
    }

    /** Removes the joiners and turns no-break spaces back into spaces. */
    public static String plain(String text) {
        return text == null ? null : strip(text).replace(NBSP, ' ');
    }

    /**
     * A drawn title as the card model holds it: {@link #plain}, with every {@link #TITLE_BREAK} read
     * as the 「 · 」 it replaced.
     */
    public static String plainTitle(String text) {
        return text == null ? null : plain(text).replace(String.valueOf(TITLE_BREAK), SEPARATOR);
    }

    /**
     * {@code shaped} with each title separator that a drawn line ends at turned into a
     * {@link #TITLE_BREAK}, the dot dropped: 「结缘网站的字条」/「山岸风花」, never 「结缘网站的字条 ·」/「山岸风花」
     * or 「结缘网站的字条」/「· 山岸风花」. {@code lineEnds} are the offsets where the lines of
     * {@code shaped} end (the last line's end may be included); every other break stays as it is.
     */
    static String breakAtSeparators(String shaped, int[] lineEnds) {
        if (shaped == null) return null;
        StringBuilder out = new StringBuilder(shaped);
        // From the last break back, so the earlier offsets still hold.
        for (int k = lineEnds.length - 1; k >= 0; k--) {
            int at = separatorAt(shaped, lineEnds[k]);
            if (at >= 0) out.replace(at, at + SEPARATOR.length(), String.valueOf(TITLE_BREAK));
        }
        return out.toString();
    }

    /**
     * Start of the separator a line break at offset {@code end} falls on, or -1: the break comes after
     * its first space (a shaped title, {@link #SHAPED_SEPARATOR}) or after the whole separator (a
     * title the card did not shape).
     */
    private static int separatorAt(String s, int end) {
        for (int at : new int[]{end - 1, end - SEPARATOR.length()}) {
            if (at >= 0 && at + SEPARATOR.length() <= s.length()
                    && ANY_SEPARATOR.matcher(s).region(at, at + SEPARATOR.length()).matches()) {
                return at;
            }
        }
        return -1;
    }

    /**
     * A card title. Every part between 「 · 」 separators is a name and stays whole (a line may still
     * break before an opening bracket: 「伊织顺平」/「（第 2 话）」); a line breaks before a separator's
     * dot, never after it ({@link #SHAPED_SEPARATOR}: no line ends in 「·」). A title without
     * separators (an activity such as 「在夏卡尔咖啡店喝费洛蒙咖啡」) keeps {@code words} (e.g. the parts of the step's location), a few fixed names and anything in 「」
     * whole, and may wrap between them. Applying it twice changes nothing.
     */
    public static String title(String text, Collection<String> words) {
        return shapeTitle(text, words, false);
    }

    /** Title shaping without extra words. */
    public static String title(String text) {
        return title(text, null);
    }

    /**
     * Free-form guide text: the legacy guide's titles and places (「收集12种饮料交给伊莉莎白，接取委托11」,
     * 「派出所旁小巷进入天鹅绒房间，确认人格面具合成功能」). Everything {@link #title} keeps whole, and
     * also the guide's names, places and game words ({@link GuideWords}), an arcana's mask
     * (「星面具」), dictionary words, a short “…” name, a number or Latin word together with the
     * character before it and its unit (「委托11」「12种」「1万日元」「魔术师→RANK2」「魅力+♪」), a Latin
     * word together with the character after it (「SL不同」「BOSS战」), and a choice count 「（2）」 with
     * the character before it. Lines prefer to break after clause punctuation: a lone character after
     * 「；」「，」「、」 goes with the word after it (「；」/「邀结子同行」, never 「；邀」/「结子同行」);
     * 「的」 and a place word such as 「对面」 never start a line, and a one-character verb such as 「邀」
     * never ends one apart from its object. The text may wrap between words, never inside one.
     * Applying it twice changes nothing.
     */
    public static String guide(String text, Collection<String> words) {
        return guide(text, words, MAX_QUOTED);
    }

    /**
     * As {@link #guide(String, Collection)}, keeping a quoted name of up to {@code maxQuoted}
     * characters whole (at least {@link #MAX_QUOTED}): the card passes how many fit one title line, so
     * 「“历史学投稿站的字条”」 stays whole where it fits and wraps between its words where it does not.
     */
    public static String guide(String text, Collection<String> words, int maxQuoted) {
        return shapeTitle(text, words, true, Math.max(MAX_QUOTED, maxQuoted));
    }

    private static String shapeTitle(String text, Collection<String> words, boolean free) {
        return shapeTitle(text, words, free, MAX_QUOTED);
    }

    private static String shapeTitle(String text, Collection<String> words, boolean free, int maxQuoted) {
        if (text == null) return null;
        // Every separator takes the shaped form, whatever keepNumbersTogether made of its spaces (「课堂 · 3」).
        String s = ANY_SEPARATOR.matcher(keepNumbersTogether(strip(text))).replaceAll(SHAPED_SEPARATOR);
        boolean named = s.contains(SHAPED_SEPARATOR);
        boolean[] join = new boolean[s.length() + 1];
        if (named) {
            int start = 0;
            for (int i = 0; i <= s.length(); i++) {
                if (i == s.length() || s.charAt(i) == ' ') {
                    // The dot that starts a part is the separator's, not the name's.
                    int name = s.startsWith("·" + NBSP, start) ? start + 2 : start;
                    if (visibleLength(s, name, i) <= MAX_NAME) joinName(s, join, start, i);
                    start = i + 1;
                }
            }
        }
        List<String> all = new ArrayList<>(WORDS);
        if (free) {
            all.addAll(GuideWords.WORDS);
            for (String arcana : GuideWords.ARCANA) all.add(arcana + MASK);
        }
        if (words != null) all.addAll(words);
        joinWords(s, join, all);
        if (free) {
            // A long 「…」 (「成瘾症状（Addiction）」) would not fit a line whole; its words still do.
            joinShortBrackets(s, join, '「', '」', maxQuoted);
            joinShortBrackets(s, join, '“', '”', maxQuoted);
            joinDictionaryWords(s, join);
            joinNumbersAndSymbols(s, join);
            joinCounts(s, join);
            joinBeforeClosing(s, join);
            joinSmallWords(s, join);
            // Last: it looks at what the rules above already keep together.
            joinAfterClauses(s, join);
        } else {
            joinBrackets(s, join, '「', '」');
        }
        return build(s, join);
    }

    /**
     * A short instruction such as 「游戏资料读取失败，请重新打开应用」: each clause up to
     * {@link #MAX_NAME} characters stays whole, so it wraps after the comma rather than leaving
     * 「用」 alone; longer clauses keep only the fixed names whole.
     */
    public static String sentence(String text) {
        if (text == null) return null;
        String s = keepNumbersTogether(strip(text));
        boolean[] join = new boolean[s.length() + 1];
        int start = 0;
        for (int i = 0; i <= s.length(); i++) {
            boolean end = i == s.length() || s.charAt(i) == ' ' || CLOSING.indexOf(s.charAt(i)) >= 0;
            if (!end) continue;
            int to = i < s.length() && s.charAt(i) != ' ' ? i + 1 : i; // the punctuation stays on its clause
            if (visibleLength(s, start, to) <= MAX_NAME) joinName(s, join, start, to);
            start = to;
            if (i < s.length() && s.charAt(i) == ' ') start = i + 1;
        }
        joinWords(s, join, WORDS);
        return build(s, join);
    }

    /**
     * Dialogue choices 「A / B」, optionally after 「参考回答：」. Every choice stays whole, its 「（1）」
     * included; lines break only after a slash (or after the prefix), never before one.
     */
    public static String choices(String text) {
        if (text == null) return null;
        String s = strip(text);
        String prefix = "";
        if (s.startsWith(ANSWER_PREFIX)) {
            prefix = keep(ANSWER_PREFIX);
            s = s.substring(ANSWER_PREFIX.length());
        }
        String separator = NBSP + "/ ";
        String[] parts = s.replace(separator, CHOICE_SEPARATOR).split(Pattern.quote(CHOICE_SEPARATOR), -1);
        StringBuilder out = new StringBuilder(prefix);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append(separator);
            out.append(keep(parts[i].replace(' ', NBSP)));
        }
        return out.toString();
    }

    /**
     * Dialogue choices for a card too small for whole ones: a choice may wrap inside, but its count
     * 「（1）」 stays with the character before it, its last character never starts a line alone, a
     * choice of two alternatives 「A或者B」 wraps after 「或者」 (each side whole while short), and a
     * line never starts with a slash.
     */
    public static String choicesLoose(String text) {
        return choicesLoose(text, true);
    }

    /**
     * As {@link #choicesLoose(String)}; without {@code keepAlternatives} a choice 「A或者B」 may also
     * wrap inside A or B (for a card where the two whole alternatives would take one line more).
     */
    public static String choicesLoose(String text, boolean keepAlternatives) {
        if (text == null) return null;
        String s = strip(text);
        String separator = NBSP + "/ ";
        String[] parts = s.replace(separator, CHOICE_SEPARATOR).split(Pattern.quote(CHOICE_SEPARATOR), -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append(separator);
            String part = parts[i];
            boolean[] join = new boolean[part.length() + 1];
            int or = keepAlternatives ? part.indexOf(ALTERNATIVES) : -1;
            if (or > 0) {
                int cut = or + ALTERNATIVES.length();
                if (visibleLength(part, 0, cut) <= MAX_NAME) joinName(part, join, 0, cut);
                if (visibleLength(part, cut, part.length()) <= MAX_NAME) joinName(part, join, cut, part.length());
            }
            int open = part.lastIndexOf('（');
            if (open > 0 && part.endsWith("）")) {
                joinRange(part, join, open - 1, part.length());
            } else {
                int end = part.length();
                while (end > 0 && CLOSING.indexOf(part.charAt(end - 1)) >= 0) end--;
                if (visibleLength(part, 0, end) > 3) joinRange(part, join, end - 2, part.length());
            }
            out.append(build(part, join));
        }
        return out.toString();
    }

    /**
     * A tag chip (「RANK 4→5」, 「魅力 +4 → Lv.2、学力 +2 → MAX」): every gain stays whole, its spaces
     * included, so a chip too wide for its row wraps only after a 「、」 and never cuts a value.
     */
    public static String chip(String text) {
        if (text == null) return null;
        String[] parts = strip(text).replace(NBSP, ' ').split(GAIN_SEPARATOR, -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append(GAIN_SEPARATOR);
            out.append(keep(parts[i].replace(' ', NBSP)));
        }
        return out.toString();
    }

    /**
     * Shorter forms of a location line for a card too narrow for all of it, longest first, the full
     * line included: the parts that matter least go first. A trailing bracket (the floor range
     * 「（145–172F）」), then the area before 「 · 」 (the mall or school: 「桐叶购物中心 · 夏卡尔咖啡店」 →
     * 「夏卡尔咖啡店」), then the words before the last space (「奢豪之庭 茨尔阿」 → 「茨尔阿」; a space next
     * to a number is part of the place: 「2 楼」) or the places before the last 「；」 of a list. Each leg
     * of a route 「A → B」 is shortened alike. No form starts or ends with a separator: alternatives
     * 「2楼教室外 / 运动社团附近」 may lose the first one, but 「宿舍2楼 / 3楼」 stays whole (never 「/ 3楼」).
     */
    public static List<String> locationForms(String location) {
        List<String> forms = new ArrayList<>();
        if (location == null || location.trim().isEmpty()) return forms;
        String full = plain(location).trim();
        forms.add(full);
        String noBracket = eachLeg(full, CardText::withoutTrailingBracket);
        addForm(forms, noBracket);
        String place = eachLeg(noBracket, leg -> lastPart(leg, AREA_SEPARATOR));
        addForm(forms, place);
        addForm(forms, eachLeg(place, CardText::lastWord));
        return forms;
    }

    /**
     * Shorter forms of a one-line note (the cue line) for a card too narrow for all of it, longest
     * first, the full line included: the clauses before each 「，」「；」「。」 or 「 · 」, from the last
     * one back, without the punctuation (「对方发来了休息日邀约，赴约也算一次见面」 → 「对方发来了休息日邀约」,
     * 「去喷泉广场找那个胖乎乎的学生 · 花费 1,000 日元」 → 「去喷泉广场找那个胖乎乎的学生」). Never part of a
     * clause, and never a clause that only leads into the next one (「填好进度后」「…交给顺平的话」).
     */
    public static List<String> clauseForms(String text) {
        List<String> forms = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return forms;
        String full = plain(text).trim();
        forms.add(full);
        for (int i = full.length() - 1; i > 0; i--) {
            boolean clause = CLAUSE_BREAKS.indexOf(full.charAt(i)) >= 0 || full.startsWith(SEPARATOR, i);
            if (!clause) continue;
            String head = full.substring(0, i).trim();
            if (head.isEmpty() || forms.contains(head) || leadsOn(head) || !closed(head)) continue;
            forms.add(head);
        }
        return forms;
    }

    /** Punctuation that ends a clause of a note. */
    private static final String CLAUSE_BREAKS = "，；。";
    /** Endings of a clause that only sets up the next one (a condition, a time). */
    private static final List<String> LEADING_ENDS = Collections.unmodifiableList(Arrays.asList("的话", "后", "时", "前"));

    private static boolean leadsOn(String clause) {
        for (String end : LEADING_ENDS) {
            if (clause.endsWith(end)) return true;
        }
        return false;
    }

    /** Whether every bracket or quote opened in {@code text} is closed again (a clause break inside one is none). */
    private static boolean closed(String text) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            if (OPENING.indexOf(text.charAt(i)) >= 0) depth++;
            else if (CLOSING_BRACKETS.indexOf(text.charAt(i)) >= 0) depth--;
        }
        return depth == 0;
    }

    private static final String GAIN_SEPARATOR = "、";
    private static final String ROUTE_SEPARATOR = " → ";
    private static final Pattern AREA_SEPARATOR = Pattern.compile("\\s*·\\s*");
    private static final Pattern TRAILING_BRACKET = Pattern.compile("\\s*[（(][^（()）]*[）)]$");

    /** Characters that separate places or alternatives; a shortened location never starts or ends with one. */
    private static final String PLACE_SEPARATORS = "/／·、，,；;";

    private static void addForm(List<String> forms, String form) {
        String f = form.trim();
        if (f.isEmpty() || forms.contains(f)) return;
        if (PLACE_SEPARATORS.indexOf(f.charAt(0)) >= 0 || PLACE_SEPARATORS.indexOf(f.charAt(f.length() - 1)) >= 0) return;
        forms.add(f);
    }

    private static String eachLeg(String route, java.util.function.UnaryOperator<String> shorten) {
        String[] legs = route.split(Pattern.quote(ROUTE_SEPARATOR), -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < legs.length; i++) {
            if (i > 0) out.append(ROUTE_SEPARATOR);
            String shorter = shorten.apply(legs[i].trim()).trim();
            out.append(shorter.isEmpty() ? legs[i].trim() : shorter);
        }
        return out.toString();
    }

    private static String withoutTrailingBracket(String leg) {
        return TRAILING_BRACKET.matcher(leg).replaceFirst("");
    }

    private static String lastPart(String leg, Pattern separator) {
        String[] parts = separator.split(leg);
        return parts.length == 0 ? leg : parts[parts.length - 1];
    }

    /**
     * The text after the last space that is not next to a number (「月光馆学园 3楼 学生会室」 → 「学生会室」),
     * or the last of a list of places (「天鹅绒房间入口；宿舍1楼大厅」 → 「宿舍1楼大厅」). A separator goes
     * with the words before it: 「2楼教室外 / 运动社团附近」 → 「运动社团附近」, and where the space after it
     * is part of the place (「宿舍2楼 / 3楼」) nothing is cut there.
     */
    private static String lastWord(String leg) {
        for (int i = leg.length() - 1; i > 0; i--) {
            if (leg.charAt(i) == '；' || leg.charAt(i) == ';') return leg.substring(i + 1);
            if (leg.charAt(i) != ' ') continue;
            boolean number = Character.isDigit(leg.charAt(i - 1)) || (i + 1 < leg.length() && Character.isDigit(leg.charAt(i + 1)));
            boolean beforeSeparator = i + 1 < leg.length() && PLACE_SEPARATORS.indexOf(leg.charAt(i + 1)) >= 0;
            if (!number && !beforeSeparator) return leg.substring(i + 1);
        }
        return leg;
    }

    /** Name-like parts of a location line, for {@link #title(String, Collection)}. */
    public static List<String> locationWords(String location) {
        if (location == null) return Collections.emptyList();
        List<String> words = new ArrayList<>();
        for (String part : LOCATION_PARTS.split(strip(location).trim())) {
            if (part.codePointCount(0, part.length()) >= 2) words.add(part);
        }
        return words;
    }

    // ------------------------------------------------------------------ internals

    private static String strip(String text) {
        return text.indexOf(WJ) < 0 ? text : text.replace(String.valueOf(WJ), "");
    }

    private static int visibleLength(String s, int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) {
            char ch = s.charAt(i);
            if (ch != NBSP && !Character.isLowSurrogate(ch)) n++;
        }
        return n;
    }

    /** A name part: whole, except before an opening bracket and after closing punctuation. */
    private static void joinName(String s, boolean[] join, int from, int to) {
        for (int i = from + 1; i < to; i++) {
            if (OPENING.indexOf(s.charAt(i)) >= 0 || CLOSING.indexOf(s.charAt(i - 1)) >= 0) continue;
            join[i] = true;
        }
    }

    /** Every pair inside [from, to). */
    private static void joinRange(String s, boolean[] join, int from, int to) {
        for (int i = from + 1; i < to; i++) join[i] = true;
    }

    private static void joinWords(String s, boolean[] join, List<String> words) {
        for (String word : words) {
            String w = word == null ? "" : strip(word).trim();
            if (w.codePointCount(0, w.length()) < 2) continue;
            for (int at = s.indexOf(w); at >= 0; at = s.indexOf(w, at + 1)) joinRange(s, join, at, at + w.length());
        }
    }

    private static void joinBrackets(String s, boolean[] join, char open, char close) {
        for (int at = s.indexOf(open); at >= 0; at = s.indexOf(open, at + 1)) {
            int end = s.indexOf(close, at + 1);
            if (end < 0) return;
            joinRange(s, join, at, end + 1);
        }
    }

    /**
     * Longest quoted name (characters inside 「」 or “”) that guide text always keeps on one line; the
     * card keeps longer ones whole where they fit a line ({@link #guide(String, Collection, int)}).
     */
    static final int MAX_QUOTED = 8;
    /** An arcana's Persona is 「星面具」「倒悬者面具」: one word. */
    private static final String MASK = "面具";
    /** Punctuation that ends a clause: guide text prefers to wrap right after it. */
    private static final String CLAUSE_END = "；，、：。";
    /** Particles that never start a line (「教室对面的」/「学生会活动室」, never 「教室对面」/「的学生会…」). */
    private static final String TRAILING_PARTICLES = "的";
    /** Place words that belong to the word before them (「教室对面」「活动室门口」). */
    private static final List<String> LOCATIVES = Collections.unmodifiableList(Arrays.asList("对面", "门口", "旁边", "附近", "隔壁"));
    /** One-character place words that belong to the word before them when 「的」 follows (「教室外的贝培」). */
    private static final String LOCATIVE_CHARS = "外内前后旁里";
    /**
     * One-character verbs of the guide that never end a line apart from their object (「邀结子」
     * 「找宫本」「带伊莉莎白」「拿申请书」「到长鸣神社」).
     */
    private static final String OBJECT_VERBS = "邀找带交送拿到";

    /** A short quoted name (「「死亡之怖」」, 「“山间补习班”」) stays whole with its brackets. */
    private static void joinShortBrackets(String s, boolean[] join, char open, char close, int maxQuoted) {
        for (int at = s.indexOf(open); at >= 0; at = s.indexOf(open, at + 1)) {
            int end = s.indexOf(close, at + 1);
            if (end < 0) return;
            if (visibleLength(s, at + 1, end) <= maxQuoted) joinRange(s, join, at, end + 1);
        }
    }

    /**
     * Small words that lean on a neighbour. A particle such as 「的」 stays with the word or closing
     * quote before it (「“…”的字条」), and so does a one-character place word before it (「教室外的」);
     * a place word such as 「对面」 stays with the character before it, and a one-character verb such
     * as 「邀」 with the character after it (the word after it starts there, unless they already form
     * one: 「邀约」).
     */
    private static void joinSmallWords(String s, boolean[] join) {
        for (int i = 1; i < s.length(); i++) {
            if (TRAILING_PARTICLES.indexOf(s.charAt(i)) < 0) continue;
            char before = s.charAt(i - 1);
            if (attaches(before) || CLOSING_BRACKETS.indexOf(before) >= 0) join[i] = true;
            if (i >= 2 && LOCATIVE_CHARS.indexOf(before) >= 0 && isHan(s.charAt(i - 2))) join[i - 1] = true;
        }
        for (String place : LOCATIVES) {
            for (int at = s.indexOf(place); at > 0; at = s.indexOf(place, at + 1)) {
                if (isHan(s.charAt(at - 1))) join[at] = true;
            }
        }
        for (int i = 0; i + 1 < s.length(); i++) {
            if (OBJECT_VERBS.indexOf(s.charAt(i)) >= 0 && attaches(s.charAt(i + 1))) join[i + 1] = true;
        }
    }

    /** Closing brackets and quotes (the part of {@link #CLOSING} that is no clause punctuation). */
    private static final String CLOSING_BRACKETS = "）)」』】〕》〉”’";

    /**
     * A character that starts a clause alone (「；邀结子同行」: 「邀」 belongs to no word) stays with the
     * character after it, so the line breaks after the punctuation rather than leaving it dangling.
     */
    private static void joinAfterClauses(String s, boolean[] join) {
        for (int i = 0; i + 2 < s.length(); i++) {
            if (CLAUSE_END.indexOf(s.charAt(i)) < 0) continue;
            int first = i + 1;
            if (isHan(s.charAt(first)) && isHan(s.charAt(first + 1)) && !join[first + 1]) join[first + 1] = true;
        }
    }

    /** Dictionary word segmentation (ICU), one per thread. */
    private static final ThreadLocal<android.icu.text.BreakIterator> DICTIONARY = new ThreadLocal<>();

    /**
     * Two- to four-character Chinese words of ICU's dictionary segmentation (「确认」「饮料」「委托」),
     * like the app's own text. Skipped where ICU is unavailable (plain JVM tests).
     */
    private static void joinDictionaryWords(String s, boolean[] join) {
        android.icu.text.BreakIterator it;
        try {
            it = DICTIONARY.get();
            if (it == null) {
                it = android.icu.text.BreakIterator.getWordInstance(android.icu.util.ULocale.SIMPLIFIED_CHINESE);
                DICTIONARY.set(it);
            }
            it.setText(s);
        } catch (RuntimeException | LinkageError unavailable) {
            return;
        }
        int start = it.first();
        for (int end = it.next(); end != android.icu.text.BreakIterator.DONE; start = end, end = it.next()) {
            if (end - start < 2 || end - start > 4) continue;
            boolean han = true;
            for (int i = start; i < end && han; i++) han = isHan(s.charAt(i));
            if (han) joinRange(s, join, start, end);
        }
    }

    /** Counters and units that belong to the number before them (「12种」「第15处」「1万日元」「22F」). */
    private static final String UNITS = "种个处次号层楼人天张件项步级话名万元岁";
    private static final String TWO_CHAR_UNITS = "日元";

    /**
     * Numbers and Latin words (「11」「LV2」「RANK2」「MAX」「G20」) stay whole and with the word before
     * them (「委托11」「愚者LV2」), a number with its unit; 「+」「♪」 and 「→」 stay with what they follow
     * (「魅力+♪♪」「魔术师→RANK2」). A 「+」 between two things to buy belongs to the next one, as the
     * line breaker already keeps it (「汽水10个」/「+莫乐蜜G20个」): joined to both, the whole list would
     * be one unbreakable run. A Latin word inside a sentence also keeps the character after it: it is
     * read as part of the phrase (「可以SL不同的人物」, 「满月BOSS战」). Spaces stay where they are: a space
     * is a deliberate break.
     */
    private static void joinNumbersAndSymbols(String s, boolean[] join) {
        int i = 0;
        while (i < s.length()) {
            if (!isRunChar(s, i)) {
                i++;
                continue;
            }
            int start = i;
            while (i < s.length() && isRunChar(s, i)) i++;
            int end = i;
            joinRange(s, join, start, end);
            if (joinsNext(s, start, end)) join[end] = true;
            else if (start > 0 && attaches(s.charAt(start - 1))) join[start] = true;
            boolean number = Character.isDigit(s.charAt(end - 1));
            if (end < s.length() && end - start >= 2 && isLatinWord(s, start, end) && isHan(s.charAt(end))) join[end] = true;
            if (end < s.length()) {
                char next = s.charAt(end);
                if (number && UNITS.indexOf(next) >= 0) {
                    join[end] = true;
                    if (next == '万' && s.startsWith(TWO_CHAR_UNITS, end + 1)) joinRange(s, join, end, end + 1 + TWO_CHAR_UNITS.length());
                } else if (number && s.startsWith(TWO_CHAR_UNITS, end)) {
                    joinRange(s, join, end - 1, end + TWO_CHAR_UNITS.length());
                }
            }
        }
    }

    /**
     * The last two characters before a closing bracket stay together, so a line never holds one
     * character and the bracket alone (「探索并救人（2个，不算 / 猫）」 → 「不算猫）」 on the next line).
     */
    private static void joinBeforeClosing(String s, boolean[] join) {
        for (int i = 2; i < s.length(); i++) {
            char ch = s.charAt(i);
            if ((ch == '）' || ch == ')' || ch == '」' || ch == '』' || ch == '】')
                    && isHan(s.charAt(i - 1)) && isHan(s.charAt(i - 2))) {
                join[i - 1] = true;
            }
        }
    }

    /** A count 「（2）」「（1个）」 stays with the character before it (「MAY BLUES（2）」「救人（1个）」). */
    private static void joinCounts(String s, boolean[] join) {
        for (int at = s.indexOf('（'); at >= 0; at = s.indexOf('（', at + 1)) {
            int end = at + 1;
            while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
            if (end == at + 1 || end - at > 3) continue;
            if (end < s.length() && UNITS.indexOf(s.charAt(end)) >= 0) end++;
            if (end >= s.length() || s.charAt(end) != '）') continue;
            if (at > 0 && attaches(s.charAt(at - 1))) joinRange(s, join, at - 1, end + 1);
        }
    }

    /** A run of only 「+」 with a word right after it (「汽水10个+莫乐蜜」): the 「+」 starts that word. */
    private static boolean joinsNext(String s, int start, int end) {
        for (int i = start; i < end; i++) {
            if (s.charAt(i) != '+' && s.charAt(i) != '＋') return false;
        }
        return end < s.length() && attaches(s.charAt(end));
    }

    /** Part of a number/Latin/symbol run: digits, Latin letters, 「+」「♪」「→」, and 「.」「,」「%」 inside a number. */
    private static boolean isRunChar(String s, int i) {
        char ch = s.charAt(i);
        if (Character.isDigit(ch) || isLatin(ch) || isSymbol(ch)) return true;
        if (ch == '.' || ch == ',' || ch == '%' || ch == '-') {
            boolean digitBefore = i > 0 && Character.isDigit(s.charAt(i - 1));
            boolean digitAfter = i + 1 < s.length() && Character.isDigit(s.charAt(i + 1));
            return ch == '%' ? digitBefore : digitBefore && digitAfter || (ch == '.' && i > 0 && isLatin(s.charAt(i - 1)));
        }
        return false;
    }

    /**
     * Letters only (「SL」「BOSS」), no number or symbol: a word, not a count or a rank. (A single letter
     * such as the 「G」 of 「莫乐蜜G」 is part of the name before it.)
     */
    private static boolean isLatinWord(String s, int start, int end) {
        for (int i = start; i < end; i++) {
            if (!isLatin(s.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isSymbol(char ch) {
        return ch == '+' || ch == '＋' || ch == '♪' || ch == '→';
    }

    private static boolean isLatin(char ch) {
        return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= 'Ａ' && ch <= 'Ｚ') || (ch >= 'ａ' && ch <= 'ｚ');
    }

    /** A character a number or symbol run sticks to: a letter or ideograph, never a space or punctuation. */
    private static boolean attaches(char ch) {
        return isHan(ch) || Character.isLetterOrDigit(ch);
    }

    private static boolean isHan(char ch) {
        return Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN;
    }

    /**
     * Inserts a joiner where {@code join[i]} asks for one between {@code s[i-1]} and {@code s[i]},
     * except inside a surrogate pair and next to a space (a no-break space already holds, a plain
     * space is a deliberate break).
     */
    private static String build(String s, boolean[] join) {
        StringBuilder out = new StringBuilder(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (i > 0 && join[i]) {
                char prev = s.charAt(i - 1);
                boolean pair = Character.isHighSurrogate(prev) && Character.isLowSurrogate(ch);
                boolean space = Character.isWhitespace(prev) || Character.isWhitespace(ch) || prev == NBSP || ch == NBSP;
                if (!pair && !space) out.append(WJ);
            }
            out.append(ch);
        }
        return out.toString();
    }
}
