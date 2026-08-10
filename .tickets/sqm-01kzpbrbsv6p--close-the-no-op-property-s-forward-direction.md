---
id: sqm-01kzpbrbsv6p
title: Close the No-op property's forward direction
status: open
type: task
priority: 2
mode: afk
created: '2026-08-10T17:33:03.675725537Z'
updated: '2026-08-10T17:37:09.592439497Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: 'The No-op property asserts both directions: equivalent schemas yield an empty Diff, and a mutated schema yields a non-empty Diff'
  done: false
- title: The forward arm is driven by the existing mutation generator, not by hand-written examples
  done: false
- title: Deliberately breaking either direction fails the property
  done: false
tags:
- phase-3
---

## Description

ADR 0010's property 1 is stated as an **iff**: `diff(a, b)` is empty *iff* the Snapshots are Equivalent. The generative suite only exercises the ⇐ direction — two realizations of the same schema produce no drift. The ⇒ direction (a real difference always shows up as at least one Diff entry) survives only as unit examples.

Add the forward arm, driven by the mutation generator that already exists for the other properties.

Found by a two-axis code review of the epic (Spec axis, partial requirement).
