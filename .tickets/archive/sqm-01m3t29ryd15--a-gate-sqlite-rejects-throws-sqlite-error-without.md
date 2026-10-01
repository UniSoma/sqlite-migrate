---
id: sqm-01m3t29ryd15
title: check and apply! name the Gate SQLite rejects, and refuse on drift first
status: closed
type: bug
priority: 3
mode: afk
created: '2026-09-30T21:07:33.421420941Z'
updated: '2026-10-01T00:48:31.230700854Z'
closed: '2026-10-01T00:48:31.230700854Z'
acceptance:
- title: bb test passes; clj-kondo --lint src test ci is clean
  done: true
- title: check against a Plan whose Gate SQL SQLite rejects throws :sqlite-error with that Gate verbatim under :gate, its :op-index, and SQLite's exception as the cause
  done: true
- title: apply! against the same Plan throws :sqlite-error with the same ex-data as check
  done: true
- title: A failure of Apply's drift probe throws :sqlite-error with no :gate and no :op-index
  done: true
- title: When the live schema drifts after the fingerprint fast-fail and a Gate then fails, check and apply! throw :drift-refused with the Gate's failure as the cause
  done: true
- title: The JDBC adapter's execute-batch! throws :sqlite-error with the failing gate-sqls index under :gate-index and the SQLiteException as the cause when SQLite rejects a gate query
  done: true
- title: CHANGELOG.md carries a Fixed entry for the attributed Gate error and a Changed entry for the :gate-index requirement on adapters
  done: true
links:
- sqm-01m3svq9hfvf
tags:
- settled
---

## Description

When SQLite rejects the SQL of one of a Plan's Gates, `check` and `apply!` throw `:sqlite-error` naming that Gate: the Gate verbatim under `:gate` and its op's plan index under `:op-index`, the same pair a Check result entry carries, with SQLite's exception as the cause. Both edges throw the same ex-data for the same Gate.

The drift probe Apply runs at the head of the Frame's gate step is not a Gate: when it fails, the `:sqlite-error` carries no `:gate` and no `:op-index`.

Drift beats Gates here too (ADR 0018). When a Gate fails because the live schema moved after the fingerprint fast-fail, `check` and `apply!` throw `:drift-refused` with the Gate's failure as the cause, never a `:sqlite-error` about a missing object.

The `SQLiteExecutor` contract grows to make this possible for Apply: a step-4 failure names the failing entry of `gate-sqls` by index. Adapters outside this library must follow.

A Gate SQLite rejects is reachable from ordinary input today: a CHECK that reads a column the same Plan adds compiles a Gate over the live table, where that column does not exist yet. That planner bug is out of scope; this ticket makes it diagnosable.

### Decisions

- The adapter reports the failing gate: a step-4 failure's ex-data carries the failing entry's zero-based index in `gate-sqls` under `:gate-index`, with the driver exception as the cause, mirroring `:statement-index` for step 5. The `SQLiteExecutor` docstring in `protocols.clj` states it as a requirement and drops "a step-4 failure carries no `:statement-index`" in favor of it. Core never replays Gates or EXPLAIN-validates them to recover the index.
- Core maps `:gate-index` back beside `gates-violated!` in `core.clj`, which already owns the index-0-is-the-drift-probe mapping: index 0 rethrows the adapter's error unchanged; index i > 0 is the Plan Gate at i - 1 in `plan-gates` order.
- Public ex-data: `{:sqlite-migrate/error :sqlite-error :gate <Gate verbatim> :op-index n}` — no `:op`. The cause is SQLite's exception, unwrapped from the adapter's wrapper as the statement path does, `(or (ex-cause e) e)`.
- A drift-probe failure is told apart from a Plan Gate failure by the absence of `:gate` and `:op-index`; no dedicated marker key.
- Drift precedence: when a Gate fails at either edge, core re-reads the fingerprint outside any transaction; if it moved, it throws `:drift-refused` through `drift-refused!` with the Gate failure as the cause. The adapter does not report the results of the gates that ran before the failing one.
- One private helper in `core.clj` builds the attributed `:sqlite-error` for both edges, as `drift-refused!` serves both. `check` attributes in core around each `execute-query` in `run-gates`; it needs no protocol change.
- The ex-message is one line naming the Gate's `:code` and op index, and says the Gate failed, not that it was rejected: SQLITE_BUSY and IO errors ride the same path.
- Tests hand-craft a Plan whose Gate `:sql` SQLite rejects (Plans are plain EDN with valid provenance); none depends on the CHECK-on-added-column bug. `check`/`apply!` tests go in `gates_test.clj`; the adapter test in `jdbc_frame_test.clj`, where `a-gate-query-sqlite-rejects-is-a-sqlite-error-without-a-statement-index` is rewritten for `:gate-index`.
- Drift tests inject the drift with a wrapping executor that drops the gated table before delegating, as `apply-refuses-drift-that-lands-after-the-outside-frame-check` does; for `check`, the wrapper drops it when it sees the first Gate's SQL.
- CHANGELOG `[Unreleased]`: a Fixed entry for `check` and `apply!`, and a Changed entry for the `:gate-index` requirement, which breaks adapters outside this library. No ADR: the protocol docstring is the requirement's normative home.

## Notes

**2026-10-01T00:48:31.230700854Z**

check and apply! throw :sqlite-error naming a Gate SQLite fails to run (:gate verbatim, :op-index, SQLite's exception as the cause), identical at both edges; a moved fingerprint turns it into :drift-refused with that error as the cause, and a failed fingerprint re-read leaves the Gate's error standing. A drift-probe failure carries no :gate or :op-index. SQLiteExecutor's execute-batch! now requires :gate-index on a failing gate query; the JDBC adapter complies. CHANGELOG carries the Fixed and Changed entries.
