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
 * {@link Zh#keepTogether}), and a line never ends with a " · " separator. The dot stays on the
 * line of the part before it (no break before it); where the text wraps right after it, it is
 * not drawn, so the next part simply starts the next line ("RANK 4" / "见面（预计不升级）").
 */
final class ZhTextView extends TextView {
    ZhTextView(Context context) {
        super(context);
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        super.setText(separators(Ui.prepare(getContext(), text)), type);
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
            }
        }
    }

    /** {@code text} with a {@link Dot} on every separator dot, joined to the text before it. */
    static CharSequence separators(CharSequence text) {
        if (text == null || text.length() < 3 || indexOfDot(text, 0) < 0) {
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
        return out;
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
