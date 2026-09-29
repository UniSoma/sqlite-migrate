---
id: sqm-01m3pk739j87
title: Route ADD COLUMN in place only when SQLite accepts it on a populated table
status: open
type: bug
priority: 1
mode: afk
created: '2026-09-29T12:46:13.809974929Z'
updated: '2026-09-29T12:48:06.518060162Z'
acceptance:
- title: The table-driven regression deftest fails before the fix and passes after
  done: false
- title: Every row that plans in place under default capabilities applies to a one-row table, except NOT NULL with no default, which fails its :empty-table Gate
  done: false
- title: gen-added-column generates non-key CURRENT_*, non-literal DEFAULT, and STORED generated shapes, and the property suite is green
  done: false
- title: current-word? is gone and route-added-column no longer uses v-alter-constraint
  done: false
- title: README, the planner comment, and CHANGELOG state the populated-table rule; ADR 0022 is committed
  done: false
- title: bb test passes; clj-kondo --lint src test ci is clean
  done: false
---

## Description

Adding a column to a table that has rows plans an in-place `:add-column` that SQLite rejects at Apply with `:sqlite-error`. Four shapes fail, all reproduced at the REPL on SQLite 3.53.2 against a one-row table:

- `DEFAULT (CURRENT_TIMESTAMP)` at any version: `current-word?` (impl/plan.clj) never strips ADR 0021's wrapping-parentheses Noise.
- `DEFAULT (random())` and any other non-literal DEFAULT, at any version: `route-added-column` never consults `default-kind`.
- Bare `DEFAULT CURRENT_TIMESTAMP` under 3.53+ capabilities, which is the default on the bundled 3.53.2.
- `AS (a+1) STORED` under 3.53+ capabilities. It plans with no Gate and fails with "cannot add a STORED column".

The last two come from the wrong belief that SQLite 3.53 relaxed these ADD COLUMN forms (`v-alter-constraint` in `route-added-column`, the comment above the version constants, README "Planner limits"). In fact, since 3.32.0 SQLite rejects them only when the table has rows, and 3.53 changed nothing about ADD COLUMN.

The fix is decided in ADR 0022 (docs/adr/0022-add-column-routing-follows-the-populated-table-rule.md). Read it before writing code, and don't reopen it.

## Design

Work test-first. Write the regression test and watch it fail before touching `route-added-column`.

**Routing** (`route-added-column`, impl/plan.clj):
- An added column plans in place only when `default-kind` of its DEFAULT is `:null` or `:constant`. `default-kind` already strips the wrapping parentheses.
- An `:opaque` DEFAULT (CURRENT_* bare or parenthesized, any expression) or a STORED generated column collapses the Change set into a Rebuild at every version. With `:rebuild? false` the Change set is left unhandled with `:rebuild-disabled`, through the existing ADR 0006 path.
- A NOT NULL column with no non-NULL default plans in place from 3.32.0 behind the existing `:empty-table` Gate, and rebuilds below that. The Rebuild already carries the same Gate.
- Add a correctly named version constant for 3.32.0. `route-added-column` stops using `v-alter-constraint`, whose remaining uses (SET/DROP NOT NULL, ADD/DROP CHECK) are correctly 3.53.
- Delete `current-word?`.

**Regression test:** one table-driven deftest in `test/sqlite_migrate/plan_test.clj`, named in CONTEXT.md vocabulary. Rows: bare and parenthesized CURRENT_TIMESTAMP, `(random())`, `(1 + 2)`, `(0)`, `-1`, `'x'`, `NOT NULL` with no default, `NOT NULL DEFAULT NULL`, `NOT NULL DEFAULT 0`, `AS (a+1) STORED`, and `AS (a+1) VIRTUAL`. For each row:
- assert the Op kind under capabilities 3.31.0, 3.32.0 and 3.53.0;
- then `m/apply!` the default-capabilities Plan to a live table with one row. It succeeds, except that NOT NULL with no default fails its `:empty-table` Gate.

Also cover `:rebuild? false` leaving an opaque-DEFAULT addition unhandled with `:rebuild-disabled`.

**Generator** (test/sqlite_migrate/generators.clj, `gen-added-column`): add non-key shapes for bare and parenthesized CURRENT_* DEFAULTs, a non-literal DEFAULT such as `(random())` or `(1 + 2)`, and a STORED generated column. The bidirectionality and data-preservation properties then exercise them on populated tables. Opaque DEFAULTs on new key columns stay ADR 0015's documented exclusion. Fix the docstring, which over-reads that exclusion.

**Docs:**
- README "Planner limits" (the "Three relaxed ADD COLUMN forms need SQLite 3.53" bullet): state the real rule.
- Correct the comment above the version constants in impl/plan.clj.
- Add a CHANGELOG `### Fixed` entry under Unreleased, citing ADR 0022.
- ADR 0022 and its amendment note on ADR 0015 are already drafted; commit them with the fix.

## Notes

**2026-09-29T12:48:06.518060162Z**

Generator trap: gen-added-column also runs on STRICT tables (strict-type-keys), and default-for exists to keep constant DEFAULTs type-compatible. The new opaque shapes need the same care. On a STRICT table, CURRENT_* on an INTEGER column, or (random()) on a TEXT column, Rebuilds correctly per ADR 0022 but then fails at the copy with "cannot store ... value in ... column". No Gate covers that, so the bidirectionality property would fail for a reason outside this ticket. Pair each shape with a compatible declared type: (random()) and (1 + 2) with INTEGER or ANY; CURRENT_* and (datetime('now')) with TEXT or ANY. The STORED shape needs a referenced column whose type makes the expression well-typed under STRICT.
