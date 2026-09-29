---
id: sqm-01m3pt4743ak
title: Decide the Adapter contract, including connection ownership
status: open
type: feature
priority: 2
mode: hitl
created: '2026-09-29T14:46:59.459011535Z'
updated: '2026-09-29T20:17:59.164272348Z'
parent: sqm-01m3pt2fgxp9
tags:
- wayfinder:grilling
deps:
- sqm-01m3pt46xqvr
links:
- sqm-01m3q0j698q9
- sqm-01m3qd29cm2s
---

## Question

With two adapters in view, what does the Adapter contract promise beyond the two Executor ops — constructors, lifecycle, and ownership? Consult codebase-design.

Found while charting: `sqlite-migrate.jdbc/connect` accepts a caller's `java.sql.Connection` and returns a conn whose `close` closes that Connection — at odds with "lifecycle belongs to the caller". Decide who closes what for each constructor input (path, Connection, DataSource), whether the protocols docstrings state lifecycle normatively, and what the babashka adapter's constructors must mirror.

## Notes

**2026-09-29T19:37:46.306726374Z**

ADR 0025 (from sqm-01m3q0j698q9) adds a third Executor op, first-statement [conn sql]: the prefix of sql that SQLite's prepare consumes as its first statement, or nil when it holds none. The babashka adapter has to supply it too, from a real prepare tail or by probing the way the JDBC adapter does.
