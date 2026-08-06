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

**Snapshot metadata**:
Provenance attached to a Snapshot without affecting its equality: the SQLite version that
read it and the file's `schema_version` fingerprint. Two Snapshots of identical schemas
compare equal regardless of provenance.
