# SQL text is the canonical Declaration; EDN is sugar that compiles to it

The canonical target-schema input is SQL text — a single string or a seq of CREATE
statements — executed into the pristine database and introspected into a Snapshot. An
EDN Schema value ships as a clearly-separated sugar layer that compiles to SQL text;
the core only ever sees SQL. Multi-statement text is split by SQLite's own prepare
loop, never by string manipulation, and SQLite itself is the validator: execution
errors are surfaced with which-statement context, with no lint pass in front.

## Considered Options

- **EDN-canonical data DSL** — rejected: the DSL would have to chase SQLite's whole
  grammar (CHECKs, generated columns, expression indexes) or grow escape hatches that
  make SQL the real canonical form anyway. Prior art shows tools that hand-own schema
  semantics pay for it; tools that let the database interpret the declaration stay
  cheap.
- **Honeysql-style statement vectors as canonical** — rejected: it is SQL with
  parentheses; the user writes statements, not state, and the compiler buys nothing
  the pristine database doesn't already provide.
- **"Anything executable" protocol as canonical** — rejected: maximal openness but a
  fuzzy front door; the same openness is preserved anyway, since any sugar layer
  (ours or a third party's) targets SQL text.

## Consequences

- A Declaration is pure state: zero migration intent — no rename markers, no
  data-movement hints, no directive comments. All intent lives in the directives
  layer. The same Declaration means the same thing when migrating and when
  bootstrapping fresh.
- Refuse loudly outside Snapshot scope: if executing the Declaration does anything
  introspection can't capture — DML, ATTACH, PRAGMA side effects, temp objects — the
  library errors, pointing at the offending statement. No silent skips.
- The Schema value is one data value mirroring the Snapshot's nesting: tables/views
  as a vector (declaration order preserved; maps lose order), columns ordered,
  indexes/triggers nested under their table. Raw statements slot in via a top-level
  `:raw` vector; expression positions accept `[:raw "…"]`.
- Sugar-layer identifiers are keywords or strings, compiled to quoted identifiers
  spelled verbatim — no dash-to-underscore munging, no case folding. Munging is a
  silent transformation of the user's schema.
- Sugar-layer column types are keywords for the STRICT-legal set (compiled to
  canonical uppercase, unknown keywords rejected) or any string passed through
  verbatim as the unchecked escape hatch.
- The sugar layer is deliberately a subset (tables, columns, common constraints,
  indexes; views/triggers raw-only initially): the escape hatch means it never
  blocks anyone, so it never needs to chase the grammar.
