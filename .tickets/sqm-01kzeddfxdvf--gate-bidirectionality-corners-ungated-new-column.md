---
id: sqm-01kzeddfxdvf
title: 'Gate bidirectionality corners: ungated new-column UNIQUE/FK failures and conservative STRICT text gate'
status: open
type: bug
priority: 2
mode: afk
created: '2026-08-07T15:28:09.130619162Z'
updated: '2026-08-07T15:28:09.130619162Z'
parent: sqm-01kzctnhwmjm
tags:
- phase-3
acceptance:
- title: UNIQUE/PK/FK over a new defaulted column gates the copy-time duplicate/orphan failure (Check pass implies apply success)
  done: false
- title: STRICT text-branch gate no longer flags values SQLite would accept ('0123', '1e2'), or the deviation is documented as a decision
  done: false
deps:
- sqm-01kzcv5gvdny
---

## Description

Two corners where the gate bidirectionality property (ADR 0010) does not hold, found in code review of the Gates and Check slice.

Direction 1 (Check pass must imply no data-dependent apply failure): index-gates, unique-gates, and foreign-key-gates skip when a key column has no live counterpart ('conformance is not row-dependent yet'). But a declared UNIQUE constraint, PK, or FK over a NEW column with a default gives every copied row the same value at rebuild time — apply fails on two or more rows with no Gate compiled, so Check passes yet apply aborts.

Direction 2 (Gate fail must imply apply would abort): strict-violation-condition's text branch is deliberately conservative — non-canonical numeric spellings such as '0123' and '1e2' may be flagged although SQLite STRICT accepts them, producing a false Gate failure. Either replicate SQLite's looks-like-number acceptance or record the conservatism as a documented deviation.

Both are corners of sqm-01kzcv5gvdny (shipped); neither is covered by the current bidirectionality scenarios in gates_test.clj.
