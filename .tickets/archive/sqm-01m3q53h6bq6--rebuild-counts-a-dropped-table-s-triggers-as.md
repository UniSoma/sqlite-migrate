---
id: sqm-01m3q53h6bq6
title: Rebuild counts a dropped table's triggers as surviving dependents
status: closed
type: bug
priority: 1
mode: afk
created: '2026-09-29T17:58:51.328249202Z'
updated: '2026-09-29T22:26:07.209842423Z'
closed: '2026-09-29T19:44:46.448826393Z'
acceptance:
- title: 'A regression deftest with the repro below fails before the fix and passes after: apply! succeeds, gone and gone_t are absent, a is Equivalent to its declared shape'
  done: true
- title: A trigger on a table dropped by :drop-table is never emitted as DROP TRIGGER or CREATE TRIGGER by any Rebuild in the same Plan
  done: true
- title: The property-suite generators produce a dropped table whose trigger body mentions a rebuilt table, and the suite is green
  done: true
- title: CHANGELOG records the fix
  done: true
- title: bb test passes; clj-kondo --lint src test ci is clean
  done: true
links:
- sqm-01m3q0kapxa2
- sqm-01m3q0hqvwa6
- sqm-01m3qmcx89yp
---

## Description

Found while resolving "Decide how a Plan leaves consumer-owned live objects alone". A table dropped under a `:drop-table` Directive has no per-trigger Diff entries: its triggers are nested in its whole-value `:removed` entry. `surviving-dependents` (impl/plan.clj, `dropped-triggers`) counts only per-trigger entries as dropped, so it treats the dropped table's triggers as still standing. When one of them mentions a table that a later Rebuild touches, the Rebuild drops and re-creates it after phase 2 has already dropped it with its table. The Plan has no unhandled entries, but Apply fails. The rollback is clean, so no data is at risk.

Reproduced on f255d1e, in memory:

```clojure
;; live
["CREATE TABLE a (x INTEGER NOT NULL, PRIMARY KEY (x))"
 "CREATE TABLE gone (y)"
 "CREATE TRIGGER gone_t AFTER INSERT ON gone BEGIN INSERT INTO a VALUES (new.y); END"]
;; declared
["CREATE TABLE a (x INTEGER, UNIQUE (x))"]
;; directives
[{:directive :drop-table :table "gone"}]
```

Plan: op 0 `DROP TABLE "gone"`; op 1 `:rebuild-table a` whose SQL includes `DROP TRIGGER "gone_t"` and, after the rename, `CREATE TRIGGER gone_t AFTER INSERT ON gone ...`. `apply!` throws `:sqlite-error` at `:op-index 1`, `:statement "DROP TRIGGER \"gone_t\""` (no such trigger: gone_t).

Expected: a dropped table's triggers leave with it and are never Rebuild dependents. ADR 0023 states the rule ("Kept objects leave with the tables they depend on"), and the keep build relies on it, so fix it with or before that work.

## Notes

**2026-09-29T19:44:46.448826393Z**

surviving-dependents now leaves out the triggers of a table removed under a :drop-table Directive: they nest in its whole-value :removed entry and leave with it in phase 2 (ADR 0023). A Rebuild of a table such a trigger mentions no longer drops and re-creates it, and the drop-column legality check no longer counts it, so that column drops in place. Covered by a regression deftest in rebuild_test (the repro), a drop-column deftest in plan_test, and a drop-table mutation in the property generators that plants a trigger naming a rebuilt table; with the fix reverted, the property suite fails with 'no such trigger: tg_gone'. CHANGELOG records it under Fixed.
