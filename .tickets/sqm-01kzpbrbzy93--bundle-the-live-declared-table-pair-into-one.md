---
id: sqm-01kzpbrbzy93
title: Bundle the live/declared table pair into one named value in plan
status: open
type: chore
priority: 2
mode: afk
created: '2026-08-10T17:33:03.870187216Z'
updated: '2026-08-10T17:33:03.870187216Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: The live table, declared table, and rename set travel as one named value instead of as separate parameters
  done: false
- title: The unnamed context locals pctx, dctx, gctx, and ctx are gone from the plan namespace
  done: false
- title: 'The Plan determinism property still holds: same inputs produce a pr-str-identical Plan'
  done: false
- title: Full suite green and clj-kondo clean
  done: false
---

## Description

In the plan namespace, the live table, the declared table, and the rename set travel together through eight signatures — a value wanting to be born. Four context maps (`pctx`, `dctx`, `gctx`, `ctx`) coexist inside the changed-table planner and half-invent it, none with a name that says what it holds; one of them is assembled inline at its use site rather than named at all.

Give the clump one named value and retire the opaque locals. Behaviour is unchanged — the Plan determinism property is the guard.

Found by a two-axis code review of the epic (Standards axis, Data Clumps and Mysterious Name).
