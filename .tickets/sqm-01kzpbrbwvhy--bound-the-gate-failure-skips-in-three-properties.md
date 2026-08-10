---
id: sqm-01kzpbrbwvhy
title: Bound the gate-failure skips in three properties
status: open
type: task
priority: 2
mode: afk
created: '2026-08-10T17:33:03.771310237Z'
updated: '2026-08-10T17:37:09.681869310Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: The skip rate for gate-failing scenarios is visible in the test run for each of the three properties
  done: false
- title: Each of the three properties fails if its assertions are never reached across a run
  done: false
- title: Suite green with the bound in place
  done: false
tags:
- phase-3
---

## Description

Residual convergence, data preservation, and version honesty all short-circuit to `true` when the Check result does not pass. Skipping data-dependent failures is defensible, but nothing bounds how often the generator lands there — so all three could pass a full run without ever reaching their assertions, and ADR 0010 states them unconditionally.

Make the skip rate observable and bounded, so a property that has quietly gone vacuous fails instead of passing.

Found by a two-axis code review of the epic (Spec axis, partial requirement).
