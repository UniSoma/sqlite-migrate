---
id: sqm-01m1j0ed5746
title: Fix the doubled verb in the :destructive-drop Refusal explanation
status: closed
type: bug
priority: 3
mode: afk
created: '2026-09-02T21:29:48.967030059Z'
updated: '2026-09-02T22:05:20.543837038Z'
closed: '2026-09-02T22:05:20.543837038Z'
---

## Description

A `:destructive-drop` Refusal on a removed column reads "dropping removing column age of table people would discard stored values". `destructive-refusal` in `src/sqlite_migrate/impl/plan.clj` (line 102) prefixes "dropping " to its argument, and its two call sites at lines 1091–1092 pass `(entry-what entry)`, which for a `:removed` entry already prefixes "removing ". The third call site (line 1640) passes a bare `"table <name>"` and reads fine.

Reproduce: plan a Diff where a table loses a column that holds data (or a fused table rename whose live side has an extra column), with no drop Directive, and read the explanation on the unhandled entry.

Fix: hand `destructive-refusal` the object phrase without the verb — either an object-only sibling of `entry-what` or strip the kind verb at the two call sites — so the text reads "dropping column age of table people". Presentation-only: the Refusal class and code are unchanged, so no CHANGELOG entry per docs/releasing.md. Add an assertion on the explanation text to the existing directives or plan tests.

## Notes

**2026-09-02T22:05:20.543837038Z**

Split `entry-object` out of `entry-what` in src/sqlite_migrate/impl/plan.clj and handed it to `destructive-refusal` at the two column call sites, so a removed column now reads "dropping column b of table t would discard stored values" instead of "dropping removing column b ...". The table-level call site was already correct and is unchanged. plan-test now asserts the full explanation text for both the column drop (data-bearing-column-drops-need-intent) and the table drop (removed-tables-need-intent), via a new `explanation-of` helper. Presentation-only — Refusal class and code unchanged, no CHANGELOG entry. bb test: 166 tests, 831 assertions, 0 failures. clj-kondo clean. Commit ca730fe.
