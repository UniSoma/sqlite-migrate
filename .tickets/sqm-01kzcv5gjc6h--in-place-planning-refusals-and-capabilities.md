---
id: sqm-01kzcv5gjc6h
title: In-place planning, refusals, and capabilities
status: open
type: task
priority: 2
mode: afk
created: '2026-08-07T00:49:58.858524393Z'
updated: '2026-08-07T01:07:08.677054539Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: All in-place op kinds plan with version gating and legalizing order
  done: false
- title: Refusal vectors carry every applicable launch code; plan never throws for refusals
  done: false
- title: Capabilities default to live version + rebuild allowed; completeness invariant holds
  done: false
- title: apply! refuses unhandled plans unless opted in
  done: false
- title: Plan determinism property passes
  done: false
deps:
- sqm-01kzcv5gbyfb
tags:
- phase-2
---

## Description

Widen planning to everything achievable in place, with the honest refusal story for everything that is not (yet). As a developer I can converge additive and 3.53-era changes, see exactly why anything else went unhandled, and trust that identical inputs give byte-identical Plans.

Scope: in-place op vocabulary — add column (append-only), restricted drop column, ALTER COLUMN SET/DROP NOT NULL and ADD/DROP CHECK behind the target-version gate, index/trigger/view create/drop; the planner may order ops to legalize an in-place form (drop the covering index before the drop-column), verifying against accumulated intermediate state; the locked phase order baked into list position. Refusals: vectors of {class, code, explanation} carrying every applicable refusal; launch codes :virtual-table-changed, :rebuild-disabled, :unsupported-by-target-version (:incapable) and :destructive-drop (:needs-intent; index/trigger/view drops plan freely); plan never throws for refusals. Capabilities: flat map, defaulting to the live Snapshot's version + :rebuild? true. Completeness invariant mechanically checked. apply! grows :allow-unhandled? (default false, refusing with the unhandled entries). Plan wrapper carries ops, both metadata blocks, capabilities, unhandled entries.

The plan determinism property (pr-str-identical Plans) lands here. Rebuild is the next slice: until then every rebuild-only change is honestly unhandled. ADRs 0006, 0007, 0011.
