# ADD COLUMN routing follows SQLite's populated-table rule

Amends ADR 0015 (the `:empty-table` Gate and the DEFAULT classification
behind key gates).

Since 3.32.0, SQLite rejects four ADD COLUMN shapes only when the table
holds at least one row: `sqlite3AlterFinishAddColumn` raises them through
`sqlite3ErrorIfNotEmpty`, a `SELECT raise(ABORT, …)` over the table. The
four are a NOT NULL column with no non-NULL default; a CURRENT_TIME,
CURRENT_DATE, or CURRENT_TIMESTAMP DEFAULT; any other DEFAULT that
`sqlite3ValueFromExpr` cannot reduce to a value, that is, any non-literal
expression, parenthesized or not; and a STORED generated column. An empty table
accepts all of them. The planner believed SQLite 3.53 relaxed these forms
and routed them in place from 3.53. It also routed a parenthesized
CURRENT_* and any expression DEFAULT in place at every version, because the
CURRENT_* test skipped ADR 0021's Noise stripping and nothing consulted the
DEFAULT classification. On a populated table each of those Plans failed at
Apply with `:sqlite-error`. 3.53.0 changed nothing here: its only ALTER
change is ALTER COLUMN SET/DROP NOT NULL and ADD/DROP CHECK, and
`sqlite3AlterFinishAddColumn` is byte-identical between the 3.52.0 and
3.53.0 tags.

A Plan cannot see row counts, so an added column routes by what works on a
populated table, using ADR 0015's classification with ADR 0021's Noise
stripped:

- **A `:null` or `:constant` DEFAULT** plans in place as `:add-column`.
- **An `:opaque` DEFAULT or a STORED generated column** always collapses
  its Change set into a Rebuild, at every version. The copy fills the
  DEFAULT, or computes the column, row by row, so a Rebuild succeeds where
  ADD COLUMN would fail. With `:rebuild?` off, the Change set stays
  unhandled with `:rebuild-disabled`, per ADR 0006.
- **A NOT NULL column with no non-NULL default** plans in place from
  3.32.0 behind the existing `:empty-table` Gate. Below 3.32 it rebuilds,
  and the Rebuild carries the same Gate. No route can succeed on a
  populated table here, because the Rebuild's copy would insert NULL, so
  the Gate states the true precondition.

The classification is lexical and conservative. SQLite also accepts forms
the classifier calls `:opaque`, such as `CAST(1 AS TEXT)` or `- 1`. Those
rebuild, which is correct but costs a table rewrite.

Measured on SQLite 3.53.2:

| ADD COLUMN clause | empty table | one row |
|---|---|---|
| `DEFAULT CURRENT_TIMESTAMP` or `(CURRENT_TIMESTAMP)` | accepted | rejected |
| `DEFAULT (random())` or `(1+1)` | accepted | rejected |
| `DEFAULT (0)`, `-1`, `'x'`, `x'00'`, `TRUE`, `NULL` | accepted | accepted |
| `NOT NULL` or `NOT NULL DEFAULT NULL` | accepted | rejected |
| `AS (a+1) STORED` | accepted | rejected |
| `AS (a+1) VIRTUAL` | accepted | accepted |

## Considered Options

- **In place behind an `:empty-table` Gate for all four shapes**:
  rejected. It makes a populated table unmigratable where a Rebuild would
  succeed. Check fails for a Plan when another route would have worked.
- **Rebuild all four, including NOT NULL with no default**: rejected. The
  Rebuild fails on a populated table just as ADD COLUMN does, so it costs
  a table rewrite for nothing, where ADD COLUMN on an empty table only
  edits schema text.
- **Fall back to in place behind an `:empty-table` Gate when `:rebuild?`
  is off**: rejected. Routing would then depend on the policy switch, and
  one entry would have two routes. ADR 0006's Change set rule leaves a set
  that must collapse, but cannot, unhandled.
- **Classify by SQLite's exact acceptance rule**: rejected. That rule
  covers literals, unary +/-, CAST, NULL and TRUE/FALSE, and matching it
  means reading expression structure beyond tokens, which ADR 0002 rules
  out. A misclassification costs only a Rebuild.

## Consequences

- ADR 0015's `:empty-table` Gate now carries an in-place `:add-column`
  from 3.32.0 instead of 3.53.0. ADR 0015's DEFAULT classification now
  drives routing as well as key gates.
- The generative suite gains added-column shapes for CURRENT_* (bare and
  parenthesized), non-literal DEFAULTs and STORED generated columns on
  non-key columns. The bidirectionality property therefore pins this rule,
  and a future SQLite that changes it shows up as a property failure.
  Opaque DEFAULTs on new key columns stay ADR 0015's documented exclusion.
- The README's claim that three "relaxed ADD COLUMN forms" need SQLite
  3.53 is withdrawn.
