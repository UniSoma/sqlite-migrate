---
id: sqm-01kzcv5g8zb3
title: Full Snapshot fidelity
status: closed
type: task
priority: 2
mode: afk
created: '2026-08-07T00:49:58.557320Z'
updated: '2026-08-07T01:42:47.723458688Z'
closed: '2026-08-07T01:42:47.723458688Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: Snapshot covers tables/indexes/triggers/views + opaque virtual tables, main schema only
  done: false
- title: Extractor lifts all pragma-invisible facts verbatim, with no SQL parser
  done: false
- title: Metadata (version, schema_version, per-object stored CREATE sql) rides equality-neutral
  done: false
- title: Declarations with introspection-invisible effects error loudly with statement context
  done: false
- title: Nasty-schema corpus introspects to expected values
  done: false
deps:
- sqm-01kzcv5g5wcd
tags:
- phase-1
---

## Description

Widen introspection to the full locked Snapshot scope so any main-schema SQLite database round-trips into a faithful value. As a developer I can introspect a gnarly real-world file and get every fact the equivalence relation will later compare — with the exact verbatim spellings.

Scope: indexes, triggers, and views nested under their tables per the locked shape; opaque virtual tables; the narrow extractor lifting pragma-invisible facts from stored CREATE text as verbatim opaque expression text (CHECK bodies, generated/index/partial expressions, DEFAULT spellings, constraint names, per-column COLLATE, AUTOINCREMENT, FK deferrability) — never a SQL parser; equality-neutral Snapshot metadata including each object's stored CREATE sql; the loud `:malformed-input`-style error (with which-statement context) when executing a Declaration does anything introspection cannot capture (DML, ATTACH, PRAGMA side effects, temp objects). Engine-internal objects (sqlite_sequence, sqlite_autoindex_*, sqlite_stat*, shadow tables) excluded from the value.

Seeds the curated nasty-schema corpus (quoted/keyword identifiers, generated columns, partial indexes, virtual tables) that later slices reuse. ADRs 0001, 0002, 0005.

## Notes

**2026-08-07T01:42:47.723458688Z**

Shipped in 928366f. Snapshot covers tables/indexes/triggers/views nested per the locked shape + opaque virtual tables, main schema only, engine-internal objects excluded. Tokenizing extractor (no SQL parser) lifts verbatim CHECK bodies, generated/index/partial exprs, DEFAULT spellings, constraint names, COLLATE, AUTOINCREMENT, FK names/deferrability; FK pairing matches pragma groups by ref-table+columns with positional fallback. Provenance (sqlite-version, schema_version, per-object stored CREATE sql) rides as Clojure metadata — equality-neutral per ADR 0005/CONTEXT.md. declared-snapshot throws :malformed-input with statement context on DML/ATTACH/PRAGMA/temp objects/CTAS/ANALYZE. Nasty-schema corpus seeded (quoted keywords, generated cols, partial/expr indexes, fts5, deferrable + table-level FKs). 13 tests, 94 assertions, green.
