---
id: sqm-01m3pt45egw5
title: 'Research: can babashka.sqlite or the sqlite3 CLI honour the Executor''s Frame?'
status: closed
type: task
priority: 1
mode: afk
created: '2026-09-29T14:46:57.744521220Z'
updated: '2026-09-29T15:16:11.436505747Z'
closed: '2026-09-29T15:16:11.436505747Z'
parent: sqm-01m3pt2fgxp9
tags:
- wayfinder:research
assignee: claude
---

## Question

For a babashka `SQLiteExecutor` adapter, evaluate two routes — (1) babashka/babashka.sqlite (experimental, https://github.com/babashka/babashka.sqlite) and (2) driving the `sqlite3` CLI as a subprocess — against the Executor contract in the `sqlite-migrate.protocols` docstrings (ADR 0013 as amended by 0016):

- The Frame: `PRAGMA foreign_keys=OFF` outside the transaction, one transaction, the plan-compiled gate SELECTs first (any row ⇒ rollback), statements in order, `PRAGMA foreign_key_check` before commit, all-or-nothing, enforcement always restored.
- Connection affinity across the whole Frame (the go-sqlite3 pod failed here and on transactions — docs/research/babashka-graal-sqlite.md).
- `execute-query` returning keyword-keyed row maps with faithful value types: INTEGER (64-bit), REAL, TEXT, BLOB, NULL.
- Error detection that identifies the failing statement (ADR 0012 `:sqlite-error` payload).
- The SQLite version each route runs and how `sqlite_version()` / `PRAGMA schema_version` are read (Snapshot provenance).

Also: can the pure core (`sqlite-migrate.core`, `impl.*`, `.directives`, `.schema`, `.protocols`) load in babashka unchanged? Name every JVM-only construct in the way.

Deliverable: a fit/gap table per route and a recommendation, in docs/research/ on a local research/babashka-adapter branch, pointed to from this ticket.

## Notes

**2026-09-29T15:16:11.336062120Z**

Resolution. Findings: docs/research/babashka-adapter.md on local branch research/babashka-adapter (ac6ec02; shims kept as reference assets in 94a0036 under docs/research/babashka-adapter-shims/).

Method: a throwaway shim per route re-implemented the sqlite-migrate.jdbc API with the Frame copied from jdbc.clj, and bb 1.13.222 ran the unchanged test suite (172 deftests, 911 assertions).

- babashka.sqlite (FFI) fits every contract point: the whole Frame, connection affinity for the conn's lifetime, in-memory databases, exact 64-bit INTEGER / REAL / TEXT / BLOB / NULL. The suite passed on SQLite 3.53.4 in 1.6 s. The adapter must lower-case column-name keys itself (babashka.sqlite keeps their case) and build the conn with reify, because bb's deftype refuses java.io.Closeable.
- On Debian's libsqlite3 3.46.1 the FFI route failed 12 assertions, all in unit tests pinned to 3.53 plan shapes, the same tests CI leaves off its 3.40.1 floor leg. The planner fell back to Rebuilds correctly, and properties-test passed.
- sqlite3 CLI: passes only as a hand-rolled long-lived coprocess (-init /dev/null to skip ~/.sqliterc, parameter newline escaping, careful statement termination) and is 13x slower. Its JSON output is not faithful. It corrupts TEXT and BLOB on CLI 3.45–3.47, which Ubuntu 24.04, Debian 13 and Ubuntu 26.04 ship. It prints Inf as invalid JSON from 3.52. A BLOB can never be told apart from TEXT. Error messages vary across versions, so a failure is tied to its statement only by sending one statement at a time.
- Pure core: all ten namespaces load in bb unchanged; only sqlite-migrate.jdbc fails, since next.jdbc isn't available in bb.
- Recommendation: build the babashka adapter on babashka.sqlite. Main risk: it and babashka.ffi are experimental and five weeks old; it is a git dependency with no tags or releases; it needs a dynamically linked bb >= 1.13.220 and the host's libsqlite3, so the SQLite version varies by host (feeds "Set the SQLite, JDK, Clojure and babashka floors and the CI matrix").

**2026-09-29T15:16:11.436505747Z**

babashka.sqlite (FFI) fits the whole Executor contract and the unchanged suite passes in bb; the sqlite3 CLI works but its JSON corrupts values on current LTS CLIs; the pure core loads unchanged. Recommend babashka.sqlite; risk: experimental driver, host-provided SQLite.
