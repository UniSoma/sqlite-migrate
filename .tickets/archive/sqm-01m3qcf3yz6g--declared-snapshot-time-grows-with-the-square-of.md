---
id: sqm-01m3qcf3yz6g
title: declared-snapshot probes every table for rows after each Declaration statement
status: closed
type: bug
priority: 3
mode: afk
created: '2026-09-29T20:07:31.033962666Z'
updated: '2026-09-30T22:16:08.272163656Z'
closed: '2026-09-30T22:16:08.272163656Z'
acceptance:
- title: Every existing guard refusal still fires with the same :statement and :statement-index (CTAS rows, engine-internal tables, no-effect statements)
  done: true
- title: bb test passes; clj-kondo --lint src test ci is clean
  done: true
- title: Between two consecutive realized statements, declared-snapshot runs the same number of queries for a 10-table Declaration as for a 40-table one
  done: true
- title: A CREATE TABLE ... AS SELECT that follows a DROP TABLE is still refused with its :statement and :statement-index
  done: true
links:
- sqm-01m3pt46qx4w
tags:
- settled
---

## Description

After each Declaration statement, `declared-snapshot` lists every table in the pristine database and reads one row from each, to catch `CREATE TABLE ... AS SELECT` and engine-internal tables. Each statement therefore costs time in proportion to the number of tables, and the whole run grows with statements × tables. The guard's contract stays as it is: every statement must change the main schema, no engine-internal table other than `sqlite_sequence` may appear, and no table may hold rows afterwards. Only the cost of checking it changes: the guard examines only the tables the statement created, and its per-statement cost no longer depends on how many tables exist.

On the 800-table wide Declaration (2560 statements), a prototype took `declared-snapshot` from 8.5 s to about 1.1 s. The remainder still grows quadratically because each statement's Frame runs a whole-database foreign-key check. That cost belongs to the Frame's contract and is out of this ticket's scope.

### Decisions

- The guard checks only tables the statement created. Premise: the Declaration starts pristine and the guard keeps every table empty after each statement, so a statement that changes the schema can put rows only in a table it creates. Confirmed for CTAS, ANALYZE, AUTOINCREMENT, rename, DROP then CTAS, and the fts4, fts5 and rtree modules, which write only into shadow tables they create. A custom virtual-table module registered on the caller's connection is outside the library's reach, as it is today.
- New tables are found through a rowid window on `sqlite_schema` (`rowid > <largest rowid before the statement> AND type = 'table'`), not by listing all tables: `pragma_table_list` and a `sqlite_schema` scan both cost time in proportion to the table count. The window relies on SQLite's documented rowid allocation (a new row gets one more than the largest existing rowid, sqlite.org/autoinc.html) and on `sqlite_schema` being an ordinary rowid table.
- `declared-snapshot` reads the largest `sqlite_schema` rowid before each statement and passes it to the guard, as it passes `before-fingerprint` today; the guard keeps no state between statements. A DROP that frees the top rowid stays covered: the next CREATE lands above the recorded value.
- Each new name from the window goes through a `pragma_table_list` lookup for that name, and only type `table` is checked, so shadow and virtual tables stay skipped as today.
- The engine-internal-table check also runs only on new tables. The ANALYZE refusal's `:table` then names `sqlite_stat1` where it names `sqlite_stat4` today.
- The query-count criterion is tested with a delegating `reify p/SQLiteExecutor` that counts queries between `execute-batch!` calls, following `drifting-executor` in `gates_test.clj`, in `snapshot_fidelity_test.clj` next to the guard's existing tests.
- The `guard-invisible-effects!` docstring states the premise in one sentence, so the narrowed check does not read as a gap.
- The change stays inside `sqlite-migrate.core`; the guard stays private.

## Notes

**2026-09-30T22:16:08.272163656Z**

declared-snapshot records the largest sqlite_schema rowid before each Declaration statement, and the guard checks only the tables above it (through a per-name pragma_table_list lookup, so shadow and virtual tables stay skipped). Queries per statement no longer depend on the table count (5 per statement for 10 and 40 tables). Every refusal keeps its :statement and :statement-index, DROP then CTAS is still refused, and the ANALYZE refusal names sqlite_stat1 under :table.
