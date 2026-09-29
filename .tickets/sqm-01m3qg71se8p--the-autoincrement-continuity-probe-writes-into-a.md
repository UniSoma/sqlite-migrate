---
id: sqm-01m3qg71se8p
title: The AUTOINCREMENT continuity probe writes into a STORED generated column
status: open
type: bug
priority: 3
mode: hitl
created: '2026-09-29T21:13:00.974484334Z'
updated: '2026-09-29T21:13:06.604727134Z'
tags:
- needs-triage
acceptance:
- title: The data-preservation property passes on seed 1790716355704 at SQM_TRIALS=400
  done: false
- title: bb test passes; clj-kondo --lint src test ci is clean
  done: false
---

## Description

Found by the data-preservation property at SQM_TRIALS=400 (seed 1790716355704) while landing sqm-01m3qdd02a9a. This is a test-harness bug; the planner is not involved.

An :add-column mutation of shape :stored-generated on a table with an AUTOINCREMENT primary key fails the property. The shrunk case is live table a (idpk INTEGER PRIMARY KEY AUTOINCREMENT, a INT); the target adds znew INTEGER GENERATED ALWAYS AS ("idpk" IS NOT NULL) STORED. Apply converges. Then autoincrement-continuity-ok? runs g/probe-insert-sql, which fills every non-AUTOINCREMENT column, including znew: INSERT INTO "a" ("a", "znew") VALUES (1001, 1001). SQLite rejects that: cannot INSERT into generated column "znew".

The generated clause rides the verbatim :type string, so probe-insert-sql cannot tell from the Schema value that the column is generated.
