# The Diff is the drift surface, not a plan-sufficient artifact; `plan` takes both Snapshots positionally

Amends ADR 0013 (`plan`'s signature and opts), ADR 0006 (`plan(diff, opts)`),
and ADR 0004 (the scope of the Diff's travel-alone promise).

Code review of the v0.1.0 epic found a spec-internal tension. ADR 0013 locks
`plan` opts as `{:capabilities … :directives […]}` and ADR 0006 says the
planner's information basis is "the Diff's entries as work items plus both
Snapshots for context (the Diff wrapper already embeds both sides' metadata)"
— but the Diff embeds only Snapshot *provenance*, not the Snapshots. Planning a
changed table needs the whole live and declared table values, and the
implementation had quietly answered with two undocumented opts,
`:live-snapshot`/`:declared-snapshot`.

**The two Snapshots become positional arguments:**

    plan [live declared diff]
    plan [live declared diff opts]

`opts` returns to exactly the locked `{:capabilities :directives}`. The
one-argument arity is removed; nothing is published (ADR 0014:
`0.1.0-SNAPSHOT`), so it carries no compatibility debt. Live before declared
mirrors `diff [live declared]` and the pipeline's live-first spine.

**The broad thesis: the Diff is the drift surface, not a plan-sufficient
artifact.** ADR 0004's travel-alone promise exists so a serialized Diff renders
in CI without either Snapshot — it covers the drift surfaces `drift?`,
`by-object`, and `drift-report`. It was never a promise that a Diff arriving
over a wire could be planned. The artifact that travels to the apply side is
the **Plan**, and the Plan is already self-contained EDN. Growing the Diff to
embed both Snapshots is closed off for two independent reasons:

1. **It would break Snapshot equality or lose the stored SQL.** Snapshot
   provenance and each object's stored CREATE sql live in Clojure metadata, and
   `diff` deliberately lifts that sql into entry *values* so the Diff survives
   `pr-str`/`read-string`. Embedding Snapshots would either drop the metadata
   on round-trip — yielding plans with nil recreate SQL — or force provenance
   into values, breaking ADR 0003's "two Snapshots of identical schemas compare
   equal regardless of provenance".
2. **Nothing asked for it.** No surface requires planning from a Diff alone.

**Why positional rather than documented opts.** The obvious alternative was to
document the two opts with the trigger "required whenever the Diff contains a
changed table". That trigger is already wrong: a `:rename-table` directive
fuses a removed/added pair and routes through the same changed-table context,
so a Diff with **no** changed entry can require the Snapshots too. Position
states the requirement by construction, instead of by a predicate that has to
grow with every new directive.

**Guards.** `plan` validates at its entry that both Snapshots are present and
Snapshot-shaped — one `:malformed-input` before any planning, replacing a throw
raised mid-plan from deep inside changed-table routing. That deep throw is
demoted to `:internal`: past the entry guard, a missing table there means a
Diff/Snapshot mismatch, not bad input.

`plan` also checks provenance, the pure-side sibling of the fingerprint probe
ADR 0016 gave `apply!`: the supplied Snapshots are compared against the Diff's
`:live-metadata`/`:declared-metadata`, `:malformed-input` on mismatch. Full-map
`=` on the live side; **`:sqlite-version` only on the declared side**, because a
declared Snapshot's `schema_version` is the mutation counter of a throwaway
pristine database — a false-positive surface carrying no information. A
live/declared mixup still fails, via the live-side comparison.

`schema_version` is a mutation counter, not a content hash: unequal proves
staleness, equal does not prove identity. Every check built on it — here and in
ADR 0016's drift probe — is a cheap staleness detector, never a proof that two
schemas match.

**The capabilities default reads the live Snapshot**, `(:sqlite-version (meta
live))`, rather than the Diff's copy: one source for the live side's identity,
now that the live side is an argument.

## Considered Options

- **Grow the Diff to embed both Snapshots** — rejected for the two reasons
  above; it also re-opens ADR 0004's "minimal entries that reference the
  Snapshots" question from the wrong end.
- **Document `:live-snapshot`/`:declared-snapshot` as opts** — rejected:
  required context in an optional-looking bag, gated by a predicate that is
  already wrong for `:rename-table` and would drift further with each
  directive.
- **Keep the mid-plan throw as the only guard** — rejected: the error names an
  opts key from deep inside table routing, after arbitrary planning work, and
  only for the diffs that happen to reach that path.
- **Compare full metadata on both sides** — rejected: the declared side's
  `schema_version` counts statements applied to a pristine database; two
  identical declarations realized through different statement splits differ
  there with no semantic difference at all.
- **No provenance check** — rejected: planning a Diff against Snapshots it was
  not computed from silently produces a Plan whose ops describe tables that
  never existed together.

## Consequences

- `plan`'s one-argument arity is gone; every call site passes both Snapshots.
  Docstrings (core and impl), README, and the recipes reflect the new shape.
- The Diff keeps its shape, its round-trip contract, and its travel-alone
  promise — now scoped, in ADR 0004, to the drift surfaces.
- A `:rename-table` directive on a Diff with no changed entry plans
  successfully; before this, it threw.
- Three input-validation branches join the example-test suite (missing context,
  live-provenance mismatch, declared `:sqlite-version` mismatch). ADR 0010's
  generative budget stays on no-op, round-trip, and convergence.
- The glossary splits **Snapshot metadata** into **Snapshot provenance** and
  **Stored CREATE sql**, which were only ever one entry because nothing had
  needed to talk about them separately.
