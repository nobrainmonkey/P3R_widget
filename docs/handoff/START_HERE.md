# P3R planner handoff — immutable snapshot, 2026-10-13 04:39 UTC

Start with PROMPT.md. Work in your own extracted copy; development continues separately.

## What this contains
- `source/android`: native offline Android app, both original guide and goal planner, resources, rules assets, all tests and Gradle 8.9 wrapper
- `source/rules/p3r`: source evidence, pinned snapshots and supplemental research; presence is not proof that a rule is enabled. Runtime asset loaders verify hashes
- Root Python files in `source`: earlier prototype/reference tests, not the shipped Android runtime
- `SOURCE_SHA256.json`: hashes of every snapshotted source file

## Published versus working
Published APK is version2.0.0/code10, SHA256 `7d9df477715318f79ff3ea9ec11dbefab0d660680715df2393da126588b21490`. It does NOT contain the new corrections below.

This source snapshot has version2.0.1/code11 in build.gradle, but no2.0.1 release has been built or published. Version text alone does not establish delivery.

Completed working changes: unknown future routine training/job dates no longer earn credited safe forecast gains; they are separate alternatives. Christmas/Jan21 branch guards and omitted April22 mandatory daytime lock corrected. Per-action assumptions visible in app details. Four grouped input sections, all22 sourced person/group/gender labels, raw draft persistence, input disabling during save, same-date retained capacity/stat/affinity, and optional prior-day preparation observation.

Verification at snapshot: full340 v2 tests passed, then final Home15/15 passed including two additional cases, for342 distinct planner tests covered. Existing41 legacy tests passed on the earlier2.0.0 source; they were not rerun after this snapshot's UI changes. Native render tests use Android/Skia through Robolectric; no new physical-phone validation.

NOT implemented at this checkpoint: full-horizon scenario continuation after unknown affinity, new secondary optimization across other manual links, action-specific Upgrade/No upgrade quick result panels, or optional protagonist-level tower-return field. These are requested next. The current strict planner can produce short prefixes and is not an adequate completed version of the user's full-calendar request.

## Build
Install JDK17, Android SDK platform35/build-tools35.0.0, accept required SDK terms yourself, and set ANDROID_HOME. Run from source/android:

    ./gradlew :app:assembleDebug
    ./gradlew :app:testDebugUnitTest
    ./gradlew :app:lintRelease :app:assembleRelease

The wrapper pins Gradle8.9; Android plugin is8.7.3. Dependency download requires internet. Existing test configuration contains environment-specific user.home/truststore paths in app/build.gradle, and screenshot tests contain `/workspace/shared/planner_v2/qa` output paths. Adapt these local output paths on another machine; do not change domain behavior to pass tests.

## Signing and update safety
Both build variants retain application ID `app.p3r.dayplanner.test`, with display nameP3R. Published release was nondebuggable and reused the existing private-install Android debug certificate to preserve update compatibility. No signing key, credentials, SDK or caches are included.

Expected installed certificate SHA256: `175da71189f9b9cee2c232c5b9304b73131a4bd819ad88f8898005aaaf9afa36`.

Building elsewhere generates a DIFFERENT debug certificate. Such an APK cannot overwrite the installed app under the same package. Do not advise uninstalling and losing progress. Use a separate development applicationId or an owner-authorized signing process with the existing key; no key is supplied here.

## Audit findings and limitations
Pinned data and hashes matched actual shipped APK. Base numerical costs/gains inspected matched Reload sources. All41 inspected Chinese proper terms were found in the original CN guide; this is not exhaustive official-game-screen verification. Foreign option ordinals are NOT verified against Chinese option order and must never be displayed as Chinese answer numbers or silently point-bound.

Ordinary encounter exact affinity gain is not independently corroborated. A future buffered model must be labeled an estimate and kept separate from observed state; do not pretend a fixed extra meeting is guaranteed. Source-positive social dates are conditional on applicable story/rescue/relationship guards. A small strict example passed: Sun7 onOct22 → Oct25 rank8 → Nov1 rank9 → Nov8 rank10; it remains conditional on no unmodeled story lock.

Working fixes preserve SQLite observation history, stable command IDs, CAS revisions, correction/idempotency, JSON legacy backups and the original guide/widget. Do not merge forecast gains into observed player state.
