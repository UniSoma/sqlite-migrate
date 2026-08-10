---
id: sqm-01kzpbrbwvhy
title: Bound the gate-failure skips in three properties
status: closed
type: task
priority: 2
mode: afk
created: '2026-08-10T17:33:03.771310237Z'
updated: '2026-08-10T20:47:07.225486791Z'
closed: '2026-08-10T20:47:07.225486791Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: Each of the three properties reports its reached/skipped trial counts in the test run output
  done: true
- title: Each of the three properties fails when fewer than a quarter of its trials reach its assertions
  done: true
- title: The floor tracks the configured trial count, so raising SQM_TRIALS raises it proportionally
  done: true
- title: bb test green and clj-kondo --lint src test clean with the bound in place
  done: true
tags:
- phase-3
---

## What to build

Residual convergence, data preservation, and version honesty short-circuit to `true` when the Check result does not pass. Skipping data-dependent failures is defensible, but nothing bounds how often the generator lands there — all three could pass a full run without ever reaching their assertions, while ADR 0010 states them unconditionally.

Give each of the three a per-run tally of trials reached versus skipped, print it with the run, and fail the property when too few trials reached its assertions. `defspec` offers no after-hook and `clojure.test` does not order vars, so each property drives `clojure.test.check/quick-check` from a plain `deftest` that tallies during the run and asserts the bound alongside the quick-check result.

The floor scales with the trial count (`SQM_TRIALS`, default 40) rather than being a fixed number, and the same tally-and-bound mechanism serves all three rather than being written out three times. Measured today at 60 sampled scenarios: 53 reach their assertions, 7 skip — roughly 12%, so a floor at a quarter of trials has wide margin and still catches a property that has quietly gone vacuous.

Out of scope: `autoincrement-continuity-ok?` short-circuits on any plan with unhandled entries — a second, inner skip inside data preservation, not a gate-failure skip.

Found by a two-axis code review of the epic (Spec axis, partial requirement).

## Blocked by

None - can start immediately.

## Notes

**2026-08-10T20:47:07.225486791Z**

Residual convergence, data preservation, and version honesty are now plain deftests driving clojure.test.check/quick-check through one shared helper, gated-property, rather than three defspecs that short-circuited to true on a failing Check with nothing watching how often.

gated-property takes a label and the property body, runs the trials over g/gen-scenario, and splits each trial at the Check result: reaching trials increment a counter and run the body, skipping trials increment the other and pass. It then prints the split — "residual convergence: 38 trials reached the assertions, 2 skipped on a failing Check" — and asserts twice: the quick-check result, and that reached trials meet reach-floor, defined as (quot trials 4) so raising SQM_TRIALS raises the floor with it. The failure message names the run vacuous rather than passing.

The bound was demonstrated by forcing every trial down the skip branch: all three failed with 0 trials reached against a floor of 10, while quick-check itself still passed — exactly the vacuous run the ticket describes.

Observed skip rates leave wide margin: 1-6 of 40 at the default, and 220-237 of 3000 (roughly 7-8%) against the 25% floor.

The tally counts shrink replays, which only happens once a trial has already failed; the docstring says so. autoincrement-continuity-ok? keeps its inner short-circuit on unhandled entries, out of scope as stated.

Verified: bb test green at 156 tests / 806 assertions, clj-kondo --lint src test clean, and the properties namespace green at SQM_TRIALS=3000.
