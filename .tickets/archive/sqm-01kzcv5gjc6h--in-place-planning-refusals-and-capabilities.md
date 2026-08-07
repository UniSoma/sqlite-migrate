---
id: sqm-01kzcv5gjc6h
title: In-place planning, refusals, and capabilities
status: closed
type: task
priority: 2
mode: afk
created: '2026-08-07T00:49:58.858524393Z'
updated: '2026-08-07T04:08:48.736202886Z'
closed: '2026-08-07T04:08:48.736202886Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: All in-place op kinds plan with version gating and legalizing order
  done: true
- title: Refusal vectors carry every applicable launch code; plan never throws for refusals
  done: true
- title: Capabilities default to live version + rebuild allowed; completeness invariant holds
  done: true
- title: apply! refuses unhandled plans unless opted in
  done: true
- title: Plan determinism property passes
  done: true
deps:
- sqm-01kzcv5gbyfb
tags:
- phase-2
---

## Description

Widen planning to everything achievable in place, with the honest refusal story for everything that is not (yet). As a developer I can converge additive and 3.53-era changes, see exactly why anything else went unhandled, and trust that identical inputs give byte-identical Plans.

Scope: in-place op vocabulary — add column (append-only), restricted drop column, ALTER COLUMN SET/DROP NOT NULL and ADD/DROP CHECK behind the target-version gate, index/trigger/view create/drop; the planner may order ops to legalize an in-place form (drop the covering index before the drop-column), verifying against accumulated intermediate state; the locked phase order baked into list position. Refusals: vectors of {class, code, explanation} carrying every applicable refusal; launch codes :virtual-table-changed, :rebuild-disabled, :unsupported-by-target-version (:incapable) and :destructive-drop (:needs-intent; index/trigger/view drops plan freely); plan never throws for refusals. Capabilities: flat map, defaulting to the live Snapshot's version + :rebuild? true. Completeness invariant mechanically checked. apply! grows :allow-unhandled? (default false, refusing with the unhandled entries). Plan wrapper carries ops, both metadata blocks, capabilities, unhandled entries.

The plan determinism property (pr-str-identical Plans) lands here. Rebuild is the next slice: until then every rebuild-only change is honestly unhandled. ADRs 0006, 0007, 0011.

## Notes

**2026-08-07T04:08:48.736202886Z**

Shipped in 733c1dd. sqlite-migrate.plan (core/plan delegates): in-place ops — create-table (with nested creates), append-only add-column (restrictions version-gated: NOT NULL-without-default/CURRENT_* default/STORED generated at 3.53, generated at 3.31, STRICT at 3.37), restricted drop-column (3.35+; legality verified against accumulated intermediate state incl. own-table FK columns and surviving view/trigger references, all REPL-verified against live SQLite; covering-index-before-drop-column legalization works), ALTER COLUMN SET/DROP NOT NULL and ADD/DROP CHECK gated at 3.53, index/trigger/view create/drop (changed views collapse to whole-value drop+create per ADR 0004). Locked phase order in list position. Refusals: vectors of {class, code, explanation} with every applicable code (:virtual-table-changed, :rebuild-disabled, :unsupported-by-target-version, :destructive-drop; interim :rebuild-not-implemented for rebuild-only changes — honest until the Rebuild slice); plan never throws for refusals (:malformed-input only for missing snapshot context). Capabilities flat map defaults to live version + :rebuild? true; completeness invariant mechanically checked (throws :internal, tested). apply! :allow-unhandled? verified per ADR 0011. Plan determinism: pr-str-identical across repeated and independently rebuilt inputs. Full suite 60 tests / 249 assertions green, clj-kondo clean.
