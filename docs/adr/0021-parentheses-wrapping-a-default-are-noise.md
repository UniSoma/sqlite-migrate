# Parentheses wrapping the whole of a DEFAULT are Noise

Amends ADR 0003 (the Equivalence relation) and ADR 0015 (the DEFAULT
classification behind key gates).

SQLite's column grammar takes a DEFAULT as a literal, a signed number, or
a parenthesized expression, and stores the CREATE text verbatim. A live
file written as `default (0.01)` against a Declaration spelling
`DEFAULT 0.01` therefore differed on every such column under Token
comparison — three tokens against one — and a real schema surfaced a
dozen `:default` facts, every one of them a rebuild's worth of drift over
nothing. Parentheses that wrap the whole value carry no meaning; only the
tokens inside do. Before Token comparison of a DEFAULT, every pair of
parentheses spanning the whole spelling is removed, repeatedly — `(0.01)`,
`((0.01))`, and `0.01` are one spelling. The check is lexical: the first
token is `(`, and its depth-matched `)` is the last token. `(a) + (b)`
keeps its parentheses, since the first one closes early. The plan layer
classifies a DEFAULT through the same normalization, so `(1)` is a
constant every copied row shares rather than an opaque expression it
cannot gate on.

Nothing else moves. Snapshots stay verbatim and emission still uses the
stored spelling. The honest-drift stance stands for what is inside the
parentheses: `(1.0)` against `1.00` is still reported.

## Considered Options

- **Strip outer parentheses from every opaque expression** — rejected:
  only a DEFAULT admits a bare literal as an alternative to the
  parenthesized form. CHECK bodies, generated columns, and index
  expressions always carry their parentheses in the grammar, so a stray
  pair inside them is an authored spelling difference, not a grammatical
  one.
- **Normalize the Snapshot at extraction** — rejected: ADR 0001 keeps
  Snapshots lossless, and ADR 0003 places all normalization at comparison
  time.
