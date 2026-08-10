---
id: sqm-01kzpgydv42q
title: probe-insert-sql emits invalid SQL for an AUTOINCREMENT-only table
status: closed
type: bug
priority: 2
mode: afk
created: '2026-08-10T19:03:45.252154390Z'
updated: '2026-08-10T19:33:22.521817017Z'
closed: '2026-08-10T19:33:22.521817017Z'
parent: sqm-01kzctnhwmjm
tags:
- phase-2
acceptance:
- title: probe-insert-sql emits DEFAULT VALUES when the surviving column list is empty
  done: false
- title: The data-preservation property no longer errors on an AUTOINCREMENT-only target table
  done: false
- title: Full suite green and clj-kondo clean
  done: false
---

## Description

`probe-insert-sql` in test/sqlite_migrate/generators.clj (~line 562) strips AUTOINCREMENT columns and then emits an unconditional column list, so a table whose only column *is* the AUTOINCREMENT primary key yields invalid SQL:

```clojure
(g/probe-insert-sql {:name "a" :columns [{:name "idpk" :type :integer
                                          :primary-key? true :autoincrement? true}]} 1000)
;=> "INSERT INTO \"a\" () VALUES ()"
```

This surfaces as an intermittent `ERROR in (data-preservation-property)` — `[SQLITE_ERROR] ... near ")"` — firing only when the generator happens to shrink onto that shape, which is why most runs are green. It is a bug in the test generator, not in the planner; the property itself is sound.

Fix: emit `INSERT INTO "a" DEFAULT VALUES` when the surviving column list is empty.

Found while running the suite for the phase-2 plan cleanups (59faff1).

## Notes

**2026-08-10T19:33:22.521817017Z**

Shipped in 29ab88d. probe-insert-sql now emits INSERT INTO "t" DEFAULT VALUES when stripping AUTOINCREMENT columns leaves an empty surviving column list; the ticket's reproduction case verified in the REPL returns the DEFAULT VALUES form. New test/sqlite_migrate/generators_test.clj pins both the empty-column-list case and the ordinary case, each executing the emitted SQL against real in-memory SQLite rather than asserting on the string alone. Full suite green (154 tests / 795 assertions), clj-kondo clean, and sqlite-migrate.properties-test run four extra times separately (once at 200 trials) to catch the intermittency.
