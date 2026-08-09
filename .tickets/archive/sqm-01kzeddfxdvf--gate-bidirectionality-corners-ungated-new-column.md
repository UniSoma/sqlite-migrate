---
id: sqm-01kzeddfxdvf
title: 'Gate bidirectionality corners: ungated new-column UNIQUE/FK failures and conservative STRICT text gate'
status: closed
type: bug
priority: 2
mode: afk
created: '2026-08-07T15:28:09.130619162Z'
updated: '2026-08-09T18:57:05.650791629Z'
closed: '2026-08-09T18:57:05.650791629Z'
parent: sqm-01kzctnhwmjm
tags:
- phase-3
acceptance:
- title: UNIQUE/PK/FK over a new defaulted column gates the copy-time duplicate/orphan failure (Check pass implies apply success)
  done: true
- title: STRICT text-branch gate no longer flags values SQLite would accept ('0123', '1e2'), or the deviation is documented as a decision
  done: true
deps:
- sqm-01kzcv5gvdny
---

## Description

Two corners where the gate bidirectionality property (ADR 0010) does not hold, found in code review of the Gates and Check slice.

Direction 1 (Check pass must imply no data-dependent apply failure): index-gates, unique-gates, and foreign-key-gates skip when a key column has no live counterpart ('conformance is not row-dependent yet'). But a declared UNIQUE constraint, PK, or FK over a NEW column with a default gives every copied row the same value at rebuild time — apply fails on two or more rows with no Gate compiled, so Check passes yet apply aborts.

Direction 2 (Gate fail must imply apply would abort): strict-violation-condition's text branch is deliberately conservative — non-canonical numeric spellings such as '0123' and '1e2' may be flagged although SQLite STRICT accepts them, producing a false Gate failure. Either replicate SQLite's looks-like-number acceptance or record the conservatism as a documented deviation.

Both are corners of sqm-01kzcv5gvdny (shipped); neither is covered by the current bidirectionality scenarios in gates_test.clj.

## Notes

**2026-08-09T18:23:08.417677635Z**

Design settled (grilling session, 2026-08-09).

Corner A (direction 1): precise per-constraint gates for UNIQUE/PK/FK over new defaulted columns, compiled as the existing duplicate-groups/orphan SQL restricted to the key's live-column subset (all-new key = degenerate empty subset, fails iff >=2 rows). NULL/no default passes UNIQUE and FK; PK per real SQLite NULL semantics (rowid-table quirks to be pinned by REPL oracle). FK gates embed the constant default spelling verbatim (CHECK-gate precedent). Existing gate codes reused (:unique/:primary-key/:foreign-key); the new-column cause goes in :explanation. Opaque expression defaults: no gate — undecidable at Check time; documented exclusion, Frame + :sqlite-error backstop. Refusal+directive escalation path recorded as future option only.

Corner B (direction 2): replace the cast round-trip text arms with an exact grammar-decomposition predicate (trim/substr/instr/GLOB well-formedness + lossless-int64 tests), verified by a value-level oracle test against real STRICT inserts (corpus: '0123', '1e2', whitespace variants, '0x1A', '1_000', NBSP, int64 boundaries both spellings, '1e999'). Perf measured ~5-6x per-text-row CPU vs current, one-shot full scan already accepted by ADR 0008; canonical-round-trip short-circuit in reserve.

Cross-cutting: example-based scenarios in gates_test.clj here (property harness demands a gate per scenario, so they start red); generative coverage stays with sqm-01kzcv5h4yna. One new ADR 'Gate bidirectionality corners' records the exclusion, the exact-predicate decision, and rejected alternatives (coarse empty-table gate, refusal+directive, documented deviation); ADR 0008/0010 untouched. No glossary changes.

**2026-08-09T18:57:05.650791629Z**

Both bidirectionality corners closed (ADR 0015, commits d467718 + c5e5e0b). Corner A: UNIQUE/PK/FK keys spanning new columns now gate over the live subset with constant DEFAULT spellings substituted verbatim (all-new key degenerates to failing iff >=2 rows); NULL defaults compile no gate (keys never collide/dangle; STRICT/WITHOUT ROWID PK columns are covered by the column-level gates since table_info marks them NOT NULL); a new INTEGER PK alias auto-assigns even over a constant DEFAULT (pinned by test); opaque expression defaults are the documented exclusion with the Frame as backstop. DEFAULT NULL now counts as no default for the :empty-table gate (adjacent hole, disclosed in the ADR). Corner B: the STRICT text branch replicates SQLite 3.53.2's acceptance exactly via trim/substr/instr/GLOB grammar decomposition plus lossless-int64, pinned by a 74-case value-level oracle test against real STRICT inserts. Six new bidirectionality scenarios, gate-shape unit tests, ADR 0015 records the exclusion and rejected alternatives.
