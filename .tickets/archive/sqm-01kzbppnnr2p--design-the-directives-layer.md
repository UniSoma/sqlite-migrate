---
id: sqm-01kzbppnnr2p
title: Design the directives layer
status: closed
type: feature
priority: 2
mode: hitl
created: '2026-08-06T14:12:43.832337314Z'
updated: '2026-08-06T21:01:58.976882437Z'
closed: '2026-08-06T21:01:58.976882437Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
deps:
- sqm-01kzbppnftsn
assignee: jonas
---

## Description

## Question

The intent channel: how does an author supply what the diff can't infer — renames (column/table), defaults for NOT NULL tightening, type-change coercions? What is a directive as data, how does it bind to a diff, and how do unused/stale directives behave?

## Notes

**2026-08-06T20:02:08.229279309Z**

From refusal taxonomy (ADR 0007): directives consume exactly the :needs-intent refusal class — an entry plans once its refusal vector is empty; :incapable is never liftable. Only launch :needs-intent code is :destructive-drop; a rename directive is a resolution of that refusal (drop-vs-rename), not a refusal kind. Open code set: directives ticket may add :needs-intent codes if it finds a second ambiguity class.

**2026-08-06T20:37:28.263295621Z**

From gates ticket (ADR 0008): directives carry NO data-transform mechanism — row transformation beyond by-name column mapping is out of scope. Directives lift :needs-intent refusals only and never touch gates. Rename resolution feeds the rebuild copy's name matching.

**2026-08-06T21:01:53.380744538Z**

Resolution (ADR 0009): Directives are a planner input — plan(diff, capabilities, directives) — the Diff stays a pure state delta with no resolved-Diff intermediate; rename fusion is visible via the serving op's :serves. A directive is a plain-EDN map with a :directive kind keyword (open add-only set); launch inventory is exactly the four :destructive-drop resolutions: :rename-table, :rename-column, :drop-table, :drop-column (virtual tables ride the table kinds; NOT-NULL defaults and coercions deliberately absent — gates / out-of-scope row transformation). Binding is name-only, per object, no wildcards or global allow-all-drops; :table/:from always name the live object, :to the declared one, identifiers normalized as everywhere. Directives are conditional and durable: unmatched ones are inert-but-reported in the Plan's :unused-directives (input order), never an error, never consulted by Apply's refusal default — fleet-safe, and typos stay loud via the un-lifted refusal. Rename matching is all-or-nothing (half-match = unused). A fused pair is just a changed object with differing names, feeding the normal in-place-vs-rebuild decision; colliding rename sets (swaps/chains) force rebuild — no temp-name dance. Conflicting directives (same live path twice, same declared target twice, rename+drop on one object) throw as malformed input, validated structurally before planning. The Plan echoes the full input directive set in :directives alongside :unused-directives. Glossary: Directive added; Plan entry amended.

**2026-08-06T21:01:58.976882437Z**

Directives = conditional per-object intent maps consumed by plan (open kind set; launch: rename-table/rename-column/drop-table/drop-column lifting :destructive-drop); name-only binding anchored live-side, no wildcards; unmatched = inert-but-reported in :unused-directives, conflicts throw; fused rename pairs feed normal in-place-vs-rebuild (collisions force rebuild); Plan echoes :directives. ADR 0009.
