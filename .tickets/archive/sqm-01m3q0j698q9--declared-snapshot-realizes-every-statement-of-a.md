---
id: sqm-01m3q0j698q9
title: declared-snapshot realizes every statement of a multi-statement Declaration string
status: closed
type: bug
priority: 1
mode: afk
created: '2026-09-29T16:39:28.807984837Z'
updated: '2026-09-29T19:37:46.211451967Z'
closed: '2026-09-29T19:37:46.211451967Z'
acceptance:
- title: 'A regression deftest: the three-statement string above gives an empty Diff against the live schema, failing before the fix'
  done: true
- title: DML, ATTACH, PRAGMA and temp-object statements after the first in a string are refused with :malformed-input, with :statement-index counting statements across the whole Declaration
  done: true
- title: Splitting a string with a semicolon inside a string literal, a comment, or a trigger body follows SQLite's statement boundaries
  done: true
- title: CHANGELOG records the fix
  done: true
- title: bb test passes; clj-kondo --lint src test ci is clean
  done: true
links:
- sqm-01m3pt45mm8c
- sqm-01m3q0hqvwa6
- sqm-01m3q0j6cb7m
- sqm-01m3pt4743ak
---

## Description

Found by the first-consumer assessment (Assess the first consumer against today's SNAPSHOT, finding 2). ADR 0002 makes a single string of several statements a valid Declaration: "Multi-statement text is split by SQLite's own prepare loop, never by string manipulation", and statement errors come with which-statement context. The `declared-snapshot` docstring accepts "a SQL statement string" too. Today only the first statement runs, and nothing warns. Reproduced on cfb225b:

```clojure
;; live
["CREATE TABLE t (a INTEGER)" "CREATE VIEW v AS SELECT a FROM t" "CREATE INDEX t_a ON t(a)"]
;; declared, as ONE string
"CREATE TABLE t (a INTEGER); CREATE VIEW v AS SELECT a FROM t; CREATE INDEX t_a ON t(a)"
;; diff => [:removed [:table "t" :index "t_a"]] [:removed [:view "v"]]
;; plan => :drop-index, :drop-view, with no Directive needed
;; apply! => succeeds and drops both
```

This loses schema silently. A Declaration read with `(slurp "schema.sql")` produces a Plan that drops every object after the first statement. DML later in the string skips the `:malformed-input` guard for the same reason: `"CREATE TABLE t (a); INSERT INTO t VALUES (1)"` is accepted.

Every statement in the string must be realized and guarded on its own, as if the statements had been passed as a seq. SQLite, not string manipulation, finds where each statement ends (ADR 0002). How the adapter exposes the prepare loop is the fixer's call. The Executor contract is protocol-level, so the babashka adapter will have to honour whatever is chosen (see Decide the Adapter contract, including connection ownership).

## Notes

**2026-09-29T19:37:46.211451967Z**

declared-snapshot now realizes and guards every statement of a multi-statement Declaration string. SQLite decides where each statement ends through a new third Executor op, first-statement (ADR 0025, amending ADR 0013's two-op contract). The JDBC adapter probes sqlite-jdbc's prepare, because the driver hides the tail: a candidate ';' ends a statement when the prepare outcome is the same whichever of two illegal-token markers follows. :statement-index counts across the whole Declaration. An unclosed last statement fails with SQLite's error instead of being dropped, and a non-string seq element is refused with :malformed-input. Regression, refusal, boundary and adapter-contract tests added; CHANGELOG, CONTEXT.md, doc/design.md and the ADR 0002/0013 banners updated.
