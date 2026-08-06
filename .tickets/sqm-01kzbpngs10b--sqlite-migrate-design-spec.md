---
id: sqm-01kzbpngs10b
title: sqlite-migrate design spec
status: open
type: epic
priority: 2
mode: afk
created: '2026-08-06T14:12:06.048909188Z'
updated: '2026-08-06T20:02:46.015590179Z'
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
- [Design the diff data model](sqm-01kzbppn9ter) — the Diff is a thin wrapper map (entries + both sides' Snapshot metadata) over a flat sequence of self-contained entries; target-relative kinds (added/removed/changed) with both sides' verbatim sub-values and a differing-facts set 1:1 with equivalence-compared facts; one entry per one-sided object, fine-grained entries only inside changed tables; no rename kind, no cost labels, no embedded dependency indices (functions over Snapshots instead); unnamed constraints pair by token-equality; locked deterministic order; plain-EDN pr-str/read-string round-trip contract; equivalent? := empty diff. ADR 0004; glossary terms Diff and Diff entry.
- [Decide the diff-as-product surfaces](sqm-01kzc398swpc) — three pure functions ship and nothing else: drift? (Diff predicate), drift-report (single-arity presentation-only Diff→string; per-fact both-sides lines for changed objects, whole verbatim CREATE sql for one-sided ones), by-object (the one nesting view); CI drift is a documented recipe, consumer filtering a documented pattern — no bundled check, no filter helpers, no render knobs; Snapshot amended to carry per-object stored CREATE sql as equality-neutral provenance. ADR 0005; glossary terms Drift and Drift report.
- [Design the plan model and operation ordering](sqm-01kzbppncxq2) — the Plan is a pure-EDN wrapper (ordered :ops + both Snapshot metadata + capabilities + unhandled entries); an Op is a logical kind + object path + :serves (Diff entry paths) + plan-time :sql (the reviewable "exactly this will run" artifact); the 12-step rebuild is one composite op per table, all-in-place or one rebuild never mixed; ordering baked into list position with a locked phase order; FK/transaction framing executor-owned, never ops; Apply is a dumb all-or-nothing fold that refuses drifted databases via schema_version; completeness invariant — every Diff entry served or listed unhandled with a reason. ADR 0006; glossary terms Plan, Op, Rebuild, Apply.
- [Define the refusal taxonomy and capability tiers](sqm-01kzbppnftsn) — two-class refusal taxonomy (:incapable / :needs-intent, the latter the directives layer's exact contract) carried as refusal vectors (class + code + explanation, all that apply) on unhandled entries; four launch codes in an add-only open set (:virtual-table-changed, :rebuild-disabled, :unsupported-by-target-version, :destructive-drop — index/trigger/view drops plan freely); data-dependence is op :gates metadata, never a refusal; capabilities = target version + :rebuild? only, no named tiers; writable_schema ruled out entirely; Apply by default refuses plans with unhandled entries (partial convergence opt-in). ADR 0007; glossary terms Refusal, Refusal class, Capabilities, Gate.

## Not yet specified

- Row-level data movement beyond what rebuilds force (kept open, not pre-ruled out) — sharpens with the gates/rebuild ticket.
- Packaging: coordinates, namespace layout, release story — sharpens near the API-surface ticket.
- Shape of the follow-on build effort.

## Out of scope

- Databases other than SQLite — different product.
- CLI or GUI tooling on top of the library — later effort.
- Compatibility mode for classic versioned migrations — the declarative framing is the point.
- writable_schema as a migration mechanism — Rebuild reaches every shape it would, so it buys only performance and risk; ruled out by [Define the refusal taxonomy and capability tiers](sqm-01kzbppnftsn) (ADR 0007). STRICT coercion stayed in scope as a Gate (gates ticket).