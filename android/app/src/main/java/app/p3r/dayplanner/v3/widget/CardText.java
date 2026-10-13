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
    private static final Pattern NUMBER_SPACE = Pattern.compile("(?<=\\d) (?=\\S)|(?<=\\S) (?=\\d)");
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
     * A card title. Every part between 「 · 」 separators is a name and stays whole (a line may still
     * break before an opening bracket: 「伊织顺平」/「（第 2 话）」); the separator stays at the end of
     * its line. A title without separators (an activity such as 「在夏卡尔咖啡店喝费洛蒙咖啡」) keeps
     * {@code words} (e.g. the parts of the step's location), a few fixed names and anything in 「」
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
     * also the guide's names, places and game words ({@link GuideWords}), dictionary words, a short
     * “…” name, a number or Latin word together with the character before it and its unit
     * (「委托11」「12种」「1万日元」「魔术师→RANK2」「魅力+♪」), and a choice count 「（2）」 with the
     * character before it. The text may wrap between
     * words, never inside one. Applying it twice changes nothing.
     */
    public static String guide(String text, Collection<String> words) {
        return shapeTitle(text, words, true);
    }

    private static String shapeTitle(String text, Collection<String> words, boolean free) {
        if (text == null) return null;
        String s = keepNumbersTogether(strip(text));
        boolean named = s.contains(SEPARATOR) || s.contains(NBSP + "· ");
        s = s.replace(SEPARATOR, NBSP + "· ");
        boolean[] join = new boolean[s.length() + 1];
        if (named) {
            int start = 0;
            for (int i = 0; i <= s.length(); i++) {
                if (i == s.length() || s.charAt(i) == ' ') {
                    if (visibleLength(s, start, i) <= MAX_NAME) joinName(s, join, start, i);
                    start = i + 1;
                }
            }
        }
        List<String> all = new ArrayList<>(WORDS);
        if (free) all.addAll(GuideWords.WORDS);
        if (words != null) all.addAll(words);
        joinWords(s, join, all);
        if (free) {
            // A long 「…」 (「成瘾症状（Addiction）」) would not fit a line whole; its words still do.
            joinShortBrackets(s, join, '「', '」');
            joinShortBrackets(s, join, '“', '”');
            joinDictionaryWords(s, join);
            joinNumbersAndSymbols(s, join);
            joinCounts(s, join);
            joinBeforeClosing(s, join);
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

    /** Longest quoted name (characters inside 「」 or “”) kept on one line in guide text. */
    static final int MAX_QUOTED = 8;

    /** A short quoted name (「「死亡之怖」」, 「“山间补习班”」) stays whole with its brackets. */
    private static void joinShortBrackets(String s, boolean[] join, char open, char close) {
        for (int at = s.indexOf(open); at >= 0; at = s.indexOf(open, at + 1)) {
            int end = s.indexOf(close, at + 1);
            if (end < 0) return;
            if (visibleLength(s, at + 1, end) <= MAX_QUOTED) joinRange(s, join, at, end + 1);
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
     * be one unbreakable run. Spaces stay where they are: a space is a deliberate break.
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
