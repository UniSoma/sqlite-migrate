---
id: sqm-01m3q0hqvwa6
title: Rebuild drops and recreates every view and trigger that reads the table through a chain of views
status: closed
type: bug
priority: 1
mode: afk
created: '2026-09-29T16:39:14.044516517Z'
updated: '2026-09-29T20:36:58.033105958Z'
closed: '2026-09-29T20:36:58.033105958Z'
acceptance:
- title: A regression deftest reproducing the view-over-view Rebuild fails before the fix and passes after, with v1 and v2 present and Equivalent afterwards
  done: true
- title: The property-suite schema generators produce chained views (a view over a view over a rebuilt table), and the suite is green
  done: true
- title: README states the transitive dependent rule, and CHANGELOG records the fix
  done: true
- title: bb test passes; clj-kondo --lint src test ci is clean
  done: true
- title: A trigger on another table whose body reads a dependent view is dropped and recreated, and Apply succeeds
  done: true
- title: An INSTEAD OF trigger on a view two views away from the rebuilt table stands afterwards
  done: true
links:
- sqm-01m3pt45mm8c
- sqm-01m3q0j698q9
- sqm-01m3q0j6cb7m
- sqm-01m3q53h6bq6
- sqm-01m3qdd02a9a
deps:
- sqm-01m3q53h6bq6
tags:
- settled
---

## Description

Found by the first-consumer assessment (Assess the first consumer against today's SNAPSHOT, finding 1): on the consumer's real file, 19 of 80 views sit two or more views away from a rebuilt table, and the real upgrade cannot apply.

A Rebuild drops and recreates only the surviving views and triggers whose text names the rebuilt table. When the Rebuild renames `<t>__sqm_rebuild` to `<t>`, SQLite reparses the schema and fails on any surviving view or trigger that reads a view the Rebuild has just dropped. Apply rolls back cleanly, so no data is at risk, but the Plan cannot apply. Reproduced on cfb225b and again on 93922ac, SQLite 3.53:

```clojure
;; live, seeded with INSERT INTO t VALUES (1),(2)
["CREATE TABLE t (a INTEGER NOT NULL, PRIMARY KEY (a))"
 "CREATE VIEW v1 AS SELECT a FROM t"
 "CREATE VIEW v2 AS SELECT a FROM v1"]
;; declared
["CREATE TABLE t (a INTEGER, UNIQUE (a))"
 "CREATE VIEW v1 AS SELECT a FROM t"
 "CREATE VIEW v2 AS SELECT a FROM v1"]
;; plan => one :rebuild-table op, whose SQL drops only v1
;; apply! => :sqlite-error, :op-index 0,
;;   :statement "ALTER TABLE \"t__sqm_rebuild\" RENAME TO \"t\""
;;   cause: error in view v2: no such table: main.v1
```

A trigger fails the same way: with `CREATE TABLE w (n)` and `CREATE TRIGGER w_trg AFTER INSERT ON w BEGIN SELECT a FROM v1; END` on both sides in place of v2, the rename fails with `error in trigger w_trg: no such table: main.v1`.

The Rebuild's dependents are every surviving view that reads the table directly or through another dependent view, plus every surviving trigger that reads the table or one of those views; a dependent view's own triggers travel with it. The Rebuild drops them all before the old table and recreates them after the rename. The README's promise ("the Rebuild drops each view and each trigger that refers to the table, and then creates them again") states this rule.

Out of scope: a surviving view that reads a view phase 1 dropped because it changed still breaks any rename op — a Rebuild or an in-place `:rename-table` alike. That cause is the phase-1 drops, not the Rebuild's dependents, and belongs to its own ticket. Finding 11 (a view reading several rebuilt tables is recreated once per Rebuild) stays as it is.

### Decisions

- Triggers that read a dependent view are dependents too, not only triggers on the dependent views.
- The closure lives inside `rebuild-dependents` in `sqlite-migrate.impl.plan`: a fixpoint over `lex/mentions?`, seeded with the table's fold and grown by each dependent view's fold. No new seam.
- Only survivors count: the input stays `surviving-dependents`, so objects dropped or changed in phases 1–2 are never recreated twice (ADR 0023).
- A surviving trigger of a table other than the rebuilt one, or a trigger on a view outside the closure, joins when it mentions the table or any closure view.
- Dependent views recreate in dependency order — a view after the views it reads — with ties by folded name; a cycle from a conservative false mention falls back to name order for its members. The order is for the Plan's reader: SQLite accepts a CREATE VIEW over a missing view. Drops run in the reverse order.
- Each Rebuild op keeps its own dependents (ADR 0006: one self-contained op per rebuilt table); finding 11 is not addressed.
- Regression deftests go in `test/sqlite_migrate/rebuild_test.clj`, one per acceptance criterion, each asserting apply with `converges?`.
- `gen-schema` gains a second flag adding `"v_chain"`, which selects `v_main`'s column from `"v_main"`, only when `v_main` exists.
- The README sentence changes to state the rule; the CHANGELOG entry goes under `[Unreleased]` `### Fixed`.

## Notes

**2026-09-29T20:36:58.033105958Z**

A Rebuild's dependents are now every surviving view that reads the table directly or through other dependent views (a fixpoint over lex/mentions? in rebuild-dependents), each with its own triggers, plus every surviving trigger of another parent that mentions the table or a dependent view. Views recreate in dependency order (ties and mention cycles by folded name) and drop in reverse. Regression deftests in rebuild_test.clj cover view-over-view, another table's trigger reading a dependent view, an INSTEAD OF trigger on a chained view, and a three-view mention cycle; gen-schema adds v_chain over v_main, which reproduces the bug without the fix. README and CHANGELOG state the rule. Planning 20 Rebuilds over 80 chained views takes ~0.1 s.
