---
id: sqm-01m1j0ed5746
title: Fix the doubled verb in the :destructive-drop Refusal explanation
status: open
type: bug
priority: 3
mode: afk
created: '2026-09-02T21:29:48.967030059Z'
updated: '2026-09-02T21:29:48.967030059Z'
---

## Description

A `:destructive-drop` Refusal on a removed column reads "dropping removing column age of table people would discard stored values". `destructive-refusal` in `src/sqlite_migrate/impl/plan.clj` (line 102) prefixes "dropping " to its argument, and its two call sites at lines 1091–1092 pass `(entry-what entry)`, which for a `:removed` entry already prefixes "removing ". The third call site (line 1640) passes a bare `"table <name>"` and reads fine.

Reproduce: plan a Diff where a table loses a column that holds data (or a fused table rename whose live side has an extra column), with no drop Directive, and read the explanation on the unhandled entry.

Fix: hand `destructive-refusal` the object phrase without the verb — either an object-only sibling of `entry-what` or strip the kind verb at the two call sites — so the text reads "dropping column age of table people". Presentation-only: the Refusal class and code are unchanged, so no CHANGELOG entry per docs/releasing.md. Add an assertion on the explanation text to the existing directives or plan tests.
