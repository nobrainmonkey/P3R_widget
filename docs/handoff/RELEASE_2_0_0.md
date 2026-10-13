# P3R 2.0.0 release checkpoint

Offline Android goal planner plus home-screen goal widget. The original fixed guide, backup/import/export, progress, and guide widget remain available independently.

## Implemented
- All 22 goals selectable with visible-state re-entry and priorities
- Conservative source-based lower bounds, dated adaptive meeting reservations, strict resource prerequisites, and independently replayed plans
- Verified training and minimum-income job actions, bounded introduction/preparation rules, and informational story milestones
- Per-unlock-stage tower counts/caps and guarded expedition candidates; actual net cash or absolute balance plus reached floor recorded after return
- Atomic SQLite observations, stable retry identities, corrections, configuration/state revision guards, and preserved unknowns
- Native goal widget with browsing, Current reset, exact-item opening, actual-result entry, and retained-host day/night fade

## Verification
326 new planner tests and 41 legacy tests passed across final runs. Release lint and assembly passed (0 lint errors; 540 warnings, primarily text/RTL/resources). APK signature and alignment verified. API 26/28/35 RemoteViews and native attached animation frames covered. No physical-phone installation or launcher test was performed in this environment.

## Limits
The plan is conservative and adaptive, not a guaranteed all-goal MAX route. Unknown ordinary-meeting point gains, unresolved preparation gates, branch choices and incomplete calendar coverage remain explicit checkpoints or conditional reservations. No tower money, floors, rescues or story completion are invented. The prior guide widget is not automatically converted; add “P3R 目标规划” from the app or launcher widget picker.

## Build identity
2.0.0 / code 10, release, non-debuggable, testOnly false, label P3R, package app.p3r.dayplanner.test. Existing signing certificate is retained for in-place private updates. See delivery/release_metadata.json for artifact identity and hashes.
