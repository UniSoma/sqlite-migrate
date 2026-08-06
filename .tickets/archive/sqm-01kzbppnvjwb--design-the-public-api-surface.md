---
id: sqm-01kzbppnvjwb
title: Design the public API surface
status: closed
type: feature
priority: 2
mode: hitl
created: '2026-08-06T14:12:44.018738427Z'
updated: '2026-08-06T23:21:18.613005960Z'
closed: '2026-08-06T23:21:18.613005960Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
deps:
- sqm-01kzbppnnr2p
- sqm-01kzbppnrsqa
assignee: jonasrodrigues
---

## Description

## Question

The namespace-level API: which functions exist (introspect, diff, plan, check, apply...), what values flow between them, how directives and policies are passed, and what the minimal effectful protocol is that keeps the core runtime-agnostic.

## Notes

**2026-08-06T18:32:45.747254951Z**

From 'Decide the diff-as-product surfaces' (ADR 0005): namespace placement of drift?, drift-report, and by-object is delegated here; the CI recipe's composition story (no bundled check fn) is also this ticket's to settle.

**2026-08-06T20:37:28.361654469Z**

From gates ticket (ADR 0008): the public API includes a read-only Check surface at the effectful edge (Plan + connection → structured gate report) alongside Apply.

**2026-08-06T23:21:12.717321430Z**

Resolution (grilling session, ADR 0013):

**Namespace shape** — four public namespaces (concrete names deferred to packaging): core (whole pipeline), protocol (effectful-edge contract), JDBC adapter, schema (EDN sugar). The protocol/adapter seam is load-bearing for the runtime-agnostic bet.

**Effectful protocol** — one protocol, `SQLiteExecutor`, exactly two ops: `execute-query [conn sql params]` → vector of keyword-keyed row maps (introspection + Check ride on this alone), and `execute-batch! [conn statements]` → nil, owning the always-identical atomic frame: FK pragma off (outside txn) → BEGIN → statements → PRAGMA foreign_key_check (any row ⇒ rollback+throw) → COMMIT → restore pragma in finally. Frame runs foreign_key_check unconditionally, even for no-Rebuild plans. Protocol docstrings are the adapter-author spec.

**Conn symmetry** — database creation is NOT in the protocol; adapters ship constructors (`connect` accepting path or existing java.sql.Connection/DataSource; `in-memory`), returning Closeable executor-satisfying conns. Every effectful core fn is conn-first: `snapshot [conn]`, `declared-snapshot [conn declaration]` (throws :malformed-input if the DB is non-empty — guards silently polluted declared Snapshots), `check [conn plan]`, `apply! [conn plan opts?]`.

**Core inventory (complete)** — snapshot, declared-snapshot, diff, drift?, by-object, plan, check, apply!, drift-report, plan-report, check-report. No equivalent? (drift? is the one predicate, ADR 0005), no migrate! one-shot (recipes over compositions; "converge on startup" is a documented recipe).

**plan signature** — `[diff]` / `[diff {:capabilities ... :directives [...]}]`; omitted capabilities default to the live side's Snapshot-metadata SQLite version + :rebuild? true (zero-config path is version-honest by construction). apply! opts exactly {:allow-unhandled? :check-gates?} (ADR 0011).

**Schema ns** — one public fn `->sql` (Schema value → vector of SQL statement strings, a Declaration).

Glossary: Executor, Adapter, Frame added to CONTEXT.md. ADR 0013 records the decision and rejected alternatives (one-shot fn, adapter/conn asymmetry, finer protocol ops, diff-takes-Declaration).

**2026-08-06T23:21:18.613005960Z**

Four public namespaces (core/protocol/jdbc-adapter/schema, names deferred to packaging); two-op SQLiteExecutor protocol (execute-query → keyword-keyed row maps; execute-batch! → nil, owning the unconditional FK-off/txn/foreign_key_check/restore frame); conn-symmetric edges with adapter-owned constructors; complete core inventory of 11 fns, no one-shot, no equivalent?; plan opts {:capabilities :directives} defaulting to live Snapshot's version + :rebuild? true; schema ns exports only ->sql. ADR 0013; glossary Executor, Adapter, Frame.
