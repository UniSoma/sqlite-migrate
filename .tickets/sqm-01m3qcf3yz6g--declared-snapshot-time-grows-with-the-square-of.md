---
id: sqm-01m3qcf3yz6g
title: declared-snapshot time grows with the square of the Declaration's size
status: open
type: bug
priority: 3
mode: afk
created: '2026-09-29T20:07:31.033962666Z'
updated: '2026-09-29T20:07:31.033962666Z'
acceptance:
- title: declared-snapshot on the 800-table wide-ddl Declaration from the bench harness takes under 1 s on the reference host, and its time grows linearly from 200 to 800 tables
  done: false
- title: Every existing guard refusal still fires with the same :statement and :statement-index (CTAS rows, engine-internal tables, no-effect statements)
  done: false
- title: bb test passes; clj-kondo --lint src test ci is clean
  done: false
links:
- sqm-01m3pt46qx4w
---

## Description

Found while resolving "Measure Rebuild cost on a large table". After each Declaration statement, `guard-invisible-effects!` (src/sqlite_migrate/core.clj) runs `SELECT 1 FROM <t> LIMIT 1` for every table in the pristine database, to catch `CREATE TABLE ... AS SELECT`. The cost is O(statements × tables).

Measured on fa862cd (docs/research/rebuild-cost.md on branch research/rebuild-cost, "Introspection and planning"), with a Declaration of 3.2 statements per table:

| Tables | Statements | declared-snapshot |
|---|---|---|
| 50 | 160 | 53 ms |
| 200 | 640 | 568 ms |
| 400 | 1280 | 2130 ms |
| 800 | 2560 | 8801 ms |

At 800 tables one guard pass took 6.7 ms. The per-statement Frame took 0.4 ms and `first-statement` 0.05 ms. `snapshot`, `diff` and `plan` stay under 100 ms at 800 tables, so the guard is the only superlinear stage.

The guard's contract stays as it is: every statement must change the main schema, no engine-internal table other than `sqlite_sequence` may appear, and no table may hold rows afterwards. Only the cost of checking it changes. One option is to check rows only in tables the statement created, if no DDL statement can put rows in a table it did not create. The fixer must confirm that premise first, including virtual-table modules that write their shadow tables.

The harness `docs/research/rebuild-cost-bench/bench.clj` (`wide-run`) reproduces the numbers.
