package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * The P3R calendar plate: big condensed italic "10/22", a white weekday plate "周四", the moon
 * phase and the time of day ("放学后" / "白天" / "晚上"). Language-neutral numerals like the
 * in-game HUD; CJK is never italicised.
 */
public final class DatePlate extends LinearLayout {
    private final TextView numerals;
    private final TextView weekday;
    private final MoonDrawable moon;
    private final View moonView;
    private final TextView moonLabel;
    private final TextView period;
    private final SlantedPanelDrawable weekdayPlate;
    private final WatermarkDrawable watermark;

    /** @param large true for the 今天 header, false for sheet headers. */
    public DatePlate(Context context, boolean large) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        numerals = Ui.numeral(context, "", 12, Ui.col(context, R.color.p3r_white));
        // Display numerals are sized in dp: they are decorative and must not overflow at large font scales.
        numerals.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, large ? 68 : 36);
        float shadow = Ui.dp(context, large ? 3 : 2);
        numerals.setShadowLayer(0.01f, shadow, shadow, Ui.alpha(Ui.col(context, R.color.p3r_royal_500), 230));
        numerals.setPadding(0, 0, (int) shadow + Ui.dp(context, 4), (int) shadow);
        addView(numerals, Ui.wrap());

        LinearLayout side = Ui.column(context);
        LinearLayout top = Ui.row(context);
        weekday = Ui.text(context, "", large ? 16 : 13, Ui.col(context, R.color.p3r_navy_950), true);
        weekday.setGravity(Gravity.CENTER);
        weekdayPlate = new SlantedPanelDrawable()
            .fill(Ui.col(context, R.color.p3r_white))
            .skew(Ui.dp(context, 6));
        weekday.setBackground(weekdayPlate);
        Ui.pad(weekday, 10, 1, 10, 1);
        top.addView(weekday, Ui.wrap());

        moon = new MoonDrawable(Ui.col(context, R.color.p3r_cyan_200), Ui.col(context, R.color.p3r_cyan_300),
            Ui.dp(context, 1.2f));
        moonView = new View(context);
        moonView.setBackground(moon);
        int moonSize = Ui.dp(context, large ? 22 : 16);
        LayoutParams moonParams = new LayoutParams(moonSize, moonSize);
        moonParams.leftMargin = Ui.dp(context, 10);
        top.addView(moonView, moonParams);
        moonLabel = Ui.text(context, "满月", 12, Ui.col(context, R.color.p3r_cyan_200), true);
        LayoutParams moonLabelParams = Ui.wrap();
        moonLabelParams.leftMargin = Ui.dp(context, 4);
        top.addView(moonLabel, moonLabelParams);
        side.addView(top, Ui.wrap());

        period = Ui.text(context, "", large ? 18 : 14, Ui.col(context, R.color.p3r_cyan_400), true);
        LayoutParams periodParams = Ui.wrap();
        periodParams.topMargin = Ui.dp(context, large ? 6 : 2);
        side.addView(period, periodParams);
        addView(side, Ui.wrap());
        if (large) {
            // WEEKDAY / WEEKEND behind the date, like the game's calendar.
            // Kept to the left, under the date, clear of the protagonist art at the top right.
            watermark = new WatermarkDrawable("WEEKDAY", Ui.numerals(context),
                Ui.alpha(Ui.col(context, R.color.p3r_cyan_300), 34), Ui.dp(context, 84), Ui.dp(context, 1.5f), -6f, 0.0f);
            setBackground(watermark);
        } else {
            watermark = null;
        }
    }

    /** Shows the date and time of day of {@code slot}. */
    public void bind(Rules rules, int slot) {
        int day = Rules.dayOf(slot);
        String[] parts = Zh.shortDate(day).split("/");
        SpannableStringBuilder text = new SpannableStringBuilder();
        int cyan = Ui.col(getContext(), R.color.p3r_cyan_300);
        appendScaled(text, parts[0], 0.56f, cyan);
        appendScaled(text, "/", 0.56f, cyan);
        text.append(parts[1]);
        numerals.setText(text);

        Rules.Day info = rules == null ? null : rules.day(day);
        weekday.setText(info != null ? Zh.weekday(info.wd) : Zh.weekdayOf(day));
        // Sundays and holidays get the game's red weekday mark.
        boolean rest = info != null && (info.type == Rules.DayType.SUNDAY || info.type == Rules.DayType.HOLIDAY);
        weekdayPlate.fill(Ui.col(getContext(), rest ? R.color.ui_holiday : R.color.p3r_white));
        weekday.setTextColor(Ui.col(getContext(), rest ? R.color.p3r_white : R.color.p3r_navy_950));
        if (watermark != null) {
            watermark.setWord(rest ? "WEEKEND" : "WEEKDAY");
        }
        int phase = info == null ? 0 : info.moon;
        boolean full = info != null && (info.fullMoon || info.moon == 4);
        moon.setPhase(phase);
        moonView.setContentDescription(full ? "满月" : null);
        moonLabel.setVisibility(full ? VISIBLE : GONE);
        period.setText(Zh.period(rules, slot));
        setContentDescription(Zh.date(day) + " " + Zh.plain(weekday.getText()) + " " + Zh.plain(period.getText()));
    }

    private static void appendScaled(SpannableStringBuilder builder, String part, float scale, int color) {
        int start = builder.length();
        builder.append(part);
        builder.setSpan(new RelativeSizeSpan(scale), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new ForegroundColorSpan(color), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
