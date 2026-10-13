package app.p3r.dayplanner.v3.ui;

import android.text.SpannableStringBuilder;
import android.text.Spanned;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.p3r.dayplanner.v3.model.Rules;

/**
 * Simplified Chinese wording and number formatting used across the v3 screens.
 *
 * <p>Conventions (docs/DESIGN_v3.md §4, §7, docs/GLOSSARY_zh.md): link ranks are "RANK N", and
 * "MAX" alone at 10 ("升到 MAX", never "RANK MAX"), stats are
 * "Lv.N" (the top level is "MAX"), money is "8,200 日元", dates are "10月22日", the free daytime block is "放学后" on school
 * days and "白天" otherwise, and the evening is "晚上".
 */
public final class Zh {
    /** Day ordinal 0 of the calendar axis. */
    private static final LocalDate EPOCH = LocalDate.of(2009, 4, 1);
    private static final String[] WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final String[] ROMAN = {
        "0", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X",
        "XI", "XII", "XIII", "XIV", "XV", "XVI", "XVII", "XVIII", "XIX", "XX", "XXI"
    };

    private Zh() {}

    /** "8,200" — thousands separators, no unit. */
    public static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /** "8,200 日元". */
    public static String yen(long value) {
        return number(value) + " 日元";
    }

    /** "+2,500 日元" / "−1,000 日元" (typographic minus). */
    public static String yenDelta(long value) {
        if (value >= 0) {
            return "+" + yen(value);
        }
        return "−" + yen(-value);
    }

    /** Calendar date for a day ordinal (days since 2009-04-01). */
    public static LocalDate dateOf(int day) {
        return EPOCH.plusDays(day);
    }

    /** Day ordinal for a calendar date. */
    public static int dayOf(LocalDate date) {
        return (int) (date.toEpochDay() - EPOCH.toEpochDay());
    }

    /** "10月22日". */
    public static String date(int day) {
        LocalDate date = dateOf(day);
        return date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
    }

    /** "10/22" — the numeral form used on date plates. */
    public static String shortDate(int day) {
        LocalDate date = dateOf(day);
        return date.getMonthValue() + "/" + date.getDayOfMonth();
    }

    /** "周四" for ISO weekday 1..7. */
    public static String weekday(int isoWeekday) {
        if (isoWeekday < 1 || isoWeekday > 7) {
            return "";
        }
        return WEEKDAYS[isoWeekday - 1];
    }

    /** "周四" for a day ordinal (independent of the device date). */
    public static String weekdayOf(int day) {
        return weekday(dateOf(day).getDayOfWeek().getValue());
    }

    /** "放学后" / "白天" / "晚上" for a slot. */
    public static String period(Rules rules, int slot) {
        if (Rules.isEvening(slot)) {
            return "晚上";
        }
        Rules.Day day = rules == null ? null : rules.day(Rules.dayOf(slot));
        if (day != null && (day.type == Rules.DayType.SCHOOL || day.type == Rules.DayType.EXAM)) {
            return "放学后";
        }
        return "白天";
    }

    /** "10月22日 晚上". */
    public static String slot(Rules rules, int slot) {
        return date(Rules.dayOf(slot)) + " " + period(rules, slot);
    }

    /** "RANK 7" / "MAX" / "未解锁" — the glossary writes 「升到 MAX」「已经 MAX」, never "RANK MAX". */
    public static String rank(int rank) {
        if (rank <= 0) {
            return "未解锁";
        }
        if (rank >= 10) {
            return "MAX";
        }
        return "RANK " + rank;
    }

    /** "7" / "MAX" (no prefix) for compact displays. */
    public static String rankValue(int rank) {
        if (rank >= 10) {
            return "MAX";
        }
        return String.valueOf(Math.max(0, rank));
    }

    /** "RANK 6 → 7" / "RANK 9 → MAX"; a link that is not unlocked yet reads "未解锁 → RANK 2". */
    public static String rankStep(int from, int to) {
        if (from <= 0) {
            return "未解锁 → " + rank(to);
        }
        return "RANK " + rankValue(from) + " → " + rankValue(to);
    }

    /** "Lv.3"; the top level (6) is written "MAX", as the game and the guide do ("勇气MAX"). */
    public static String lv(int level) {
        return level >= 6 ? "MAX" : "Lv." + level;
    }

    /** "1" … "5", "MAX" — compact stat level labels for segmented pickers. */
    public static String lvValue(int level) {
        return level >= 6 ? "MAX" : String.valueOf(Math.max(1, level));
    }

    /**
     * A pack text as one sentence: trimmed, ending with exactly one sentence mark ("。" added only
     * when missing), or "" when there is no text. Pack fields such as {@code rewardZh} are already
     * full sentences, so they are shown as they are instead of being glued into another sentence.
     */
    public static String sentence(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        while (t.endsWith("。。")) {
            t = t.substring(0, t.length() - 1);
        }
        if (t.isEmpty()) {
            return "";
        }
        boolean closed = t.endsWith("。") || t.endsWith("！") || t.endsWith("？") || t.endsWith("。」");
        return closed ? t : t + "。";
    }

    /** {@link #sentence} without its final "。" (for "·"-separated lines). */
    public static String clause(String text) {
        String t = sentence(text);
        return t.endsWith("。") ? t.substring(0, t.length() - 1) : t;
    }

    /** "5月12日–1月29日" for a day range ("5月12日" when both ends are the same day). */
    public static String dateRange(int fromDay, int toDay) {
        return fromDay == toDay ? date(fromDay) : date(fromDay) + "–" + date(toDay);
    }

    /**
     * "小田桐秀利 · 男"; a long name puts the gender on its own line ("特别课外活动部\n团体 · 男女")
     * so it is never cut off. Every "·" has a space on both sides, as everywhere else.
     */
    public static String person(Rules.Link link) {
        String gender = dots(link.genderZh);
        String one = link.personZh + " · " + gender;
        return one.length() > 11 ? link.personZh + "\n" + gender : one;
    }

    /** "团体·男女" → "团体 · 男女": the separator dot always has a space on both sides. */
    public static String dots(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().replaceAll("\\s*[·・]\\s*", " · ");
    }

    /** Roman numeral for an arcana number ("0" for the Fool). */
    public static String roman(int arcanaNo) {
        if (arcanaNo < 0 || arcanaNo >= ROMAN.length) {
            return String.valueOf(arcanaNo);
        }
        return ROMAN[arcanaNo];
    }

    /** "5月2日 晚上" for the slot that was last recorded (the one before {@code nextSlot}). */
    public static String lastRecorded(Rules rules, int nextSlot) {
        return slot(rules, Math.max(0, nextSlot - 1));
    }

    /** "3天前" style relative age for real-world timestamps. */
    public static String age(long nowMillis, long thenMillis) {
        long minutes = Math.max(0, (nowMillis - thenMillis) / 60_000L);
        if (minutes < 60) {
            return minutes <= 1 ? "刚刚" : minutes + " 分钟前";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " 小时前";
        }
        return (hours / 24) + " 天前";
    }

    // ------------------------------------------------------------------ line breaks

    /** U+2060 WORD JOINER: invisible, and no line break on either side of it. */
    public static final char WJ = '⁠';

    /** Glossary terms that are never split across lines. */
    private static final String[] TERMS = {
        "人格面具", "阿尔卡那", "塔尔塔罗斯", "羁绊剧情", "个人能力值", "结缘网站的字条", "失踪者", "满月作战",
        "修学旅行", "平安夜", "影时间", "自由活动", "放学后", "见面", "学力", "魅力", "勇气",
    };

    /**
     * Short tags kept on one line: "本区块第 2 次", "第 3 话", "共 3 步", dates, "RANK 7", amounts,
     * counts, short 「…」 names and a short （…） note together with the character before it.
     */
    private static final Pattern TAGS = Pattern.compile(
        "本区块第\\s*\\d+\\s*次"
            + "|(?:学力|魅力|勇气)\\s*[+−]\\d+"
            + "|[^\\s，。；：、]{2}\\s?×\\s?\\d[\\d.]*"
            + "|[第共]\\s*\\d+\\s*[次话步天个项]"
            + "|\\d{1,2}月\\d{1,2}日"
            + "|RANK\\s+\\d+"
            + "|[+−-]?\\d[\\d,]*\\s*日元"
            + "|\\d[\\d,.]*\\s*[次话个点层件天项步级张]"
            + "|「[^「」\\n]{1,10}」"
            + "|[^\\s（）\\n·、，。；：/]（[^（）\\n]{1,8}）");

    private static volatile Object namesOwner;
    private static volatile Pattern names = buildNames(new LinkedHashSet<>());

    /** "舞⁠子": {@code text} with a word joiner between every two characters (one unbreakable run). */
    public static String glue(String text) {
        if (text == null || text.length() < 2) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length() * 2);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (i > 0 && joinable(text.charAt(i - 1), ch)) {
                sb.append(WJ);
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    private static boolean joinable(char before, char ch) {
        return ch != WJ && before != WJ && before != '\n' && ch != '\n' && !Character.isHighSurrogate(before);
    }

    /** {@code text} without word joiners (comparisons, measuring). */
    public static String plain(CharSequence text) {
        return text == null ? "" : text.toString().replace(String.valueOf(WJ), "");
    }

    /**
     * Makes the names of {@code rules} (arcana, people, missing persons, Tartarus blocks, venues,
     * Link Episode people) known to {@link #keepTogether}. Screens call it when their data loads.
     */
    public static void useNames(Rules rules) {
        if (rules == null || rules == namesOwner) {
            return;
        }
        Set<String> tokens = new LinkedHashSet<>();
        if (rules.links != null) {
            for (Rules.Link l : rules.links) {
                addToken(tokens, l.arcanaZh);
                if (l.personZh != null) {
                    addToken(tokens, l.personZh);
                    for (String part : l.personZh.split("[、，,]")) {
                        addToken(tokens, part);
                    }
                }
                addVenue(tokens, l.locationZh);
            }
        }
        if (rules.rescues != null) {
            for (Rules.Rescue r : rules.rescues) {
                addToken(tokens, r.zh);
                if (r.zh != null) {
                    addToken(tokens, "救出" + r.zh.trim());
                }
            }
        }
        if (rules.blocks != null) {
            for (Rules.Block b : rules.blocks) {
                addToken(tokens, b.zh);
                if (b.zh != null) {
                    for (String part : b.zh.trim().split("\\s+")) {
                        addToken(tokens, part);
                    }
                }
            }
        }
        if (rules.episodes != null) {
            for (Rules.Episode e : rules.episodes) {
                addToken(tokens, e.personZh);
                addVenue(tokens, e.locationZh);
            }
        }
        if (rules.activities != null) {
            for (Rules.Activity a : rules.activities) {
                addVenue(tokens, a.locationZh);
            }
        }
        if (rules.datingNote != null) {
            addVenue(tokens, rules.datingNote.locationZh);
        }
        names = buildNames(tokens);
        namesOwner = rules;
    }

    /** Venue parts of a location line ("桐叶购物中心 · 放纵俱乐部 2 楼"). */
    private static void addVenue(Set<String> tokens, String location) {
        if (location == null) {
            return;
        }
        for (String segment : location.split("[·・／/→]")) {
            String seg = segment.trim();
            String[] words = seg.split("\\s+");
            boolean allWords = words.length > 1;
            for (String w : words) {
                allWords &= w.length() >= 2 && w.codePoints().allMatch(cp -> cp >= 0x2E80);
            }
            if (allWords) {
                for (String w : words) {
                    addToken(tokens, w);
                }
            } else {
                addToken(tokens, seg);
            }
        }
    }

    private static void addToken(Set<String> tokens, String token) {
        if (token == null) {
            return;
        }
        String t = token.trim();
        if (t.length() >= 2 && t.length() <= 12 && t.codePoints().anyMatch(cp -> cp >= 0x2E80)) {
            tokens.add(t);
        }
    }

    private static Pattern buildNames(Set<String> tokens) {
        List<String> all = new ArrayList<>(tokens);
        for (String term : TERMS) {
            all.add(term);
        }
        all.sort(Comparator.comparingInt(String::length).reversed());
        StringBuilder sb = new StringBuilder();
        for (String t : all) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(Pattern.quote(t));
        }
        return Pattern.compile(sb.toString());
    }

    /**
     * {@code text} with word joiners inside every name, venue, glossary term and short tag
     * ("救出舞子", "本区块第 2 次", "10月22日", "RANK 7", "2,500 日元", "「皇帝」", "（1）" with the
     * character before it), each short answer of a "参考回答：" line, and every two- to
     * four-character Chinese word, so a line never breaks inside one. Idempotent; spans are kept.
     */
    public static CharSequence keepTogether(CharSequence text) {
        if (text == null || text.length() < 2) {
            return text;
        }
        String original = text.toString();
        StringBuilder flat = new StringBuilder(original.length());
        int[] at = new int[original.length()];
        for (int i = 0; i < original.length(); i++) {
            char ch = original.charAt(i);
            if (ch != WJ) {
                at[flat.length()] = i;
                flat.append(ch);
            }
        }
        List<int[]> ranges = new ArrayList<>();
        collect(names.matcher(flat), ranges);
        collect(TAGS.matcher(flat), ranges);
        answers(flat, ranges);
        words(flat, ranges);
        if (ranges.isEmpty()) {
            return text;
        }
        List<Integer> inserts = new ArrayList<>();
        boolean[] join = new boolean[flat.length()];
        for (int[] r : ranges) {
            for (int k = r[0] + 1; k < r[1]; k++) {
                join[k] = true;
            }
        }
        for (int k = 1; k < flat.length(); k++) {
            if (!join[k]) {
                continue;
            }
            int prev = at[k - 1];
            int next = at[k];
            if (next == prev + 1 && joinable(original.charAt(prev), original.charAt(next))) {
                inserts.add(next);
            }
        }
        if (inserts.isEmpty()) {
            return text;
        }
        if (text instanceof Spanned) {
            SpannableStringBuilder sb = new SpannableStringBuilder(text);
            for (int i = inserts.size() - 1; i >= 0; i--) {
                sb.insert(inserts.get(i), String.valueOf(WJ));
            }
            return sb;
        }
        StringBuilder sb = new StringBuilder(original);
        for (int i = inserts.size() - 1; i >= 0; i--) {
            sb.insert((int) inserts.get(i), WJ);
        }
        return sb.toString();
    }

    private static void collect(Matcher m, List<int[]> out) {
        while (m.find()) {
            if (m.end() - m.start() >= 2) {
                out.add(new int[]{m.start(), m.end()});
            }
        }
    }

    /** Longest answer of a "参考回答：A / B" line kept on one line as a whole. */
    private static final int MAX_ANSWER = 10;

    /** Each short answer of a "参考回答：A / B；…" line, so lines only break between answers. */
    private static void answers(CharSequence flat, List<int[]> out) {
        String s = flat.toString();
        String prefix = "参考回答：";
        int at = s.indexOf(prefix);
        if (at < 0) {
            return;
        }
        int start = at + prefix.length();
        int end = s.indexOf('；', start);
        if (end < 0) {
            end = s.length();
        }
        int from = start;
        while (from < end) {
            int sep = s.indexOf(" / ", from);
            int to = sep < 0 || sep > end ? end : sep;
            String answer = s.substring(from, to).trim();
            if (answer.length() >= 2 && answer.length() <= MAX_ANSWER) {
                int a = s.indexOf(answer, from);
                out.add(new int[]{a, a + answer.length()});
            }
            from = to + (to == end ? 0 : 3);
            if (to == end) {
                break;
            }
        }
    }

    /** Dictionary word segmentation (ICU), one per thread; null when unavailable. */
    private static final ThreadLocal<android.icu.text.BreakIterator> WORDS = new ThreadLocal<>();

    /**
     * Two- to four-character Chinese words found by ICU's dictionary segmentation ("规矩", "奇怪",
     * "和尚"), so ordinary text also breaks between words, never inside one.
     */
    private static void words(CharSequence flat, List<int[]> out) {
        android.icu.text.BreakIterator it;
        try {
            it = WORDS.get();
            if (it == null) {
                it = android.icu.text.BreakIterator.getWordInstance(android.icu.util.ULocale.SIMPLIFIED_CHINESE);
                WORDS.set(it);
            }
            it.setText(flat.toString());
        } catch (RuntimeException | LinkageError unavailable) {
            return;
        }
        int start = it.first();
        for (int end = it.next(); end != android.icu.text.BreakIterator.DONE; start = end, end = it.next()) {
            int length = end - start;
            if (length < 2 || length > 4) {
                continue;
            }
            boolean han = true;
            for (int i = start; i < end && han; i++) {
                han = Character.UnicodeScript.of(flat.charAt(i)) == Character.UnicodeScript.HAN;
            }
            if (han) {
                out.add(new int[]{start, end});
            }
        }
    }
}
