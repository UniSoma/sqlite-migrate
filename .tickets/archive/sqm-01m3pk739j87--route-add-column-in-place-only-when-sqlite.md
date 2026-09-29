---
id: sqm-01m3pk739j87
title: Route ADD COLUMN in place only when SQLite accepts it on a populated table
status: closed
type: bug
priority: 1
mode: afk
created: '2026-09-29T12:46:13.809974929Z'
updated: '2026-09-29T13:26:32.323271810Z'
closed: '2026-09-29T13:26:32.323271810Z'
acceptance:
- title: The table-driven regression deftest fails before the fix and passes after
  done: true
- title: gen-added-column generates non-key CURRENT_*, non-literal DEFAULT, and STORED generated shapes, and the property suite is green
  done: true
- title: current-word? is gone and route-added-column no longer uses v-alter-constraint
  done: true
- title: bb test passes; clj-kondo --lint src test ci is clean
  done: true
- title: Every row's default-capabilities Plan, in place or Rebuild, applies to a one-row table, except NOT NULL with no non-NULL default, where Apply throws :gate-failed on the :empty-table Gate
  done: true
- title: README, the comment above the planner's version constants, and CHANGELOG state the populated-table rule
  done: true
tags:
- settled
---

## Description

Adding a column to a table that has rows plans an in-place `:add-column` that SQLite rejects at Apply with `:sqlite-error`. Reproduced on SQLite 3.53.2 against a one-row table:

- `DEFAULT (CURRENT_TIMESTAMP)`, at any target version: the CURRENT_* test skips ADR 0021's Noise stripping.
- `DEFAULT (random())` and any other non-literal DEFAULT, at any target version: routing never consults the DEFAULT classification.
- Bare `DEFAULT CURRENT_TIMESTAMP` and `AS (a+1) STORED` under 3.53+ capabilities, the default on the bundled SQLite. The STORED column plans with no Gate.
- `NOT NULL DEFAULT NULL` below 3.32. It plans in place behind an `:empty-table` Gate, but SQLite before 3.32 rejects that form even on an empty table.

The planner believed SQLite 3.53 relaxed these ADD COLUMN forms. In fact, since 3.32.0 SQLite rejects them only when the table has rows, and 3.53 changed nothing about ADD COLUMN.

After the fix, an added column routes by ADR 0022's populated-table rule. A NULL or constant DEFAULT plans in place. An opaque DEFAULT or a STORED generated column collapses its Change set into a Rebuild at every version, and stays unhandled with `:rebuild-disabled` when `:rebuild?` is off. A NOT NULL column with no non-NULL default plans in place from 3.32.0 behind the `:empty-table` Gate, and rebuilds below that behind the same Gate. The generative suite exercises the opaque and STORED shapes on populated tables, and the README states the real rule.

ADR 0022 (docs/adr/0022-add-column-routing-follows-the-populated-table-rule.md) decides the fix. Read it before writing code, and don't reopen it.

### Decisions

- An added column plans in place only when `default-kind` (impl/plan.clj) classifies its DEFAULT as `:null` or `:constant`; `default-kind` already strips the wrapping-parentheses Noise.
- An opaque DEFAULT or a STORED generated column makes `route-added-column` return `{:rebuild []}` at every version. `:rebuild? false` goes through the existing ADR 0006 `:rebuild-disabled` path.
- NOT NULL with no non-NULL default gates on a new version constant for 3.32.0, named for what SQLite added then (e.g. `v-add-column-empty-check`). `route-added-column` stops using `v-alter-constraint`, whose remaining uses (SET/DROP NOT NULL, ADD/DROP CHECK) stay at 3.53. Fix the comment above the version constants.
- `current-word?` is deleted.
- `route-added-column`'s blocking check becomes a plain boolean: the unused reason strings go, and its `{:rebuild []}` / `{:ops ...}` contract stays.
- One table-driven deftest in `test/sqlite_migrate/plan_test.clj`, named in CONTEXT.md vocabulary. Rows: bare and parenthesized CURRENT_TIMESTAMP, `(random())`, `(1 + 2)`, `(0)`, `-1`, `'x'`, `NOT NULL` with no default, `NOT NULL DEFAULT NULL`, `NOT NULL DEFAULT 0`, `NOT NULL DEFAULT (random())`, `AS (a+1) STORED`, `AS (a+1) VIRTUAL`. Each row asserts the Op kind under capabilities 3.31.0, 3.32.0 and 3.53.0, then applies the default-capabilities Plan to a live one-row table. A separate case covers `:rebuild? false` leaving an opaque-DEFAULT addition unhandled with `:rebuild-disabled`.
- The regression deftest uses only the public API (`m/plan`, `m/check`, `m/apply!`) and never calls `default-kind`, so sqm-01m3pk7mca32 can move the DEFAULT classification without touching it.
- `gen-added-column` (test/sqlite_migrate/generators.clj) gains non-key shapes: bare and parenthesized CURRENT_* DEFAULTs, a non-literal DEFAULT such as `(random())` or `(1 + 2)`, and a STORED generated column. Opaque DEFAULTs on new key columns stay ADR 0015's exclusion; fix the docstring, which over-reads it.
- Each opaque shape carries a declared type that STRICT accepts for its value: `(random())` and `(1 + 2)` with INTEGER, INT or ANY; CURRENT_* with TEXT or ANY.
- The STORED shape is spelled through the Schema value's verbatim string `:type`, because a Schema value has no `:generated` key: `{:type "INTEGER GENERATED ALWAYS AS (<quoted existing column> IS NOT NULL) STORED"}`. `IS NOT NULL` yields 0 or 1, so the column is well-typed on STRICT tables. A short comment in `gen-added-column` says why the type string carries the clause. The Schema value is not extended.
- The opaque and STORED shapes force at least one live row in `table-inserts`, as `:not-null-no-default` already controls its rows; a rowless trial cannot pin the populated-table rule.
- README "Planner limits": replace the "Three relaxed ADD COLUMN forms need SQLite 3.53" bullet with the 3.32 floor for a NOT NULL column with no default, plus a line that opaque DEFAULTs and STORED columns always rebuild. CHANGELOG gets a `### Fixed` entry under Unreleased citing ADR 0022.

## Notes

**2026-09-29T12:48:06.518060162Z**

Generator trap: gen-added-column also runs on STRICT tables (strict-type-keys), and default-for exists to keep constant DEFAULTs type-compatible. The new opaque shapes need the same care. On a STRICT table, CURRENT_* on an INTEGER column, or (random()) on a TEXT column, Rebuilds correctly per ADR 0022 but then fails at the copy with "cannot store ... value in ... column". No Gate covers that, so the bidirectionality property would fail for a reason outside this ticket. Pair each shape with a compatible declared type: (random()) and (1 + 2) with INTEGER or ANY; CURRENT_* and (datetime('now')) with TEXT or ANY. The STORED shape needs a referenced column whose type makes the expression well-typed under STRICT.

**2026-09-29T13:26:32.323271810Z**

An added column now routes by ADR 0022's populated-table rule. route-added-column plans :add-column in place only for a :null or :constant DEFAULT (default-kind, Noise stripped). An opaque DEFAULT or a STORED generated column rebuilds at every version, or stays unhandled with :rebuild-disabled when :rebuild? is off. NOT NULL with no non-NULL default goes in place from 3.32.0 (new v-add-column-empty-check) and rebuilds below, behind the :empty-table Gate both ways. current-word? is deleted; v-alter-constraint is left to SET/DROP NOT NULL and CHECK. New table-driven deftest added-column-routes-by-the-populated-table-rule (13 rows × 3.31/3.32/3.53, then apply! to a one-row table) plus a :rebuild-disabled case. gen-added-column gains :current-default, :expression-default and :stored-generated shapes with STRICT-safe types, each forced to one live row; the property suite fails on them against the old planner. README, the version-constant comment and CHANGELOG state the rule.
