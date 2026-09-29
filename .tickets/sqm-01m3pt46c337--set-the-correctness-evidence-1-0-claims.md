---
id: sqm-01m3pt46c337
title: Set the correctness evidence 1.0 claims
status: open
type: feature
priority: 2
mode: hitl
created: '2026-09-29T14:46:58.691229212Z'
updated: '2026-09-29T14:46:58.775464267Z'
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