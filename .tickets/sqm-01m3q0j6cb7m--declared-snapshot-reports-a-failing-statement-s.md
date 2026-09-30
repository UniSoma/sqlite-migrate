---
id: sqm-01m3q0j6cb7m
title: declared-snapshot reports a failing statement's Declaration index and text
status: open
type: bug
priority: 2
mode: afk
created: '2026-09-29T16:39:28.907500697Z'
updated: '2026-09-30T19:12:36.393445483Z'
acceptance:
- title: 'A regression deftest: the example above throws :sqlite-error with :statement-index 2 and :statement "CREATE TABLE a (z)", failing before the fix'
  done: false
- title: The ex-message names the Declaration index, not a batch index
  done: false
- title: bb test passes; clj-kondo --lint src test ci is clean
  done: false
- title: A multi-statement string whose second statement SQLite rejects throws :sqlite-error with :statement-index 1 and that statement's trimmed text, trailing `;` included, as the :malformed-input refusals carry it
  done: false
- title: CHANGELOG.md carries a Fixed entry for the Declaration index and statement text on a rejected statement
  done: false
links:
- sqm-01m3pt45mm8c
- sqm-01m3q0hqvwa6
- sqm-01m3q0j698q9
- sqm-01m3svq9hfvf
deps:
- sqm-01m3q0j698q9
tags:
- settled
---

## Description

Found by the first-consumer assessment (Assess the first consumer against today's SNAPSHOT, finding 12). When SQLite rejects a Declaration statement, `declared-snapshot` passes on the Executor's batch-level error unchanged. The error says "statement 0 of the batch failed" and carries `:statement-index 0` wherever the statement sits, because each statement runs in a batch of its own. It also does not carry the statement. Its `:malformed-input` refusals already carry the real `:statement-index` and `:statement`. The consumer's Declaration has 241 statements, so they found the failing one by bisecting. Reproduced on ee75dc7, in list form and in multi-statement string form alike:

```clojure
(m/declared-snapshot pristine ["CREATE TABLE a (x)" "CREATE TABLE b (y)" "CREATE TABLE a (z)"])
;; ex-message "statement 0 of the batch failed"
;; ex-data   {:sqlite-migrate/error :sqlite-error, :statement-index 0}
;; cause     "table a already exists"
```

ADR 0002 promises that "execution errors are surfaced with which-statement context". After the fix, a statement SQLite rejects throws `:sqlite-error` carrying its zero-based index counted across the whole Declaration (ADR 0025) and its text, with SQLite's exception as the cause.

### Decisions

- The ex-message names the Declaration index only, for example "SQLite rejected Declaration statement 2". SQLite's reason stays on the cause. This matches `apply!` and the guard's "Declaration statement N" wording, and keeps adapter-specific driver text out of the message.
- The fix lives in `declared-snapshot`'s statement loop in `sqlite-migrate.core`. It catches an `execute-batch!` failure that carries `:statement-index` and throws a new `:sqlite-error` with the loop's Declaration `:statement-index` and the trimmed `:statement` (the same text the guard carries, trailing `;` included). The driver exception rides as the cause through `(or (ex-cause e) e)`, as `apply!` does. The Executor contract does not change: no index offset on `execute-batch!`.
- An `execute-batch!` failure without `:statement-index` passes through unchanged, as in `apply!`.
- The failure class stays `:sqlite-error`. No new error class, no `:element-index`.
- The `declared-snapshot` docstring gains the `:sqlite-error` shape.
- The Executor protocol docstring, `jdbc_frame_test`, and the style-doc example keep their batch-index wording.
- The regression tests sit next to the existing which-statement tests in `snapshot_fidelity_test.clj`. The ex-message check needs the exception itself, not the `declared-snapshot-error` helper, which returns only ex-data.
- `CHANGELOG.md` gets a `### Fixed` entry under `[Unreleased]`.
