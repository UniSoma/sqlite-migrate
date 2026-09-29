---
id: sqm-01m3qmcx89yp
title: Enumerate every small schema and change, and require each Plan to apply with residual convergence
status: open
type: feature
priority: 2
mode: hitl
created: '2026-09-29T22:26:07.209842423Z'
updated: '2026-09-29T22:26:07.209842423Z'
acceptance:
- title: 'An enumerator yields every schema up to the settled bound: tables, views over a table or view, and triggers on a table or view whose body reads a view'
  done: false
- title: Each enumerated schema is paired with every assignment of a change per object, and every case Applies against an in-memory database with residual convergence
  done: false
- title: 'A bystander property: adding a view or trigger that reads any object of a case with residual convergence keeps the case applying with residual convergence'
  done: false
- title: 'Checked at the REPL: the enumeration fails against the planners at 7bb9506^, 0ec5e90^ and 3228374^ on the bug each of those commits fixed'
  done: false
- title: The property suite reports how often each (change kind x dependency shape) combination occurs, so a combination at zero is visible
  done: false
- title: bb test passes; clj-kondo --lint src test ci is clean
  done: false
links:
- sqm-01m3pt46c337
- sqm-01m3q53h6bq6
- sqm-01m3q0hqvwa6
- sqm-01m3qdd02a9a
---

## Description

Three bugs in a row were the same shape: sqm-01m3q53h6bq6 (7bb9506), sqm-01m3q0hqvwa6 (0ec5e90) and sqm-01m3qdd02a9a (3228374). Each time a Plan ran a statement that makes SQLite check the whole schema again (ALTER TABLE RENAME, RENAME COLUMN, DROP COLUMN) while some surviving view or trigger read an object that was gone.

The oracle was never the problem: Apply against real SQLite plus residual convergence failed at once whenever the scenario existed. Coverage was. The property-suite generators are templates (one v_main over t1, a hand-listed set of mutations), so they only reach shapes someone already imagined, and each fix added one mutation after the fact. sqm-01m3q0hqvwa6 was found by the first consumer's real file, not the suite. The case sqm-01m3qdd02a9a needed fires about once in 1000 generated scenarios, so it is effectively never reached at SQM_TRIALS=40.

All three bugs needed only two to four objects. Enumerating every small case with real SQLite as the oracle finds this family before it ships, instead of hoping the sampler hits it.

Considered and set aside: core.logic. Enumerating small structures needs only nested for; running the planner backwards would need a relational model of what SQLite checks during ALTER, which is the very knowledge these bugs got wrong.

## Design

Decisions to settle before this is afk:

- The bound: how many objects (about 4 proposed), which object kinds, and whether to reduce cases that differ only by renaming.
- The change kinds per object: unchanged, view text changed, Rebuild, renamed by a :rename-table Directive, column renamed, column dropped, dropped.
- Where it runs: inside bb test (needs a time budget) or as a separate bb task that CI and a pre-ticket check run.
- Whether the oracle also checks the schema after each Op (every existing view and trigger resolves), not only after Apply. First confirm that no correct Plan passes through a state where an object legitimately reads a missing one between phases.
- Whether the first consumer's file joins a corpus that sweeps one change over each table and view in turn.
