package app.p3r.dayplanner.v3.widget;

import android.widget.ViewAnimator;

import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowViewGroup;

/**
 * Lets the framework's own {@link ViewAnimator} code run (setDisplayedChild starts the real in/out
 * animations) instead of Robolectric's stand-in. Robolectric sandboxes are shared per SDK, so every
 * test that inflates the palette host ({@code widget_palette_host}) must use this same shadow.
 */
@Implements(ViewAnimator.class)
public class RealViewAnimator extends ShadowViewGroup {}
