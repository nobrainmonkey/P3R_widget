# Test suite, build, signing and legacy-guide map for the P3R planner redesign

The project currently builds and all its tests pass. The current tests check that the planner is safe and conservative, but several of them also lock in the short-plan defect. Builds from this machine cannot overwrite the installed app, because the signing certificate is different (see section 3).

**Note on scope.** The user asked us to also think about input convenience and how deviations should be handled (用户怎么输入更方便？每次用户deviate/需要调整怎么做最好？). Section 8 covers that and section 9 turns it into tests.

## 0. Current state on this machine

| Check | Result |
|---|---|
| `assembleDebug` | Passed. Log: `.toolchain/build_baseline.log`. Output: `app/build/outputs/apk/debug/app-debug.apk`, 6,053,355 bytes |
| First `testDebugUnitTest` run | 383 tests ran, 16 failed. All 16 failures were `FileNotFoundException` on hard-coded `/workspace/shared/...` QA image paths. Log: `.toolchain/test_baseline.log` |
| Second run, after the lead adapted the paths at 01:18 | 383 tests passed, 0 failed (`.toolchain/test_baseline2.log`, results in `android/app/build/test-results/testDebugUnitTest/*.xml`) |

How the second run was fixed:
- The QA output folder now comes from a system property: `System.getProperty("p3r.qa.dir","build/qa")`.
- `app/build.gradle:36-38` sets that property to `rootProject.file('../qa_out')`, which is `/Users/xihehan/P3R_Widget/qa_out`.
- A `doFirst` block creates the five output folders before tests run.

Two leftover fragilities:
- `WidgetPaletteFadeTest.java:95-98` writes `fade-frame-*.png` without creating its own folder. It works only because of that `doFirst`.
- Every Robolectric test runs with `robolectric.graphicsMode=NATIVE` (`build.gradle:35`), which makes the run slower.

How the 383 runs break down:
- **Legacy tests:** 32 test methods, 41 runs. `WidgetPaletteFadeTest` and `WidgetRemoteViewsTest` run some methods under SDK 26, 28 and 35.
- **Adaptive planner (v2) tests:** 340 test methods, 342 runs. `GoalPlannerWidgetTest.remoteViewsInflateAndInitialHostDoesNotAnimate` runs under SDK 26, 28 and 35.

## 1. Build files

**`android/build.gradle`**
- AGP `com.android.application` 8.7.3.

**`settings.gradle`**
- Uses google and mavenCentral repositories, with `FAIL_ON_PROJECT_REPOS`. Root project is named `P3RDailyWidget`.

**`gradle.properties`**
- `-Xmx2g -Dfile.encoding=UTF-8` and `useAndroidX=true`. No AndroidX libraries are actually used.

**Gradle wrapper**
- Gradle 8.9, with `validateDistributionUrl=false`.
- `gradle-wrapper.jar` is listed in `.gitignore` but is present on disk.

**`app/build.gradle`**
- Line 8: `applicationId 'app.p3r.dayplanner'`.
- Lines 20 and 24: both the debug and release build types add `applicationIdSuffix '.test'`. The installed package is therefore **`app.p3r.dayplanner.test`**.
- Lines 9–12: minSdk 26, targetSdk 35, compileSdk 35, versionCode 11, versionName 2.0.1.
- Java 17.
- Release build type: `debuggable false`, `minifyEnabled false`, and `signingConfig signingConfigs.debug` (line 28).
- Lines 21 and 25: `resValue 'string','app_name','P3R'` replaces the `app_name` "P3R 日程" in `res/values/strings.xml`. The merged output was checked and the label is "P3R".
- Test settings:
  - `includeAndroidResources=true`.
  - `user.home` points to `../.toolchain/test-home`. The Robolectric Android jars for SDK 8.0 (26), 9 (28) and 15 (35) are already cached in `.toolchain/test-home/.m2`, so tests can run offline.
  - `robolectric.dependency.repo.url` points to Maven Central and is used only when the cache misses.
  - Proxy settings are passed through.
- Dependencies are test-only: `junit:junit:4.13.2` and `org.robolectric:robolectric:4.14.1`. The app itself has no runtime libraries.

**Toolchain (`.toolchain/env.sh`)**
- Everything lives inside the project: JDK 17.0.20.1, Android SDK with platform 35 and build-tools 34.0.0 and 35.0.0, `GRADLE_USER_HOME=.toolchain/gradle-home` (695 MB), and `ANDROID_USER_HOME=.toolchain/android-user-home`. This satisfies the requirement that all dependencies stay in this directory.

**Manifests**
- `app/src/main/AndroidManifest.xml`:
  - `allowBackup=false` and `usesCleartextTraffic=false`.
  - The launcher activity is `.v2.GoalPlannerHomeActivity` (lines 13–18).
  - `.MainActivity` is the legacy guide. It is `exported=true` but has no intent-filter, so it is reached only through the button "打开原固定攻略与原进度" and widget PendingIntents.
  - `.v2.GoalPlannerActivity` is `exported=false` and **nothing starts it** (no `GoalPlannerActivity.class` reference anywhere). It is dead UI, yet 9 tests cover it.
  - Receivers: `.v2.GoalPlannerWidget` (label "P3R 目标规划"), `.WidgetUpgradeReceiver` (`MY_PACKAGE_REPLACED`; refreshes both widgets) and `.PlannerWidget` (the legacy widget).
- `app/src/release/AndroidManifest.xml`: only `<application android:testOnly="false"/>`.

**CI (`android/.github/workflows/build-apk.yml`)**
- It only runs if `android/` is the repository root.
- It uses system Gradle 8.9 through `setup-gradle` rather than the wrapper, and runs `gradle --no-daemon :app:assembleDebug` only. It runs no tests and no lint.
- It uploads `app-debug.apk`. That APK is signed with a new random debug key every run, so **it can never update the installed app**. Treat it as a smoke build only.

**`build_apk.sh`**
- Builds debug only and may download Gradle. It is not used by the current toolchain.

**APK contents worth tidying**
- `calendar_full_candidate.json` (5.9 MB) appears four times with an identical SHA-256: under `planner_v2/rules`, `rules_v03`, `rules_v04` and `rules_v051`.
- `.py` and `.md` files are also packaged into the assets.
- Not urgent, since the APK is 6 MB compressed. But a new rules layout should load from one pack.
- Every pack is pinned by a manifest hash: `RulePack.java:7` and `PlannerRules.java:25`, plus the version strings asserted in the `pinsSourceManifestAndScope` tests. Any data edit requires updating these pins.

## 2. Lint

- The handoff mentioned an error with `windowLightNavigationBar` on API 26. That is **already fixed**: the base `res/values/styles.xml` no longer contains it, and it now lives only in `res/values-v27/styles.xml`.
- The 2.0.0 release recorded 0 errors and 540 warnings.
- `lintRelease` has not been run since. Run it before release.

Expected warnings:
- `UnusedResources`. These layouts are never referenced: `widget_day`, `widget_day_compact`, `widget_night`, `widget_night_compact`, `widget_scene*` (4 files) and `widget_palette_fade`.
- `UnusedAttribute` for `targetCellWidth/Height` and `previewLayout` in `res/xml/*_widget_info.xml` on API below 31.
- `DiscouragedApi` for `getIdentifier` (`PlannerWidget.java:119`).
- Deprecated calls in `MainActivity`: `setSystemUiVisibility`, `setStatusBarColor` and `onBackPressed`.
- The `setAllowFileAccess(true)` warning on the WebView.

Two things to check during the UI rework:
- `GoalPlannerHomeActivity` handles window insets only when `SDK_INT>=30`, and `MainActivity` only when `SDK_INT>=35`. Targeting SDK 35 forces edge-to-edge on Android 15, so recheck these.
- `GoalPlannerWidget` picks its layout using only `OPTION_APPWIDGET_MIN_WIDTH/HEIGHT`. The legacy `PlannerWidget.updateOne` (lines 261–274) uses the Android 12+ `OPTION_APPWIDGET_SIZES` responsive map.

## 3. Signing and what a release needs

**The certificate on this machine does not match the installed app**

| | SHA-256 |
|---|---|
| Required (from START_HERE) | `175da71189f9b9cee2c232c5b9304b73131a4bd819ad88f8898005aaaf9afa36` |
| `.toolchain/android-user-home/debug.keystore` on this machine (created 2026-10-13 01:15) | `BE:4A:DF:64:D2:ED:4A:64:A8:80:CE:DF:33:2E:60:C8:B2:E9:83:04:70:2E:ED:05:26:81:60:B0:9D:5C:0B:95` |

`~/.android/debug.keystore` does not exist. So **every APK built here, debug or release, will fail to install over the existing `app.p3r.dayplanner.test`** with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

Options, without ever telling the player to uninstall:
1. **The owner provides the original debug keystore** (the `~/.android/debug.keystore` from the environment that built 2.0.0).
   - Put it at `.toolchain/android-user-home/debug.keystore`, or add an explicit `signingConfigs.release` read from `local.properties` or environment variables.
   - Never package or share it.
   - After building, check with `.toolchain/android-sdk/build-tools/35.0.0/apksigner verify --print-certs app-release.apk` and compare against `175da711…`.
2. **No key available: install side by side.** Build with a separate dev applicationId, for example suffix `.dev` and label "P3R 开发版", and explicitly say it is a separate app.
   - Legacy progress can move over with the existing JSON export/import in the guide (`exportProgress` in `assets/index.html`, then the import page, then `AndroidBridge.syncState`).
   - **The v2 planner has no user-facing export/import.** The data is SQLite `p3r_goal_planner_v2.db` with tables `runs`, `checkpoints`, `events`, `preferences` and `plans` (`PlannerStateStore`), plus SharedPreferences `p3r_goal_planner_commands_v2` (`pending`, `last_return`, `setup_draft`) and `p3r_goal_widget_cursor`.
   - Adding "导出/导入规划存档" (planner save export/import) would be needed for real migration. Otherwise the user re-enters the current state once.
3. Do not change the release applicationId silently. Changing the debug-only suffix is acceptable only if stated explicitly.

**Version**
- Published: 2.0.0, code 10.
- Source: 2.0.1, code 11, never published.
- Next release: any code above 10 works. Code 12 / 2.1.0 is suggested so it cannot be confused with unpublished 2.0.1 builds.

**Release checklist**
- `./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease`, with the toolchain environment sourced.
- `apksigner verify --print-certs`.
- `aapt2 dump badging`: package `app.p3r.dayplanner.test`, versionCode, label P3R, not debuggable, `testOnly` false.
- `zipalign -c -p 4`.

## 4. Test classes in detail

The rule-pack tests (adapter suites) and unit tests all use `@Config(sdk=28)` with Robolectric. Robolectric is needed mainly for real `org.json` and the assets. `GameDateTest` is the only plain JUnit class.

### Legacy (`app.p3r.dayplanner`)

**PlannerIntegrityTest (5)**
Checks the data files:
- `data.json` has 284 `blocks`, 691 records and 623 checkable records.
- The SHA-256 of the newline-joined, ordered ids is `1ac4393f…a638`.
- 3 parents have substeps, with 20 indexed substeps, hash `f7f9c1ac…0a49`.
- `display_metadata.json` has exactly 691 entries, each with a non-empty `widgetTitle`, plus `locationLabel`, `appBullets`, `extraNotes` and `hasDetails`.

Checks storage:
- Round-trip of the `completed` and `substeps` preference keys, plus `selectedDate` and `theme`.
- A malformed import, an import with `completed` only and an invalid date do not change progress.

**These are the guide's data-safety tripwires. Keep them unchanged.**

**PlannerNavigationTest (12)**
Uses widget cursor id 47.
- Browsing goes through every record without changing completion, crosses dates, and stops at both ends.
- "Current" goes to the earliest pending task.
- Done completes the viewed task and skips tasks already completed.
- A stale Done intent cannot complete a different task.
- Indexed substeps are kept when the parent completes.
- `free` and `auto` records cannot be completed.
- Done on the last task wraps around.
- Runtime display fields exactly match the metadata, including `dialogueChoices` and `dialogueCue`.

**WidgetDialogueRenderTest (2)**
- Choices are joined with " / " and have no forced newline.
- The `t014_03` wording is exact: "不能进或者保密", "成熟的好或者无所谓".
- Fit is checked across 6 widget sizes. The choices text is 12sp and the title at least 10.9sp. No ellipsis or clipping, otherwise a fallback cue appears.
- Writes PNGs to `${p3r.qa.dir}/p3r_dialogue_qa`.

**WidgetNativeRenderTest (7)**
Pixel-level checks:
- Titles and header are not clipped.
- Done and Current buttons are 80px tall with a 16px gap. Their 34dp shape sits inside a 40dp touch target (top 6, height 68).
- The day colour is `#0FB1F5` and the night colour `#034572`.
- All 22 motif × palette combinations stay inside the right-aligned area (`FIT_END`).
- The wordmark is not clipped and does not collide with header text.
- The date group is centred within ±0.5px.

**WidgetPaletteFadeTest (3 methods, 8 runs, SDK 26/28/35)**
- The ViewFlipper fades over 220ms. Rapid repeated taps do not queue fades: `allowPeriodFade` uses a 300ms gap.
- Resizing cancels the transient fade.
- Frame pixels differ at 1000, 1110 and 1230ms.
- It uses a shadow `RealViewAnimator` so the real ViewAnimator code runs.

**WidgetRemoteViewsTest (3 methods, 7 runs)**
- Real RemoteViews inflate.
- Ordinal and dot string, for example `●◎●` and `●○◉`.
- 昼/夜 label.
- Current and Done labels.
- Done is disabled for informational tasks, and Undo is allowed for completed ones.

### v2 (`app.p3r.dayplanner.v2`)

**GameDateTest (3)**
- Slot axis: `slot(1,2,false)=552` and `slot(4,21,false)=40`.
- Crosses the year boundary.
- Feb 30 is rejected.

**PlannerEngineTest (50)**
Synthetic rules only. Tests the engine contract:
- `transition` does not modify its inputs.
- Results are deterministic.
- Replay matches the search.
- Costs are paid before rewards.
- Unknown values are neither 0 nor impossible.
- Statuses are kept distinct: `feasible_within_model`, `conditional`, `unknown`, `search_limit`, `unsupported`, `proven_infeasible`.
- Pruning never proves failure (`beamPruningNeverProvesFailure`, line 228).
- Budget exhaustion has its own status (`budgetExhaustionHasDistinctHonestStatus`, line 237).
- Visit caps and floor locks are enforced.
- Replay rejects duplicate instances and wrong slots (line 315).
- Forged effects are rejected.
- Tie-break tests at lines 374 and 377 expect `__wait__` after the goal is reached.

**PlannerStateStoreTest (37)**
Synthetic data. Tests the event ledger:
- Retries are idempotent across restarts and do not depend on key order.
- Conflicting payloads and stale commands are rejected.
- Cash: net change and absolute balance are mutually exclusive, overflow is rejected, and unknown stays unknown.
- Corrections add no visit and no time.
- Plan installation uses compare-and-set.
- Atomic setup.
- Capacity of the next slot.
- Unlock flags expire when the slot advances, but preparation is kept.

**PlannerCoordinatorTest (9) and PlannerCoordinatorAdversarialTest (5)**
- Tested through `/api/baseline|preferences|return|observed_social|observed_stats|setup`.
- A tower return stays recorded even if planning throws.
- A dungeon action cannot be submitted through the social-result route.
- A reversed relationship cannot carry `ready=true`.
- An unmodeled link is `unsupported`, not impossible.

**PlayerStateFormTest (16)**
Reaches private fields by reflection: `links`, `goal`, `rank`, `priority`, `month`, `day`, `phase`, `cash`, `used`, `currentCapacity`, `stage`, `preparation`, `preparationTiming`, `trainingAccess`, `towerEntry`, `towerRoutine`, `affinity`, `answerAssumption`, `original`.
- All 22 links are selectable.
- An unchanged stat interval is kept (57–80).
- The hidden affinity input has no parent view.
- Changing a rank clears the readiness recorded for the old rank.
- July days 9–17 belong to no tower stage.
- The raw draft keeps "0" and blank values as typed.

**GoalPlannerActivityTest (9)**
- Covers the **unreachable** old two-link screen.
- Reflects on fields `cash`, `floor`, `visits`, `sunRank`, `devilRank`, `returnCash`, `cashMode`, `exitDay`, `correctLast`, `socialEntity`, `worker`, `busy`, and others.
- Tests tower return: net −200, retry, correction without an extra visit, and stale pending receipts.

**GoalPlannerHomeActivityTest (15)**
- Uses `en-rUS-w320dp-h640dp-xhdpi` with native graphics.
- Finds controls by **Chinese label text**, for example "选择目标并填写当前状态", "记录行动后的实际结果", "保存实际结果", "爬塔现金填写方式", "结束后这个时段还可行动吗？", "修正上次爬塔记录（不再增加次数）".
- Reflects into `PlayerStateForm`.
- Covers:
  - October setup followed by January re-entry, with legacy `{"t005_03":true}` untouched.
  - One visit per tower return across net, absolute and correction entries.
  - Retries.
  - A stale widget tap commits nothing.
  - A current widget tap opens the result form without committing.
  - Assumptions are translated into readable text.
  - The draft survives a restart.
  - Inputs are disabled while a save is pending.
  - All 22 identity labels.
- Writes screenshots to `${p3r.qa.dir}/planner_v2/qa/native_home`.

**GoalPlannerWidgetTest (5 methods, 7 runs)**
- `items()` drops `__wait__`, sorts by slot, and marks reservations.
- An empty plan shows "选择目标，从当前存档开始" with Done disabled.
- Text fits at 180, 250 and 320 widths.
- Fade frames.

**NativeExpandedRulesTest (34) and NativeManualRulesTest (60; its first 34 mirror Expanded against `rules_v04`)**
- Adapter rules: conservative bounds; Christmas, Jan 21 and story locks; rank gates; no zero-knowledge defaults; romance branches; rescues; the Emperor Nov 16 exception.
- `octoberMultiStepGoalSearchReplaysFiveRanks`: Magician 2→7 (prints the dates).

**NativeProgressionRulesTest (30)**
- Stat bands, jobs, tower stages, dungeon guards.
- The Apr 22 daytime lock and the Christmas and Jan 21 guards.

**NativeUnlockRulesTest (23)**
- Introductions, preparation and payments (for example, the final payment charges only the remaining 10,000), food items, the Hermit online-game check.

**UnifiedPlannerRulesTest (44)**
- Composite tests: `sunCanReachMaxBeforeJanuaryWithThreeGuardedVisits` checks Sun 7→10 in 3 visits before Jan 2 (prints the dates).
- Tower pin tests, reservations, unsupported versus unknown status.

### Tests that encode the defect

These tests assert the current conservative-prefix behaviour, which is exactly the short-plan problem. **They will fail when the redesign works as intended and must be rewritten deliberately, not "fixed":**
- `UnifiedPlannerRulesTest`:
  - `conservativeUnknownReadinessHasDatedActionableReservation`: Magician 3→4 gives 0 steps and status unknown.
  - `futureRoutineTrainingStaysUncreditedAlternative`: 0 steps.
  - `routineTrainingCannotSatisfyIntroductionPrerequisite`: 0 steps.
  - `routineJobAlternativesCannotFinanceTrainingOrUnlock`: 0 steps, cash stays 0.
  - `defaultUnknownPreparationsNeverScheduleOldPaymentsAgain`: Devil gives 0 steps.
  - `introductionDoesNotInventRestOfMaxRankRoute`: Emperor 0→10 gives only 1 step.
  - `unknownAffinityFloorZeroIsNotAnExactObservedBalance` and `unknownRankEventChoicesCreateNoInventedPositiveGain`: `rank_04` is not compiled.
- `NativeManualRulesTest`/`NativeExpandedRulesTest`: `observedReadyOneStepStopsWhenFollowingGateUnknown`.
- `NativeProgressionRulesTest`: `unknownJobAvailabilityBlocksAndFutureTrainingRemainsGuarded` and `futureRoutineJobCannotCreditCashWithoutExactAvailability`.
- `PlannerEngineTest`: lines 374 and 377 (idle after the goal), which conflicts with the requirement to optimise other links as well.

The useful parts of these tests must be kept by splitting each into two assertions:
- **(a)** The verified lower bound and the observed ledger are **not** credited.
- **(b)** The scenario layer **does** produce dated, estimate-labelled steps that go further.

**Tests that will break on any UI rewrite**
- `GoalPlannerActivityTest`, `GoalPlannerHomeActivityTest` and `PlayerStateFormTest` reach into private fields and Chinese labels.
- Recommendation: delete `GoalPlannerActivity` and its 9 tests.
- Rebuild the UI tests on stable `View` ids or tags, and on a pure-Java presenter or view-model layer that can be tested without the UI.

## 5. Legacy fixed guide: structure and safety rules

**`assets/data.json` (232 KB)**
- Shape: `{"blocks":[{date:"4月8日",month,day,items:[...]}]}`, with 284 days and 691 items.
- Each item has: `id` (`tNNN_MM`), `period`, `kind`, `title` and `where` (637 items).
- Optional fields: `steps[]` (211), `hint` (24), `originalRef` (19), `substeps[]` (3: `t005_03` with 14 Dark Hour shard locations, `t024_03` with 2, `t088_01` with 4).
- `period` values: 晚上 274, 白天 201, 放学后 189, 未标注 17, 下午 5, 夜晚 2, 上午/深夜/午休 1 each.
- `kind` values: action 499, class 69, free 50, optional 36, auto 18, exam 17, review 1, conditional 1.

**`assets/display_metadata.json`**
- Maps each id to `widgetTitle`, `locationLabel`, `appBullets[]`, `extraNotes[]`, `hasDetails`, `dialogueChoices[]` and `dialogueCue`. All 691 ids are present.

**`PlannerStore.java`**
- Loads the metadata first, then the data (lines 75–102). Results are cached in static fields.
- `Task.isCheckable()` is false for kinds `free`, `auto`, `note` and `info` (line 52).
- `requiresConfirmation` (line 50) is calculated but **not used**: widget Done completes multi-step tasks directly, and the test `completingComplexTaskRetainsIndexedPartialSubstepRecords` depends on that.

**Persistent storage**
- SharedPreferences `p3r_native_progress_v1`:
  - `completed`: JSON string mapping id to true.
  - `substeps`: `"id::index"` mapped to a boolean.
  - `selectedDate` in "4月8日" format.
  - `theme`: day or night.
  - `widgetCursor_<appWidgetId>`.
  - `widgetActionRevision` (long).
- `acceptWebState` (lines 333–349) **replaces** `completed` and `substeps` wholesale, but only if both are present. It also increments the action revision.
- `toggleViewed` (lines 235–251) checks the expected task, the done state and the revision before acting.

**WebView (`MainActivity` and `assets/index.html`)**
- localStorage keys:
  - `p3r_hunterjia_checklist_v1`
  - `p3r_hunterjia_substeps_v2`
  - `p3r_theme_mode_v1`
  - `p3r_minimal_motion_v1`
  - `p3r_widget_selected_day_v1`
- Native state is pushed to the page in `onPageFinished` via `P3RNative.applyState` (`MainActivity.java:121-135`).
- The page writes back through `AndroidBridge.syncState` only on user actions; `init()` does not call `notifyNative`.
- Export JSON: `{format:'p3r_hunterjia_checklist_v1', completed, completedSubsteps, schemaVersion:2}`.

**`PlannerWidget.java`**
- Picks the layout from `widget_final_{tiny,compact,regular}_{day,night}` (lines 110–116).
- The motif comes from an FNV-1a hash of the date (lines 91–96).
- Night means the period contains 夜 or 晚 (line 98).
- Broadcast PendingIntent identity includes widget id, offset, task id, revision and complete/undo (lines 63–79).
- The fade host is `widget_palette_host`, settling after 1000ms.

**Isolation from the planner**
- No v2 class reads or writes `p3r_native_progress_v1`. `PlannerStateStore` states this in its Javadoc, and `legacy_guide_backup` is only passed through.
- **Rules to keep:**
  - Planner commands never touch guide preferences.
  - Guide actions never touch the planner database.
  - `WidgetUpgradeReceiver` only refreshes.
  - The 691 ids, their order hash and the substep hash never change.
  - Legacy widget behaviour stays the same.

## 6. Python prototype (`prototype/`)

**Test status**, run from a copy in the scratchpad so the project was not touched:
- `test_planner_core` and `test_adversarial`: **89 passed**.
- `test_service` and `test_local_app`: **10 errors** when `rules/p3r` is the top-level `/Users/xihehan/P3R_Widget/rules/p3r`. Its v0.5.1 manifest lists `.md` files, so `load_january_pack` raises "Invalid rule pack path" (`real_rules_adapter.py:21`).
- The same 10 pass when `prototype/rules/p3r` is a copy of `assets/planner_v2/rules`.
- The prototype expects the rules at `prototype/rules/p3r`, which does not exist in this layout.

**Algorithm**
- `planner_core.py` is the reference specification that `PlannerEngine.java` ports: `Interval`, `transition`, `validate_plan`, and a layered beam search with the same status rules.
- It has **no algorithm better than the Java**. The Java adds two tie-breaks (earlier attainment and fewer actions) that Python lacks.
- Both share two weaknesses that explain the short plans:
  1. **Scoring uses only the goal fields' lower bound** (`_score` / `PlannerEngine.score` at line 382). Meetings with unknown gain do not raise the lower bound, so the search never prefers them over waiting. Plans stall after the steps that are proven.
  2. **No merging of identical states.** The 48 beam slots fill up with equivalent histories. Also, `sort()` recalculates `score()` inside the comparator (lines 397–409), allocating `BigInteger` lists on every comparison.
- **The search budget explains the early cut-off.** `UnifiedPlannerRules.java:103` uses width 48 and 60,000 expansions, and each slot costs about 48 × (available actions + 1) expansions:

  | Actions per slot | Expansions per slot | Slots covered | Days covered |
  |---|---|---|---|
  | ~10 | ~528 | ~113 | ~57 |
  | ~25 | ~1,250 | ~48 | ~24 |

  When the budget runs out, there is no final layer and the result is `search_limit` with a mid-horizon `best` prefix.
- Ideas worth carrying forward:
  - A canonical state hash for duplicate detection.
  - A scenario score that credits estimated progress.
  - Secondary objectives across all links.
  - Rolling-horizon or greedy-plus-repair search.
- `rules/p3r/full_horizon_forecast_policy.json` already defines a scenario model:
  - Estimated base gain per meeting is 10 (not verified).
  - Filler meetings needed = `ceil(d/10)+1`.
  - The rank-up event uses a separate later slot.
  - Surplus affinity resets after each rank event.
  - Estimated gains never go into verified bounds or the observed ledger.
  - Its `fixtures` can be used directly in tests: `{gate:30,model:15,fillers:3}`, `{gate:15,model:0,fillers:3}`, `{gate:0,fillers:0}`, `{gate:30,model:30,fillers:0}`, Sun needs no points, and `{estimatedOrdinaryGain:10, verifiedLowerBoundIncrement:0, observedLedgerIncrement:0}`.

## 7. Facts relevant to the current UX

- Result entry today is one generic form, "记录行动后的实际结果". It uses a "行动类型" spinner, cross-labelled fields such as "爬塔实际金额（社群会面不填）" and "训练属性（其他行动不填）", and a manual question "结束后这个时段还可行动吗？".
- Widget Done opens this form with the action type preselected; see `currentWidgetDoneOpensObservationWithoutCommittingForecast`.
- Setup confirmation is a blanket modal (`AlertDialog`) on "确认实际基线与目标".
- The design document asks for result panels specific to each action.

## 8. Recommended input and deviation UX (the user's question)

**Principle: the plan is a forecast, and the player reports only what differed. The default answer to everything is "按计划" (as planned), in one tap.**

1. **Widget Done opens a small result sheet for that action**, keyed by `(plan_revision, instance_id)`. Stale keys show "安排已更新" and the current item (this already exists).
   - **Rank event** (e.g. 皇帝 6→7): large buttons "已升到 7" / "没升级" / "没去成", plus a link "其他结果…" for reversed or a different rank. Only the rank changes; nothing else is asked.
   - **Ordinary filler meeting**: "已完成会面" / "没去成", plus an optional toggle "游戏提示可以升级了". Observed readiness is the most valuable signal, because it lets the remaining fillers be dropped.
   - **Training or job**: a predicted line such as "学力 → 3" or "现金约 +X". Buttons "符合" (matches) / "不一样". Only "不一样" reveals a stepper for the rank or the balance.
   - **Tartarus**: a floor stepper prefilled with the last floor, a cash input with a 余额 / 变化量 switch (only one, the last-used mode remembered), and an optional protagonist level. "修正上次" edits the same event and never adds a visit.
   - **Story or forced events**: acknowledged automatically, no input.
   - **Can the widget commit directly?** Low-risk items (a filler meeting, or training whose outcome is certain) could be committed from the widget with an in-app undo. Rank events and tower trips always open the sheet. **This is a product decision for the lead.** The existing rule is that forecasts must not be silently applied; a user tap that confirms completion counts as an observation.
2. **Skipped or swapped action**: "没做" moves time forward with no gains. "做了别的" shows only the actions that were feasible in that slot, taken from the compiled rules, each with its predicted result prefilled. One commit, then replan.
3. **Coming back weeks later ("我玩到了…")**:
   - Pick the current in-game date with a month/day picker, ±1-day buttons and a 昼/夜 switch.
   - Show the plan items between the last observed slot and that date, each defaulting to 按计划 and each switchable to 没做 or 结果不同.
   - Show rank steppers only for links touched in that span. Untouched fields keep their values; unknown stays unknown, never 0.
   - Save everything as one atomic command and one revision, then replan.
4. **Full calibration stays a separate entry ("校准全部状态")**, with grouped sections and a **diff preview** before confirming, for example "魔术师 5→6；现金 未知→12,000". This replaces the blanket modal.
5. **Controls**: steppers for link rank (0–10) and stat rank (1–6); a numeric keypad with thousands separators for cash. Remove questions the time model can answer: after a daytime action the evening follows, so do not ask "这个时段还可行动吗".
6. **Feedback after every commit**: an undo snackbar, and a one-line replan summary such as "已重新规划 · 覆盖至 1月31日 · 有效行动 N 项 · 皇帝预计 12月X日 MAX". Add a short diff of what moved and why. Badges belong in the detail view, not on every widget line.

## 9. Test plan for the redesigned planner

**A. Replay invariants.** One helper, `assertPlanInvariants(rules, observed, plan, constraints)`, runs on every plan produced in every test below:
- No slot has more than one action; reservations, steps and pins never collide.
- Scenario cash never drops below 0 at any step, and costs are paid before rewards.
- No action lands on a forbidden date. Cover: known story locks, Apr 22 daytime, Dec 24 with an accepted invitation, the Jan 21 guard, exam blackouts, holiday restrictions for school links, rank gates, a reversed relationship.
- Per-stage visit counts never exceed caps, and an unknown count blocks capped visits.
- Gains and unlocks propagate: an independent `validatePlan` replay reproduces the scenario forecast, and each step's preconditions come from earlier steps or observations.
- The observed snapshot, ledger revision, verified bounds and `p3r_native_progress_v1` are byte-identical before and after planning.
- Every estimated item carries `estimate:true` and an assumption id. A failed search produces `search_limit` or `unknown`, never `proven_infeasible`.

**B. Plan span and density metrics** (report and assert, not just count tests):
- First and last dated useful step.
- Number of useful actions (not waits, not observation-only).
- Free slots left unused per month.
- Cases:
  - **Early game** (Apr 27, Emperor 0, simple goal Emperor MAX, secondary all links): the last useful step falls within the final 2 weeks of the horizon. Slot 612 is the exclusive end, so the last plannable day is Jan 31.
  - **Midgame** (Oct 22, Magician 2, Sun 7, cash 1,000): same assertion.
  - No long run of idle days while feasible actions exist.
- Store a golden summary file per case under `src/test/resources`.

**C. Scenario-model unit tests**: the `full_horizon_forecast_policy.json` fixtures (filler counts, surplus reset, `verifiedLowerBoundIncrement=0`); observed "not ready" forces at least 1 filler; Sun needs no points.

**D. Deviation scenarios, end to end through the coordinator**, with the invariants checked after each step:
- An accepted plan item.
- A skipped item.
- A rank event with no rank-up.
- Rank-up arriving earlier than predicted, which must drop the remaining fillers.
- A swapped action.
- Re-entry 3 weeks later with a mix of accepted and skipped items: the observed state matches expectations, and stat intervals, affinity bounds and availability observations are kept.
- Low money (0 cash means no paid actions, with a job or tower trip planned first).
- Tower cap 0 and 1.
- A story lock on the meeting day.
- A seeded random sequence of 30 deviations.

**E. Persistence and concurrency**:
- Retries are idempotent across restarts.
- A tower correction does not add a visit.
- A stale `plan_revision` install is rejected.
- A replan for an older state revision is discarded.
- The draft survives a process kill.
- Saving is atomic and inputs are disabled while it runs.

**F. Targeted result flows**, testable through the presenter without the UI, plus Robolectric smoke tests at 320×640 with screenshots in `qa_out`:
- **Emperor 6→7**: "已升到 7" sets only rank 7 and clears readiness; "没升级" keeps rank 6 and records the attempt.
- **Tower**: balance mode and change mode are mutually exclusive; the floor is checked against the stage ceiling.
- A stale widget intent cannot commit.

**G. Legacy safety**:
- Keep all 41 legacy test runs as they are.
- Add a cross-store isolation test: run all planner flows and compare a snapshot of `p3r_native_progress_v1` before and after.

**H. Determinism and performance**:
- Same input gives the same plan.
- Plan time is under about 3s on the JVM for the full horizon. Record the number of expansions.

## Files referenced

- `/Users/xihehan/P3R_Widget/android/app/build.gradle`
- `/Users/xihehan/P3R_Widget/android/app/src/main/AndroidManifest.xml`
- `/Users/xihehan/P3R_Widget/android/app/src/release/AndroidManifest.xml`
- `/Users/xihehan/P3R_Widget/android/.github/workflows/build-apk.yml`
- `/Users/xihehan/P3R_Widget/.toolchain/env.sh`
- `/Users/xihehan/P3R_Widget/.toolchain/android-user-home/debug.keystore` (the mismatched certificate)
- `/Users/xihehan/P3R_Widget/android/app/src/test/java/app/p3r/dayplanner/**`
- `/Users/xihehan/P3R_Widget/android/app/src/main/java/app/p3r/dayplanner/{PlannerStore,PlannerWidget,MainActivity}.java`
- `/Users/xihehan/P3R_Widget/android/app/src/main/java/app/p3r/dayplanner/v2/{PlannerEngine,UnifiedPlannerRules,GoalPlannerWidget}.java`
- `/Users/xihehan/P3R_Widget/rules/p3r/full_horizon_forecast_policy.json`
- `/Users/xihehan/P3R_Widget/prototype/planner_core.py`