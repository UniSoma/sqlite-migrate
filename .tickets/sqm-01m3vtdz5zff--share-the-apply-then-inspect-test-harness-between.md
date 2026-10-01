---
id: sqm-01m3vtdz5zff
title: Share the Apply-then-inspect test harness between the Plan and Rebuild tests
status: open
type: task
priority: 3
mode: afk
created: '2026-10-01T13:28:31.167793050Z'
updated: '2026-10-01T13:28:31.167793050Z'
tags:
- depth-review
acceptance:
- title: test-util owns a single applied that takes plan opts and apply opts
  done: false
- title: plan_test and rebuild_test define no private applied or converges?
  done: false
- title: bb test passes and clj-kondo --lint src test ci is clean
  done: false
---

## Description

plan_test.clj and rebuild_test.clj each define private `applied` and `converges?` helpers that open a live and a pristine in-memory database, realize the live Declaration, plan against the declared one, apply!, and hand the open live conn to a continuation. The two copies have diverged in their fourth argument: one threads plan opts into `plan`, the other apply opts into `apply!`. Found by /depth-review on the reuse check.

Move one `applied` into `sqlite-migrate.test-util` taking both option maps (plan opts and apply opts), define `converges?` over it there, and delete both private pairs. No behaviour change; `bb test` stays green.
