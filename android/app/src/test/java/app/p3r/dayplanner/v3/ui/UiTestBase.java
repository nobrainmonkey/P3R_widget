package app.p3r.dayplanner.v3.ui;

import android.app.Activity;
import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;

import app.p3r.dayplanner.v3.Intents;

/** Common setup: fake planner, synchronous background work, no animations. */
abstract class UiTestBase {
    FakePlannerApi api;

    @Before
    public void installFakes() {
        api = FakePlannerApi.midGame();
        Api.setForTest(api);
        Async.setExecutorForTest(Runnable::run);
        Ui.setAnimationsEnabled(false);
        UndoCenter.clear();
        RuntimeEnvironment.getApplication().getSharedPreferences(DraftStore.PREFS, 0).edit().clear().commit();
        RuntimeEnvironment.getApplication().getSharedPreferences(UiPrefs.NAME, 0).edit().clear().commit();
    }

    @After
    public void removeFakes() {
        Api.setForTest(null);
        Async.setExecutorForTest(null);
        Ui.setAnimationsEnabled(true);
    }

    HomeActivity home() {
        return home(new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class));
    }

    HomeActivity home(Intent intent) {
        ActivityController<HomeActivity> controller = Robolectric.buildActivity(HomeActivity.class, intent).setup();
        Shots.idle();
        return controller.get();
    }

    ActivityController<ResultActivity> resultController(int slot, String action, String mode, boolean fromApp) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), ResultActivity.class);
        intent.putExtra(Intents.EXTRA_SLOT, slot);
        intent.putExtra(Intents.EXTRA_ACTION, action);
        intent.putExtra(Intents.EXTRA_MODE, mode);
        intent.putExtra(Intents.EXTRA_PLAN_ID, Fixtures.PLAN_ID);
        intent.putExtra(Intents.EXTRA_STATE_REV, Fixtures.STATE_REV);
        intent.putExtra(HomeActivity.EXTRA_FROM_APP, fromApp);
        ActivityController<ResultActivity> controller = Robolectric.buildActivity(ResultActivity.class, intent).setup();
        Shots.idle();
        return controller;
    }

    ResultActivity result(int slot, String action, String mode) {
        return resultController(slot, action, mode, true).get();
    }

    ActivityController<StateActivity> stateController(String mode) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), StateActivity.class);
        intent.putExtra(Intents.EXTRA_STATE_MODE, mode);
        ActivityController<StateActivity> controller = Robolectric.buildActivity(StateActivity.class, intent).setup();
        Shots.idle();
        return controller;
    }

    static boolean finishing(Activity activity) {
        return activity.isFinishing();
    }
}
