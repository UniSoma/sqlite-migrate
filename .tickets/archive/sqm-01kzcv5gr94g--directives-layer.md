---
id: sqm-01kzcv5gr94g
title: Directives layer
status: closed
type: task
priority: 2
mode: afk
created: '2026-08-07T00:49:59.047780891Z'
updated: '2026-08-07T15:28:57.808490233Z'
closed: '2026-08-07T15:28:57.808490233Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: Four launch kinds validated; conflicting sets throw before planning
  done: true
- title: Rename matching all-or-nothing; unmatched directives inert but reported; Plan echoes directives
  done: true
- title: Fused renames feed in-place-vs-rebuild; collisions force rebuild with correct name mapping
  done: true
- title: 'Demo: rename preserves data; drops require explicit authorization'
  done: true
deps:
- sqm-01kzcv5gne5m
tags:
- phase-3
---

## Description

The intent channel: explicit, conditional, per-object Directives that lift :needs-intent refusals. As a developer a rename is a rename (data survives), a drop only happens because I said so, and a typo'd directive is loud twice.

Scope: the four launch kinds (:rename-table, :rename-column, :drop-table, :drop-column) as plain maps; structural validation of the directive set before planning — same live path claimed twice, same declared target claimed twice, or rename-vs-drop conflicts throw as malformed input; name-only binding (live-side anchoring, declared-side :to, identifiers normalized exactly as the equivalence relation normalizes, no wildcards, no allow-all-drops); all-or-nothing rename matching with half-matches inert; unmatched directives reported in :unused-directives in input order, never an error, never consulted by apply!; the Plan echoes the full input directive set; a fused rename pair becomes a changed object feeding the normal in-place-vs-rebuild decision, with colliding rename sets (swaps, chains) forced onto the rebuild path where the copy maps old names to new.

Demo: a populated table renames with data intact; an unauthorized drop blocks apply! by default and proceeds once directed. ADRs 0007, 0009.

## Notes

**2026-08-07T15:28:57.808490233Z**

Shipped in 0781d00. Four launch kinds validated with conflicting sets throwing :malformed-input before planning; all-or-nothing rename matching, unmatched directives inert but reported in :unused-directives, Plan echoes :directives; fused renames feed in-place-vs-rebuild with collisions forced onto rebuild and the copy mapping old names to new; demos pass — rename preserves data, drops require explicit authorization.
