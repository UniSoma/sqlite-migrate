---
id: sqm-01kzpbrc31d0
title: Rename plan/core's opaque helpers and tighten test naming
status: open
type: chore
priority: 2
mode: afk
created: '2026-08-10T17:33:03.969800604Z'
updated: '2026-08-10T17:33:03.969800604Z'
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
---

## Description

Mechanical naming cleanup across source and tests, per `docs/agents/clojure-style.md`.

**Source.** Two single-letter-ish identifier-quoting helpers reveal nothing about what they do, and one constructor is named `op` while returning an order/op envelope rather than an Op as `CONTEXT.md` defines it.

**Tests.** Five `deftest` names state a topic rather than the claim being asserted, against the style doc's rule that a `deftest` name reads as the claim being made. Separately, five hand-rolled `(try … (catch Exception e e))` blocks in the gates tests duplicate the shared `thrown-info` helper that 29 other sites already use.

Found by a two-axis code review of the epic (Standards axis: Mysterious Name, deftest naming, Duplicated Code).
