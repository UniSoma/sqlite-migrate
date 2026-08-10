---
id: sqm-01kzmhxtcwmj
title: plan takes both Snapshots positionally; the Diff stays lean (ADR 0017)
status: in_progress
type: task
priority: 2
mode: afk
created: '2026-08-10T00:42:25.051829192Z'
updated: '2026-08-10T16:27:15.998620648Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: ADR 0017 is recorded; ADRs 0013, 0006, and 0004 are amended to match
  done: false
- title: plan is [live declared diff] / [live declared diff opts]; opts is exactly {:capabilities :directives} and the 1-arity is gone
  done: false
- title: Missing context, live-provenance mismatch, and declared :sqlite-version mismatch each throw :malformed-input at plan's entry guard
  done: false
- title: The capabilities default reads the live Snapshot, not the Diff
  done: false
- title: CONTEXT.md splits Snapshot provenance from Stored CREATE sql; Diff, Plan, and Apply report entries updated
  done: false
- title: README.md, doc/recipes.md, and both plan docstrings show the new signature
  done: false
- title: A :rename-table directive on a Diff with no changed entry plans successfully
  done: false
- title: bb test passes and clj-kondo --lint src test is clean
  done: false
---

## Description

Code review of the v0.1.0 epic (Spec axis) found a spec-internal tension in
plan's signature. ADR 0013 locks plan opts as `{:capabilities … :directives […]}`
and says "plan is pure: Diff entries are the work items, both embedded Snapshots
the context" — but the Diff (ADR 0004) embeds only Snapshot provenance, not full
Snapshots. The implementation resolved it with undocumented opts
`:live-snapshot`/`:declared-snapshot`.

**Decided (grilling session, 2026-08-10): Option B, taken further than the
ticket first framed it — the two Snapshots become positional arguments, not
documented opts.**

    plan [live declared diff]
    plan [live declared diff opts]

`opts` returns to exactly the locked `{:capabilities :directives}`. The 1-arity
is removed.

Why Option A (grow the Diff to embed both Snapshots) is closed off — two
independent reasons:

1. Snapshot provenance and each object's stored CREATE sql live in Clojure
   metadata (`core.clj:109,133,169,176,183`), and `diff.clj:66` deliberately
   lifts the sql into entry values so the Diff survives `pr-str`/`read-string`.
   Embedding Snapshots would either drop that metadata on round-trip (plans with
   nil recreate SQL) or force provenance into values — breaking "two Snapshots of
   identical schemas compare equal regardless of provenance" (ADR 0003).
2. Planning was never required to work from a Diff that arrived over a wire. The
   Diff's travel-alone promise (ADR 0004) covers the drift surfaces — `drift?`,
   `by-object`, `drift-report`. The Plan is the artifact that travels to the
   apply side, and it is already self-contained EDN.

Why positional rather than documented opts: the stated trigger ("required
whenever the Diff contains a changed table") is already wrong. A `:rename-table`
directive fuses a removed/added pair and routes through `plan-fused-table`
(`plan.clj:1664`) → `table-context` (`1411-1412`), so a Diff with no changed
entry still throws `:malformed-input`. Position states the requirement by
construction instead of by a predicate that grows with each directive.

Nothing is published (ADR 0014: `0.1.0-SNAPSHOT`), so removing the 1-arity
carries no compatibility debt.

## Design

### Signature and guards

- `plan [live declared diff]` / `[live declared diff opts]`; `opts` is exactly
  `{:capabilities :directives}`. Live before declared, mirroring `diff [live declared]`
  and the pipeline's live-first spine.
- **Entry guard**: validate at `plan`'s entry that both Snapshots are present and
  Snapshot-shaped; one `:malformed-input` before any planning. `table-context`'s
  existing throw is demoted to `:internal` — after the entry guard, a missing table
  there means a Diff/Snapshot mismatch, not bad input.
- **Provenance check**: compare the supplied Snapshots against the Diff's
  `:live-metadata`/`:declared-metadata`. Full-map `=` on the live side; `:sqlite-version`
  only on the declared side — its `schema_version` is the counter of a throwaway pristine
  database, so comparing it is a false-positive surface with no information in it. A
  live/declared mixup still fails via the live-side comparison. `:malformed-input` on
  mismatch. Sibling of the fingerprint probe ADR 0016 gave `apply!`.
- **Capabilities default** reads `(:sqlite-version (meta live))` instead of
  `(get-in diff [:live-metadata :sqlite-version])` — one source for the live side's
  identity.

### ADRs

- **New ADR 0017**, broad thesis: *the Diff is the drift surface, not a plan-sufficient
  artifact* — the signature follows from it. Record the metadata/equality reasoning and
  state that `schema_version` is a mutation counter, not a content hash (unequal proves
  staleness; equal does not prove identity).
- **ADR 0013**: `> Amended by ADR 0017` banner alongside the 0016 one; replace the `plan`
  row in the inventory table, the "plan opts are `{:capabilities … :directives …}`"
  sentence, and the Consequences bullet "plan must read the live Snapshot metadata
  embedded in the Diff for its capabilities default".
- **ADR 0006** line 3: `plan(diff, opts)` → the new signature.
- **ADR 0004**: leave the "Minimal entries that reference the Snapshots" rejection
  standing (entries still embed verbatim sub-values) but scope its travel-alone claim to
  the drift surfaces — as written it reads as covering planning, which is what kept this
  tension invisible.

### CONTEXT.md (glossary only, no implementation detail)

1. Replace **Snapshot metadata** (82-85) with two entries — **Snapshot provenance**
   (SQLite version + source `schema_version` fingerprint; travels with Diff and Plan;
   mutation counter, not a content hash; equality-neutral; `_Avoid_: Snapshot metadata`)
   and **Stored CREATE sql** (per-object verbatim CREATE, equality-neutral, carried inside
   a Diff entry's both-sides sub-values).
2. **Diff** (69): "both sides' Snapshot metadata" → "both sides' Snapshot provenance".
3. **Plan** (92-96): "produced by planning a Diff under given capabilities and directives"
   → "produced by planning a Diff against the two Snapshots it was computed from, under
   given capabilities and directives"; same metadata → provenance swap.
4. **Apply report** (123): metadata → provenance.

No collective term for the Snapshot pair — it is an argument list, not a domain concept.

### Docs and call sites

`README.md:27`; `doc/recipes.md:58,104`; both `plan` docstrings (`core.clj:295`,
`impl/plan.clj:1586`). Call sites planning without the Snapshots today:
`walking_skeleton_test.clj:21,42,50`, `plan_test.clj:64`.

### Tests (example tests only — no generative property)

These are input-validation branches with no interesting input space; ADR 0010's generative
budget is for no-op, round-trip, and convergence.

- Entry guard: missing context throws `:malformed-input` regardless of diff content.
- Live-provenance mismatch throws.
- Declared `:sqlite-version` mismatch throws.
- Rewrite `plan_test.clj:412` from "throws mid-plan for a changed table" to "throws at the
  entry guard".
- New: a `:rename-table` directive on a Diff with no changed entry plans successfully
  (throws today).

### Commits

Follow the ADR 0016 pattern (4582bb9 / 287dabe): "Record ADR 0017…" then
"Implement ADR 0017…". `bb test` and `clj-kondo --lint src test` before each.
