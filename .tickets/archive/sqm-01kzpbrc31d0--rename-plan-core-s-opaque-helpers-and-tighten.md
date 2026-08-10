---
id: sqm-01kzpbrc31d0
title: Rename plan/core's opaque helpers and tighten test naming
status: closed
type: chore
priority: 2
mode: afk
created: '2026-08-10T17:33:03.969800604Z'
updated: '2026-08-10T18:37:22.962101150Z'
closed: '2026-08-10T18:37:22.962101150Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: The identifier-quoting helpers and the order/op envelope constructor have names that reveal what they do; none shadows or misuses the glossary word Op
  done: false
- title: Every deftest name in the suite reads as the claim being asserted, not as a topic
  done: false
- title: The hand-rolled try/catch blocks in the gates tests use the shared thrown-info helper
  done: false
- title: Full suite green and clj-kondo clean
  done: false
tags:
- phase-1
---

## Description

Mechanical naming cleanup across source and tests, per `docs/agents/clojure-style.md`.

**Source.** Two single-letter-ish identifier-quoting helpers reveal nothing about what they do, and one constructor is named `op` while returning an order/op envelope rather than an Op as `CONTEXT.md` defines it.

**Tests.** Five `deftest` names state a topic rather than the claim being asserted, against the style doc's rule that a `deftest` name reads as the claim being made. Separately, five hand-rolled `(try … (catch Exception e e))` blocks in the gates tests duplicate the shared `thrown-info` helper that 29 other sites already use.

Found by a two-axis code review of the epic (Standards axis: Mysterious Name, deftest naming, Duplicated Code).

## Notes

**2026-08-10T18:37:22.962101150Z**

Shipped in 551f2b8. u/q-ident -> quote-identifier (40 call sites), plan's q-str -> quote-string-literal (it quotes a SQL string literal, not an identifier, and now says so in a docstring), plan's op -> ordered-op since it returns an order/op envelope rather than an Op as CONTEXT.md defines it. Twelve deftest names now read as the claim asserted: the five named in the description, plus six in schema_test.clj and one in snapshot_fidelity_test.clj that a code review caught on the same rule. Five try/catch blocks in gates_test.clj now use thrown-info; gates_test.clj:597 was left alone because it is a boolean 'did SQLite accept this' probe, not an exception capture, and thrown-info would invert it. The seven defspec names in properties_test.clj keep their -property suffix — the rule is written for deftest and the suffix names the generative property. Suite green at 152 tests / 791 assertions, clj-kondo clean.
