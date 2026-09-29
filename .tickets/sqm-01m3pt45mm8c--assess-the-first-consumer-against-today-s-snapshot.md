---
id: sqm-01m3pt45mm8c
title: Assess the first consumer against today's SNAPSHOT
status: open
type: task
priority: 1
mode: hitl
created: '2026-09-29T14:46:57.940649069Z'
updated: '2026-09-29T14:48:43.669174814Z'
parent: sqm-01m3pt2fgxp9
tags:
- wayfinder:task
assignee: jonasrodrigues
---

## Question

What does the old project — the motivating first consumer — find when it meets today's 0.2.0-SNAPSHOT? The user runs this (the project can't be shared here) and reports back.

Hands-on, on a *copy* of the project's real database file:

1. `snapshot` the copy; `declared-snapshot` the project's real Declaration.
2. `diff` and `drift-report` — any drift that shouldn't be there (false drift, Noise the relation keeps)? Any real change it misses?
3. `plan` and `plan-report` — any surprising route (Rebuild where in place was expected, or the reverse), refusal, or unhandled entry?
4. `check`, then `apply!` on the copy; re-plan afterwards and confirm zero Ops.

On paper: what the project needs that the design doesn't cover, and every point of API friction or doc gap hit along the way.

Resolution: a findings list as a note, one line per finding. Each becomes a ticket or a fog patch on the map.