---
id: sqm-01m3pt46hqrw
title: Decide Apply's transaction mode under concurrent writers
status: open
type: feature
priority: 2
mode: hitl
created: '2026-09-29T14:46:58.871007203Z'
updated: '2026-09-29T16:40:20.728157459Z'
parent: sqm-01m3pt2fgxp9
tags:
- wayfinder:grilling
---

## Question

How does Apply (and Check) behave when another connection writes to the live file?

Found while charting: the JDBC adapter's Frame opens a plain deferred `BEGIN` (src/sqlite_migrate/jdbc.clj). A concurrent writer can make it fail partway with `SQLITE_BUSY` — nothing is lost, but it surfaces as `:sqlite-error`.

Decide: `BEGIN IMMEDIATE` (or `EXCLUSIVE`) as part of the Frame contract, so every adapter — the babashka one included — follows it; who owns busy-timeout (adapter constructor, caller, or neither); whether busy/lock failures deserve their own non-success class or stay `:sqlite-error`; what Check's read-only run guarantees about consistency; and what the docs say about WAL mode and the pre-Frame `PRAGMA foreign_keys` toggle.

## Notes

**2026-09-29T16:40:20.728157459Z**

Data point from the first-consumer assessment: the consumer migrates a staged copy of the file and then registers it as a new Snapshot (the stage-then-swap recipe). A single-writer rule plus the staged copy keep it away from concurrent writers, so this consumer puts no pressure on the transaction-mode decision.
