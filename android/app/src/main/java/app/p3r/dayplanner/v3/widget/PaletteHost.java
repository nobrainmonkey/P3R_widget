package app.p3r.dayplanner.v3.widget;

import android.content.Context;
import android.view.View;
import android.widget.RemoteViews;

import app.p3r.dayplanner.R;

/**
 * Builds the stable host tree the plan widget uses for the day/night crossfade
 * ({@code widget_palette_host}: a ViewFlipper with a day and a night slot, plus a static slot).
 *
 * <p>Normal update: both flipper slots are refreshed, the flipper stays INVISIBLE and the static
 * slot shows the card. Transition update: only the incoming slot is replaced (the outgoing slot keeps
 * its laid-out child so the fade starts from an opaque card), the flipper is made VISIBLE and
 * {@code setDisplayedChild} runs the ViewAnimator crossfade; a later settle update restores the
 * static tree.
 */
public final class PaletteHost {

    /** Produces one card for a palette. */
    public interface CardFactory {
        RemoteViews card(boolean night);
    }

    private PaletteHost() {}

    /**
     * @param night   palette of the card that should end up visible
     * @param animate true for a transition update
     */
    public static RemoteViews build(Context context, boolean night, boolean animate, CardFactory cards) {
        RemoteViews host = new RemoteViews(context.getPackageName(), R.layout.widget_palette_host);
        if (!animate || !night) {
            host.removeAllViews(R.id.widget_palette_day_slot);
            host.addView(R.id.widget_palette_day_slot, cards.card(false));
        }
        if (!animate || night) {
            host.removeAllViews(R.id.widget_palette_night_slot);
            host.addView(R.id.widget_palette_night_slot, cards.card(true));
        }
        host.removeAllViews(R.id.widget_palette_static);
        host.addView(R.id.widget_palette_static, cards.card(night));
        host.setDisplayedChild(R.id.widget_palette_flipper, night ? 1 : 0);
        host.setViewVisibility(R.id.widget_palette_flipper, animate ? View.VISIBLE : View.INVISIBLE);
        host.setViewVisibility(R.id.widget_palette_static, animate ? View.GONE : View.VISIBLE);
        return host;
    }
}
