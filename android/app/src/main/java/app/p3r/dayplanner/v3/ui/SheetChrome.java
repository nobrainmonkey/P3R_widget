package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.TextView;

import app.p3r.dayplanner.R;

/** Shared visual treatment for secondary sheets; content and actions stay with their owner. */
final class SheetChrome {
    private SheetChrome() {}

    static SlantedPanelDrawable surface(Context c) {
        return new SlantedPanelDrawable()
            .gradient(Ui.col(c, R.color.p3r_navy_700), Ui.col(c, R.color.ui_sheet))
            .stroke(Ui.alpha(Ui.col(c, R.color.p3r_cyan_400), 175), Ui.dp(c, 1))
            .cuts(Ui.dp(c, 28), 0, 0, 0);
    }

    static View handle(Context c) {
        View handle = new View(c);
        handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        handle.setBackground(new SlantedPanelDrawable()
            .gradient(Ui.col(c, R.color.p3r_cyan_300), Ui.alpha(Ui.col(c, R.color.p3r_cyan_400), 80))
            .skew(Ui.dp(c, 3)));
        return handle;
    }

    static TextView title(Context c, CharSequence label) {
        TextView title = Ui.title(c, label);
        Drawable slash = new SlantedPanelDrawable().fill(Ui.col(c, R.color.p3r_cyan_400))
            .skew(Ui.dp(c, 3));
        slash.setBounds(0, 0, Ui.dp(c, 6), Ui.dp(c, 24));
        title.setCompoundDrawables(slash, null, null, null);
        title.setCompoundDrawablePadding(Ui.dp(c, 10));
        Ui.pad(title, 0, 2, 0, 6);
        return title;
    }

    /** A small check is separate from the unchanged, searchable category label. */
    static void selectChoice(TextView choice, boolean selected) {
        Ui.setChipSelected(choice, selected);
        Context c = choice.getContext();
        Drawable check = null;
        if (selected) {
            choice.setBackground(new SlantedPanelDrawable()
                .gradient(Ui.col(c, R.color.p3r_cyan_200), Ui.col(c, R.color.p3r_cyan_400))
                .pressedFill(Ui.col(c, R.color.p3r_cyan_200))
                .stroke(Ui.col(c, R.color.p3r_white), Ui.dp(c, 1))
                .skew(Ui.dp(c, 6)));
            check = Ui.tinted(c, R.drawable.ui_ic_check, Ui.col(c, R.color.p3r_navy_950));
            check.setBounds(0, 0, Ui.dp(c, 12), Ui.dp(c, 12));
        }
        choice.setCompoundDrawables(null, null, check, null);
        choice.setCompoundDrawablePadding(Ui.dp(c, 3));
    }
}
