---
id: sqm-01m3pt460acv
title: Freeze the public surface
status: open
type: feature
priority: 2
mode: hitl
created: '2026-09-29T14:46:58.314457843Z'
updated: '2026-09-29T14:46:58.402062698Z'
parent: sqm-01m3pt2fgxp9
tags:
- wayfinder:grilling
---

## Question

What is the complete 1.0 public surface, and which of today's loose ends become contract (ADR), become private, or go? Consult codebase-design.

Found while charting:

- `sqlite-migrate.jdbc` leaks `->JdbcExecutor` and the class `sqlite_migrate.jdbc.JdbcExecutor` (from `deftype`); no ADR grants them.
- `directives/against` has a `[diff initial]` arity seeding the set with existing Directives; ADR 0020 doesn't grant it.
- Six `core` docstrings (`diff`, `drift-report`, `by-object`, `plan`, `plan-report`, `check-report`) defer "the full contract" to `sqlite-migrate.impl.*`, which is `^:no-doc` — the contract never reaches cljdoc.
- Value shapes no ADR pins: the Plan's `:capabilities` (`:sqlite-version`, `:rebuild?`), the Apply report `{:live-provenance :declared-provenance :ops :schema-version :check}`, Snapshot metadata (provenance, stored CREATE sql).

Also decide: which value shapes (Snapshot, Diff, Plan, Op, Gate, Check result, Apply report) are contract down to every key, and whether any gets a spec/schema.