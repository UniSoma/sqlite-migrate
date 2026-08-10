---
id: sqm-01kzmhwm0vjk
title: Extract shared identifier-quoting and malformed! helpers
status: closed
type: task
priority: 2
mode: afk
created: '2026-08-10T00:41:45.755382916Z'
updated: '2026-08-10T00:52:06.496274608Z'
closed: '2026-08-10T00:52:06.496274608Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: One shared helper owns SQL identifier quote-doubling; core, plan, and schema all call it
  done: true
- title: One shared malformed! helper replaces the duplicates in schema and plan
  done: true
- title: core.clj sets *warn-on-reflection* true immediately after ns and uses no bare Java string methods
  done: true
- title: bb test passes and clj-kondo --lint src test is clean with no reflection warnings
  done: true
tags:
- phase-1
---

## Description

Code review of the v0.1.0 epic (Standards axis) found identifier quote-doubling implemented three times — in core (via a Java `.replace` interop call), in plan (`q-ident`), and in schema (`identifier`) — and `malformed!` defined identically in schema and plan. The core site also violates two documented standards at once: the namespace does interop without `(set! *warn-on-reflection* true)`, and reaches for a Java method where the style doc says to prefer `clojure.string`.

Extract one shared impl helper for identifier quoting and one for `malformed!`, call them from all sites, and add the missing reflection guard. Behavior must be unchanged: the full suite (including the Plan-determinism property) stays green.

## Notes

**2026-08-10T00:52:06.496274608Z**

Extracted sqlite-migrate.impl.util owning q-ident (SQL identifier quote-doubling) and malformed! (:malformed-input throw). plan and schema now delegate to it; core's guard-invisible-effects! uses u/q-ident instead of a bare Java .replace, and core.clj sets *warn-on-reflection* true after the ns form. bb test green (144 tests, 765 assertions), clj-kondo clean, no reflection warnings.
