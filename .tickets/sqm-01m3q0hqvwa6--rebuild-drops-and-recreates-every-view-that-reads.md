---
id: sqm-01m3q0hqvwa6
title: Rebuild drops and recreates every view that reads the table transitively
status: open
type: bug
priority: 1
mode: afk
created: '2026-09-29T16:39:14.044516517Z'
updated: '2026-09-29T19:22:19.437910821Z'
acceptance:
- title: A regression deftest reproducing the view-over-view Rebuild fails before the fix and passes after, with v1 and v2 present and Equivalent afterwards
  done: false
- title: A trigger on a transitively dependent view is also dropped and recreated
  done: false
- title: The property-suite schema generators produce chained views (a view over a view over a rebuilt table), and the suite is green
  done: false
- title: README states the transitive dependent rule, and CHANGELOG records the fix
  done: false
- title: bb test passes; clj-kondo --lint src test ci is clean
  done: false
links:
- sqm-01m3pt45mm8c
- sqm-01m3q0j698q9
- sqm-01m3q0j6cb7m
- sqm-01m3q53h6bq6
deps:
- sqm-01m3q53h6bq6
---

## Description

Found by the first-consumer assessment (Assess the first consumer against today's SNAPSHOT, finding 1): on the consumer's real file, 19 of 80 views sit two or more views away from a rebuilt table, and the real upgrade cannot apply.

A Rebuild drops and recreates only the views that name the rebuilt table directly. A view that reads the table through another view stays in place. When the Rebuild renames `<t>__sqm_rebuild` to `<t>`, SQLite re-checks the schema and fails on that view, whose inner view has just been dropped. Apply rolls back cleanly, so no data is at risk, but the Plan cannot apply. Reproduced on cfb225b, SQLite 3.53:

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

The dependent set a Rebuild drops has to be the transitive closure over views that read the table, plus triggers on those views. They are recreated in dependency order. The README's promise ("the Rebuild drops each view and each trigger that refers to the table, and then creates them again") should state the transitive rule.

Related, not required: finding 11. Today each Rebuild drops and recreates its own dependents, so a view that reads several rebuilt tables is recreated once per Rebuild. The consumer's Plan has 119 DROP VIEW/CREATE VIEW pairs for 63 distinct views. Where this fix puts the dependent drops may settle that too. If it doesn't, leave the behaviour as it is.
