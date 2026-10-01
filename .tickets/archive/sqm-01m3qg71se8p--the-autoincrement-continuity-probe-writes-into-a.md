---
id: sqm-01m3qg71se8p
title: The AUTOINCREMENT continuity probe writes into a STORED generated column
status: closed
type: bug
priority: 3
mode: afk
created: '2026-09-29T21:13:00.974484334Z'
updated: '2026-10-01T00:01:00.327295354Z'
closed: '2026-10-01T00:01:00.327295354Z'
tags:
- settled
acceptance:
- title: bb test passes; clj-kondo --lint src test ci is clean
  done: true
- title: probe-insert-sql omits STORED and VIRTUAL generated columns, and SQLite accepts the emitted probe (generators-test)
  done: true
- title: Replaying seed 1790716355704 at 400 trials through the data-preservation property at the REPL passes
  done: true
---

## Description

Found by the data-preservation property at SQM_TRIALS=400 (seed 1790716355704) while landing sqm-01m3qdd02a9a. This is a test-harness bug; the planner is not involved.

An :add-column mutation of shape :stored-generated on a table with an AUTOINCREMENT primary key fails the property. The shrunk case is live table a (idpk INTEGER PRIMARY KEY AUTOINCREMENT, a INT); the target adds znew INTEGER GENERATED ALWAYS AS ("idpk" IS NOT NULL) STORED. Apply converges. Then the AUTOINCREMENT continuity check inserts a probe row that fills every non-AUTOINCREMENT column, znew included: INSERT INTO "a" ("a", "znew") VALUES (1001, 1001). SQLite rejects it: cannot INSERT into generated column "znew". The probe is the only INSERT the harness issues against a target-shaped table.

After the fix, the probe leaves out every generated column as well as the AUTOINCREMENT key. On a table with an AUTOINCREMENT key and a generated column, SQLite accepts the probe, and the continuity check compares the new id against the pre-Apply high.

### Decisions

- `probe-insert-sql` recognizes a generated column by the `GENERATED` token in its `:type` string, matched case-insensitively. This is how `literal` and `default-for` already classify columns in `sqlite-migrate.generators`, and the `:stored-generated` mutation in the same namespace writes that spelling.
- The probe skips any generated column, STORED or VIRTUAL. The unit test covers both, even though the Schema-value path emits only STORED.
- The regression test goes in `sqlite-migrate.generators-test`, shaped like the two `probe-insert-sql-*` tests there: a string assertion, plus a "SQLite accepts the emitted probe" block against a real `CREATE TABLE`.
- A table left with only its AUTOINCREMENT key after the skip uses the existing `DEFAULT VALUES` form.

## Notes

**2026-10-01T00:01:00.327295354Z**

probe-insert-sql now leaves out generated columns (STORED or VIRTUAL, matched by the GENERATED token in the type string) as well as the AUTOINCREMENT key, falling back to DEFAULT VALUES when nothing is left. generators-test covers both kinds against a real table; seed 1790716355704 at 400 trials passes the data-preservation property, and fails at trial 221 on the :stored-generated add-column with the skip disabled.
