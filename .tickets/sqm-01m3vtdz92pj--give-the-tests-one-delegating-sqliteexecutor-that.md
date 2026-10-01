---
id: sqm-01m3vtdz92pj
title: Give the tests one delegating SQLiteExecutor that overrides a single op
status: open
type: task
priority: 3
mode: afk
created: '2026-10-01T13:28:31.266511930Z'
updated: '2026-10-01T13:28:31.266511930Z'
tags:
- depth-review
acceptance:
- title: test-util exposes a delegating executor that forwards every unoverridden SQLiteExecutor op to a real conn
  done: false
- title: No test namespace reifies SQLiteExecutor directly
  done: false
- title: bb test passes and clj-kondo --lint src test ci is clean
  done: false
---

## Description

Five test sites reify `sqlite-migrate.protocols/SQLiteExecutor` to override one op and forward the rest verbatim to a real connection: three in gates_test.clj (the probe-rejecting executor, the failing-re-read executor, `drifting-executor`) and the counting executor in snapshot_fidelity_test.clj, plus the forwarding of `first-statement` every one of them repeats since ADR 0025 added the op. Each new protocol op means editing all five. Found by /depth-review on the reuse check.

Add a `delegating-executor` helper to `sqlite-migrate.test-util` that takes the real conn and a map of the ops to override (fn per op, same arity as the protocol method) and forwards everything else, then rewrite the five sites over it. Keep the arity choice each site makes deliberately (some implement only the gate-sqls arity so a wrong reach fails loudly) expressible through the helper.
