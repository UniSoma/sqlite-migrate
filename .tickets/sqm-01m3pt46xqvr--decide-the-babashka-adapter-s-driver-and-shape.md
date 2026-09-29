---
id: sqm-01m3pt46xqvr
title: Decide the babashka adapter's driver and shape
status: open
type: feature
priority: 2
mode: hitl
created: '2026-09-29T14:46:59.255311473Z'
updated: '2026-09-29T14:48:43.555732558Z'
parent: sqm-01m3pt2fgxp9
tags:
- wayfinder:grilling
deps:
- sqm-01m3pt45egw5
- sqm-01m3pt46hqrw
---

## Question

Given the research findings, which driver backs the babashka adapter — babashka.sqlite or the sqlite3 CLI — and what is the adapter's shape: namespace name, constructors (file, in-memory), how the Frame is realized, how rows and errors come back, and how its driver dependency is declared and labelled (experimental dependency allowed; experimental contract not — map Notes)? If neither route honours the Frame, this ticket takes that back to the user before 1.0 drops babashka. Consult codebase-design.