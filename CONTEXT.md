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

**Snapshot metadata**:
Provenance attached to a Snapshot without affecting its equality: the SQLite version that
read it and the file's `schema_version` fingerprint. Two Snapshots of identical schemas
compare equal regardless of provenance.
