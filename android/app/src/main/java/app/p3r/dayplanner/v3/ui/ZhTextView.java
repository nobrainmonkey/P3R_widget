package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.UpdateAppearance;
import android.widget.TextView;

/**
 * The text view of every v3 screen ({@link Ui#text}). Text set on it, at creation or later, is
 * shaped the same way: names, words and short tags kept whole ({@link Ui#prepare},
 * {@link Zh#keepTogether}), and a line never ends with a " · " or "/" separator. The dot stays on
 * the line of the part before it (no break before it); where the text wraps right after it, it
 * is not drawn, so the next part simply starts the next line ("RANK 4" / "见面（预计不升级）"). A
 * slash goes with the part after it: a " / " separator starts the next line ("回答：我会帮你的"
 * / "/ 这样的规矩没有意义"), and "道具/特性" never breaks after its slash.
 */
final class ZhTextView extends TextView {
    ZhTextView(Context context) {
        super(context);
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        super.setText(separators(Ui.prepare(this, text)), type);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        hideDotsAtLineEnds(getLayout(), getText());
        super.onDraw(canvas);
    }

    /** A " · " separator dot; not drawn while it ends a line. */
    static final class Dot extends CharacterStyle implements UpdateAppearance {
        boolean hidden;

        @Override
        public void updateDrawState(TextPaint paint) {
            if (hidden) {
                paint.setColor(Color.TRANSPARENT);
                // A heading's drop shadow would still draw the dot.
                paint.clearShadowLayer();
            }
        }
    }

    /**
     * {@code text} with a {@link Dot} on every separator dot, joined to the text before it, and
     * every slash joined to the text after it ({@link #bindSlash}).
     */
    static CharSequence separators(CharSequence text) {
        if (text == null || text.length() < 3 || (indexOfDot(text, 0) < 0 && indexOfSlash(text, 0) < 0)) {
            return text;
        }
        SpannableStringBuilder out = new SpannableStringBuilder(text);
        for (Dot old : out.getSpans(0, out.length(), Dot.class)) {
            out.removeSpan(old);
        }
        for (int at = indexOfDot(out, 0); at >= 0; at = indexOfDot(out, at + 1)) {
            if (out.charAt(at - 1) != Zh.WJ) {
                out.insert(at, String.valueOf(Zh.WJ));
                at++;
            }
            out.setSpan(new Dot(), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        for (int at = indexOfSlash(out, 0); at >= 0; at = indexOfSlash(out, at + 1)) {
            at = bindSlash(out, at);
        }
        return out;
    }

    /**
     * Index of the next slash ("/" or "／") from {@code from} with more text on its line, or -1. A
     * slash between digits ("10/22") is left alone: a number never breaks there anyway.
     */
    private static int indexOfSlash(CharSequence text, int from) {
        for (int i = Math.max(1, from); i < text.length() - 1; i++) {
            char ch = text.charAt(i);
            char next = text.charAt(i + 1);
            if ((ch == '/' || ch == '／') && next != '\n'
                && !(Character.isDigit(text.charAt(i - 1)) && Character.isDigit(next))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Joins the slash at {@code at} to the text after it ("道具/⁠特性"). A " / " separator also keeps
     * its space with the next part and gets a {@link Zh#BREAK} in front, as Unicode never breaks
     * before a slash and the line would end with it ("我会帮你的 \u200B/⁠ ⁠这样的…"). Idempotent;
     * returns the slash's new index.
     */
    private static int bindSlash(SpannableStringBuilder out, int at) {
        if (out.charAt(at - 1) == ' ') {
            out.insert(at, String.valueOf(Zh.BREAK));
            at++;
        }
        int after = joinAfter(out, at);
        if (after + 1 < out.length() && out.charAt(after) == ' ') {
            joinAfter(out, after);
        }
        return at;
    }

    /** A word joiner right after {@code at} unless there is one; returns the index after it. */
    private static int joinAfter(SpannableStringBuilder out, int at) {
        if (out.charAt(at + 1) != Zh.WJ) {
            out.insert(at + 1, String.valueOf(Zh.WJ));
        }
        return at + 2;
    }

    /**
     * Index of the next separator dot from {@code from}: a "·" after a space or a closing bracket
     * ("（2–22F）· 本区块第 1 次") and before a space, or -1.
     */
    private static int indexOfDot(CharSequence text, int from) {
        for (int i = Math.max(1, from); i < text.length() - 1; i++) {
            if (text.charAt(i) != '·' || text.charAt(i + 1) != ' ') {
                continue;
            }
            int before = i - 1;
            while (before > 0 && text.charAt(before) == Zh.WJ) {
                before--;
            }
            char ch = text.charAt(before);
            if (ch == ' ' || ch == '）' || ch == '」' || ch == ')') {
                return i;
            }
        }
        return -1;
    }

    /** Marks each separator dot hidden exactly when it is the last visible character of a line. */
    static void hideDotsAtLineEnds(Layout layout, CharSequence text) {
        if (layout == null || !(text instanceof Spanned)) {
            return;
        }
        Spanned spanned = (Spanned) text;
        for (Dot dot : spanned.getSpans(0, spanned.length(), Dot.class)) {
            int at = spanned.getSpanStart(dot);
            int line = layout.getLineForOffset(at);
            boolean end = line < layout.getLineCount() - 1;
            for (int i = at + 1; end && i < layout.getLineEnd(line); i++) {
                end = Character.isWhitespace(spanned.charAt(i)) || spanned.charAt(i) == Zh.WJ;
            }
            dot.hidden = end;
        }
    }
}
