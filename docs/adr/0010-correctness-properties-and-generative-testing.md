# Correctness properties and the generative testing strategy

The spec commits to six locked correctness properties, tested generatively with
real SQLite in the loop. The first two — **no-op** and **round-trip** — are
inherited verbatim from ADR 0003. The rest supersede ADR 0003's provisional
convergence property, which predated the refusal taxonomy:

1. **Residual convergence**: for *any* live file, Declaration, Capabilities, and
   Directives — after Apply (with partial convergence opted in where needed) —
   `diff(introspect(live), target)` equals **exactly the Plan's unhandled
   entries**: no more, no less. The plan converges everything it claimed and
   touches nothing it refused. Full equivalence on empty-unhandled plans falls
   out as a corollary, as does the fixpoint: re-planning after Apply yields a
   Plan with zero Ops and the same unhandled entries.
2. **Data preservation**: for every column that survives (pairing live →
   declared by name, after rename Directives), the post-Apply rows at those
   columns equal the pre-Apply rows, compared as multisets. Data disappears only
   where a `:drop-table` / `:drop-column` Directive said so. Two physical
   identities join the promise:
   - **Rowid stability**: when both sides are rowid tables, Rebuild copies
     `rowid` explicitly — row identities survive, not just values. Explicitly
     void when either side is WITHOUT ROWID.
   - **AUTOINCREMENT continuity**: when the table is AUTOINCREMENT on both
     sides, Rebuild restores the `sqlite_sequence` counter — the next inserted
     id is greater than any id ever issued.
3. **Gate bidirectionality**, scoped to the inventoried Gate codes: if Check
   passes every Gate, Apply does not fail for data-dependent reasons (on an
   otherwise-unchanged database); if a Gate fails, running Apply anyway would
   abort. Gates are a predicate, not advice.
4. **Plan determinism**: same (Diff, Capabilities, Directives) ⇒ byte-identical
   Plan (`pr-str` equality), including every Op's `:sql` and every Gate's SQL.
   Check report sample order is explicitly outside the property.
5. **Version honesty**: a Plan compiled for target version V executes
   successfully on version V — tested by actually running it there.

The generative approach the spec commits to:

- **Four generators**: (1) a **schema generator** producing EDN Schema values
  (structured, shrinkable), compiled to SQL — reaching *beyond* the sugar's
  subset via the raw escape hatches so opaque-expression machinery is
  generated, not just the easy subset; (2) a **mutation generator** perturbing a
  generated schema into a nearby target (add/drop/rename column, retype,
  reorder, toggle constraints/STRICT/WITHOUT ROWID) so (live, target) pairs have
  dense diffs and renames arrive with their matching Directive; (3) a **row
  generator** populating live files with rows both conforming and violating the
  target, driving the data-preservation and gate properties; (4) a **curated
  corpus** of nasty schemas (quoted/keyword identifiers, generated columns,
  partial indexes, virtual tables) as deterministic regression seeds.
- **Real SQLite always**: every property run uses real in-memory databases and
  real Apply; no mocked engine.
- **Tooling**: properties are stated tool-agnostically; `org.clojure/test.check`
  is named as the reference tooling, revisitable by the build effort.
- **CI version matrix**: two points — a floor version and the latest
  sqlite-jdbc — with the version-honesty property run on each. The floor is set
  pragmatically to the oldest sqlite-jdbc conveniently pinnable in CI; the
  specific number is a build-effort detail, not a spec commitment.

## Considered Options

- **Weak convergence only (empty-unhandled ⇒ equivalent)** — rejected: leaves
  what a partial plan does unspecified; the residual statement is strictly
  stronger, equally testable, and turns "unhandled" into a precise promise.
- **Schema convergence without a data property** — rejected: convergence alone
  is satisfiable by a plan that truncates every rebuilt table; the data
  property is what makes Rebuild trustworthy.
- **Bare rowids documented as unstable across rebuilds** — rejected: users rely
  on rowids (`last_insert_rowid`, FTS external-content tables); copying `rowid`
  is one extra column in the copy statement.
- **Generating raw SQL text** — rejected: shrinks terribly; structured Schema
  values shrink toward minimal counterexamples.
- **Generators confined to the sugar's subset** — rejected: the round-trip
  property would only ever be tested on the easy subset, leaving the riskiest
  code (opaque expressions, escape hatches) ungenerated.
- **Mocked SQLite engine** — rejected: the pristine mechanism already puts real
  SQLite in the core path; a mock tests a model of SQLite, not SQLite.

## Consequences

- Rebuild's copy statement grows two obligations beyond copy-by-name: explicit
  `rowid` when both sides are rowid tables, and `sqlite_sequence` restoration
  for AUTOINCREMENT-on-both-sides tables.
- The gate inventory carries a tested contract in both directions; adding a
  gate code means adding generators that violate it.
- Plan determinism makes plans reviewable artifacts: golden-file tests and
  code-review diffs of plans are trivial.
- The row generator and multiset comparison define data preservation without
  reference to ordering — no reliance on unspecified row order.
