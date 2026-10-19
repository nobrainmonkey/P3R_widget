# P3R goal planner: isolated implementation preview

This directory is separate from the installed fixed-guide Android app. It does not replace that app or its backup format.

## Run locally

Python 3.10+; standard library only:

```sh
cd /workspace/shared/planner_v2
python -m unittest discover -v
python local_app.py --port 8765
```

Open `http://127.0.0.1:8765` on the same computer. The server binds only to loopback. It does not upload player data. Observations persist in `local_state.sqlite`; use `--database PATH` for a separate run. The preview currently manages one run named `local`.

## What is implemented

- Observed state is an authoritative checkpoint plus durable, idempotent commands. Returning after a deviation can replace the current baseline without reconstructing earlier history.
- Unknown money, floor, hidden-value intervals and stage visit counts remain unknown. They are never silently zero.
- Goals have priorities. Dungeon caps are keyed to the stage unlocked at entry, not the highest floor visited or calendar month.
- A dungeon return accepts **net cash delta or absolute balance**, exactly one, and highest floor reached on that visit. Actual observations update resources once; repeated submission of the same command returns the receipt. The UI retains the request until a receipt arrives.
- Return corrections reference the original command, recompute subsequent observations, and never add a visit. Later absolute money observations remain authoritative.
- Existing history above a cap can be recorded truthfully; the cap constrains proposed future visits.
- Preferences advance the same revision guard as observations, so a plan computed for old goals/caps cannot replace a newer one.
- Search uses deterministic bounded beam search and separate replay validation. Forecast values never overwrite observed values. Search exhaustion is not automatically proof of impossibility.
- A confirmed return is durable even if replanning needs a new baseline or preferences.

## Actual game coverage

`rules/p3r/` contains the audited, provenance-bearing rule package. The current adapter executes only already-unlocked, normal Sun and Devil meetings in January 2–30. Two safely sourced tower nights are a conservative candidate subset; omission does not mean other nights are forbidden. It does not assume tower income, clearance or rescue completion.

Every real candidate is conditional because story/free-time coverage is incomplete. Unknown visible rank/relationship state asks for observation. Other links are unsupported, not declared impossible. The 22-link catalogue and additional raw audit records are not a claim that all links have executable mechanics.

Core tests use clearly synthetic rules. Real-adapter integration tests validate schema/provenance boundaries and conservative behavior, not the accuracy of a played-through game.

## Current limitations

- This is a local implementation preview, not a new Android APK or a mature all-goal solver.
- The browser form exposes the initial two-link slice. General social action confirmation and broader rule coverage are subsequent integration work; for now update the observed baseline after other actions.
- JSON export is available at `/api/export`; importing planner-v2 backups is not yet implemented. Legacy guide backup data can be carried opaquely and is never inferred into player resources.
- No automatic story completion, hidden affinity reconstruction, stochastic cash forecast or guaranteed optimality.
- Desktop browser interaction is still subject to final UI verification; engine checks do not establish Android/launcher behavior.

## Files and responsibilities

- `planner_core.py`: pure rules, transitions, intervals, bounded search and replay validator
- `state_store.py`: SQLite observations, revisions, command receipts, corrections and preferences
- `planner_service.py`: observation/search orchestration and stale-result rejection
- `real_rules_adapter.py`: checked, narrow adapter from audited JSON
- `local_app.py`, `ui.html`: loopback-only state/goals/return interface
- `test_*.py`: synthetic, independent adversarial and integration tests
- `rules/p3r/README.md`: actual evidence, unresolved items and coverage

## Status meanings

`feasible_within_model` is a replay-legal plan in the loaded complete model, not a promise about unmodeled game mechanics. `conditional` lists assumptions/incomplete coverage. `unknown` means required observations are missing. `unsupported` means rules are absent. `search_limit` means the bounded search cannot conclude. `proven_infeasible` is reserved for complete, exhaustively checked modeled scope.
