# The Diff is a flat, self-contained, intent-free EDN value

A Diff is a thin wrapper map — a flat sequence of Diff entries plus both sides'
Snapshot metadata — produced by `diff(live, declared)`. Each entry is one
self-contained semantic difference: a target-relative change kind (`added` =
declared-only, `removed` = live-only, `changed` = both sides present but not
equivalent), a path addressing the object, both sides' verbatim sub-values, and for
`changed` entries the set of differing fact keywords. Grouped/nested views are
ordinary functions over the entries, not a second canonical shape.

Granularity: an object present on only one side is **one** entry with the whole
verbatim sub-value embedded; fine-grained entries (columns, constraints, indexes,
triggers) exist only inside a `changed` table, alongside at most one table-level
entry for table-scoped facts (STRICT, WITHOUT ROWID, column order, primary key).
Named constraints pair by folded name; unnamed constraints pair by token-equality,
with the unpaired remainder reported as added/removed. Entries are emitted in a
locked deterministic order (fixed kind order, folded-name sort, declared column
order), so identical Snapshot pairs yield byte-identical serialized diffs.

The Diff is plain EDN all the way down — no records or custom types; `pr-str`/
`read-string` round-trip is a promised serialization contract. `equivalent?` over
two Snapshots is defined as emptiness of their Diff, making the no-op property
executable.

## Considered Options

- **Nested tree as the canonical shape** — rejected: every consumer (drift CI,
  renderers, the plan layer) iterates differences rather than walks a tree, and a
  second canonical shape reintroduces the two-models-drift problem rejected in
  ADR 0001. The tree view is a function.
- **Minimal entries that reference the Snapshots** — rejected: the diff is a
  first-class surface that must travel alone (a serialized CI artifact renders
  without either Snapshot). Entries embed both sides' verbatim sub-values.
  Travelling alone means the **drift surfaces** — `drift?`, `by-object`,
  `drift-report` — render from a deserialized Diff and nothing else; it was
  never a claim that a Diff off a wire suffices to plan against, and ADR 0017
  makes that scope explicit by handing `plan` both Snapshots.
- **A `renamed` change kind** — rejected: rename intent is explicit user data
  (directives layer), never inferred; a Diff derives from two intent-free
  Snapshots, so a rename is honestly a removed/added pair until a directive
  reinterprets it.
- **Cost/severity labels on entries (`destructive?`, `rebuild?`)** — rejected:
  cost is a property of the operations chosen, which depend on directives, SQLite
  version, and policies the diff cannot see. The plan links operations back to the
  entries they serve.
- **Embedded dependency indices (inbound FKs, dependents)** — rejected: those are
  facts about a schema, not about a difference; they duplicate Snapshot content
  and go stale. They are plain functions over a Snapshot.
- **Ordinal pairing for unnamed constraints** — rejected: one inserted constraint
  misaligns every later pairing; token-equality pairing reuses the equivalence
  relation's own comparison and degrades to honest add/remove.
- **Per-column entries for an added/removed table** — rejected: inflates entry
  counts ("40 differences" for one new table) and the pairing that makes
  fine-grained entries meaningful doesn't exist on a one-sided object.

## Consequences

- The detail-fact vocabulary is mechanical: one keyword per Snapshot fact the
  equivalence relation compares — no fact can differ without a nameable keyword
  (no-silent-skips holds at the diff level). The enumeration falls out of the
  final Snapshot shape in docs/spec.md.
- Impact analysis (what depends on this table?) legitimately requires a Snapshot;
  the API exposes it as functions over Snapshots, not diff content.
- Determinism of entry order is part of the public contract and must be
  property-tested alongside no-op, round-trip, and convergence.
- The plan layer consumes entries as its input units and may cite entry paths in
  its own structures; nothing in the diff anticipates it.
