---
id: sqm-01kzbppn728d
title: Decide the schema equivalence relation
status: closed
type: feature
priority: 2
mode: hitl
created: '2026-08-06T14:12:43.362511999Z'
updated: '2026-08-06T16:57:36.546831629Z'
closed: '2026-08-06T16:57:36.546831629Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
deps:
- sqm-01kzbppn13g7
- sqm-01kzbppn403v
assignee: jonas
---

## Description

## Question

What counts as 'the same schema'? Physical column order, quoting/case of identifiers, type-name aliases and affinity, default-expression formatting, constraint naming, whitespace in CHECK expressions. Which differences are semantic and which are noise? This relation defines when a migration is a no-op and what the round-trip correctness property means.

## Notes

**2026-08-06T16:57:36.449180641Z**

Resolution — the equivalence relation is one fixed, knobless relation over Snapshot values, normalizing at comparison time only (ADR 0003):

- Identifiers: ASCII case-fold, quoting style ignored; original spelling kept for emission.
- Declared column type text: compared case-insensitively with whitespace normalized — never by affinity (rowid-alias and STRICT make affinity-equivalence wrong).
- Opaque expressions: token-sequence comparison via a lexical SQLite tokenizer (no grammar/AST). Whitespace, comments, keyword case erased; identifiers dequoted+folded; string/blob literals byte-exact. Anything beyond token identity is honest drift (x>0 vs 0<x, explicit COLLATE BINARY vs absent, 1.0 vs 1.00).
- Semantic: physical column order, constraint names, PK/index column order, STRICT/WITHOUT ROWID, all pragma-visible structure.
- Noise: order among named siblings (indexes/triggers/views — matched by folded name); engine-internal objects (sqlite_sequence, sqlite_autoindex_*, sqlite_stat*, vtab shadow tables) excluded entirely.
- Locked properties: (1) no-op — diff empty iff equivalent; (2) round-trip — introspect → emit → pristine → introspect yields an equivalent Snapshot; (3) convergence — applying the plan for diff(live, declared) makes live equivalent to declared.

Artifacts: docs/adr/0003-schema-equivalence-relation.md; CONTEXT.md gains Equivalence, Noise, Semantic difference, Token comparison.

**2026-08-06T16:57:36.546831629Z**

One fixed knobless relation over Snapshots, comparison-time normalization; identifiers case-folded, type text verbatim-insensitive (never affinity), expressions token-compared (lexical, no parser); column order & constraint names semantic; no-op/round-trip/convergence properties locked; ADR 0003
