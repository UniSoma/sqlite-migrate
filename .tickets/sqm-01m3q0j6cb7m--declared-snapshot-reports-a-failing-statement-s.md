---
id: sqm-01m3q0j6cb7m
title: declared-snapshot reports a failing statement's Declaration index and text
status: open
type: bug
priority: 2
mode: afk
created: '2026-09-29T16:39:28.907500697Z'
updated: '2026-09-29T19:22:19.575855992Z'
acceptance:
- title: 'A regression deftest: the example above throws :sqlite-error with :statement-index 2 and :statement "CREATE TABLE a (z)", failing before the fix'
  done: false
- title: The ex-message names the Declaration index, not a batch index
  done: false
- title: bb test passes; clj-kondo --lint src test ci is clean
  done: false
links:
- sqm-01m3pt45mm8c
- sqm-01m3q0hqvwa6
- sqm-01m3q0j698q9
deps:
- sqm-01m3q0j698q9
---

## Description

Found by the first-consumer assessment (Assess the first consumer against today's SNAPSHOT, finding 12). When SQLite rejects a Declaration statement, the error says "statement 0 of the batch failed" and carries `:statement-index 0`, whatever the statement's position, and it doesn't carry the statement. Its `:malformed-input` refusals, by contrast, already carry the real `:statement-index` and `:statement`. The consumer's Declaration has 241 statements, so they found the failing one by bisecting. Reproduced on cfb225b:

```clojure
(m/declared-snapshot pristine ["CREATE TABLE a (x)" "CREATE TABLE b (y)" "CREATE TABLE a (z)"])
;; ex-message "statement 0 of the batch failed"
;; ex-data   {:sqlite-migrate/error :sqlite-error, :statement-index 0}
;; cause     "table a already exists"
```

ADR 0002 promises that "execution errors are surfaced with which-statement context". The index should be the statement's position in the Declaration, not in the one-statement batch that ran it.
