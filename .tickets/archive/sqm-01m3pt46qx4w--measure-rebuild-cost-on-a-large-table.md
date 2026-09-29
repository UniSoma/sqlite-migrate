---
id: sqm-01m3pt46qx4w
title: Measure Rebuild cost on a large table
status: closed
type: task
priority: 2
mode: afk
created: '2026-09-29T14:46:59.069473460Z'
updated: '2026-09-29T20:18:21.944341085Z'
closed: '2026-09-29T20:07:48.000906838Z'
parent: sqm-01m3pt2fgxp9
tags:
- wayfinder:task
assignee: jonasrodrigues
links:
- sqm-01m3qcf3yz6g
- sqm-01m3qd29cm2s
---

## Question

What does a Rebuild cost on a large table, so the docs can state it honestly (no performance promise — map Notes)?

Measure, on the JVM adapter with the current planner: wall time, peak extra disk, and journal/WAL growth for a Rebuild of a table at ~1M and ~10M rows (with a couple of indexes and a trigger), under rollback-journal and WAL modes; plus Introspection and `plan` time for a ~200-table schema. Record the method, machine, and numbers in docs/research/ and note any surprise (e.g. a step that is quadratic, or disk growth beyond 2× the table).

Resolution: the numbers and where they live. They feed the documentation fog and may inform the concurrent-writer decision.

## Notes

**2026-09-29T16:40:20.638435261Z**

Data point from the first-consumer assessment (JVM, sqlite-jdbc 3.53.4.0, JDK 25, WAL). File: 738 MB, 151 tables, 80 views, 7.25M rows. Snapshot 65 ms, declared-snapshot 110 ms, diff 15 ms, plan 47 ms. check 0.27 s over 20 Gates, 2.8 s over 65. apply! 2.2 s for 15 Rebuilds, including in-transaction Gates. The largest Rebuild was 437,956 rows. Disk and journal growth weren't measured. This is a real consumer's number, not the controlled measurement this ticket asks for.

**2026-09-29T20:07:47.907691652Z**

Resolution. Findings: docs/research/rebuild-cost.md on local branch research/rebuild-cost (commit 275d10b). The harness docs/research/rebuild-cost-bench/ reruns every number. Host: i9-13900HX, Crucial P3 Plus NVMe, ext4, JDK 25.0.4.1, sqlite-jdbc 3.53.2.1, driver-default pragmas, load average 3 to 5. Runs of the same cell varied by up to 3x, so treat the numbers as orders of magnitude.

Table under test: 8 columns, an FK, 2 indexes, 1 trigger. 1M rows = 145 MB file, 10M = 1475 MB. Two Rebuilds: a column retype (no Gate), and an added UNIQUE (sku) (one Gate).

- Wall time of apply!: 1M rows 1.4 to 6.6 s, 10M rows 19 to 111 s. At 10M: retype 19 to 40 s (rollback journal), 28 to 37 s (WAL); unique 43 to 63 s, 84 to 111 s. The copy INSERT...SELECT and the two CREATE INDEX statements dominate. COMMIT in WAL includes the auto-checkpoint (up to 13 s).
- Peak extra disk: rollback journal 1.0x the table with its indexes (the journal peaks at the index size, because the indexes are rebuilt into the pages the old table freed). WAL 1.7x, or 2.0x with UNIQUE. The WAL peaks at the table plus its indexes and stays that size until the last connection closes (journal_size_limit -1).
- After Apply the file stays larger by the table's data size, as free pages (freelist_count = the old table's pages exactly) until VACUUM.
- Readers: in rollback-journal mode, reads from other connections are refused for almost the whole Apply. In WAL mode none were refused. Recorded as a note on "Decide Apply's transaction mode under concurrent writers".
- Planning on 200 tables: snapshot 25 ms, declared-snapshot 568 ms, diff 7 ms, plan 6 ms (20 Rebuild ops). Introspection does not read rows: 7 to 28 ms at both 1M and 10M rows. A raised cache_size did not change the picture beyond noise.

Surprises:
1. declared-snapshot is quadratic in Declaration size (8.8 s at 800 tables): guard-invisible-effects! queries every table after every statement. Filed as the bug "declared-snapshot time grows with the square of the Declaration's size" (sqm-01m3qcf3yz6g).
2. A table that declares a UNIQUE clause (or a primary key that is not the rowid alias) copies 6 to 10x slower. Its automatic index fills row by row during the copy, while the planner sorts the secondary indexes in after. This applies to every Rebuild of such a table, and it is a docs fact, not a defect.
3. The Frame's PRAGMA foreign_key_check has no table argument, so every Plan pays for every foreign key in the file.

Consistent with the first consumer's 15 Rebuilds in 2.2 s (largest 438K rows, WAL). The "Material for the docs" section of the findings is the input to the documentation fog.

**2026-09-29T20:07:48.000906838Z**

Rebuild costs seconds per million rows (1M: 1.4 to 6.6 s; 10M: 19 to 111 s). Peak extra disk is 1.0x the table with its indexes in rollback-journal mode and 1.7 to 2.0x in WAL. The file keeps the old table's space until VACUUM. Readers are locked out in rollback-journal mode. declared-snapshot is quadratic (filed as a bug). Findings: docs/research/rebuild-cost.md on research/rebuild-cost.

**2026-09-29T20:18:21.944341085Z**

Correction to surprise 3. The whole-file foreign_key_check is a correctness issue as well as a cost: one orphan row that was in the file before the Apply fails every Plan, even one that touches no related table. Reproduced on 52b7f06. It became the map ticket "Decide what the Frame's foreign-key check does with rows that were orphaned before the Apply" (sqm-01m3qd29cm2s). The findings doc was corrected in commit f1c9f6d on research/rebuild-cost.
