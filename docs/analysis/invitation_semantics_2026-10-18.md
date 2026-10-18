# Reload holiday invitation semantics review — 2026-10-18

The user reported that a planned weekend SMS invitation sometimes did not exist, while another
actual action could be found under 改做别的. The date import is consistent with its source; the
guarantee and transition inferred from those dates were not justified.

## What the imported data proves

`rules/sources/aqiu_gamedata/HolidayScheduleDataAsset.tsv` has only Month, Day, and three Arcana ID
columns. Its 35 dates and 77 date/link pairs identify possible senders. The builder's Arcana ID
minus one conversion matches the existing calendar. Neither that table nor the imported calendar
contains every SMS trigger, rank/history condition or message selection rule. A candidate date
therefore cannot establish that a particular player actually received the message.

## Different from a normal rank-up scene

The Reload-specific [community mechanics wiki](https://w.atwiki.jp/wiki10_persona3/pages/455.html)
lists holiday outings among ways to obtain affinity, separately from ordinary rank-up scenes.
The [In High Demand achievement guide](https://www.trueachievements.com/a423926/in-high-demand-achievement)
also explains that these outings cannot directly rank a Social Link up. The source review did
not establish a complete executable table of holiday dialogue, affinity gains or SMS eligibility.
No untranslated/guessed rank-scene answers are substituted for outing dialogue.

## Conservative executable behavior

- Automatic planning, remaining meeting capacity and LINK_AVAIL unlock steps use ordinary O
  availability only. D-only dates never promise an SMS or a rank-up. An O+D overlap remains an
  ordinary O meeting opportunity.
- 改做别的 additionally offers `invite:<linkId>` for that day's possible sender when the link is
  established, not MAX, not missing/lost and the daytime slot is open. Its visible condition is
  「收到短信才选」. These are conditional records, not claims that all listed messages arrived.
- Confirming 「赴约了」 consumes one daytime slot. It does not run a rank scene, change the rank,
  infer a ready cue, or display the next ordinary rank scene's answers.
- Answer-dependent affinity is currently unmodeled. The recorded hidden-point lower bound is
  retained, with this limitation explained in the sheet; a player-confirmed 「下次就能升级」 cue is
  accepted. This can underestimate the benefit of an actual outing, but avoids fabricated progress.
- The independent plan validator rejects `invite:` forecasts and ordinary `meet:` actions placed
  on D-only dates. Historical confirmed `meet:` records keep their recorded ranks when replayed.

Regression coverage is in `InvitationTest`, the invitation case in `ResultSheetTest`, and the
real SQLite/restart case in `ResultFlowsRealPackTest`. These distinguish conditional choices from
guaranteed actions, preserve ordinary O semantics and real observations, and check single-slot
time consumption and retry idempotency.
