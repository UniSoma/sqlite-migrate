---
id: sqm-01kzcv5g5wcd
title: 'Walking skeleton: bootstrap an empty database from a Declaration'
status: closed
type: task
priority: 2
mode: afk
created: '2026-08-07T00:49:58.460015152Z'
updated: '2026-08-07T01:42:47.624676472Z'
closed: '2026-08-07T01:42:47.624676472Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: 'Scaffolding: deps.edn with next.jdbc + sqlite-jdbc, runnable test suite'
  done: false
- title: SQLiteExecutor protocol + JDBC adapter constructors with the unconditional Frame
  done: false
- title: snapshot / declared-snapshot (empty-db guard) / diff / plan / apply! / drift? wired end-to-end for plain added tables
  done: false
- title: 'Demo test: empty file + Declaration converges; fingerprint mismatch refuses; all on real in-memory SQLite'
  done: false
tags:
- phase-1
---

## Description

The tracer bullet: the whole pipeline end-to-end at minimal width, plus project scaffolding. As a developer I can point the library at an empty SQLite file and a Declaration, apply, and see `drift?` go false — everything running through the real public surfaces against real in-memory SQLite.

Scope: deps.edn (next.jdbc, sqlite-jdbc) and a test harness; `sqlite-migrate.protocols` with the two-op `SQLiteExecutor` whose docstrings are the normative adapter spec; `sqlite-migrate.jdbc` with `connect`/`in-memory` constructors returning Closeable, protocol-satisfying conns and the unconditional Frame (FK off outside txn, BEGIN, statements in order, foreign_key_check, COMMIT, restore in finally); minimal `snapshot` (plain tables/columns via pragmas, string identifiers, ordered columns, schema_version + version metadata); `declared-snapshot` realizing the pristine database with the non-empty-database guard; `diff` at added/removed-table granularity; `plan` emitting `:create-table` ops with plan-time `:sql` and `:serves`; `apply!` executing via the Frame, refusing on fingerprint mismatch, returning a minimal Apply report; `drift?`.

Every later slice widens a layer this ticket has already connected. ADRs 0001, 0006, 0011, 0013, 0014 (namespace names).

## Notes

**2026-08-07T01:42:47.624676472Z**

Shipped in 928366f. deps.edn (next.jdbc + sqlite-jdbc, cognitect test-runner, nREPL alias for bb.edn); sqlite-migrate.protocols two-op SQLiteExecutor with normative docstrings; sqlite-migrate.jdbc connect/in-memory Closeable conns + unconditional Frame (FK off outside txn, BEGIN, indexed statements, foreign_key_check, COMMIT, FK restore in finally); core snapshot/declared-snapshot (non-empty guard)/diff/plan/apply!/drift? end-to-end for added tables on real in-memory SQLite. Demo tests: empty file + Declaration converges, fingerprint mismatch refuses (:drift-refused), mid-apply :sqlite-error carries op/op-index/statement per ADR 0012. Suite green.
