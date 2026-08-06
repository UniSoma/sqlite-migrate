# One fixed equivalence relation over Snapshots, normalizing at comparison time

"Same schema" is a single canonical relation over Snapshot values, with no
configuration knobs. Snapshots stay verbatim (ADR 0001); all normalization happens at
comparison time. Identifiers compare ASCII-case-insensitively with quoting ignored;
declared column type text compares case-insensitively with whitespace normalized —
never by affinity. Opaque expressions compare as token sequences via a lightweight
SQLite tokenizer (no grammar, no AST): whitespace and comments vanish, keywords and
bare identifiers case-fold, string/blob literals stay byte-exact. Physical column
order, constraint names, PK/index column order, and table flags (STRICT, WITHOUT
ROWID) are semantic. Order among named siblings (indexes, triggers, views) is noise;
they match by folded name. Engine-internal objects (`sqlite_sequence`,
`sqlite_autoindex_*`, `sqlite_stat*`, virtual-table shadow tables) are outside the
relation entirely.

Three properties are locked as the contract:

1. **No-op**: `diff(a, b)` is empty iff `a ≡ b`.
2. **Round-trip**: introspect a live file, emit its schema as SQL, execute into a
   pristine database, introspect again — the result is equivalent to the original.
3. **Convergence**: after applying the plan for `diff(live, declared)`, the live
   file's Snapshot is equivalent to the declared Snapshot.

## Considered Options

- **Configurable strictness (ignore-column-order, ignore-case knobs)** — rejected:
  knobs make "no-op" ambiguous between two users' configs and multiply the round-trip
  proof surface. The diff is first-class data; a consumer wanting a looser view
  post-filters the diff.
- **Byte-exact expression comparison** — rejected: flags `price > 0` vs `price>0` as
  drift — the noise class users actually hit (whitespace, newlines, comments, keyword
  case) — and pristine-DB execution cannot remove it because SQLite stores CREATE
  text verbatim.
- **Semantic expression comparison (`x>0` ≡ `0<x`)** — rejected: requires the SQL
  parser ADR 0001 forbids. Beyond token identity, differences are honestly reported
  as drift; the tool re-emits the declared spelling and converges.
- **Affinity-based type comparison (`INT` ≡ `INTEGER`)** — rejected: wrong at exactly
  the two places it matters (`INTEGER PRIMARY KEY` is the rowid alias, `INT PRIMARY
  KEY` is not; STRICT tables accept only canonical names), and type-text drift is a
  real signal.
- **Column order as noise** — rejected: order is observable (`SELECT *`, positional
  inserts) and treating it as noise makes convergence a lie — a live file could never
  be brought to the declared order. The rebuild cost of a pure reorder is a
  plan/policy concern, not an equivalence concern.

## Consequences

- Equivalence never mutates or reformats a Snapshot; it is a pure comparison.
  Emission always uses the stored verbatim spelling.
- The tokenizer is lexical only: it must recognize SQLite's token classes (bare and
  quoted identifiers — dequoted and case-folded before comparison — keywords,
  numeric/string/blob literals, operators, comments) and nothing more. It is not a
  parser and must never grow grammar knowledge.
- Honest-drift stance: any difference token comparison cannot erase is reported, even
  when behavior is identical (explicit `COLLATE BINARY` vs absent, `NO ACTION`
  spelled vs omitted where pragmas don't already normalize it, `1.0` vs `1.00`).
  Converging such drift may cost a rebuild; pricing that is the plan layer's job.
- Constraint-name differences are semantic and reported; the diff model may label
  their cost class, but the relation itself has no cosmetic tier.
- The testing strategy inherits three concrete properties to generate against
  (no-op, round-trip, convergence).
