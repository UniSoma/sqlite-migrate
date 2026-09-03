---
id: sqm-01m1jhmtvyjg
title: Treat redundant parentheses around a DEFAULT as Noise in the Equivalence relation
status: closed
type: bug
priority: 2
mode: afk
created: '2026-09-03T02:30:25.406306088Z'
updated: '2026-09-03T02:33:16.894066176Z'
closed: '2026-09-03T02:33:16.894066176Z'
acceptance:
- title: diff of DEFAULT (0.01) vs DEFAULT 0.01 is empty
  done: true
- title: default-kind classifies (1) as :constant
  done: true
- title: ADR 0003 carries the amendment note
  done: true
- title: bb test and clj-kondo clean
  done: true
---

## Description

Diffing a live file whose columns spell defaults as `default (0.01)` against a Declaration spelling them `DEFAULT 0.01` reports a :default fact on every such column, and the Plan carries them as unhandled. SQLite's DEFAULT grammar takes a literal, a signed number, or a parenthesized expression; parentheses wrapping the whole value carry no meaning. Strip whole-spanning outer parentheses lexically (match-paren, no grammar) before Token comparison of defaults, feed the same normalization to the plan layer's default classifier so `(1)` counts as a constant, amend ADR 0003, and cover it in the equivalence and gates suites.

## Notes

**2026-09-03T02:33:16.894066176Z**

Added extract/unparenthesize (lexical, depth-matched outer parens only), routed the diff's default comparison and the planner's default-kind and key-part through it, recorded ADR 0021 with an amendment note on ADR 0003, extended the Noise glossary entry and the changelog. Tests in equivalence_test (noise, semantic-inside, and the helper's guard cases) and gates_test (parenthesized constant gates the same). bb test 167/840 green, clj-kondo clean. On the fiddle's live/mark files the plan's unhandled entries fell from 18 to 10.
