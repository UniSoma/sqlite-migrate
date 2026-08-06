---
id: sqm-01kzbpngs10b
title: sqlite-migrate design spec
status: open
type: epic
priority: 2
mode: afk
created: '2026-08-06T14:12:06.048909188Z'
updated: '2026-08-06T16:58:01.261761003Z'
tags:
- wayfinder:map
---

## Destination

A design spec for `sqlite-migrate`: a general open-source, data-driven Clojure library that introspects a live SQLite file, diffs it against a declared target schema, and produces an executable migration plan — with the diff as a first-class public surface. The spec is done when every core design decision is locked (accrued in CONTEXT.md + docs/adr/) and synthesized into docs/spec.md, ready to hand to a build effort.

## Notes

- **Clean-room**: the old project's code is off-limits; only the seed notes exist, and their policy decisions are re-derived as open questions here, not inherited.
- **Audience**: general OSS library designed properly; the old project is the motivating first consumer.
- **Runtime**: pure core is runtime-agnostic; JVM first; GraalVM native binary generation is a bonus target; babashka support pending research.
- **SQLite version**: assume the latest SQLite — everything on https://www.sqlite.org/lang_altertable.html as of today is available.
- **Diff is a first-class public surface**, not internal machinery.
- Every grilling ticket: invoke /grilling and /domain-modeling. Decisions accrue into CONTEXT.md (glossary) and docs/adr/ (hard-to-reverse choices); docs/spec.md is synthesized at the end.

## Decisions so far

- [Research: SQLite access from babashka and GraalVM native images](sqm-01kzbppmtvnp) — JVM + Graal native have one low-risk path (next.jdbc + sqlite-jdbc, native-image-tested since 3.40.1.0); babashka is the constraint (go-sqlite3 pod lacks transactions/connection affinity) — shape the effectful edge as a two-op protocol (introspective query, atomic apply) and defer bb support. Findings: docs/research/babashka-graal-sqlite.md on branch research/babashka-graal-sqlite.
- [Research: prior art in declarative schema migration tools](sqm-01kzbppmxwry) — pristine-database diffing beats hand-rolled SQL parsing (and is uniquely cheap on SQLite); only Alembic/Atlas treat the diff as first-class data (validates the core bet); rename intent must be explicit data, never heuristics; data-dependent legality is handled almost nowhere (opportunity); avoid lossy rebuilds, silent skips, auto-destructive drift resets. Findings: docs/research/migration-prior-art.md on branch research/migration-prior-art.
- [Research: SQLite DDL capabilities and ALTER TABLE limits](sqm-01kzbppmqqvd) — latest is 3.53.4; 3.53.0 added ALTER COLUMN SET/DROP NOT NULL and ADD/DROP CHECK (feature-gate on version); otherwise in-place ALTER is only renames + append-only ADD COLUMN + restricted DROP COLUMN, everything else forces the 12-step rebuild (FK pragma off outside txn; create-under-temp-name-then-rename, never rename-first). Introspection pragmas cannot recover CHECK expressions, constraint names, per-column COLLATE, AUTOINCREMENT, or exact DEFAULT spelling — only the stored CREATE SQL has them, so the tool must parse SQL or restrict its schema model. Findings: docs/research/sqlite-ddl-capabilities.md on branch research/sqlite-ddl-capabilities.
- [Decide the introspection model](sqm-01kzbppn403v) — one canonical Snapshot shape produced only by introspection (live file or pristine in-memory DB running the declared schema); pragmas for structure plus a narrow extractor lifting pragma-invisible facts as verbatim opaque expression text — no SQL parser; scope is main-schema tables/indexes/views/triggers + opaque virtual tables; plain EDN, string identifiers, columns ordered, indexes/triggers nested under tables; provenance as equality-neutral metadata. ADR 0001; glossary in CONTEXT.md.
- [Decide the target-schema declaration format](sqm-01kzbppn13g7) — SQL text is the canonical Declaration (string or seq of statements, split by SQLite's prepare loop); an EDN Schema value is sugar compiling to SQL, subset coverage with raw escape hatches; Declarations are pure state with zero migration intent (intent lives in the directives layer); execution effects invisible to introspection error loudly; SQLite itself validates. ADR 0002; glossary terms Declaration and Schema value.
- [Decide the schema equivalence relation](sqm-01kzbppn728d) — one fixed knobless relation over Snapshots, normalizing at comparison time only; identifiers case-folded and dequoted, declared type text compared verbatim-insensitively (never by affinity), opaque expressions compared as token sequences via a lexical tokenizer (no parser) with beyond-token differences honest drift; column order, constraint names, and table flags semantic; sibling order and engine-internal objects noise; no-op, round-trip, and convergence properties locked. ADR 0003; glossary terms Equivalence, Noise, Semantic difference, Token comparison.

## Not yet specified

- Diff-as-product surfaces (CI drift checks, rendering, assertions) — which concrete surfaces earn spec space; sharpens once the diff data model lands.
- Row-level data movement beyond what rebuilds force (kept open, not pre-ruled out) — sharpens with the gates/rebuild ticket.
- The writable_schema / STRICT-coercion capability frontier (kept open) — sharpens with the capability-tiers ticket.
- Packaging: coordinates, namespace layout, release story — sharpens near the API-surface ticket.
- Shape of the follow-on build effort.

## Out of scope

- Databases other than SQLite — different product.
- CLI or GUI tooling on top of the library — later effort.
- Compatibility mode for classic versioned migrations — the declarative framing is the point.