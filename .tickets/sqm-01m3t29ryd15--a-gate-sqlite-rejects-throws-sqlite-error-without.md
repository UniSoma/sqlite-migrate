---
id: sqm-01m3t29ryd15
title: A Gate SQLite rejects throws :sqlite-error without naming the Gate
status: open
type: bug
priority: 3
mode: hitl
created: '2026-09-30T21:07:33.421420941Z'
updated: '2026-09-30T21:07:37.374793943Z'
acceptance:
- title: check against a Plan whose Gate SQL SQLite rejects throws :sqlite-error naming that Gate, with SQLite's exception as the cause
  done: false
- title: apply! with such a Gate throws :sqlite-error naming that Gate, and a rejected drift probe is distinguishable from a rejected Plan Gate
  done: false
- title: bb test passes; clj-kondo --lint src test ci is clean
  done: false
links:
- sqm-01m3svq9hfvf
---

## Description

Found while landing sqm-01m3svq9hfvf. Since 320c396, the JDBC adapter wraps every driver failure on its query path in `:sqlite-error`, with the message "the query failed" and no other ex-data. When SQLite rejects a Gate's SQL, both public paths that run Gates report only that:

- `check` runs each Gate through `p/execute-query` in `run-gates`; the error escapes with no Gate and no op index.
- `apply!` passes the gate SQLs to `execute-batch!`, whose `check-gates!` runs them in step 4. The error already carries `:sqlite-migrate/error`, so `apply!` rethrows it unchanged. Nothing says whether the drift probe (index 0) or a Plan Gate failed, or which one.

```clojure
(p/execute-batch! conn ["CREATE TABLE made (x INTEGER)"] ["SELECT * FROM nope"])
;; ex-data {:sqlite-migrate/error :sqlite-error}, cause SQLiteException "no such table: nope"
```

The planner compiles every Gate, so a Gate SQLite rejects is a planner bug. That is exactly when the caller needs the Gate to diagnose it. clojure-style.md asks errors to carry "the facts a caller needs to attribute the failure", as `:gate-failed` already does through `:check`.

### Open questions

- **Where the attribution lives.** `check` can name the Gate in core, around each `execute-query`. For `apply!`, the gate queries run inside the adapter's Frame, so either the adapter reports the failing gate's index (a `SQLiteExecutor` protocol docstring change that outside adapters must follow, cf. the compatibility policy in sqm-01m3pt45tj3h), or `apply!` recovers it some other way.
- **What the ex-data carries.** The Gate verbatim under `:gate` with its `:op-index`, matching the Check result entry, or the gate-sqls index; and how the drift probe is told apart from a Plan Gate.