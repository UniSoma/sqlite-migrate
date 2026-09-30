---
id: sqm-01m3svq9hfvf
title: snapshot throws :sqlite-error naming a view SQLite cannot resolve
status: closed
type: bug
priority: 2
mode: afk
created: '2026-09-30T19:12:36.393445483Z'
updated: '2026-09-30T20:30:50.681798241Z'
closed: '2026-09-30T20:30:50.681798241Z'
links:
- sqm-01m3q0j6cb7m
tags:
- settled
acceptance:
- title: snapshot of a live database whose view reads a missing column, and of one whose view reads a dropped table, throws :sqlite-error with :view "v" and the SQLiteException as the cause
  done: true
- title: declared-snapshot of a Declaration holding an unresolvable view throws the same error
  done: true
- title: A Declaration that creates a view before the table it reads still realizes
  done: true
- title: (p/execute-query conn "SELECT * FROM nope" []) on the JDBC Executor throws :sqlite-error with the SQLiteException as the cause
  done: true
- title: CHANGELOG.md carries a Fixed entry for these errors
  done: true
- title: bb test passes; clj-kondo --lint src test ci is clean
  done: true
---

## Description

Found while refining sqm-01m3q0j6cb7m. SQLite accepts a view it cannot resolve: one that reads a missing table, column, function or view, or one left dangling by `DROP TABLE`. `snapshot` reads each view's output columns through `pragma_table_xinfo`, SQLite fails there, and the raw `SQLiteException` escapes with no `:sqlite-migrate/error` key. Reproduced on ee75dc7:

```clojure
(p/execute-batch! conn ["CREATE TABLE a (x)" "CREATE VIEW v AS SELECT nope FROM a"])
(m/snapshot conn)
;; org.sqlite.SQLiteException "[SQLITE_ERROR] SQL error or missing database (no such column: nope)", ex-data nil
```

`declared-snapshot` fails the same way, in its final `snapshot` after every statement has run. Under ADR 0012 an introspection failure is `:sqlite-error`. After the fix, `snapshot` throws `:sqlite-error` naming the view, with SQLite's exception as the cause, and `declared-snapshot` inherits that.

A view is resolved only when something reads it, so a Declaration may create a view before the table it reads. That stays valid, which is why the error names the view and not a Declaration statement.

The JDBC Executor's `execute-query` wraps a driver failure in `:sqlite-error` with the driver exception as the cause, as the `SQLiteExecutor` contract requires.

### Decisions

- An unresolvable view is refused, never recorded: `snapshot` throws `:sqlite-error` (ADR 0012, and the no-silent-skips rule ADR 0001 cites). The Snapshot shape does not change; view `:columns` stays. A live database with a dangling view needs a hand `DROP VIEW` before the library can plan against it; the error names the view so that fix is one line.
- The fix lives in `snapshot`'s view loop in `sqlite-migrate.core`. It catches any `Exception` from that view's column read and throws `:sqlite-error` with `:view <name>`, the cause being `(or (ex-cause e) e)`, so an outside adapter that throws the bare driver exception still yields the right cause.
- When several views are broken, `snapshot` reports the first in name order, the order it already reads them. The ex-message is one line naming the view; SQLite's reason stays on the cause.
- `declared-snapshot` gets no branch of its own. The error comes from its final `snapshot` call and carries no `:statement-index`: checking each view at its CREATE VIEW would reject Declarations that create a view before the table it reads.
- The `snapshot` docstring gains the failure clause.
- In `sqlite-migrate.jdbc`, `execute-query` wraps a driver `SQLException` in `:sqlite-error` with the exception as the cause, following `probe` in the same file. `execute-batch!`'s step-4 gate failure then also arrives as ex-info, with no `:statement-index`, as the protocol docstring allows.
- The `SQLiteExecutor` protocol docstring stays as it is: requiring `:sqlite-error` there would force outside adapters to change.
- `CHANGELOG.md` gets one `### Fixed` entry under `[Unreleased]` covering the view error and the adapter wrap.
- Tests go in `snapshot_fidelity_test.clj`, plus `jdbc_frame_test.clj` for the adapter wrap.

## Notes

**2026-09-30T20:30:50.681798241Z**

snapshot throws :sqlite-error with :view and SQLite's exception as the cause when a view cannot be resolved (missing column, dropped table); declared-snapshot inherits it from its final snapshot, and a view created before its table still realizes. The JDBC adapter's query path wraps driver failures in :sqlite-error, covering execute-query, the Frame's gate queries, and its PRAGMA reads. CHANGELOG Fixed entry added.
