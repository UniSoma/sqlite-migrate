---
id: sqm-01m3pt460acv
title: Freeze the public surface
status: open
type: feature
priority: 2
mode: hitl
created: '2026-09-29T14:46:58.314457843Z'
updated: '2026-09-29T20:17:59.370749249Z'
parent: sqm-01m3pt2fgxp9
tags:
- wayfinder:grilling
deps:
- sqm-01m3q0kapxa2
- sqm-01m3qd29cm2s
links:
- sqm-01m3q0kb3z87
---

## Question

What is the complete 1.0 public surface, and which of today's loose ends become contract (ADR), become private, or go? Consult codebase-design.

Found while charting:

- `sqlite-migrate.jdbc` leaks `->JdbcExecutor` and the class `sqlite_migrate.jdbc.JdbcExecutor` (from `deftype`); no ADR grants them.
- `directives/against` has a `[diff initial]` arity seeding the set with existing Directives; ADR 0020 doesn't grant it.
- Six `core` docstrings (`diff`, `drift-report`, `by-object`, `plan`, `plan-report`, `check-report`) defer "the full contract" to `sqlite-migrate.impl.*`, which is `^:no-doc` — the contract never reaches cljdoc.
- Value shapes no ADR pins: the Plan's `:capabilities` (`:sqlite-version`, `:rebuild?`), the Apply report `{:live-provenance :declared-provenance :ops :schema-version :check}`, Snapshot metadata (provenance, stored CREATE sql).

Also decide: which value shapes (Snapshot, Diff, Plan, Op, Gate, Check result, Apply report) are contract down to every key, and whether any gets a spec/schema.

## Notes

**2026-09-29T16:40:20.445804998Z**

From the first-consumer assessment (Assess the first consumer against today's SNAPSHOT):
- Snapshot provenance rides as metadata, so a Snapshot archived with plain `pr-str` loses it. `plan` over restored Snapshots plus the restored Diff then throws `:malformed-input` ("the live Snapshot is not the one this Diff was computed from"). It works under `*print-meta*` true, but no doc says so. The README promises the round trip only for the Diff. Decide whether Snapshots are archivable values with provenance in the data, or whether `*print-meta*` is documented as the contract (findings 7 and 8).
- A Declaration string: ADR 0002 already accepts multi-statement text, and the bug "declared-snapshot realizes every statement of a multi-statement Declaration string" restores that. What's left for the surface is whether the docstring and README say outright that a `.sql` script is a valid Declaration (finding 9 is the doc gap).
- The consumer would feed its migration audit trail from the Apply report's `:ops` and each Op's `:sql`. That's evidence for pinning the Apply report shape.
- Blocked by "Decide how a Plan leaves consumer-owned live objects alone", which may add a `plan` opt and change what a live-only trigger drop requires.

**2026-09-29T17:59:08.132806339Z**

From "Decide how a Plan leaves consumer-owned live objects alone" (ADRs 0023, 0024): the surface to freeze gains four Directive kinds (:keep-table, :keep-view, :keep-index, :keep-trigger with :table or :view), four Directive set steps (keep-tables, keep-views, keep-indexes, keep-triggers), the Plan's :kept slot, and Diff paths [:view v :trigger x] (the view :triggers fact is retired).
