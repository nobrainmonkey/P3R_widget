package app.p3r.dayplanner.v3.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;
import android.text.style.ReplacementSpan;

/**
 * Applies the bundled condensed italic numeral face (Barlow Condensed) to Latin letters and
 * digits only, so mixed strings such as "皇帝 RANK 7" keep upright CJK and get P3R-style numerals.
 * Never use a fake italic on CJK; this span is the only way italics enter the UI.
 */
public final class NumeralSpan extends MetricAffectingSpan {
    private final Typeface typeface;
    private final float scale;

    public NumeralSpan(Typeface typeface, float scale) {
        this.typeface = typeface;
        this.scale = scale;
    }

    @Override
    public void updateDrawState(TextPaint paint) {
        apply(paint);
    }

    @Override
    public void updateMeasureState(TextPaint paint) {
        apply(paint);
    }

    private void apply(Paint paint) {
        paint.setTypeface(typeface);
        paint.setTextSize(paint.getTextSize() * scale);
        paint.setFakeBoldText(false);
        paint.setTextSkewX(0f);
    }

    /**
     * Wraps every run of Latin letters, digits and numeric punctuation ("RANK 6 → 7", "8,200",
     * "Lv.3", "10/22") in a {@link NumeralSpan} ({@link #style}). CJK characters are left
     * untouched, and so is the en dash of a range ("69–117F"): Barlow's is hardly longer than its
     * hyphen, so a range is drawn with the text face's dash, as in "5月12日–1月29日".
     */
    public static CharSequence styleLatinRuns(CharSequence text, Typeface typeface, float scale) {
        if (text == null) {
            return "";
        }
        SpannableString out = new SpannableString(text);
        int n = text.length();
        int i = 0;
        while (i < n) {
            if (!isLatinStart(text.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            int end = i;
            while (i < n && isLatinPart(text.charAt(i))) {
                // A space before the next Chinese word stays in the text face ("2 个时段").
                if (!Character.isWhitespace(text.charAt(i)) && text.charAt(i) != Zh.WJ) {
                    end = i + 1;
                }
                i++;
            }
            style(out, start, end, typeface, scale);
        }
        return out;
    }

    /** Sets {@code start..end} of {@code text} in the numeral face, each arrow as an {@link Arrow}. */
    static void style(Spannable text, int start, int end, Typeface typeface, float scale) {
        text.setSpan(new NumeralSpan(typeface, scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        for (int i = start; i < end; i++) {
            if (text.charAt(i) == '→') {
                text.setSpan(new Arrow(), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    /**
     * The arrow of a numeral run ("RANK 6 → 7", "RANK 3 → MAX"). Barlow has no "→", and the
     * fallback glyph sits right of centre in its advance, so the gap after it looked like none
     * ("6 →7"). Drawn as its ink between small margins, the spaces around it make equal gaps;
     * the margin before is the larger one, as measured on the renders the italic numerals leave
     * a little more room after the arrow than before it.
     */
    static final class Arrow extends ReplacementSpan {
        private static final String ARROW = "→";
        /** Margins before and after the ink, in em. */
        private static final float BEFORE = 0.09f;
        private static final float AFTER = 0.03f;

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) {
                paint.getFontMetricsInt(fm);
            }
            return Math.round(ink(paint).width() + (BEFORE + AFTER) * paint.getTextSize());
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom,
                         Paint paint) {
            canvas.drawText(ARROW, x + BEFORE * paint.getTextSize() - ink(paint).left, y, paint);
        }

        private static Rect ink(Paint paint) {
            Rect bounds = new Rect();
            paint.getTextBounds(ARROW, 0, 1, bounds);
            return bounds;
        }
    }

    private static boolean isLatinStart(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
            || c == '+' || c == '−' || c == '≈' || c == '×';
    }

    /** Latin run characters; a word joiner (Zh#WJ) inside "RANK 7" does not split the run. */
    private static boolean isLatinPart(char c) {
        return isLatinStart(c) || c == ' ' || c == '.' || c == ',' || c == '/' || c == '→' || c == '-'
            || c == '%' || c == ':' || c == Zh.WJ;
    }
}
