package app.p3r.dayplanner.v3.ui;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;

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
     * "Lv.3", "10/22") in a {@link NumeralSpan}. CJK characters are left untouched.
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
                if (!Character.isWhitespace(text.charAt(i))) {
                    end = i + 1;
                }
                i++;
            }
            out.setSpan(new NumeralSpan(typeface, scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    private static boolean isLatinStart(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
            || c == '+' || c == '−' || c == '≈';
    }

    private static boolean isLatinPart(char c) {
        return isLatinStart(c) || c == ' ' || c == '.' || c == ',' || c == '/' || c == '→' || c == '-'
            || c == '%' || c == ':' || c == '–';
    }
}
