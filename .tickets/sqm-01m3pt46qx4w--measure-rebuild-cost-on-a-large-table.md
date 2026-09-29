---
id: sqm-01m3pt46qx4w
title: Measure Rebuild cost on a large table
status: open
type: task
priority: 2
mode: afk
created: '2026-09-29T14:46:59.069473460Z'
updated: '2026-09-29T14:46:59.155907838Z'
parent: sqm-01m3pt2fgxp9
tags:
- wayfinder:task
---

## Question

What does a Rebuild cost on a large table, so the docs can state it honestly (no performance promise — map Notes)?

Measure, on the JVM adapter with the current planner: wall time, peak extra disk, and journal/WAL growth for a Rebuild of a table at ~1M and ~10M rows (with a couple of indexes and a trigger), under rollback-journal and WAL modes; plus Introspection and `plan` time for a ~200-table schema. Record the method, machine, and numbers in docs/research/ and note any surprise (e.g. a step that is quadratic, or disk growth beyond 2× the table).

Resolution: the numbers and where they live. They feed the documentation fog and may inform the concurrent-writer decision.