---
id: sqm-01kzpbrkxdzf
title: Break up plan's long functions
status: closed
type: chore
priority: 2
mode: afk
created: '2026-08-10T17:33:11.977095327Z'
updated: '2026-08-10T19:03:26.007592641Z'
closed: '2026-08-10T19:03:26.007592641Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: The shared changed-table planner, plan itself, and the column-entry fusion function are each decomposed so no let binds a dozen names or defines single-use lambdas inline
  done: false
- title: 'The Plan determinism property still holds: same inputs produce a pr-str-identical Plan'
  done: false
- title: Full suite green and clj-kondo clean
  done: false
deps:
- sqm-01kzpbrbzy93
tags:
- phase-2
---

## Description

Three functions in the plan namespace have outgrown the style doc's "a `let` earns its place":

- the shared changed-table planner — ~106 lines, with a 30-line `let` binding twelve names, three of them lambdas used only by the `cond` beneath it;
- `plan` itself — ~105 lines;
- the column-entry fusion function — ~71 lines.

Decompose into named helpers. Behaviour unchanged; the Plan determinism property is the guard.

**Blocked by** the table-pair bundling ticket, which rewrites the same signatures — running both concurrently would conflict.

Found by a two-axis code review of the epic (Standards axis, Long Function).

## Notes

**2026-08-10T19:03:26.007592641Z**

Shipped in 59faff1. fuse-column-entries 75 -> 35 lines, plan-table-changes 111 -> 42, plan 109 -> 56; no let among the three binds more than eight names or defines a single-use lambda. ~30 named private helpers extracted: rename-candidates/anchored-renames/rename-consumed?/rename-unit around fusion; route-units plus one helper per cond branch (in-place-result, rebuild-disabled-result, rebuild-blockers/blocked-result, rebuild-result) around the changed-table planner; resolve-claims, fused-table-pairs, plan-entry-group, collect-results around plan. No signature or docstring contract changed. A spec-axis review independently verified the one non-obvious equivalence (rename-unit's :orig filter dropping the consumed? conjunct) holds for every entry kind. 152 tests / 791 assertions green, clj-kondo clean.
