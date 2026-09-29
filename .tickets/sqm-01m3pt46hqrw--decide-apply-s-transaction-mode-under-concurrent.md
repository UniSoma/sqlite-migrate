---
id: sqm-01m3pt46hqrw
title: Decide Apply's transaction mode under concurrent writers
status: open
type: feature
priority: 2
mode: hitl
created: '2026-09-29T14:46:58.871007203Z'
updated: '2026-09-29T20:17:59.164272348Z'
parent: sqm-01m3pt2fgxp9
tags:
- wayfinder:grilling
links:
- sqm-01m3qd29cm2s
---

## Question

How does Apply (and Check) behave when another connection writes to the live file?

Found while charting: the JDBC adapter's Frame opens a plain deferred `BEGIN` (src/sqlite_migrate/jdbc.clj). A concurrent writer can make it fail partway with `SQLITE_BUSY` — nothing is lost, but it surfaces as `:sqlite-error`.

Decide: `BEGIN IMMEDIATE` (or `EXCLUSIVE`) as part of the Frame contract, so every adapter — the babashka one included — follows it; who owns busy-timeout (adapter constructor, caller, or neither); whether busy/lock failures deserve their own non-success class or stay `:sqlite-error`; what Check's read-only run guarantees about consistency; and what the docs say about WAL mode and the pre-Frame `PRAGMA foreign_keys` toggle.

## Notes

**2026-09-29T16:40:20.728157459Z**

Data point from the first-consumer assessment: the consumer migrates a staged copy of the file and then registers it as a new Snapshot (the stage-then-swap recipe). A single-writer rule plus the staged copy keep it away from concurrent writers, so this consumer puts no pressure on the transaction-mode decision.

**2026-09-29T20:07:47.802920399Z**

Data point from "Measure Rebuild cost on a large table" (docs/research/rebuild-cost.md on branch research/rebuild-cost, "Readers during a Rebuild"). A second connection with busy_timeout 0 read every 2 ms during a 1M-row apply!. In rollback-journal mode SQLite refused 712 of 716 reads (longest refusal 1.53 s of a 1.55 s Apply), and 1555 of 1605 in the UNIQUE case (3.33 s of 3.44 s). The default 2 MB page cache spills early, so the Frame holds the EXCLUSIVE lock for almost the whole transaction. In WAL mode SQLite refused no read. sqlite-jdbc's default busy_timeout is 3000 ms, so a reader on another sqlite-jdbc connection fails with SQLITE_BUSY during any rollback-journal Rebuild longer than 3 s. 10M-row Rebuilds took 19 to 111 s, and writers wait that long in both modes. The Frame's PRAGMA foreign_key_check scans every foreign key in the file (0.4 to 2.6 s at 10M rows), so it lengthens the write lock for every Plan, not only a large one.
