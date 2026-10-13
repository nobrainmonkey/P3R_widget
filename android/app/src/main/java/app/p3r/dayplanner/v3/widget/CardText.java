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
        if (words != null) all.addAll(words);
        joinWords(s, join, all);
        joinBrackets(s, join, '「', '」');
        return build(s, join);
    }

    /** Title shaping without extra words. */
    public static String title(String text) {
        return title(text, null);
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
     * 「（1）」 stays with the character before it and a line never starts with a slash.
     */
    public static String choicesLoose(String text) {
        if (text == null) return null;
        String s = strip(text);
        String separator = NBSP + "/ ";
        String[] parts = s.replace(separator, CHOICE_SEPARATOR).split(Pattern.quote(CHOICE_SEPARATOR), -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append(separator);
            String part = parts[i];
            boolean[] join = new boolean[part.length() + 1];
            int open = part.lastIndexOf('（');
            if (open > 0 && part.endsWith("）")) joinRange(part, join, open - 1, part.length());
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
