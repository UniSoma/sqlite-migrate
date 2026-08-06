# One Snapshot shape, produced only by introspection, with a pristine database as the normalizer

Both sides of a diff are the same canonical Snapshot value, produced by a single
introspection function: the live side by reading the user's file, the declared side by
executing the target schema into a pristine in-memory SQLite and introspecting that. The
library never parses SQL as its source of truth — pragmas provide structure, and a narrow
extractor lifts only the pragma-invisible facts (CHECK bodies, generated/index/partial
expressions, DEFAULT spellings, constraint names, per-column COLLATE, AUTOINCREMENT, FK
deferrability) out of the stored CREATE text as opaque expression text, stored verbatim.

## Considered Options

- **Two shapes (live catalog + declared model) with a projection** — rejected: migration
  intent (renames, directives) lives in the directives layer, not the schema value, so
  the asymmetry that would justify two shapes doesn't exist, and two models drift.
- **A full CREATE-statement parser** — rejected: prior art (sqldef) shows a hand-rolled
  SQL parser is the largest sustained bug source, and we never need to understand an
  expression — only carry, compare, and re-emit it.
- **Restricting the model to pragma-visible features** — rejected: it would silently
  ignore CHECKs, collations, and generated columns, violating the no-silent-skips rule.

## Consequences

- Snapshots are lossless w.r.t. the file: opaque expressions keep the user's spelling
  and are re-emitted verbatim (reformatting expression-index text changes query plans).
  Normalization is entirely the equivalence relation's job, at comparison time.
- The diff never knows which side came from a file and which from a declaration.
- Introspection reads the `main` schema only, through the library's own bundled SQLite —
  so the introspection surface (table_list, table_xinfo, 3.53 ALTER forms) is always
  available, and Snapshot metadata records provenance for reproducibility, not
  capability gating.
