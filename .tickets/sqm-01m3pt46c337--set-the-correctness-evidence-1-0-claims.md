---
id: sqm-01m3pt46c337
title: Set the correctness evidence 1.0 claims
status: open
type: feature
priority: 2
mode: hitl
created: '2026-09-29T14:46:58.691229212Z'
updated: '2026-09-29T17:59:08.226860628Z'
parent: sqm-01m3pt2fgxp9
tags:
- wayfinder:grilling
---

## Question

What generative evidence must hold before 1.0 claims the ADR 0010 properties, and at what depth?

Found while charting (test/sqlite_migrate/properties_test.clj and generators):

- ADR 0010 says "six" properties but lists seven (no-op, round-trip, residual convergence, data preservation, gate bidirectionality, plan determinism, version honesty).
- No row generator violates the `:check`, `:primary-key`, or `:without-rowid` Gates, though ADR 0010 says each Gate code needs a violating generator; `:foreign-key` is covered only by a corpus deftest.
- Gate bidirectionality has no reached-trials floor, unlike the other gated properties (trials/4).
- The re-plan fixpoint is example-tested only (rebuild_test.clj:284).
- Version honesty compiles only for the running engine's version; cross-version planning is covered only by CI's two self-consistent legs.
- Plan determinism is checked within one process only.
- `SQM_TRIALS` is 40 locally, 100 in CI.

Decide the 1.0 trial depth, which gaps must close, and whether a long soak run is part of the release gate.

## Notes

**2026-09-29T16:40:20.541140259Z**

From the first-consumer assessment: the bug "Rebuild drops and recreates every view that reads the table transitively" (a Rebuild fails when a view reads the table through another view) got past the property suite, because the generators never chain views. That ticket's acceptance criteria add chained views to the generators. When deciding which evidence 1.0 claims, ask which other dependency shapes (a trigger on a view, a view over several rebuilt tables) the generators still can't produce.

**2026-09-29T17:59:08.226860628Z**

From "Decide how a Plan leaves consumer-owned live objects alone" (ADRs 0023, 0024): ADR 0010's residual convergence now reads 'the re-diff equals exactly the unhandled entries plus the Kept entries'. The Directive generator needs keeps, and the schema generator needs an INSTEAD OF trigger on its view (it generates no view triggers today). The new bug "Rebuild counts a dropped table's triggers as surviving dependents" asks for a generator case where a dropped table's trigger mentions a rebuilt table.
