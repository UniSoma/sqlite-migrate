# Design

sqlite-migrate is a data-driven Clojure library that introspects a live SQLite
file into a Snapshot, diffs it against a declared target schema, and produces
an executable migration Plan — with the Diff as a first-class public surface.

The whole pipeline is pure values between two thin effectful edges:
`snapshot` and `declared-snapshot` read schemas in; `check` and `apply!` run a
Plan's Gates and Ops against a connection. Everything in between — `diff`,
`plan`, the reports — is a pure function over plain EDN.

## The problem

Clojure teams shipping SQLite evolve their schemas by hand: numbered migration
scripts, ad-hoc `ALTER TABLE` incantations, and tribal knowledge of SQLite's
12-step rebuild procedure. The developer knows what the schema *should* be,
but no tool will tell them how the live file differs from that intent, whether
the difference is safe to converge, or exactly what SQL would run. Existing
migration tools either target other databases, hand-roll fragile SQL parsers,
guess at renames heuristically, or silently destroy data on drift resets.

## The pipeline

```
Declaration ──▶ pristine database ──▶ Snapshot ─┐
                                                ├─▶ Diff ──▶ Plan ──▶ Apply
live file ───────────────────────────▶ Snapshot ─┘
```

1. **One Snapshot shape via pristine introspection.** The Declaration —
   canonically plain SQL text — is executed into a throwaway in-memory
   pristine database and introspected exactly like the live file, so both
   sides of a diff are the same normalized shape and no SQL parser exists
   anywhere in the library. Expression text (CHECK bodies, DEFAULT spellings,
   index WHERE clauses) is opaque: compared token-for-token by SQLite's
   lexical rules, carried verbatim, never parsed into an AST.

2. **One fixed Equivalence relation.** Noise (identifier case and quoting,
   expression whitespace, sibling order) is erased; Semantic differences
   (physical column order, declared type text, constraint names) are kept.
   No configuration knobs. An empty Diff means Equivalent, and vice versa.

3. **The Diff is a product.** Flat, self-contained plain-EDN entries you can
   filter, store, and dispatch on with ordinary seq functions. It survives
   `pr-str`/`read-string` byte-identically, so CI can archive and compare it.
   Exactly three pure functions operate on it: `drift?`, `drift-report`, and
   `by-object`.

4. **The Plan is the review artifact.** Planning a Diff under explicit
   Capabilities (target SQLite version, `:rebuild?`) compiles the exact SQL
   statements that will run, in execution order. Every Diff entry is either
   served by an Op or honestly unhandled with its full Refusal vector:
   `:incapable` (no route exists) or `:needs-intent` (a route exists but
   planning it without explicit intent would risk data).

5. **Intent is explicit, never inferred.** Renames and destructive drops
   require Directives — plain-EDN data supplied to the planner. Directives
   are conditional: unmatched ones are inert and reported as unused, so one
   checked-in directive set serves many databases converging at different
   times. A rename is never guessed from a drop-plus-add.

6. **Data preconditions are Gates, not guesses.** Whether rows conform to a
   new NOT NULL, UNIQUE, or STRICT shape is undecidable at plan time, so the
   planner attaches Gates — plan-compiled SELECTs that sample violating
   rows — and the read-only `check` runs them before any maintenance window.

7. **Apply is strictly atomic.** One transaction inside the executor-owned
   Frame, all-or-nothing, no partial modes. Apply refuses a database whose
   `schema_version` fingerprint no longer matches the Plan's source Snapshot,
   with no override — the remedy is re-diff, re-plan, re-apply. Success
   returns an Apply report; every non-success throws.

8. **One error envelope.** Every exception carries
   `:sqlite-migrate/error` with structured, reused payload values. All
   machine vocabularies — error classes, refusal codes, gate codes, directive
   kinds — are open add-only sets: added, never removed or renamed.

## Public surface

Four namespaces (ADR 0013/0014); everything else lives under
`sqlite-migrate.impl.*` and is not part of the public surface.

| Role | Namespace |
|---|---|
| core pipeline | `sqlite-migrate.core` |
| executor protocol | `sqlite-migrate.protocols` |
| JDBC adapter | `sqlite-migrate.jdbc` |
| EDN schema sugar | `sqlite-migrate.schema` |

Adapter authors implement the two-op `SQLiteExecutor` protocol in
`sqlite-migrate.protocols`: `execute-query [conn sql params]` and
`execute-batch! [conn statements] [conn statements gate-sqls]`, where
`gate-sqls` is a vector of caller-compiled read-only SELECTs the Frame
runs inside its open transaction — all of them, any rows meaning
rollback (ADR 0016). Its docstrings are the normative contract.

## Where the details live

The design is recorded as ADRs in `docs/adr/` (0001–0015) with `CONTEXT.md`
as the glossary of domain terms (Snapshot, Diff, Plan, Op, Rebuild, Gate,
Directive, Refusal, Executor, Frame, …). Where this overview and an ADR
disagree, the ADR governs.
