---
id: sqm-01kzpbrbzy93
title: Bundle the live/declared table pair into one named value in plan
status: closed
type: chore
priority: 2
mode: afk
created: '2026-08-10T17:33:03.870187216Z'
updated: '2026-08-10T19:03:25.903758796Z'
closed: '2026-08-10T19:03:25.903758796Z'
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
tags:
- phase-2
---

## Description

In the plan namespace, the live table, the declared table, and the rename set travel together through eight signatures — a value wanting to be born. Four context maps (`pctx`, `dctx`, `gctx`, `ctx`) coexist inside the changed-table planner and half-invent it, none with a name that says what it holds; one of them is assembled inline at its use site rather than named at all.

Give the clump one named value and retire the opaque locals. Behaviour is unchanged — the Plan determinism property is the guard.

Found by a two-axis code review of the epic (Standards axis, Data Clumps and Mysterious Name).

## Notes

**2026-08-10T19:03:25.903758796Z**

Shipped in 59faff1. The live table, declared table, and rename set now travel as one named value, `pairing` ({:live-table :declared-table :rename-map}), through the rebuild family, the gate family, routing, and plan-table-changes. All four opaque locals are gone: pctx -> planning-context, dctx -> claims, ctx -> routing-state (built by table-routing-state, which no longer carries :live-table), and gctx retired outright rather than renamed — its snapshots and route flag became explicit arguments so each gate fn takes only what it needs. rebuild-recreate-sqls was deliberately left on [declared-table deps]: it reads only the declared side, and handing it the whole pairing for family symmetry would give it more than it needs. Pure refactor; determinism property holds; 152 tests / 791 assertions green, clj-kondo clean.
