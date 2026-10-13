# Native widget work paused for design review

Paused at the user's request on 2026-10-12. This is unfinished source, not an approved widget design or a verified release. Resume only after design approval or a new instruction.

## Preserved changes

- Original native baseline backed up before edits at /workspace/shared/p3r_backups/android_before_native_animation_20261012.tgz
- Four new RemoteViews scene layouts: compact/standard, static/motion
- Launcher-hosted XML palette fade and staged figure slide/fade via supported ViewFlipper; no per-frame broadcasts, polling or background animation service
- Task-based palette retained, independent of full app theme; static layout fallback for non-transition updates and animator-scale zero
- Clean A day/night art; separate experimental compact reading candidate, not approved final art
- Month + tilted day-number + sun/moon header; no trailing date dot
- Equal Done/Next actions; game date and completion storage keys unchanged
- Multi-step instruction tasks, as well as substep tasks, require explicit confirmation via MainActivity rather than silent completion
- Separate debug application ID suffix .test and test label configured by build worker to avoid replacing the original app or its progress
- Size-aware bitmap output bounded against responsive variants' aggregate image-memory budget

## Validation status

The in-flight build completed after the pause: Java compilation, Android resource/manifest processing and assembleDebug passed for an initial snapshot. lintDebug failed with one preexisting error (styles.xml:8 windowLightNavigationBar needs API 27 while minSdk is 26), plus 230 warnings. testDebugUnitTest reported NO-SOURCE. No APK was delivered. A later bitmap-budget source adjustment has not been confirmed compiled. No device, emulator or launcher test was run. No automated native unit tests were added before the pause. Source is NOT release-ready.

## Important remaining work

- Port latest approved design only: newest HTML has PERSONA 3 / RELOAD typography, left counter with location alongside, both separators at 53% inner width, tall figure 47% with feather/shadow. Native current work is only an earlier approximation and still needs this alignment.
- Art width is currently 50% standard and 42% compact; native tall and standard do not yet have distinct geometry. Full-reading candidate is selected for compact day only.
- Verify compact tiny-height legibility, header overlap, click targets, font scaling, text/art balance, clipping and image-memory budget on real launchers
- Verify XML transition replays: cached motion RemoteViews can replay on host recreation; target state stays correct, but the cosmetic replay may need refinement
- Verify rapid Next/Done, stale PendingIntent rejection, complex confirmation cancellation, process death, multiple widgets, resized responsive variants and reduced motion
- Next when every task is done currently opens the app; review this fallback before final acceptance
- Verify JSON import/export and original user data preservation; .test install has separate storage, so existing progress will not automatically appear there
- Preserve generated debug key for test-build continuity; never instruct uninstalling the original app

## Verified platform references

- https://developer.android.com/reference/android/widget/RemoteViews
- https://developer.android.com/reference/android/widget/ViewAnimator
- https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/widget/ViewAnimator.java
- https://developer.android.com/develop/ui/views/appwidgets/advanced

ViewFlipper supports XML in/out tween animations and remotely changing the displayed child. A visual crossfade is feasible; this is not proof of arbitrary color-property interpolation or CSS animation in launcher widgets. Phone-launcher behavior remains unverified.
