# P3R 0.3 adapter handoff

## Stable conventions
- Slot index = game ordinal × 2 + (daytime 0 / evening 1). Epoch remains 2009-04-01. Do not use device year.
- Entity slug is the final component of `p3r.social_link.<slug>`; state remains `socialLinks.<slug>`.
- All source-backed predictions remain conditional. Source coverage cannot prove global impossibility. Existing January actions and hashes are unchanged.

## Magician and Chariot transitions
Read `<slug>_base_model.json`:
1. `incoming_thresholds`: gate into `to_rank`, indexed by the NEW rank. A rank-r checkpoint compares known current-rank lower bound with gate r+1.
2. `rank_events`: answers in the event that ends at `to_rank`. Compute raw gains from selected source-option ordinals only; unknown choice contributes no known lower bound. These are source ordinals, not newly invented official Chinese UI choices.
3. On successful rank-up, assign new rank; replace affinity lower-bound knowledge with this event's known raw gains. Discard prior-rank bound knowledge instead of adding it or subtracting a presumed spend. This is deliberately conservative, not a claim that the game's hidden value resets exactly.
4. Assign ready=true only if that bound reaches the next gate; otherwise forget ready. A shortfall never establishes false. A fresh explicit user observation may establish false. Rank10 has no further readiness.
5. Any reversal/unknown negative effect invalidates bound and readiness; current relationship state must be normal. Old checked guide tasks are not observations.
6. `bonus_readiness_lookup` is separate and default-off. It is a link-local Wiki sufficiency claim and requires optimal answers plus flags at the event. Current possession of the matching Persona cannot prove it was present at the earlier event. Do not infer one universal rounding formula. Chariot's conflicting rank7 lookup is disabled.

A fully known upcoming answer policy can be an explicit planning assumption. Executing a plan must reconcile actual choices/result before preserving the prediction. No automatic localization of English answer text.

## Calendar
Use `calendar_execution_contract.json` when included in the manifest. Raw `calendar_full_candidate.json` is evidence; do not directly turn null `available` into false. Ordinary O, invitation D, source-negative dash and empty are distinct. Introductory O rows are not optional normal meetings. Rank-up and calendar legality are separate decisions.

Existing `early_link_calendar_witnesses.json` captures fixed-route examples. Its starting ranks describe those examples; the conditional execution contract, rather than those witness ranks, defines arbitrary-checkpoint candidates. A witness does not assert complete calendar coverage or globally free slots.

## Observation-only links
`observation_gated_rank_actions.json` provides explicit set-null/forget effects after rank+1. Forget both observedValue and observedRank and affinity. Scope these fields to the entity. This enables a known-ready next action; it does not establish repeated rank gains.

## Jobs
`jobs_wage_rules_candidate.json` has three wage schedules as evidence, not enabled exact pay formulas. Only the guarded Chagall action exposes minimum income. Map yenLowerBound +2500 to an interval/lower-bound resource, never to an exact balance. Job availability and a free evening are required; ordinary task completion must reconcile actual pay. Shift boundaries and software effects remain source candidates.

## Validation
Run both `validate_rules.py` and `validate_expansion.py`. The latter independently recalculates 16 base-model fixtures. Embedded source-extraction and lookup fixtures are additional checks, not Android or game replay results.
