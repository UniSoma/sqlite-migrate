---
id: sqm-01kzbppnftsn
title: Define the refusal taxonomy and capability tiers
status: closed
type: feature
priority: 2
mode: hitl
created: '2026-08-06T14:12:43.642380223Z'
updated: '2026-08-06T20:01:58.179787341Z'
closed: '2026-08-06T20:01:58.179787341Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
deps:
- sqm-01kzbppncxq2
assignee: jonas
---

## Description

## Question

Which shapes does the library auto-apply, which does it refuse — and are refusals 'we can't' or 'we lack your intent'? Enumerate refusal codes as data, decide collect-all-blockers vs fail-fast, and decide where capability ends: is writable_schema / STRICT coercion a tier, a refusal, or out of scope?

## Notes

**2026-08-06T20:01:58.076346934Z**

Resolution:

Two-class refusal taxonomy, carried as data on each unhandled Plan entry:
- :incapable — no route under the given capabilities; no directive can lift it.
- :needs-intent — a route exists but risks data without explicit intent; the directives layer consumes exactly this class.

An unhandled entry carries a VECTOR of refusals (class + code + explanation) — every one that applies, never just the first (collect-all one level down). plan never throws for refusals; collect-all per ADR 0006's completeness invariant.

Launch codes (open set — add-only, never remove/rename):
- :virtual-table-changed (:incapable) — changed virtual tables have no alter/rebuild route; adds plan verbatim, removes fall under :destructive-drop.
- :rebuild-disabled (:incapable) — only route is rebuild and :rebuild? is false; absorbs version gaps (older targets just rebuild more).
- :unsupported-by-target-version (:incapable) — declared object can't exist on the target version (e.g. STRICT < 3.37); detected from Snapshot flags.
- :destructive-drop (:needs-intent) — removed table/column/virtual table; every drop is destructive-in-kind to a pure planner. Rename is a resolution of this refusal, not a code. Index/trigger/view drops plan freely — boundary is 'loses values', not 'is a DROP'.

Data-dependence is NOT a refusal class: ops that may choke on rows (NOT NULL, UNIQUE, STRICT coercion) plan and carry preconditions in a :gates slot on the Op — mechanism owned by the gates ticket.

Capabilities: flat map — target SQLite version + :rebuild? (default true). No named tiers. writable_schema entirely out of scope (not even a refusal code — Rebuild reaches every shape it would).

Half-applied-rename trap closed at Apply, not planner: entries stay independent; Apply by default REFUSES a Plan with non-empty unhandled — partial convergence is explicit opt-in (flag named by execution-policies ticket).

ADR 0007; glossary gains Refusal, Refusal class, Capabilities, Gate.

**2026-08-06T20:01:58.179787341Z**

Two-class refusal taxonomy (:incapable / :needs-intent) as refusal vectors on unhandled entries; four launch codes (open set); gates are op metadata not refusals; capabilities = version + :rebuild? only; writable_schema out of scope; Apply refuses plans with unhandled entries by default. ADR 0007.
