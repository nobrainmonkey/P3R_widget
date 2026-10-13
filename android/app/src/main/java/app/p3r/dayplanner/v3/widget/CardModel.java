package app.p3r.dayplanner.v3.widget;

import java.util.ArrayList;
import java.util.List;

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
    /** "白天" / "放学后" / "晚上" (or the legacy guide's period word); null hides it. */
    public String periodZh;
    /** Spoken form of the plate for accessibility, e.g. "10月22日 周四 放学后". */
    public String dateDescription = "";
    public boolean arrowsVisible = true;
    public boolean prevEnabled = true;
    public boolean nextEnabled = true;

    // ---- background
    /** Day of month drawn as the outlined watermark, or -1 for none. */
    public int watermarkDay = -1;
    /** Light-shaft composition 0..3 (chosen from the game date so both widgets agree). */
    public int motif;

    // ---- status cluster (top right)
    /** Short label next to the ticks, e.g. "今天", "明天", "3/8"; null hides it. */
    public String progressLabel;
    public final List<Tick> ticks = new ArrayList<>();
    public String progressDescription = "";

    // ---- body
    public String title = "";
    public TitleIcon titleIcon = TitleIcon.NONE;
    /**
     * Hang the title from the top of its box instead of sitting it on the tag row, so the kind mark
     * stays on its first line when it wraps. Set by {@link CardRenderer#fit}.
     */
    public boolean titleTop;
    /** Compact subtitle chip such as "RANK 6→7" or "学力 +2"; null hides it. */
    public String tag;
    /**
     * Widest the tag chip may be (dp, padding included) so the location keeps some room; 0 means
     * unbounded. Set by {@link CardRenderer#fit}; the chip ellipsizes beyond it.
     */
    public float tagMaxWidthDp;
    public String location;
    /** Short reminder line such as "记得带上「皇帝」阿尔卡那的人格面具"; null hides it. */
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
     * Dialogue choices joined with " / ": the legacy guide's choices (already shaped for line breaks
     * by the guide widget), or the plan widget's 「参考回答：A / B」 line from the guide (plain text;
     * with {@link #keepWordsWhole} {@link CardRenderer} keeps every choice whole, see
     * {@link CardText#choices}); null hides them.
     */
    public String dialogue;
    /**
     * The dialogue line may be left out: {@link CardRenderer#fit} drops it when it would need more
     * than {@link CardRenderer#MAX_DIALOGUE_LINES} lines or push the title below a comfortable size
     * (plan widget). The legacy guide decides about its choices itself.
     */
    public boolean dialogueOptional;
    /**
     * Keep names in the title whole when it wraps ({@link CardText#title}: 「皇帝 ·」/「小田桐秀利」,
     * never 「小田桐/秀利」). The legacy guide's free-form titles keep their plain breaks.
     */
    public boolean keepWordsWhole;
    public String taskDescription = "";
    /** Drop the body's vertical padding to make room (legacy dialogue choices). */
    public boolean tightBody;
    /** Title line limit; the layout's autosize shrinks the text to fit within it. */
    public int titleMaxLines = 3;

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
        m.motif = motif;
        m.progressLabel = progressLabel;
        m.ticks.addAll(ticks);
        m.progressDescription = progressDescription;
        m.title = title;
        m.titleIcon = titleIcon;
        m.titleTop = titleTop;
        m.tag = tag;
        m.tagMaxWidthDp = tagMaxWidthDp;
        m.location = location;
        m.hint = hint;
        m.side = side;
        m.cue = cue;
        m.cueEssential = cueEssential;
        m.cueMaxLines = cueMaxLines;
        m.dialogue = dialogue;
        m.dialogueOptional = dialogueOptional;
        m.keepWordsWhole = keepWordsWhole;
        m.taskDescription = taskDescription;
        m.tightBody = tightBody;
        m.titleMaxLines = titleMaxLines;
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
