# The Plan is an ordered, self-contained, capability-aware EDN value

> Amended by ADR 0017: `plan` takes both Snapshots positionally, ahead of the Diff.

A Plan is a thin wrapper map produced by the pure function
`plan(live, declared, diff, opts)` — whose information basis is the Diff's
entries as work items plus the two Snapshots it was computed from, passed as
arguments because the Diff embeds only their provenance. The wrapper
holds exactly three things: an ordered `:ops` vector, both sides' Snapshot
provenance (so a Plan states what live state it was computed against), and the
capabilities it was planned for. Capabilities (target SQLite version, defaulting
to latest) are an explicit planner input: the same Diff legitimately produces
different Plans for different targets.

An **Op** is one logical schema change: a map with a `:kind` (logical
vocabulary: `:create-table`, `:drop-index`, `:add-column`, `:rebuild-table`, …),
the path addressing its object, a `:serves` set of Diff entry paths it realizes,
and a `:sql` vector of the exact statements it executes — compiled at plan time,
never at execution time. The Plan is the reviewable artifact: "exactly these
statements will run."

The 12-step rebuild is **one composite `:rebuild-table` op** per rebuilt table,
its fixed internal statement order locked inside it (create-new →
INSERT...SELECT → drop-old → rename-new — never rename-first). Selection rule
per table: if every change touching the table is achievable in-place under the
given capabilities, emit in-place ops; otherwise collapse the table's entire
change set into one rebuild. Never mix in-place and rebuild for one table. The
planner may exploit plan ordering to make an in-place form legal (drop the
covering index before the drop-column), verifying legality against the
accumulated intermediate state.

Ordering is **baked into list position** — the `:ops` sequence is the execution
order; dependency edges are planner-internal and discarded. The locked phase
order: (1) drop removed indexes/triggers/views, (2) drop removed tables, (3)
per-table change ops, tables name-sorted, (4) create added tables, name-sorted
(safe: SQLite resolves FK references lazily at DML time), (5) create added
indexes/triggers/views. Deterministic: identical inputs yield byte-identical
Plans.

Transaction and FK framing (`PRAGMA foreign_keys=OFF` → BEGIN → ops →
`foreign_key_check` → COMMIT → FK on) is **executor-owned**, signaled by
plan-level metadata, never materialized as ops. **Apply** is a dumb fold:
executes each op's `:sql` in plan order inside the frame, never reorders,
rewrites, or skips; stops at the first error and rolls back; all-or-nothing by
default (variants belong to execution policies). Before executing, Apply
re-checks the live file's `schema_version` fingerprint against the Plan's
embedded source Snapshot provenance and refuses to run against a drifted database.

Completeness is a locked invariant: every Diff entry is either served by ≥1 op
or listed in the wrapper's **unhandled-entries collection** with a reason
(reason vocabulary belongs to the refusal taxonomy). Served ∪ unhandled = all
entries, checkable mechanically. Empty ops **and** empty unhandled ⇔ nothing to
do; empty ops with non-empty unhandled is an honest "can't get you there" and
must not look like success.

The Plan shares the Diff's contracts: plain EDN all the way down, no records,
`pr-str`/`read-string` round-trip promised, nothing executable or
connection-bound in the value.

## Considered Options

- **Planner consumes the Diff alone / the Snapshots alone** — rejected: entries
  are the work items but rebuilds need whole-table shapes and dependents;
  pretending entries alone suffice hides the real information basis.
- **One op = one SQL statement** — rejected: the rebuild smears into ~8
  semantically coupled statements that are meaningless and dangerous
  individually (the rename-first corruption trap lives between them).
- **Ops as pure intent, SQL generated at execution time** — rejected: the plan
  is the reviewable product; an executor that writes SQL is an executor you
  can't audit. Version adaptation is a planning input, not executor cleverness.
- **Explicit dependency edges, executor topo-sorts** — rejected: a second
  interpretation layer and a smarter executor for no identified consumer;
  positional order keeps the executor a fold and determinism trivial.
- **Pragmas/BEGIN/COMMIT as ops** — rejected: a consumer could reorder or drop
  half a frame; framing is *how* a plan is applied, not *what* changes.
- **Timestamps/ids in the wrapper** — rejected: they break the byte-identical
  determinism property shared with the Diff.

## Consequences

- The refusal-taxonomy ticket fills the unhandled-entry reason vocabulary; the
  seam (wrapper collection + completeness property) is fixed here.
- The execution-policies ticket may add Apply variants (stage-then-swap,
  non-atomic); the default contract is fixed here.
- The gates ticket inherits the rebuild op as the unit that data-dependent
  legality attaches to.
- Determinism of op order is a public contract, property-tested alongside the
  Diff's no-op/round-trip/convergence properties.
- Glossary gains Plan, Op, Rebuild, Apply.
