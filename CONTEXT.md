# SQLite Migrate

A data-driven Clojure library that introspects a live SQLite file, diffs it against a
declared target schema, and produces an executable migration plan — with the diff as a
first-class public surface.

## Language

**Snapshot**:
The canonical, normalized data value describing one SQLite schema. Produced only by
introspection — whether of a live file or of a pristine database — so both sides of a
diff are the same shape.
_Avoid_: catalog, reflection, schema object

**Introspection**:
Reading a schema from a SQLite connection (pragmas plus stored CREATE sql) into a
Snapshot. The single function that produces Snapshots; it reads the `main` schema only.
_Avoid_: reflection, discovery

**Pristine database**:
A throwaway in-memory SQLite into which the declared target schema is executed so it can
be introspected like any live file. The normalizer that makes declared and live schemas
comparable without parsing SQL.
_Avoid_: scratch DB, shadow DB, dev DB

**Live file**:
The user's actual SQLite database — the current-state side of a diff.
_Avoid_: production DB, source DB

**Opaque expression**:
Expression text (CHECK bodies, generated-column expressions, index expressions, partial
WHERE clauses, DEFAULT spellings) carried in a Snapshot as extracted text, never parsed
into an AST. Compared, carried, and re-emitted — never understood.

**Declaration**:
The user-supplied target schema: canonically SQL text (a single string or a seq of
statements), pure state carrying no migration intent. Meaningful only once executed into
a pristine database and introspected into a Snapshot.
_Avoid_: target schema file, schema DSL

**Schema value**:
The EDN sugar form of a Declaration: one data value describing target state, compiled to
SQL statement text. Never consumed directly by the core.
_Avoid_: schema DSL, schema map

**Equivalence**:
The single fixed relation over Snapshots that defines "the same schema" — no
configuration knobs. An empty diff means equivalent, and vice versa. Normalizes only
at comparison time; Snapshots themselves stay verbatim.
_Avoid_: equality, schema match, sameness

**Noise**:
A difference the Equivalence relation erases: identifier case and quoting,
whitespace/comments/keyword case inside opaque expressions, ordering among named
siblings (indexes, triggers, views), engine-internal objects.

**Semantic difference**:
A difference the Equivalence relation keeps — it appears in the diff. Includes
physical column order, declared type text, constraint names, and any expression
difference beyond token identity.

**Token comparison**:
How opaque expressions are compared: both sides tokenized by SQLite's lexical rules
and matched token-for-token — keywords and bare/quoted identifiers case-folded,
string and blob literals byte-exact. Lexical only; never a parser.

**Diff**:
The first-class data value produced by comparing two Snapshots (live, declared): a thin
wrapper map holding a flat sequence of Diff entries plus both sides' Snapshot metadata.
Empty entries ⇔ the Snapshots are Equivalent. A pure state delta: no migration intent,
no cost labels, no derived dependency data.
_Avoid_: changeset, delta report, drift report (a drift report is a rendering of a Diff)

**Diff entry**:
One self-contained semantic difference inside a Diff: target-relative change kind
(`added` = declared-only, `removed` = live-only, `changed` = both, not equivalent), a
path addressing the object, both sides' verbatim sub-values, and for `changed` the set
of differing facts. Carries no rename concept — a rename is a removed/added pair until
the directives layer says otherwise.
_Avoid_: hunk, change record

**Snapshot metadata**:
Provenance attached to a Snapshot without affecting its equality: the SQLite version that
read it, the file's `schema_version` fingerprint, and each object's stored CREATE sql.
Two Snapshots of identical schemas compare equal regardless of provenance.

**Drift**:
A non-empty Diff between a live file and a Declaration — the live schema is not
Equivalent to the declared one.
_Avoid_: schema mismatch, divergence

**Plan**:
The ordered, executable data value produced by planning a Diff under given
capabilities: a thin wrapper holding an ordered sequence of Ops, both sides'
Snapshot metadata, the capabilities planned for, and the unhandled Diff entries.
List position is execution order. Pure EDN; nothing connection-bound.
_Avoid_: migration, changeset, script

**Op**:
One logical schema change inside a Plan: a change kind, the path of the object
it touches, the Diff entries it serves, and the exact SQL statements it
executes — compiled at plan time. "Operation" is acceptable in prose.
_Avoid_: step, statement, action

**Rebuild**:
The composite Op kind implementing SQLite's 12-step generalized ALTER TABLE
procedure for one table — create-new, copy, drop-old, rename-new, recreate
dependents — subsuming all of that table's changes at once.
_Avoid_: table recreation, copy migration

**Apply**:
The effectful edge that executes a Plan on a connection: a dumb fold over the
Ops in plan order, inside the executor-owned transaction/FK frame, all-or-
nothing by default. Refuses to run against a database whose schema has drifted
from the Plan's source Snapshot.
_Avoid_: run, execute, migrate

**Drift report**:
The human-readable plain-text rendering of a Diff: per-fact both-sides lines for changed
objects, whole verbatim CREATE sql for one-sided ones. Presentation only — never a parse
contract; the Diff itself is the machine surface.
_Avoid_: diff output, diff summary
