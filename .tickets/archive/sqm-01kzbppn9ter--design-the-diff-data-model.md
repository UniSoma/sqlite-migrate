---
id: sqm-01kzbppn9ter
title: Design the diff data model
status: closed
type: feature
priority: 2
mode: hitl
created: '2026-08-06T14:12:43.449941678Z'
updated: '2026-08-06T17:52:26.084309812Z'
closed: '2026-08-06T17:52:26.084309812Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
deps:
- sqm-01kzbppn728d
assignee: jonas.rodrigues@unisoma.com
---

## Description

## Question

The first-class public value: what does a schema diff look like as data? Added/removed/changed per object kind, how column-level changes nest, what derived indices (inbound FKs, dependents) it carries, and how it stays queryable and renderable without a database.

## Notes

**2026-08-06T17:52:25.995091327Z**

Resolution: the Diff is a thin wrapper map {entries + both sides' Snapshot metadata} over a FLAT sequence of self-contained Diff entries; grouped/nested views are plain functions. Entry = target-relative change kind (added=declared-only, removed=live-only, changed=both-not-equivalent), path, both sides' verbatim sub-values, and for changed the set of differing fact keywords (vocabulary is mechanical — 1:1 with equivalence-compared Snapshot facts; enumeration deferred to spec synthesis). One-sided objects are ONE entry with the whole verbatim object embedded; fine-grained entries exist only inside a changed table, plus at most one table-level entry for table-scoped facts (STRICT, WITHOUT ROWID, column order, PK). No renamed kind (rename intent = directives layer), no cost/severity labels (plan layer), no embedded dependency indices (inbound FKs/dependents are functions over Snapshots). Named constraints pair by folded name; unnamed pair by token-equality, remainder is honest add/remove. Entry order is locked-deterministic (byte-identical serialized diffs for identical Snapshot pairs). Plain EDN throughout; pr-str/read-string round-trip is a promised serialization contract; (equivalent? a b) := empty diff. ADR 0004; glossary terms Diff and Diff entry in CONTEXT.md.

**2026-08-06T17:52:26.084309812Z**

Flat self-contained intent-free EDN Diff: wrapper map + flat entries, target-relative kinds, verbatim both-sides values, no rename/cost/dependency data, token-equality pairing for unnamed constraints, deterministic order, pr-str round-trip contract. ADR 0004.
