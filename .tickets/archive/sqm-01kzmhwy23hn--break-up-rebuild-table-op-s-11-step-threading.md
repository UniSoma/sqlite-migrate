---
id: sqm-01kzmhwy23hn
title: Break up rebuild-table-op's 11-step threading chain
status: closed
type: task
priority: 2
mode: afk
created: '2026-08-10T00:41:56.035153224Z'
updated: '2026-08-10T00:53:47.772667859Z'
closed: '2026-08-10T00:53:47.772667859Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: rebuild-table-op reads as named steps within the style doc's 3-7 step threading limit
  done: true
- title: Plans are byte-identical before and after (Plan determinism property green)
  done: true
- title: bb test passes and clj-kondo --lint src test is clean
  done: true
tags:
- phase-1
---

## Description

Code review of the v0.1.0 epic (Standards axis) flagged `rebuild-table-op` in the plan impl namespace as a hard violation of the documented threading rule: "Threading chains run 3–7 steps. Longer means a fn wants extracting." The current chain runs ~11 steps.

Extract the chain into named functions so each thread stays within the limit. Pure refactor: rebuild behavior and Plan output must be unchanged — the existing rebuild tests and the Plan-determinism property (byte-identical pr-str Plans) pin it.

## Notes

**2026-08-10T00:53:47.772667859Z**

Extracted the 11-step sql chain into three phase-named helpers matching the locked internal order: rebuild-stage-sqls (create under temp, copy, AUTOINCREMENT restore), rebuild-swap-sqls (drop dependents, drop old, rename into place), rebuild-recreate-sqls (declared indexes/triggers, dropped dependents). rebuild-table-op now threads 3 steps. Verified byte-identical pr-str Plans before/after in the REPL on a rebuild exercising all three phases; bb test green (144/765), clj-kondo clean.
