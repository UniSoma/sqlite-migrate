---
id: sqm-01kzcv5gy8gq
title: EDN schema sugar
status: closed
type: task
priority: 2
mode: afk
created: '2026-08-07T00:49:59.237714491Z'
updated: '2026-08-07T15:28:57.982513266Z'
closed: '2026-08-07T15:28:57.982513266Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: ->sql compiles the documented subset with verbatim quoted identifiers and STRICT-legal type keywords
  done: true
- title: Raw escape hatches work at statement and expression positions
  done: true
- title: Compiled sugar introspects equivalent to its hand-written SQL counterpart
  done: true
deps:
- sqm-01kzcv5g5wcd
tags:
- phase-3
---

## Description

The EDN sugar: one data value describing target state, compiled to SQL text the core consumes like any other Declaration. As a developer I can build schemas as data without the library ever chasing SQLite's grammar.

Scope: `sqlite-migrate.schema/->sql` — Schema value to vector of SQL statement strings; one value mirroring the Snapshot nesting (tables/views as vectors preserving declaration order, columns ordered, indexes/triggers nested under their table); identifiers (keywords or strings) compiled to quoted verbatim spelling with no munging or case folding; column types as keywords for the STRICT-legal set compiled to canonical uppercase with unknown keywords rejected, or any string passed through verbatim as the unchecked escape hatch; a top-level :raw vector for whole statements and [:raw "..."] accepted in expression positions; views/triggers raw-only at launch. Deliberately a subset — the escape hatches mean it never blocks.

Verified through declared-snapshot: compiled sugar introspects equivalent to the hand-written SQL it mirrors. ADR 0002.

## Notes

**2026-08-07T15:28:57.982513266Z**

Shipped in 0781d00. sqlite-migrate.schema/->sql compiles the documented subset with verbatim quoted identifiers and STRICT-legal type keywords (unknown keywords rejected, strings pass through); raw escape hatches at statement and expression positions; compiled sugar introspects equivalent to its hand-written SQL counterpart via declared-snapshot.
