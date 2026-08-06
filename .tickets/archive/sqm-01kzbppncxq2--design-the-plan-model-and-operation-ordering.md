---
id: sqm-01kzbppncxq2
title: Design the plan model and operation ordering
status: closed
type: feature
priority: 2
mode: hitl
created: '2026-08-06T14:12:43.549592505Z'
updated: '2026-08-06T19:15:29.045363630Z'
closed: '2026-08-06T19:15:29.045363630Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
deps:
- sqm-01kzbppn9ter
- sqm-01kzbppmqqvd
assignee: jonas.rodrigues@unisoma.com
---

## Description

## Question

What is a migration plan as data? Op granularity, whether each op carries its own DDL, how dependency ordering is expressed (baked into list position vs explicit edges), where the 12-step rebuild lives, and what the executor's contract is.

## Notes

**2026-08-06T19:15:28.951640821Z**

Resolution: A Plan is a pure-EDN wrapper — ordered :ops vector + both Snapshot metadata + capabilities + unhandled entries. Planner signature plan(diff, opts); information basis is Diff entries as work items plus both Snapshots for context; capabilities (target SQLite version, default latest) are an explicit input, so one Diff can yield different Plans per target. An Op is one logical schema change: :kind + object path + :serves (Diff entry paths) + :sql (exact statements, compiled at plan time — the plan is the reviewable 'exactly this will run' artifact; the executor never writes SQL). The 12-step rebuild is one composite :rebuild-table op per table with the create-new→copy→drop→rename order locked inside (never rename-first). Per-table selection: all-in-place or one rebuild, never mixed; planner may exploit plan ordering for legality (verified against accumulated intermediate state). Ordering is baked into list position, with a locked phase order: drop removed indexes/triggers/views → drop removed tables → per-table change ops (name-sorted) → create added tables (name-sorted; FK refs resolve lazily) → create added indexes/triggers/views. Deterministic: identical inputs → byte-identical plans. Transaction/FK framing (FK off → BEGIN → ops → foreign_key_check → COMMIT → FK on) is executor-owned, signaled by plan metadata, never ops. Apply is a dumb fold: plan order, first error stops+rolls back, all-or-nothing default; re-checks schema_version fingerprint vs the plan's source Snapshot metadata and refuses drifted databases. Completeness invariant: every Diff entry served by ≥1 op or listed unhandled with a reason (vocabulary → refusal-taxonomy ticket); empty ops + non-empty unhandled must not look like success. Same serialization contract as the Diff: plain EDN, pr-str round-trip, no records. ADR 0006; glossary terms Plan, Op, Rebuild, Apply.

**2026-08-06T19:15:29.045363630Z**

Plan = pure-EDN wrapper (ordered :ops + Snapshot metadata + capabilities + unhandled entries); Op = logical kind + path + :serves + plan-time :sql; one composite rebuild op per table (all-in-place or one rebuild, never mixed); ordering baked into position with locked phase order; executor-owned FK/txn frame; Apply = dumb all-or-nothing fold refusing drifted DBs; completeness invariant. ADR 0006.
