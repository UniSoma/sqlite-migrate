---
id: sqm-01kzpbrbsv6p
title: Close the No-op property's forward direction
status: closed
type: task
priority: 2
mode: afk
created: '2026-08-10T17:33:03.675725537Z'
updated: '2026-08-10T20:47:07.129465452Z'
closed: '2026-08-10T20:47:07.129465452Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: 'The No-op property asserts both directions: two realizations of one Schema value give an empty Diff, and a mutated target gives a Diff with entries'
  done: true
- title: The forward arm draws its targets from the existing mutation generator, not from hand-written examples, and skips no mutation kind
  done: true
- title: The forward arm asserts that the Diff entries name the mutated table, not only that the Diff is non-empty
  done: true
- title: Each direction was broken on purpose, failed, and the demonstration is recorded in the close summary
  done: true
- title: Suite green and clj-kondo --lint src test clean
  done: true
tags:
- phase-3
---

## Description

ADR 0003 and ADR 0010 lock the No-op property as an iff: `diff(a, b)` is
empty if and only if the two Snapshots are Equivalent. The generative
suite asserts one half only — two realizations of one Schema value give
an empty Diff. Nothing generative asserts the forward half: that a
Semantic difference always makes a minimum of one Diff entry. That half
has cover from hand-written examples only.

## What to build

The No-op property asserts both directions over generated Schema values,
end to end through the public API and real in-memory SQLite.

The forward arm takes a live Schema value and a target one perturbation
away from the existing mutation generator. It realizes both sides into
pristine databases, diffs them, and asserts the Diff has entries and that
those entries name the mutated table. Every mutation kind the generator
makes is a Semantic difference by ADR 0003 — physical column order,
declared type text, and table flags included — so no kind is exempt and
no filter is needed. If any kind can make an Equivalent target, that is a
defect in the generator, not a case for the arm to skip.

The arm needs no rows, no Plan, and no Apply — the tracer path is
Schema value -> Snapshot -> Diff -> assertion.

Both arms must be able to fail. Break each one on purpose, watch the
property go red, and record the two demonstrations in the close summary.

## Blocked by

None - can start immediately.

## Notes

**2026-08-10T20:47:07.129465452Z**

The No-op property now asserts both halves of ADR 0003/0010's iff over one generator. no-op-property runs over g/gen-rowless-scenario (gen-schema bound through gen-mutation, the row load left off — neither arm realizes rows). The reverse arm is unchanged: two realizations of the scenario's live Schema value give an empty Diff and a Plan with no ops and no unhandled entries. The forward arm diffs that live realization against the target one perturbation away and asserts the Diff drifts and that every entry's path is [:table X ...] with X folding to the mutated table — or its post-rename spelling, which g/mutated-table-names supplies from the scenario's :table-rename.

No mutation kind is filtered or skipped. A REPL sample of 400 scenarios covers all twelve — add-column, add-table, drop-column, drop-table, rename-column, rename-table, retype, reorder, toggle-not-null, toggle-unique, toggle-strict, toggle-without-rowid — with every scenario satisfying both the drift and the naming assertion, so no kind produces an Equivalent target.

Both directions were broken on purpose and went red:
- Reverse arm: realizing b from (:target scenario) instead of (:live scenario) failed (not (m/drift? d)), shrinking to a one-column table gaining a "znew" column.
- Forward arm: diffing a against a second realization of (:live scenario) instead of the target — a stand-in for a generator handing back an Equivalent target — failed (m/drift? forward), shrinking to the same add-column case.

Generator refactor from the Standards review: the row-free generator lives in generators.clj as gen-rowless-scenario rather than being restated in the test ns, and gen-scenario is now that plus its row load; mutated-table-names moved beside it, since it reads scenario keys only.

Verified: bb test green at 156 tests / 806 assertions, clj-kondo --lint src test clean, and the properties namespace green at SQM_TRIALS=600 (several runs) and 3000. The 3000-trial run first surfaced a latent rebuild bug on tables named "if" — filed and fixed as sqm-01kzppn8zkvm, committed separately.
