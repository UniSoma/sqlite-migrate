---
id: sqm-01kzppn8zkvm
title: A table named "if", "not", or "exists" rebuilds under the wrong name
status: closed
type: bug
priority: 1
mode: afk
created: '2026-08-10T20:43:36.819686755Z'
updated: '2026-08-10T20:46:35.604082887Z'
closed: '2026-08-10T20:46:35.604082887Z'
parent: sqm-01kzctnhwmjm
tags:
- phase-3
acceptance:
- title: A rebuild of a table named if, not, or exists creates the shadow table under the shadow name and Apply succeeds
  done: true
- title: A regression test covers a rebuild of a table named if, driven through the public API
  done: true
- title: CREATE TABLE IF NOT EXISTS still has its table-name token found correctly
  done: true
- title: Suite green and clj-kondo --lint src test clean
  done: true
---

## Description

A rebuild of a table whose name folds to `if`, `not`, or `exists` renames the wrong token: the shadow name lands in the first column position while the table keeps its own name, so Apply aborts with `table "if" already exists`.

Reproduction (REPL, retype `:int` -> `:integer` on a table named `if`):

    live   {:tables [{:name "if" :columns [{:name "a" :type :int}]}]}
    target {:tables [{:name "if" :columns [{:name "a" :type :integer}]}]}

Check passes, and the rebuild Op emits:

    CREATE TABLE "if" ("if__sqm_rebuild" INTEGER)
    INSERT INTO "if__sqm_rebuild" (rowid, "a") SELECT rowid, "a" FROM "if"
    DROP TABLE "if"
    ALTER TABLE "if__sqm_rebuild" RENAME TO "if"

Apply then fails: `SQLite error during apply - op 0 (rebuild-table) failed`, cause `[SQLITE_ERROR] table "if" already exists`.

Cause: `create-sql-under-temp-name` (src/sqlite_migrate/impl/plan.clj:395-410) steps over an `IF NOT EXISTS` clause by skipping any token whose fold is in `#{"if" "not" "exists"}`. The skip reaches `:qid` tokens as well as `:word` tokens, so the quoted identifier `"if"` is taken for the keyword and the next identifier - the first column - is substituted instead. A quoted identifier is never a keyword, so the skip belongs to `:word` tokens alone.

Found by the generative suite at `SQM_TRIALS=3000` while closing sqm-01kzpbrbsv6p and sqm-01kzpbrbwvhy; the generator reaches names like `if` from its plain-name alphabet. Pre-existing - it fails `residual-convergence-property` and `gate-bidirectionality-property` alike, and the latter is untouched by that work. The default 40 trials rarely reach it.

## Notes

**2026-08-10T20:46:35.604082887Z**

Fixed in create-sql-under-temp-name (src/sqlite_migrate/impl/plan.clj): the IF NOT EXISTS skip now applies to :word tokens only, so a quoted identifier spelling "if", "not", or "exists" is read as the table name it is. Before, the skip ate the quoted table name and the first column took the shadow name — CREATE TABLE "if" ("if__sqm_rebuild" INTEGER) — and Apply aborted with [SQLITE_ERROR] table "if" already exists.

Two regression tests in rebuild_test.clj: rebuild-names-the-temp-table-when-the-table-is-named-if walks all three reserved spellings, asserting both the four locked statements and convergence against real SQLite; rebuild-of-an-if-not-exists-declaration-names-the-temp-table pins what the substitution actually sees, since sqlite_master normalizes IF NOT EXISTS away before the planner ever reads the stored CREATE sql — the skip stands as a guard on that path, not a live branch.

Verified: the reproduction from the description now applies cleanly, converges, and keeps its row. bb test green at 156 tests / 806 assertions; properties at SQM_TRIALS=3000 green, which is where the bug first showed.
