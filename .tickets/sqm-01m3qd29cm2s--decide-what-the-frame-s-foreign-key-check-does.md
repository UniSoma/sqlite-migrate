---
id: sqm-01m3qd29cm2s
title: Decide what the Frame's foreign-key check does with rows that were orphaned before the Apply
status: open
type: feature
priority: 2
mode: hitl
created: '2026-09-29T20:17:59.164272348Z'
updated: '2026-09-29T20:17:59.277361118Z'
parent: sqm-01m3pt2fgxp9
tags:
- wayfinder:grilling
links:
- sqm-01m3pt46qx4w
- sqm-01m3pt4743ak
- sqm-01m3pt46hqrw
---

## Question

Step 6 of the Frame (the `SQLiteExecutor/execute-batch!` docstring in src/sqlite_migrate/protocols.clj; ADRs 0006, 0013, 0016) runs `PRAGMA foreign_key_check` with no table argument and rolls back on any row. Should it fail an Apply over foreign-key violations that were in the file before the Apply started? If not, what should it check instead? Consult codebase-design: the Frame is the contract every adapter follows, the babashka adapter included, so a change after 1.0 would be a break.

Found after closing "Measure Rebuild cost on a large table". SQLite ships with foreign-key enforcement off, so a live file can hold orphan rows that its application never noticed. Reproduced on 52b7f06, in memory:

```clojure
;; live, with the orphan inserted outside the Frame
["CREATE TABLE parent (id INTEGER PRIMARY KEY)"
 "CREATE TABLE child (id INTEGER PRIMARY KEY, parent_id INTEGER REFERENCES parent(id))"
 "CREATE TABLE unrelated (id INTEGER PRIMARY KEY)"]
;; INSERT INTO child VALUES (1, 999)
;; declared: the same, plus
"CREATE INDEX unrelated_id ON unrelated(id)"
```

The Plan is one `:create-index` op with no unhandled entries. `apply!` throws `:sqlite-error` "foreign_key_check failed — 1 violating row(s)". One orphan anywhere in the file blocks every Plan. The error does not name the cause as pre-existing, and `check` gives no warning beforehand, because it runs only the Gates.

ADR 0008 treats the check as the backstop for orphans the Plan creates. The FK Gates report those first. No ADR considers orphans that were there before.

The check also costs time on every Plan, in proportion to the file: 0.4 to 2.6 s at 10M rows in docs/research/rebuild-cost.md (branch research/rebuild-cost), and it holds the write lock while it runs.

Options seen so far:

1. Limit the check to the tables the Plan touches: `PRAGMA foreign_key_check(<table>)` for each child table the Plan creates, rebuilds or alters, and each child of a parent it rebuilds, renames or drops. The Plan would have to carry that table list across the Executor seam as data (ADR 0016).
2. Fail only on violations the Apply added: run the whole-file check at the start of the transaction too and compare the two results. This keeps the full cost and doubles it.
3. Keep the whole-file check, but give pre-existing orphans their own non-success class and a warning in Check, so the operator learns about them before Apply.

Also decide: whether the FK Gates of ADR 0008 then become the only guard against orphans the Plan creates, or stay paired with a narrower backstop; and what a violation reports (the pragma rows, the tables involved, the count).