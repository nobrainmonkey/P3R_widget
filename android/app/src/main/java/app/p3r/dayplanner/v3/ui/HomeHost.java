package app.p3r.dayplanner.v3.ui;

import android.app.Activity;
import android.view.View;

import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;

/** Services {@link HomeActivity} offers to its tab pages, sub-pages and sheets. */
interface HomeHost {
    Activity activity();

    /** Latest data (never null after the first load). */
    UiData data();

    /** Opens the day detail sheet for a slot. */
    void openDetail(int slot);

    /** Opens the quick result sheet ({@link app.p3r.dayplanner.v3.Intents#MODE_RESULT} etc.). */
    void openResult(int slot, String actionKey, String mode);

    /** Opens the state wizard in onboarding / sync / edit mode. */
    void openState(String mode);

    /** Records "完成" for a deterministic step with one tap (training, job, story). */
    void commitDirect(Plan.Step step);

    /** Pushes a full-screen sub-page with a back arrow. */
    void pushPage(String title, View content);

    /** Pops the top sub-page; returns false when none was open. */
    boolean popPage();

    /** Shows content in the bottom sheet. */
    void showSheet(View content);

    /** Shows content in the bottom sheet; {@code onDismiss} runs once it closes (any way). */
    void showSheet(View content, Runnable onDismiss);

    void dismissSheet();

    /** Saves the profile (CAS on the profile revision) and replans. */
    void saveProfile(Profile profile);

    /**
     * Saves {@code state} as the new progress record (a checkpoint, CAS on the state revision)
     * together with {@code profile} (null keeps the profile), then shows {@code doneZh}. Used for
     * small edits outside the wizard, e.g. the episodes already watched when opting into a
     * 羁绊剧情 track.
     */
    void saveCheckpoint(SimState state, Profile profile, String doneZh);

    /** Short confirmation or error message in the undo bar (no action). */
    void showMessage(String text);

    void requestReplan();

    /**
     * 从头重新安排: re-read the records and settings and plan from scratch. Shows the running state
     * at once; the result is shown once in the message bar and the 今天 status line.
     */
    void requestFullReplan();

    /** 「先不算了」: stops a running 从头重新安排. */
    void cancelFullReplan();

    /** Hides the return banner until the next record. */
    void dismissReturnBanner();

    void selectTab(int index);

    /** Re-reads the snapshot (after imports, undo, …). */
    void reload();
}
