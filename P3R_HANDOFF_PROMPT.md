# P3R Planner: implementation handoff

You are taking over or independently improving an existing Android app and home-screen widget for Persona 3 Reload. Inspect the accompanying source package before proposing changes. The owner wants a working adaptive calendar planner, not a fixed walkthrough reader or a two-day recommender. Continue the existing app and preserve its established visual design, user data, and widget behavior.

## Product outcome

A player selects Social Links to maximize and their priorities, enters their current visible game state, and receives a useful day-by-day plan for the entire remaining playable calendar. Simulate the consequences of every suggested action: money, social stats, affinity estimates, ranks, prerequisites, dates, and Tartarus visits. Those projected changes must enable later actions in the same plan. Do not stop after the first uncertain meeting or require repeated full-state entry to see further dates.

The player may ignore suggestions, return weeks later, edit current state, and regenerate the remaining plan without reconstructing every missed action. Existing values must remain filled in. Only changed fields should need editing.

Global optimality is NOT required. Practical useful scheduling, correct constraints, conservative estimates, and clear interactions matter more.

## Planning objective

1. Respect hard constraints: story/calendar locks, prerequisites, affordability, time-slot exclusivity, and user-defined Tartarus visit caps per story-unlocked stage.
2. Maximize satisfaction of the player's explicit goals in priority order.
3. With remaining time, maximize OTHER Social Links too: additional MAX links, then useful rank progress. An easy explicit goal must not leave the rest of the calendar arbitrary or empty.
4. Prefer less fragile routes and sensible resource buffers. Do not force romance just to advance an optional objective.

Use a suitable offline algorithm. The shipped Java implementation uses slot-layered beam search, width 48 and up to 60,000 attempted transitions, followed by independent replay validation. Its existing scoring emphasizes lower-bound target completion and progress. Treat these as implementation facts, not mandatory design choices. Investigate pruning, search-budget exhaustion, compiled action coverage, and best-prefix selection before retaining them. A long nominal horizon is meaningless if the output contains only two useful days.

## Conservative forecasting, without paralysis

Players cannot see raw affinity or social-stat points. Never require those hidden numbers. Start from conservative values compatible with visible ranks and confirmed observations. Do not silently assume matching-Persona or Charm bonuses.

Separate three things rigorously:

- Observed current state: what the player has confirmed
- Forecast state: projected results used to plan later actions
- Evidence/model assumptions: what is sourced versus estimated

Forecasts must propagate forward without pretending they are actual saved progress. Use evidence-backed recurring availability plus known exceptions; lack of an individually annotated date is not automatically proof that no future activity can be planned. Conversely, a weekday rule must not override forced story events or an accepted invitation.

Where ordinary hangout gains are uncertain, choose and document a reasonable conservative forecasting model with buffers. Do not label a chosen safety margin a mathematical guarantee. The current research has a reported base +10 ordinary-meeting claim that lacks independent raw-value corroboration; do not promote it to a verified mechanic. A schedule may contain explicitly estimated meetings and rank-ups, but must continue meaningfully through the horizon. Avoid putting warning badges on every widget line. Explain important assumptions in app detail.

Replan when actual results differ or the user changes goals/state. Never claim a failed heuristic search proves infeasibility. Distinguish unmet goals, insufficient rule coverage, and search limits.

## Interaction requirements

Main settings:

- Clear grouped inputs; preserve all previously saved values and unfinished drafts
- Current date/time, cash, visible stats, Social Link ranks and relevant unlocks, tower progress, visit limits, goals/priorities
- Unknown is not zero. Saving an unrelated change must not erase known affinity bounds, widen a precise stat interval, or reset availability observations
- Social Link choices show Arcana + character name + gender. Multi-person links must be labeled accurately; avoid unnecessary identity/story spoilers
- Save state and goals atomically, then recalculate; reject stale results

Action-specific results, not a giant generic form:

- For Emperor 6→7, open directly on that link with “已升到 7”, “没升级”, and a compact other-result edit
- For Tartarus, show cash and reached floor, with protagonist level editable if relevant. Support either net money change or current total balance unambiguously, never both counted together
- For training/jobs, show the relevant result only, with a simple normal-result confirmation and correction path
- Full-state editing remains a separate entry point
- Normal predicted results should be easy to accept; do not force the player to re-enter unrelated information
- Record each tower trip once; correcting it must not consume another visit. Keep idempotency across retries/restarts

## Existing visual/product decisions to preserve

Native Android app plus widget; offline operation. Simplified Chinese guide text with correct Reload terminology. P3R flat cyan daytime/deep-blue nighttime colors, paired right-biased line-art motifs, official-style/logo assets already present. No character illustration redesign requested. Existing day/night fade works and must not regress.

Widget: Current on the left, Done on the right as the primary action; date-area arrows browse individual tasks; Current returns to the pending action. Keep compact readable dialogue choices separated by slashes and natural wrapping. Complex steps live in app detail reached by tapping the task. Existing fixed-guide widget and progress must remain safe when adding adaptive planning.

## Sources and localization

Verify Persona 3 RELOAD specifically. Some Wiki pages mix older P3/FES/Portable mechanics even under Reload labels. Preserve source URLs, applicable variant, evidence confidence, and exceptions in structured data.

Current rule data and original Chinese guide are included. Hash matching proves packaging identity, NOT factual accuracy. Existing audit found no mistranslation among 41 sampled proper terms, but exhaustive official Simplified Chinese dialogue verification is NOT complete. Do not back-translate names/options from English. Preserve source Chinese text and cross-check against Reload Simplified Chinese evidence. Foreign option order must not automatically determine Chinese choice-point binding.

## Confirmed defects and work in progress

The published 2.0.0/code10 APK is not a completed reliable full-horizon optimizer. Reported issues:

- Actual plans can stop after only a few days because only readiness-proven rank prefixes are compiled and future resource progression is curtailed
- Routine training/job adapter could credit actions on Dec24 despite an accepted invitation; Jan21 conditional handling also needed review
- Apr22 mandatory daytime lock was omitted in one adapter
- Per-action assumptions were stored but not all displayed in app detail
- Known preparation steps were dated today, unnecessarily blocking Devil/Justice/Hanged Man openings that require earlier preparation
- Reopening/editing could lose availability or reset/widen previously known values
- Result entry opened a generic form instead of a targeted action panel
- Secondary optimization of unspecified Social Links was inadequate

Several calendar/input/localization fixes have been written and tested in a working tree. Full-horizon forecasting and targeted-result UX are being redesigned. Read the package's timestamped state manifest to know exactly what is included; do not assume this prompt means those changes shipped. Existing test counts do not establish product correctness.

## Engineering and deliverables

Inspect build scripts and existing data migration/signing identity. Preserve app data. Do not silently change applicationId. Never include private signing keys in shared artifacts. If your APK uses a different signing key, explain that it cannot overwrite the installed app and provide a safe testing/data-preservation approach rather than telling the player to uninstall.

Work in this order:

1. Reproduce a midgame input producing the inadequate short plan; inspect algorithm, rule adapters, UI and persistence
2. Give a concise design for full-horizon simulation and event-specific updates, then implement under the above requirements
3. Validate at least one early-game and one midgame case through the remaining calendar, including a simple explicit goal plus useful secondary goals
4. Test skipped actions, no-rank-up deviations, later reentry, low money, tower caps, story locks, known stat intervals, input persistence, and stale replan rejection
5. Test targeted Emperor and tower result flows end to end; inspect actual small-screen/native renders
6. Provide source changes, build instructions, usable release APK, and a concise explanation of what works and what remains approximate

Use concrete replay assertions: no double-booked slots, no forecast overspending, no forbidden-date actions, no visit-cap violations, correct propagation of gains/unlocks, and no mutation of observed state from forecasts. Report the dated plan span and useful action count, not only unit-test totals.

Do not rewrite the entire UI or restart the project without a specific technical need. Do not claim ongoing work, successful delivery, official translation verification, or installation without evidence. The owner wants useful software, not long disclaimers or repeated requests for information already present in the package.

The result is a android APK. All dependencies must be included in this directory ONLY

The UI, terminology, and APP must all be in simplified Chinese with proper, official ATLUS translation unless you  can't find specific line, then you can translate yourself.
