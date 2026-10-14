package app.p3r.dayplanner.v3.widget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Everything one widget card shows, as plain data. Built off the main thread by the providers and
 * turned into {@link android.widget.RemoteViews} by {@link CardRenderer}. Click targets are not part
 * of the model; each provider attaches its own PendingIntents to the rendered views.
 *
 * <p>All user-visible strings are final Simplified Chinese copy (or Latin numerals); null hides the
 * corresponding view.
 */
public final class CardModel {

    /** Progress tick glyphs, one per slot/task of the shown day. */
    public enum Tick { DONE, PENDING, CURRENT, LOCKED }

    /** Icon on the primary button. */
    public enum DoneIcon { CHECK, UNDO, NONE }

    /** Kind glyph left of the title (only the step kinds that need telling apart at a glance). */
    public enum TitleIcon { NONE, NOTE, EPISODE }

    /** Use the night palette (evening slot). */
    public boolean night;

    // ---- calendar plate
    /** Latin numerals such as "10/22"; the brand "P3R" when there is no date yet. */
    public String dateText = "";
    /** "周四", or null to hide the weekday chip. */
    public String weekdayZh;
    /** Moon phase 0..7 (4 = full), or -1 to hide the glyph. */
    public int moonPhase = -1;
    /** "白天" / "放学后" / "晚上"; null hides it. */
    public String periodZh;
    /** Spoken form of the plate for accessibility, e.g. "10月22日 周四 放学后". */
    public String dateDescription = "";
    public boolean arrowsVisible = true;
    public boolean prevEnabled = true;
    public boolean nextEnabled = true;

    // ---- background
    /** Day of month drawn as the outlined watermark, or -1 for none. */
    public int watermarkDay = -1;
    /**
     * Text runs behind the watermark: draw it fainter so it never competes with the words. Set by
     * {@link CardRenderer#fit}.
     */
    public boolean watermarkFaint;
    /** Light-shaft composition 0..3 (chosen from the game date so every card of a day agrees). */
    public int motif;

    // ---- status cluster (top right)
    /** Short label next to the ticks, e.g. "今天", "明天", "3/8"; null hides it. */
    public String progressLabel;
    public final List<Tick> ticks = new ArrayList<>();
    public String progressDescription = "";

    // ---- body
    public String title = "";
    /**
     * A shorter title for a card too small for the whole one (「岳羽由加莉」 beside the note mark for
     * 「结缘网站的字条 · 岳羽由加莉」): {@link CardRenderer#fit} shows it rather than cut a name with "…".
     * Null when there is none.
     */
    public String titleShort;
    public TitleIcon titleIcon = TitleIcon.NONE;
    /**
     * Hang the title from the top of its box instead of sitting it on the tag row, so the kind mark
     * stays on its first line when it wraps. Set by {@link CardRenderer#fit}.
     */
    public boolean titleTop;
    /** Compact subtitle chip such as "RANK 6→7" or "学力 +2"; null hides it. Never cut short. */
    public String tag;
    /**
     * Text size (sp) of a chip a little too wide for the card, set a step smaller so it stays whole
     * on one line; 0 for the layout's size. Set by {@link CardRenderer#fit}.
     */
    public float tagTextSp;
    /**
     * Lines of the tag chip: 2 when it is wider than the card even at the smaller size, so it wraps
     * after a 「、」 instead of ending in "…". Set by {@link CardRenderer#fit}.
     */
    public int tagMaxLines = 1;
    /**
     * Where to go. {@link CardRenderer#fit} shows the longest of {@link CardText#locationForms} that
     * fits beside the chip, and none when even the shortest does not.
     */
    public String location;
    /** The 人格面具 reminder such as "记得带上「皇帝」阿尔卡那的人格面具"; null hides it. */
    public String hint;
    /** Side steps done in the same slot, e.g. "顺便：把 20,000 日元交给穿西装的男人"; null hides it. */
    public String side;
    /** One extra line such as "✓ 已记录 · 再点撤销"; null hides it. */
    public String cue;
    /** Keep the cue even when space is short (it replaces information that did not fit). */
    public boolean cueEssential;
    /**
     * Lines the cue may wrap to: a status card's cue is its instruction (「游戏资料读取失败，请重新打开应用」)
     * and gets two; {@link CardRenderer#fit} falls back to one line when the card is too short.
     */
    public int cueMaxLines = 1;
    /**
     * The answers row (docs/DESIGN_v3.md §18), one labelled line each, in this order: the step's own
     * answers (「答：A / B」, 「要是升级了，答：A / B」, an unlock step's picks), the side steps' picks
     * (「顺便选：有话想和你说」), then the class and exam answers of the card's slots (「课堂：马（第 2 项）」).
     * Plain text; {@link CardRenderer#fit} picks how to draw them ({@link #answersShown}) and never
     * drops them. Empty for none.
     */
    public final List<AnswerLine> answers = new ArrayList<>();
    /**
     * The other 当日提醒 the card carries, most important first: {@link CardRenderer#fit} shows the
     * first on the reminder row whole or in its short form, with 「 · 共 N 条」 when there are more
     * ({@link #reminderShown}). Empty for none.
     */
    public final List<ReminderLine> reminders = new ArrayList<>();
    /**
     * The answers row as drawn: one paragraph per {@link #answers} line, shaped for line breaks;
     * null hides the row. Set by {@link CardRenderer#fit}.
     */
    public String answersShown;
    /** Text size (sp) of the answers row; 0 for the layout's. Set by {@link CardRenderer#fit}. */
    public float answersTextSp;
    /** The answers row gives up its gap above (the shortest cards). Set by {@link CardRenderer#fit}. */
    public boolean answersTight;
    /** The reminder row as drawn, or null to hide it. Set by {@link CardRenderer#fit}. */
    public String reminderShown;
    /**
     * The title row is left out: on the shortest cards the answers need its room, and they are never
     * hidden (the card's description still names the step). Set by {@link CardRenderer#fit}.
     */
    public boolean titleHidden;
    /**
     * Keep names in the title whole when it wraps ({@link CardText#title}: 「皇帝」/「小田桐秀利」,
     * never 「小田桐/秀利」).
     */
    public boolean keepWordsWhole;
    public String taskDescription = "";
    /** Title line limit; the layout's autosize shrinks the text to fit within it. */
    public int titleMaxLines = 3;
    /**
     * Room (dp) the body's text leaves on a tall card, shared out by {@link CardRenderer#fit}: above
     * and below the text (so it sits centred between the plate and the buttons) and under the buttons.
     * Negative above/below: body padding given up so an instruction keeps its second line.
     */
    public float spaceAboveDp;
    public float spaceBelowDp;
    public float spaceUnderButtonsDp;

    // ---- actions
    public boolean currentVisible = true;
    public boolean currentEnabled = true;
    public String currentLabel = "当前";
    public String currentDescription = "当前：回到下一项要做的安排";
    public boolean doneEnabled = true;
    public String doneLabel = "完成";
    public DoneIcon doneIcon = DoneIcon.CHECK;
    public String doneDescription = "完成：记录这一项";

    public WidgetPalette palette() {
        return WidgetPalette.of(night);
    }

    /** Labels of the class and exam answers of a card's slots in the answers row. */
    public static final String CLASS_LABEL = "课堂：";
    public static final String EXAM_LABEL = "考试：";

    /**
     * One labelled line of the answers row: 「答：A / B · 别选表白」, 「要是升级了，答：A / B」,
     * 「顺便选：有话想和你说」, 「课堂：马（第 2 项）」.
     */
    public static final class AnswerLine {
        /** 「答：」, 「要是升级了，答：」, 「顺便选：」, 「课堂：」, 「考试：」. */
        public final String label;
        /** A shorter label for a card too narrow for {@link #label} (「升级时答：」), or null. */
        public final String shortLabel;
        /** The choices in order; may be empty. */
        public final List<String> choices;
        /**
         * What follows the choices (「别选表白」, 「别选「我喜欢风花」」), or the whole line when there are
         * none (「怎么回答都可以」, 「别选表白的回答」); null for nothing.
         */
        public final String note;
        /** The meeting may not rank up after all: drawn in a muted colour. */
        public final boolean conditional;

        public AnswerLine(String label, String shortLabel, List<String> choices, String note, boolean conditional) {
            this.label = label == null ? "" : label;
            this.shortLabel = shortLabel;
            this.choices = Collections.unmodifiableList(new ArrayList<>(choices));
            this.note = note;
            this.conditional = conditional;
        }

        /** A class answer's option position as it ends the choice: 「（第 2 项）」. */
        static String position(int option) {
            return "（第 " + option + " 项）";
        }

        private static final Pattern POSITION = Pattern.compile("（第 \\d+ 项）$");

        /**
         * This line without a class answer's option position (「课堂：马」 for 「课堂：马（第 2 项）」): the
         * answer names the option itself, so a card short of room leaves the position out before it
         * cuts any choice. The line itself when it has none.
         */
        public AnswerLine withoutPosition() {
            List<String> bare = new ArrayList<>(choices.size());
            boolean changed = false;
            for (String choice : choices) {
                String cut = POSITION.matcher(choice).replaceFirst("");
                boolean keep = cut.isEmpty() || cut.equals(choice);
                changed |= !keep;
                bare.add(keep ? choice : cut);
            }
            return changed ? new AnswerLine(label, shortLabel, bare, note, conditional) : this;
        }

        /** The whole line on one line, as accessibility and tests read it. */
        public String text() {
            StringBuilder sb = new StringBuilder(label).append(String.join(" / ", choices));
            if (note != null) sb.append(choices.isEmpty() ? "" : " · ").append(note);
            return sb.toString();
        }

        @Override public String toString() {
            return text();
        }
    }

    /** One 当日提醒 for the reminder row: the whole line and the short form for a small card (or null). */
    public static final class ReminderLine {
        public final String text;
        public final String shortText;

        public ReminderLine(String text, String shortText) {
            this.text = text;
            this.shortText = shortText;
        }

        @Override public String toString() {
            return text;
        }
    }

    /** Shallow copy (the tick list is copied). */
    public CardModel copy() {
        return withNight(night);
    }

    /** Shallow copy with a forced palette (used for the day/night slots of the fade host). */
    public CardModel withNight(boolean forcedNight) {
        CardModel m = new CardModel();
        m.night = forcedNight;
        m.dateText = dateText;
        m.weekdayZh = weekdayZh;
        m.moonPhase = moonPhase;
        m.periodZh = periodZh;
        m.dateDescription = dateDescription;
        m.arrowsVisible = arrowsVisible;
        m.prevEnabled = prevEnabled;
        m.nextEnabled = nextEnabled;
        m.watermarkDay = watermarkDay;
        m.watermarkFaint = watermarkFaint;
        m.motif = motif;
        m.progressLabel = progressLabel;
        m.ticks.addAll(ticks);
        m.progressDescription = progressDescription;
        m.title = title;
        m.titleShort = titleShort;
        m.titleIcon = titleIcon;
        m.titleTop = titleTop;
        m.tag = tag;
        m.tagTextSp = tagTextSp;
        m.tagMaxLines = tagMaxLines;
        m.location = location;
        m.hint = hint;
        m.side = side;
        m.cue = cue;
        m.cueEssential = cueEssential;
        m.cueMaxLines = cueMaxLines;
        m.answers.addAll(answers);
        m.reminders.addAll(reminders);
        m.answersShown = answersShown;
        m.answersTextSp = answersTextSp;
        m.answersTight = answersTight;
        m.reminderShown = reminderShown;
        m.titleHidden = titleHidden;
        m.keepWordsWhole = keepWordsWhole;
        m.taskDescription = taskDescription;
        m.titleMaxLines = titleMaxLines;
        m.spaceAboveDp = spaceAboveDp;
        m.spaceBelowDp = spaceBelowDp;
        m.spaceUnderButtonsDp = spaceUnderButtonsDp;
        m.currentVisible = currentVisible;
        m.currentEnabled = currentEnabled;
        m.currentLabel = currentLabel;
        m.currentDescription = currentDescription;
        m.doneEnabled = doneEnabled;
        m.doneLabel = doneLabel;
        m.doneIcon = doneIcon;
        m.doneDescription = doneDescription;
        return m;
    }
}
