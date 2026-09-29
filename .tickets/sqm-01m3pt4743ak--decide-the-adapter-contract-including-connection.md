---
id: sqm-01m3pt4743ak
title: Decide the Adapter contract, including connection ownership
status: open
type: feature
priority: 2
mode: hitl
created: '2026-09-29T14:46:59.459011535Z'
updated: '2026-09-29T16:39:29.128933131Z'
parent: sqm-01m3pt2fgxp9
tags:
- wayfinder:grilling
deps:
- sqm-01m3pt46xqvr
links:
- sqm-01m3q0j698q9
---

## Question

With two adapters in view, what does the Adapter contract promise beyond the two Executor ops — constructors, lifecycle, and ownership? Consult codebase-design.

Found while charting: `sqlite-migrate.jdbc/connect` accepts a caller's `java.sql.Connection` and returns a conn whose `close` closes that Connection — at odds with "lifecycle belongs to the caller". Decide who closes what for each constructor input (path, Connection, DataSource), whether the protocols docstrings state lifecycle normatively, and what the babashka adapter's constructors must mirror.