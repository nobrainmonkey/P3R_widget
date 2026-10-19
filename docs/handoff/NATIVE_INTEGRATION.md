# Offline Android integration checkpoint

The copied project is `android/`. The original delivered 1.3.3 source, APK and signing identity are not modified by this working directory.

## Runtime architecture

- `v2.PlannerEngine`: offline Java deterministic model/search/replay validator
- `v2.PlannerStateStore`: separate SQLite ledger (`p3r_goal_planner_v2.db`)
- `v2.PlannerRules`: manifest-checked narrow January rule adapter
- `v2.PlannerCoordinator`: revision-safe command and search boundary
- `v2.GoalPlannerActivity`: native Android forms, persisted pending command identity, observed state and conditional candidate presentation
- Original `MainActivity`, guide storage and widget remain accessible unchanged

There is no Python runtime, localhost server or network service dependency in this Android path. The HTML/Python prototype is development scaffolding only.

## Build and checks

```sh
source /workspace/shared/p3r_toolchain/env.sh
cd /workspace/shared/planner_v2/android
gradle --no-daemon :app:testDebugUnitTest --tests 'app.p3r.dayplanner.v2.*'
```

The first native pass compiled production code and passed 23 tests: 20 native ledger cases and 3 real-adapter/coordinator cases. Further UI/core and expanded-rule tests are in progress; consult current XML reports rather than treating this historical count as a release certification.

## Important lifecycle protections

- Pending request ID/body are committed before submitting observations
- Retried requests retain the original expected revision and captured time
- Completion clears a pending request only if its command ID still matches, under a process-wide lock
- Destroyed Activity callbacks do not refresh obsolete UI
- A ledger transaction remains durable if subsequent planning fails
- Invalid social rank bounds are rejected before queued submission and at the native coordinator boundary
- Goal/cap changes invalidate older plans through state/config revision

## Not yet release-ready

The current Activity exposes the initial two-link January slice. Expanded source-backed adapters, general observed social-result confirmation and broader goals are ongoing. Existing guide Done toggles do not establish player rank/stat/resource changes. A goal widget must route predicted actions through observed-result confirmation rather than silently applying forecasts.

No new APK has been delivered from this worktree. The retained versionCode/package configuration is not a release decision; update metadata and full regression checks are required before delivery.
