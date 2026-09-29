# Phase 1 takes a changed view's readers with it

Amends ADR 0006 (what phases 1 and 5 hold, and what an Op's `:serves`
means).

A changed view is dropped in phase 1 and created again in phase 5.
SQLite checks the whole schema again during `ALTER TABLE … RENAME`,
`RENAME COLUMN` and `DROP COLUMN`, so a surviving view or trigger that
reads the missing view fails any Rebuild, `:rename-table`,
`:rename-column` or `:drop-column` in phase 3, and Apply rolls back. A
Rebuild that creates again a trigger of its table reading the missing
view plants the same failure for every later Rebuild.

Between phase 1 and phase 5, no view or trigger reads a view that is
missing. Phase 1 therefore also drops a changed view's **readers**:
every surviving view that mentions it directly or through another
reader, with its triggers, and every surviving trigger that mentions it
or a reader. Phase 5 creates them again from their live stored SQL. A
trigger of a table that a `:rename-table` Directive renames is no
reader: the Diff pairs it with the renamed table as a changed trigger,
and the rename's own Ops drop it and create its declared text. A
Rebuild drops in phase 1 each live trigger of its table that mentions a
changed view or a reader, since that trigger would otherwise stand
until the Rebuild drops the table, and defers to phase 5 each such
declared trigger. The mention test is the lexical one the Rebuild's
dependents use; a false mention only adds a drop and a create.

Each reader gets its own drop and create Op, on the reader's own path,
and each serves the entries of the changed views the reader reaches.
`:serves` therefore means "the entries this Op exists to realize", not
"this Op's own entry". Readers move in every Plan with a changed view,
whether or not a phase-3 op checks the schema again: the rule stays
independent of phase-3 routing, at the cost of one extra pair per
reader in a Plan that only changes a view.

## Considered Options

- **Each op that checks the schema again wraps the readers around its
  own statement** — rejected: four op kinds gain dependents machinery,
  a reader is dropped once per such op, and `:drop-column` stops being
  one statement.
- **`PRAGMA legacy_alter_table=ON` in the Frame** — rejected: it does
  not cover DROP COLUMN, changes how a rename rewrites references, and
  leaves the schema dangling in the middle of Apply.
- **Drop changed views after phase 3** — rejected: ADR 0006's phase
  order drops them first so that a drop-column is legal against what
  survives.
- **Fold the readers into the changed view's own Ops** — rejected: a
  reader of two changed views has no owner.
- **Reader Ops with an empty `:serves`** — rejected: an Op that
  realizes no entry is new to ADR 0006 and hides why it runs.
- **Move readers only when a phase-3 op checks the schema again** —
  rejected: phase 1 would depend on phase-3 routing.

## Consequences

- Readers leave the surviving dependents, so a Rebuild never drops them
  a second time and drop-column legality no longer counts them.
- A removed view has no declared reader, since `declared-snapshot`
  refuses a view over a missing view. Kept readers (ADR 0023) are the
  keep build's to place under this rule.
