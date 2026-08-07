---
id: sqm-01kzcv5gne5m
title: Rebuild
status: closed
type: task
priority: 2
mode: afk
created: '2026-08-07T00:49:58.956065456Z'
updated: '2026-08-07T15:28:57.719549999Z'
closed: '2026-08-07T15:28:57.719549999Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: Composite rebuild op with locked internal order, compiled at plan time
  done: true
- title: 'Per table: all in-place or one rebuild, never mixed; dependents recreated'
  done: true
- title: Copy by name with rowid stability and sqlite_sequence restoration
  done: true
- title: Residual convergence and data preservation properties pass
  done: true
deps:
- sqm-01kzcv5gjc6h
tags:
- phase-3
---

## Description

The 12-step generalized ALTER TABLE as one composite, reviewable op — the slice that makes arbitrary shape changes reachable. As a developer any table-shape change converges, and the data provably survives.

Scope: the :rebuild-table op with its fixed internal statement order (create under temp name, INSERT...SELECT copy, drop old, rename new — never rename-first) compiled wholly at plan time; per-table selection rule — all changes in place or one rebuild, never mixed; dependent indexes/triggers/views recreated; the copy maps columns strictly by name, copies rowid explicitly when both sides are rowid tables, restores the sqlite_sequence counter when AUTOINCREMENT on both sides, gives new columns their declared defaults, and skips authorized-dropped columns; :rebuild-disabled refusal fires when capabilities forbid rebuild.

The residual convergence property (post-apply diff equals exactly the unhandled entries; fixpoint corollary) and the data preservation property (multiset row survival, rowid stability, AUTOINCREMENT continuity) land here. ADRs 0006, 0008, 0010.

## Notes

**2026-08-07T15:28:57.719549999Z**

Shipped in 0781d00. :rebuild-table composite compiled wholly at plan time with the locked order (create temp, copy, drop old, rename — never rename-first); per table all in-place or one rebuild, never mixed; dependents recreated; copy by name with explicit rowid stability and sqlite_sequence restoration; residual convergence and data preservation land as end-to-end tests in rebuild_test.clj.
