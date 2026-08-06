# Three diff-as-product surfaces: drift?, drift-report, by-object — and nothing else

The library ships exactly three functions over the Diff, all pure: `drift?`, a
predicate on a Diff that is true when its entries are non-empty; `drift-report`, a
single-arity, deterministic `Diff → string` plain-text renderer; and `by-object`, the
one nesting view — flat entries regrouped under the object they belong to, a `changed`
table's table-level entry reunited with its fine-grained children. CI drift checking
is a documented recipe (introspect live, diff against the Declaration, fail on
`drift?`, archive the `pr-str` diff as artifact), not a bundled function. Consumer
filtering is a documented pattern over plain-EDN entries, not API.

`drift-report` is explicitly presentation-only: its wording and layout are not a parse
contract — the EDN Diff is the machine surface. It renders from a deserialized Diff
alone. For `changed` entries it prints each differing fact with both sides' values;
for `added`/`removed` objects it prints the whole verbatim CREATE sql. To make that
possible, the Snapshot shape is amended (against ADR 0001): each object carries its
stored `CREATE` sql as **equality-neutral per-object provenance** — the live side's
from the file's `sqlite_master`, the declared side's from the pristine database — and
it rides along into Diff entry sub-values.

## Considered Options

- **A bundled `check` function for CI** — rejected: it hides the two Snapshots CI
  usually also wants to log; composition is the public-API ticket's story.
- **Multi-format / pluggable rendering, or options on `drift-report`** — rejected:
  knobs on a presentation-only string invite parse-and-depend behavior the contract
  disclaims; consumers wanting different output have the EDN.
- **A family of grouping views (by-kind, by-type)** — rejected: trivial `group-by`
  one-liners the spec shows as idioms; only object-nesting is genuinely fiddly.
- **Filter helpers (`ignore-objects`, predicate combinators)** — rejected: the helper
  vocabulary grows without bound and re-creates equivalence knobs by the back door;
  flat plain-EDN entries make ordinary seq functions the filtering API.
- **Rendering one-sided objects as their EDN sub-value** — rejected: an added table
  reads as a data dump, not DDL.
- **Regenerating CREATE sql from the EDN sub-value at render time** — rejected: the
  library would emit SQL the Snapshot never held, against the verbatim-truth grain.

## Consequences

- ADR 0001's Snapshot shape gains one equality-neutral field per object (its stored
  CREATE sql); ADR 0003 is untouched because provenance never enters equivalence.
- A filtered entry seq is consumer data, not "the" Diff of two Snapshots;
  `equivalent?`'s emptiness definition applies only to unfiltered diffs.
- `drift-report` output order is the diff's locked entry order, so reports are
  deterministic — but only the EDN, never the text, is a stable machine contract.
- Namespace placement of the three functions belongs to the public-API-surface
  decision.
