---
id: sqm-01kzmhxtcwmj
title: 'Decide: plan''s changed-table context vs the locked opts shape'
status: open
type: task
priority: 2
mode: hitl
created: '2026-08-10T00:42:25.051829192Z'
updated: '2026-08-10T00:42:25.051829192Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: 'A decision is recorded (ADR amendment): either the Diff embeds full Snapshots or the extra plan opts become documented contract'
  done: false
- title: plan's docstring and the spec agree on when the 1-arity can plan a changed table
  done: false
- title: Code and tests conform to the decision; bb test passes
  done: false
---

## Description

Code review of the v0.1.0 epic (Spec axis) found a spec-internal tension in plan's signature. ADR 0013 locks plan opts as `{:capabilities … :directives […]}` and says "plan is pure: Diff entries are the work items, both embedded Snapshots the context" — but the Diff model (ADR 0004) embeds only Snapshot *metadata*, not full Snapshots. The implementation resolves this by adding undocumented-in-spec opts `:live-snapshot`/`:declared-snapshot`, required whenever the Diff contains a changed table — meaning the spec's 1-arity `plan [diff]` cannot plan a changed table.

Resolve the tension one way and record it:

- Option A — grow the Diff to embed both full Snapshots (keeps the locked opts shape and the 1-arity promise; enlarges the Diff value and its pr-str round-trip surface).
- Option B — amend ADR 0013 to document `:live-snapshot`/`:declared-snapshot` as required opts for changed-table planning (keeps the Diff lean; narrows the 1-arity's promise).

Human decision required; implementation and ADR amendment follow whichever option is picked.
