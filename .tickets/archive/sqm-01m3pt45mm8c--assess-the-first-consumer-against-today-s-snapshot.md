---
id: sqm-01m3pt45mm8c
title: Assess the first consumer against today's SNAPSHOT
status: closed
type: task
priority: 1
mode: hitl
created: '2026-09-29T14:46:57.940649069Z'
updated: '2026-09-29T16:40:52.334018099Z'
closed: '2026-09-29T16:40:52.334018099Z'
parent: sqm-01m3pt2fgxp9
tags:
- wayfinder:task
assignee: jonasrodrigues
links:
- sqm-01m3q0hqvwa6
- sqm-01m3q0j698q9
- sqm-01m3q0j6cb7m
- sqm-01m3q0kapxa2
- sqm-01m3q0kb3z87
---

## Question

What does the old project — the motivating first consumer — find when it meets today's 0.2.0-SNAPSHOT? The user runs this (the project can't be shared here) and reports back.

Hands-on, on a *copy* of the project's real database file:

1. `snapshot` the copy; `declared-snapshot` the project's real Declaration.
2. `diff` and `drift-report` — any drift that shouldn't be there (false drift, Noise the relation keeps)? Any real change it misses?
3. `plan` and `plan-report` — any surprising route (Rebuild where in place was expected, or the reverse), refusal, or unhandled entry?
4. `check`, then `apply!` on the copy; re-plan afterwards and confirm zero Ops.

On paper: what the project needs that the design doesn't cover, and every point of API friction or doc gap hit along the way.

Resolution: a findings list as a note, one line per finding. Each becomes a ticket or a fog patch on the map.

## Notes

**2026-09-29T16:40:34.157432730Z**

Findings from the user's run against 0.2.0-SNAPSHOT (cfb225b), on a copy of the consumer's real 738 MB file: 151 tables, 80 views, 7.25M rows. The full report is FINDINGS.md, which the user holds; it is not in git because it names the consumer's schema. Headline: the Equivalence relation was exact across 395 real entries (no false drift, no missed change). Rebuilds preserved every value and rowid. A failed Apply rolled back completely, and after the workaround the file converged to zero Ops.

One line per finding, with where it went:
1. [apply, blocker] A Rebuild misses views that read the table through another view, so the real upgrade can't apply → bug "Rebuild drops and recreates every view that reads the table transitively" (sqm-01m3q0hqvwa6).
2. [api-friction, major] A multi-statement Declaration string realizes only its first statement, silently, contrary to ADR 0002 → bug "declared-snapshot realizes every statement of a multi-statement Declaration string" (sqm-01m3q0j698q9).
3. [missing-feature, major] Live objects a consumer owns but never declares have no way through a Plan that doesn't also waive other refusals → "Decide how a Plan leaves consumer-owned live objects alone" (sqm-01m3q0kapxa2).
4. [missing-feature, major] A live-only trigger is dropped without intent; appended triggers on removed tables break declared-snapshot → same ticket as 3.
5. [missing-feature, minor] No row-expression channel (backfill/coerce) → already on the map's Out of scope list. The consumer confirms no pack uses it today.
6. [gate, minor] A Check result reports a capped sample, not a violation count → "Decide whether a Check result reports how many rows violate a Gate" (sqm-01m3q0kb3z87).
7. [api-friction, minor] Snapshots lose provenance under plain pr-str, so archived Snapshots can't be planned → note on "Freeze the public surface".
8. [doc-gap, minor] No doc says Snapshots need *print-meta* → same note as 7.
9. [doc-gap, minor] No guide from a .sql script to a Declaration → the map's documentation fog.
10. [doc-gap, minor] The README install coordinate is 0.1.0 → by design: the README documents the released version (docs/releasing.md step 4).
11. [route, minor] A view over several rebuilt tables is recreated once per Rebuild, which bloats the Plan (119 view pairs for 63 views) → noted as related, not required, on bug 1.
12. [api-friction, minor] A failing Declaration statement is reported at index 0 without its text → bug "declared-snapshot reports a failing statement's Declaration index and text" (sqm-01m3q0j6cb7m).
Not exercised: the Directives API. No entry needed a Directive once the caches were kept → the late consumer gate's fog.

Also recorded as data points: the timings on "Measure Rebuild cost on a large table", the staged-copy isolation on "Decide Apply's transaction mode under concurrent writers", and the chained-views generator gap on "Set the correctness evidence 1.0 claims".

**2026-09-29T16:40:52.334018099Z**

The user assessed 0.2.0-SNAPSHOT on a copy of the consumer's real file. Equivalence and Rebuild data safety held. The view-over-view Rebuild bug blocks the real upgrade. The 12 findings became 3 bugs, 2 grilling tickets, notes on 4 tickets, and map fog and out-of-scope updates.
