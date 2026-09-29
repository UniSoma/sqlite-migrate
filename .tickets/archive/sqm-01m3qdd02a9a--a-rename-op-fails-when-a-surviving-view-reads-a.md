---
id: sqm-01m3qdd02a9a
title: A Plan drops and recreates every view and trigger that reads a changed view
status: closed
type: bug
priority: 2
mode: afk
created: '2026-09-29T20:23:50.084528713Z'
updated: '2026-09-29T21:13:19.668077329Z'
closed: '2026-09-29T21:13:19.668077329Z'
acceptance:
- title: A regression deftest for each reproduction (Rebuild and in-place :rename-table) fails before the fix and passes after, with v1 and v2 present and Equivalent afterwards
  done: true
- title: bb test passes; clj-kondo --lint src test ci is clean
  done: true
- title: A changed v1 with a surviving v2 over it applies and converges under an authorized :rename-column and under an authorized :drop-column
  done: true
- title: A surviving trigger on another table that reads the changed view, and an INSTEAD OF trigger on a reader, stand after a :rename-table, and Apply converges
  done: true
- title: Two rebuilt tables, the first carrying a trigger that reads the changed view, apply and converge
  done: true
- title: A Plan changing v1 drops v2 in phase 1 and creates it in phase 5, and each of the two Ops serves [:view "v1"]
  done: true
- title: A perturbation that changes v_main's text joins the property suite, and the suite is green
  done: true
- title: README's Views row states the reader rule, and CHANGELOG records the fix
  done: true
- title: A chain v1 ← v2 ← v3 with v1 changed applies and converges under a :rename-table, with v2 and v3 dropped in phase 1 and created again in phase 5
  done: true
links:
- sqm-01m3q0hqvwa6
tags:
- settled
---

## Description

Found while refining sqm-01m3q0hqvwa6 (Rebuild's transitive dependents), which left it out because the cause differs.

A changed view is dropped in phase 1 and created again in phase 5. Its readers stay in place in between: every surviving view that reads it, directly or through other readers, and every surviving trigger that reads it or one of those views. SQLite checks the whole schema again during `ALTER TABLE … RENAME`, `RENAME COLUMN` and `DROP COLUMN`, so any Rebuild, `:rename-table`, `:rename-column` or `:drop-column` in phase 3 fails on the first reader: `error in view v2: no such table: main.v1`. Apply rolls back cleanly, but the Plan cannot apply. `:add-column`, `:set-not-null`, `:drop-not-null`, `:add-check`, `:create-table` and `:drop-table` apply fine. Reproduced on 0ec5e90, SQLite 3.53.

A Rebuild:

```clojure
;; live
["CREATE TABLE t (a INTEGER NOT NULL, PRIMARY KEY (a))"
 "CREATE VIEW v1 AS SELECT a FROM t"
 "CREATE VIEW v2 AS SELECT a FROM v1"]
;; declared — v1 changed, v2 unchanged
["CREATE TABLE t (a INTEGER, UNIQUE (a))"
 "CREATE VIEW v1 AS SELECT a, a AS b FROM t"
 "CREATE VIEW v2 AS SELECT a FROM v1"]
;; plan => [:drop-view :rebuild-table :create-view]
;; apply! => :sqlite-error, :op-index 1,
;;   :statement "ALTER TABLE \"t__sqm_rebuild\" RENAME TO \"t\""
;;   cause: error in view v2: no such table: main.v1
```

An in-place rename done by a Directive:

```clojure
;; live:     t (a INTEGER); v1 over t; v2 over v1
;; declared: t2 (a INTEGER); v1 over t2; v2 over v1
;; directives [{:directive :rename-table :from "t" :to "t2"}]
;; plan => [:drop-view :rename-table :create-view]
;; apply! => error in view v2: no such table: main.v1
```

A Rebuild fails the same way when an earlier Rebuild in phase 3 created again a trigger of its own table that reads the changed view: with `w` and `x` both rebuilt and `w_trg` on `w` reading `v1`, `x`'s Rebuild fails with `error in trigger w_trg: no such table: main.v1`.

Phase 1 drops a changed view's readers together with it, and phase 5 creates them again, so between the two phases no view or trigger reads a view that is missing (ADR 0026). A Rebuild does not create again a trigger of its table that reads a changed view or a reader; phase 5 creates it. Each such drop and create is its own Op on the reader, and it serves the entries of the changed views the reader reaches.

Out of scope: kept readers (ADR 0023's build ticket), and finding 11 (a view reading several rebuilt tables is recreated once per Rebuild).

### Decisions

- ADR 0026 fixes the mechanism: phase 1 drops the readers and phase 5 creates them again; not per-op wrapping, not `PRAGMA legacy_alter_table`, not moving changed-view drops past phase 3.
- Each reader gets its own `:drop-view` / `:create-view` / `:drop-trigger` / `:create-trigger` Op on the reader's path, whose `:serves` holds the changed-view entries the reader reaches. A reader of two changed views gets one pair of Ops (ADR 0026).
- Readers move in every Plan with a changed view, whether or not a phase-3 op makes SQLite check the schema again (ADR 0026).
- The readers are a fixpoint over `lex/mentions?`, seeded only with the `:changed` view folds; a removed view has no declared reader, since `declared-snapshot` refuses a view over a missing view. A false mention only adds a harmless drop and create.
- `rebuild-dependents` in `sqlite-migrate.impl.plan` takes a set of seed folds in place of one `tfold`: the Rebuild passes `#{tfold}`, the reader pass the changed-view folds — one closure, two callers. The reader pass is computed beside `surviving-dependents` in `planning-context-for`; no new namespace. `plan.clj` already fails the locality check at 1992 lines; splitting it is its own refactor, not this slice.
- Readers leave `surviving-dependents`, so a Rebuild never drops them a second time and drop-column legality no longer counts them.
- Readers are created again from their live stored SQL, as Rebuild dependents are.
- The existing phase-1 and phase-5 sort keys order the reader Ops: views by folded name, then triggers. SQLite accepts CREATE VIEW and CREATE TRIGGER over a missing view, so dependency order is not needed.
- `rebuild-recreate-sqls` skips a declared trigger of the table that mentions a changed view or a reader. A phase-5 `:create-trigger` creates it and serves the changed-view entries, plus the trigger's own entry when it has one; the Rebuild keeps that entry in its `:serves` too, since completeness takes the union.
- Regression deftests go in `test/sqlite_migrate/plan_test.clj`, one per acceptance criterion, each asserting `converges?`.
- A new perturbation in `test/sqlite_migrate/generators.clj` appends a constant column to `v_main`'s text; the forward arm in `properties_test.clj` accepts a `[:view "v_main"]` entry for that kind.
- README's Views row states the reader rule and its Rebuild paragraph the deferred trigger; the `sqlite-migrate.impl.plan` ns docstring's phase order names the readers; the CHANGELOG entry goes under `[Unreleased]` `### Fixed`.

## Notes

**2026-09-29T21:13:19.668077329Z**

Phase 1 now drops the readers of each changed view: every surviving view that reads it directly or through another reader, with its triggers, and every surviving trigger that reads it or a reader. Phase 5 creates them again from their live stored SQL. rebuild-dependents takes a set of seed folds, so the Rebuild (#{tfold}) and the reader pass (the changed views) share one closure. Readers leave surviving-dependents. Each reader Op serves the changed-view entries it reaches. A Rebuild leaves out each declared trigger of its table that reads a missing view and creates it in phase 5; readers are excluded, so no trigger is created twice. Two additions beyond the Decisions, found in review: (1) triggers of a table that a matched :rename-table renames are not readers, because the fused plan already drops them and creates their declared text; without this, both in-place and Rebuild renames regressed from HEAD. (2) A Rebuild also drops in phase 1 each changed or removed live trigger of its table that reads a missing view; otherwise the trigger stands until that Rebuild, and an earlier Rebuild's rename fails on it (this failed at HEAD too). ADR 0026, README, CHANGELOG and CONTEXT.md (new Reader entry) record the rule. There is a regression deftest for each AC plus the two additions, and each one fails on HEAD's planner or guards a Plan that applied at HEAD. A new :change-view perturbation, which sometimes rebuilds the table v_main reads, joins the property suite. The 400-trial run found an unrelated harness bug, filed as sqm-01m3qg71se8p.
