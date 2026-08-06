---
id: sqm-01kzbppn403v
title: Decide the introspection model
status: closed
type: feature
priority: 2
mode: hitl
created: '2026-08-06T14:12:43.264537180Z'
updated: '2026-08-06T14:50:41.451836240Z'
closed: '2026-08-06T14:50:41.451836240Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
deps:
- sqm-01kzbppmqqvd
assignee: jonas
---

## Description

## Question

Re-derive live-file-as-truth from first principles: what does the library read from a live SQLite file (sqlite_schema + which pragmas), what normalized data structure does introspection produce, and is the introspected shape the same shape as the declared target (one schema representation) or a distinct one with a projection between them?

## Notes

**2026-08-06T14:50:41.355393819Z**

Resolution — introspection model decided:

1. ONE canonical Snapshot shape (not two + projection). A single introspection function produces it from either the live file or a pristine in-memory DB into which the declared schema was executed. The diff never knows which side is which. Migration intent (renames etc.) lives in the directives layer, never in the Snapshot.
2. Pragmas for structure; a narrow extractor (not a parser) lifts pragma-invisible facts out of stored CREATE sql as opaque expression text: CHECK bodies, generated/index/partial expressions, DEFAULT spellings, constraint names, per-column COLLATE, AUTOINCREMENT, FK deferrability. Stored verbatim-as-extracted; normalization is the equivalence relation's job at comparison time.
3. Snapshot scope: tables, indexes, views, triggers, plus virtual tables as opaque CREATE-text values. Shadow tables and sqlite_* internals excluded. Views/triggers in from day one (the 12-step rebuild must replay them).
4. main schema only; attached DBs and temp out of scope.
5. Provenance (sqlite_version read with, schema_version fingerprint) attached as metadata that does not affect Snapshot equality. Since the library reads and writes through its own bundled SQLite, this is for reproducibility, not capability gating.
6. Plain EDN data, unqualified keys, nested shape, optional malli schema. Identifiers are strings (SQLite names can hold spaces/dots/case that keywords mangle).
7. Shape: {:tables {name -> table} :views {name -> view}}; indexes and triggers nested under their owning table; :columns an ordered vector (column order is semantically real).
8. Columns carry both :type (verbatim declared) and :affinity (derived by a pure function of the type string).

Captured: ADR docs/adr/0001-one-snapshot-shape-via-pristine-introspection.md; glossary terms (Snapshot, Introspection, Pristine database, Live file, Opaque expression, Snapshot metadata) in CONTEXT.md.

**2026-08-06T14:50:41.451836240Z**

One Snapshot shape via pristine-DB introspection; pragmas + narrow extractor for opaque expressions; no SQL parser; main-only; plain EDN with string identifiers; ADR 0001 + CONTEXT.md glossary
