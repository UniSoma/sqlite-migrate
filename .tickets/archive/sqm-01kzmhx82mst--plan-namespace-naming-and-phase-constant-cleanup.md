---
id: sqm-01kzmhx82mst
title: Plan namespace naming and phase-constant cleanup
status: closed
type: task
priority: 2
mode: afk
created: '2026-08-10T00:42:06.289654142Z'
updated: '2026-08-10T01:16:41.618084767Z'
closed: '2026-08-10T01:16:41.618084767Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: Plan phase sort keys use named constants instead of bare numeric literals and sentinels
  done: true
- title: The local version-floor helper in route-added-column no longer uses the name gate
  done: true
- title: check-gates naming no longer reads as related to the :check-gates? apply option
  done: true
- title: bb test passes (Plan determinism included) and clj-kondo --lint src test is clean
  done: true
deps:
- sqm-01kzmhwy23hn
tags:
- phase-2
---

## Description

Code review of the v0.1.0 epic (Standards axis, judgement calls) flagged two readability problems in the plan impl namespace:

1. Primitive Obsession — plan phase numbers ride as bare literals in the `:order` sort vectors (`[3 tfold]`, `[1 sub …]`, `-1` sentinels, the pad in `op`). The ns docstring documents the phase order, but named phase constants would make the sort keys honest.
2. Mysterious Name / domain-term collision — the local `gate` fn in `route-added-column` is a *version* gate returning `[minimum what]`, colliding with the CONTEXT.md domain term Gate (a data precondition); nearby, `check-gates` (Gates for CHECK constraints) sits confusingly close to the unrelated `:check-gates?` apply option.

Introduce named phase constants and rename the colliding locals so plan code speaks the glossary vocabulary. Pure refactor: Plan output byte-identical (determinism property pins it).

Blocked by the rebuild-table-op extraction ticket — both reshape the same namespace; land the structural change first.

## Notes

**2026-08-10T01:07:14.411390233Z**

Naming decisions agreed (afk-ready):
1. Phase constants: flat private defs above the op helper, two groups. Top-level: phase-drop-secondary=1, phase-drop-tables=2, phase-change-tables=3, phase-create-tables=4, phase-create-secondary=5. Phase-3 sub-phases: sub-rename-table=-1, sub-drop-check=0, sub-drop-column=1, sub-alter-column=2 (NOT NULL alters and column renames share the slot, per the ns docstring), sub-add-column=3, sub-add-check=4. Flat defs, not a lookup map.
2. Sentinels named by meaning, not one shared sort-first: line 1145 rename-table-op -1 -> sub-rename-table (see group above); line 199 route-added-column -1 -> unpatched-position (placeholder overwritten by declared-position). The -1/0/1 inside check-sort-key (line 271) stays local — a docstring/comment on that fn suffices (optionally named-check=0 / anon-check=1).
3. Renames: local gate in route-added-column -> version-floor (returns [minimum what]); check-gates -> check-constraint-gates, keeping the <subject>-gates sibling pattern (column-gates, index-gates, table-gates, unique-gates, foreign-key-gates).
4. The zero-pad in op (line 163) becomes order-pad=0, referenced in op's docstring together with the fact that -1 sentinels intentionally sort before it.
Pure refactor: Plan output byte-identical; determinism property + bb test + clj-kondo verify.

**2026-08-10T01:16:41.618084767Z**

Shipped in adb7d22. Flat private phase constants above op (phase-drop-secondary=1..phase-create-secondary=5) and phase-3 sub-phase constants (sub-rename-table=-1..sub-add-check=4, alters and renames sharing sub-alter-column); add-column placeholder named unpatched-position, op pad named order-pad with the sort-before-pad sentinel fact in op's docstring; check-sort-key internals stayed local with a new docstring. Renamed route-added-column's local gate to version-floor and check-gates to check-constraint-gates. All op call-site key values verified identical by code review (Spec axis, all 11 sites + both secondary helpers); bb test 144/765 green incl. plan-determinism-property, clj-kondo clean. Review judgement calls left open: the recurring [phase-change-tables tfold sub-*] key shape could take a helper; 'version gate' wording survives in the capabilities section header and supports?/ns docstrings; secondary-op bare sub literals 0/1/2 were out of the agreed scope.
