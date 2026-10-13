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
    /** Short label next to the ticks, e.g. "今日", "明天", "3/8"; null hides it. */
    public String progressLabel;
    public final List<Tick> ticks = new ArrayList<>();
    public String progressDescription = "";

    // ---- body
    public String title = "";
    /** Compact subtitle chip such as "RANK 6→7" or "学力 +2"; null hides it. */
    public String tag;
    public String location;
    /** One extra line such as "✓ 已记录 · 再点撤销"; null hides it. */
    public String cue;
    /** Keep the cue even when space is short (it replaces information that did not fit). */
    public boolean cueEssential;
    /** Legacy guide only: dialogue choices joined with " / "; null hides them. */
    public String dialogue;
    public String taskDescription = "";
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
        m.tag = tag;
        m.location = location;
        m.cue = cue;
        m.cueEssential = cueEssential;
        m.dialogue = dialogue;
        m.taskDescription = taskDescription;
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
