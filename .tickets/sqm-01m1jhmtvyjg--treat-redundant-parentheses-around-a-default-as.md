---
id: sqm-01m1jhmtvyjg
title: Treat redundant parentheses around a DEFAULT as Noise in the Equivalence relation
status: in_progress
type: bug
priority: 2
mode: afk
created: '2026-09-03T02:30:25.406306088Z'
updated: '2026-09-03T02:30:31.307757883Z'
acceptance:
- title: diff of DEFAULT (0.01) vs DEFAULT 0.01 is empty
  done: false
- title: default-kind classifies (1) as :constant
  done: false
- title: ADR 0003 carries the amendment note
  done: false
- title: bb test and clj-kondo clean
  done: false
---

## Description

Diffing a live file whose columns spell defaults as `default (0.01)` against a Declaration spelling them `DEFAULT 0.01` reports a :default fact on every such column, and the Plan carries them as unhandled. SQLite's DEFAULT grammar takes a literal, a signed number, or a parenthesized expression; parentheses wrapping the whole value carry no meaning. Strip whole-spanning outer parentheses lexically (match-paren, no grammar) before Token comparison of defaults, feed the same normalization to the plan layer's default classifier so `(1)` counts as a constant, amend ADR 0003, and cover it in the equivalence and gates suites.
