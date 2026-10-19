package app.p3r.dayplanner.v3.ui;

import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;

/**
 * Draws every screen edge to edge on all API levels (API 35 enforces it for targetSdk 35) and
 * reports the system-bar and keyboard insets so screens can pad their own content.
 */
final class EdgeToEdge {
    /** Inset receiver. */
    interface Receiver {
        void onInsets(int left, int top, int right, int bottom, int ime);
    }

    private EdgeToEdge() {}

    @SuppressWarnings("deprecation")
    static void enable(Window window) {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        if (Build.VERSION.SDK_INT < 35) {
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.argb(160, 3, 10, 36));
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.setNavigationBarContrastEnforced(false);
        }
    }

    /** Listens for insets on {@code root}; insets are not consumed. */
    @SuppressWarnings("deprecation")
    static void listen(View root, Receiver receiver) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                receiver.onInsets(bars.left, bars.top, bars.right, bars.bottom, Math.max(0, ime.bottom - bars.bottom));
            } else {
                int bottom = insets.getSystemWindowInsetBottom();
                int nav = insets.getStableInsetBottom();
                receiver.onInsets(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), Math.min(bottom, nav), Math.max(0, bottom - nav));
            }
            return insets;
        });
        root.requestApplyInsets();
    }
}
