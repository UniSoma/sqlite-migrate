# sqlite-migrate

Declarative SQLite schema migration for Clojure: introspect a live database into a
Snapshot, diff it against a Declaration, and turn the Diff into an executable Plan.

## Installation

```clojure
io.github.unisoma/sqlite-migrate {:mvn/version "0.1.0"}
```

## Quickstart

```clojure
(require '[sqlite-migrate.core :as m]
         '[sqlite-migrate.jdbc :as jdbc])

(def declaration
  ["CREATE TABLE person (id INTEGER PRIMARY KEY, name TEXT NOT NULL)"])

(with-open [live (jdbc/connect "app.db")
            pristine (jdbc/in-memory)]
  (let [live-snap (m/snapshot live)                              ; introspect the live file
        declared-snap (m/declared-snapshot pristine declaration) ; realize the Declaration
        diff (m/diff live-snap declared-snap)]                   ; the first-class Diff
    (when (m/drift? diff)
      (let [plan (m/plan live-snap declared-snap diff)]
        (println (m/plan-report plan)) ; review: exactly these statements will run
        (m/apply! live plan)))))       ; one transaction, all-or-nothing
```

`plan` refuses renames and destructive drops until you pass explicit
`:directives`; data preconditions (a new NOT NULL over existing
rows) surface as Gates you probe read-only with `m/check`. To build
schemas as data, `sqlite-migrate.schema/->sql` compiles an EDN Schema
value into the same Declaration statement vector.

## Supported transformations

The planner makes a change in place when these two conditions are true:

- The target SQLite version has the necessary `ALTER TABLE` form.
- No object that depends on the table prevents the change.

If one condition is false, the planner puts all the changes to that regular table
into one table Rebuild.

| Object | Supported changes |
|---|---|
| Tables | The planner can create, rename, and drop a table. It can change a table between the ordinary, `STRICT`, and `WITHOUT ROWID` forms. It can add, remove, or change a primary key, an `AUTOINCREMENT`, or the column order. |
| Columns | The planner can add, rename, and drop a column. It can change the declared type, the `NOT NULL` flag, the default, the collation, and the generated expression or storage. A new column can go at any position in the declared order. |
| CHECK constraints | The planner can add, drop, and change a CHECK constraint. The constraint can have a name, or no name. |
| UNIQUE constraints | The planner can add, drop, and change a UNIQUE constraint. The constraint can have one column or more than one column. |
| Foreign keys | The planner can add, drop, and change a foreign key. It compares the child columns, the parent table, the parent columns, the two actions, `MATCH`, and the deferrability. |
| Indexes | The planner can create, drop, and change an index. An index can be ordinary, unique, expression, or partial. The planner compares the key order, the collation, and the sort direction of each key. |
| Triggers | The planner can create, drop, and replace a trigger on a table or on a view. |
| Views | The planner can create, drop, and replace a view. A view is opaque: a change to its text replaces the full view. The planner creates the declared triggers of the view again with it. |
| Virtual tables | The planner can create and drop a virtual table. It cannot change a virtual table, because the data of the module can be in shadow tables. |

The planner has these in-place operations:

- a table rename and a column rename
- a new column at the end of the table
- a legal column drop
- `SET NOT NULL` and `DROP NOT NULL`
- a new CHECK constraint
- a drop or a replacement of a CHECK constraint that has a name
- a create or a drop of a table, an index, a trigger, or a view

The planner drops a column in place only when no other object needs that column.
These objects can need it: the primary key, a UNIQUE clause, a FOREIGN KEY clause,
an index, a CHECK constraint, a generated expression, a view, or a trigger. The
plan drops some of these objects first, and the planner examines only the objects
that stay.

All other changes to a regular table go through a Rebuild. A UNIQUE change and a
foreign-key change always go through a Rebuild, because SQLite has no `ALTER TABLE`
form for them.

A SQLite version floor has one of two different effects.

An in-place floor changes only the route. Below the floor, the planner still does
the change, but with a Rebuild. These are the in-place floors:

- A column rename needs SQLite 3.25 or later.
- A column drop needs SQLite 3.35 or later.
- The `NOT NULL` and CHECK alterations need SQLite 3.53 or later.
- Three relaxed `ADD COLUMN` forms need SQLite 3.53 or later: a `NOT NULL` column
  with no default, a column with a `CURRENT_*` default, and a `STORED` generated
  column.

An object floor stops the change. The planner reports the entry as unhandled,
because a Rebuild must also create the shape that the target cannot hold. These
are the object floors:

- A declared `STRICT` table needs SQLite 3.37 or later.
- A declared generated column needs SQLite 3.31 or later.

Four changes need a Directive. Each Directive gives your permission for one object:

- `:rename-table`
- `:rename-column`
- `:drop-table`
- `:drop-column`

The planner never guesses a rename. A `VIRTUAL` generated column holds no data. A
drop of that column needs no Directive.

A Rebuild copies the shared columns by name. A rename from a Directive also applies
to this copy. A new column gets its declared default. The Rebuild does not change
any value.

A Rebuild keeps the `rowid` when the two sides are rowid tables. It also moves the
`AUTOINCREMENT` counter to the new table. Therefore SQLite cannot use an old id a
second time.

SQLite reads each view and each trigger a second time during the rename in the
Rebuild. For this reason, the Rebuild drops each view and each trigger that refers
to the table, and then creates them again.

A Gate examines the rows of the live table before a change that adds a constraint
or makes a constraint more strict. A Gate can do this for these constraints:
`NOT NULL`, CHECK, UNIQUE, the primary key, a foreign key, `STRICT`, and
`WITHOUT ROWID`.

## Unsupported transformations and limits

A Rebuild can do each edit that a Snapshot can show for a regular table. The limits
below are different. Some changes have no support. Some changes are not visible to
the planner. Some changes are conditional.

| Area | Limit |
|---|---|
| Row data | The library does not do a DML migration or a backfill. A Rebuild copies each value without a change. It cannot do a cast, a calculation, a `CASE` map, or a value from a different column. Correct the data before you apply the Plan. A Gate shows you the rows that do not agree with the new shape. |
| Declarations with side effects | Each Declaration statement must change the `main` schema. DML, `ATTACH`, `PRAGMA`, and a temporary object change nothing. A `CREATE TABLE AS SELECT` statement adds rows. The library refuses all of these with a `:malformed-input` error. |
| Unmodeled table clauses | The Snapshot does not hold the CREATE TABLE conflict policy (`ON CONFLICT`). It does not hold an `ASC`, `DESC`, or `COLLATE` modifier in a PRIMARY KEY or a UNIQUE definition. It does not hold the name of a NOT NULL, DEFAULT, COLLATE, or generated column constraint. A change to one of these facts can give no Diff entry. |
| Virtual tables | The planner cannot change a regular table into a virtual table. It cannot alter, rename, or rebuild a virtual table. It cannot change the arguments of the module. It can only create a virtual table, or drop one with a Directive. |
| Rename inference | The planner never decides that a removed object and an added object are the same object. Only a table rename and a column rename accept a Directive. All other named objects change with a drop and a create, or with a table Rebuild. |
| Bulk destructive intent | There is no wildcard, no pattern, and no global "allow all drops" option. Each table drop needs its own Directive. Each drop of a column that holds data also needs its own Directive. |
| Custom Plan operations | You cannot put your own SQL or a callback of your application into a Plan. There are no numbered up-migration and down-migration scripts. A Declaration gives only the target state of the schema. |
| Expression semantics | The library does not parse, simplify, or rewrite an expression. It cannot show that two expressions are equivalent. It compares each CHECK, generated, DEFAULT, index, and partial-index expression as text, and writes the text again without a change. |
| Schema scope | The library does not use a temporary schema or an attached schema. Introspection and Apply use only the `main` schema of SQLite. The Snapshot does not include an engine-internal object or a shadow table of a virtual table. |
| Catalog rewriting | The library does not use a `PRAGMA writable_schema` shortcut. Each change to a regular table uses the generalized Rebuild procedure of SQLite. |
| Selective equivalence | You cannot configure the equivalence relation, and the planner has no ignore rules. You can filter the Diff entries as your own data, but only the complete Diff is an input to the planner. |
| Apply modes | Apply is always atomic. There is no per-operation mode, no checkpoint mode, and no continue-on-error mode. There is no force option for a stale Plan. There is no built-in stage-then-swap mode. |

The planner reports an entry as unhandled in these conditions:

- A virtual table changed.
- The target SQLite version cannot hold the declared object.
- The only route is a Rebuild, and `:rebuild?` is false.
- A destructive drop has no Directive. This entry waits for your permission. It is
  not a technical limit.

`apply!` refuses a Plan that has unhandled entries. To apply such a Plan, you must
set `:allow-unhandled?`. This prevents an accidental change to the database from an
incomplete Plan.

One refusal has no override. The live database can have a schema fingerprint that
is different from the fingerprint of the Plan. Then `check` refuses with
`:drift-refused`, and `apply!` refuses again from inside the transaction. There is
no drift window. To continue, make a new Diff and a new Plan.

An opaque default on a new key column can make a data pre-check impossible. In this
condition, SQLite does the enforcement in the atomic Apply frame. This is the last
protection.

## The Diff

`sqlite-migrate.core/diff` compares two Snapshots and returns `{:entries [...] :live-provenance
... :declared-provenance ...}`. Each entry is one self-contained Semantic difference:
`:kind` (`:added`, `:removed`, `:changed`), `:path` addressing the object, both sides'
verbatim sub-values under `:live`/`:declared` (stored CREATE sql included), and for
`:changed` the set of differing fact keywords under `:facts`. Entries come out in a
locked deterministic order, the whole value is plain EDN, and it survives
`pr-str`/`read-string`. `:entries` is empty iff the two Snapshots are Equivalent.

## CI drift check

Drift checking is a recipe, not a bundled function: introspect the live file, diff it
against the Declaration, fail the build on `drift?`, and archive the printed Diff as a
build artifact.

```clojure
(ns ci.drift-check
  (:require [sqlite-migrate.core :as m]
    [sqlite-migrate.jdbc :as jdbc]))

(defn -main [& _]
  (let [declaration (read-string (slurp "schema.edn")) ; a vector of CREATE statement strings
        diff (with-open [live (jdbc/connect "app.db")
                         pristine (jdbc/in-memory)]
               (m/diff (m/snapshot live)
                 (m/declared-snapshot pristine declaration)))]
    (spit "target/drift.edn" (pr-str diff))       ; archive the machine surface
    (when (m/drift? diff)
      (println (m/drift-report diff))             ; human-readable, presentation-only
      (System/exit 1))))
```

The archived `drift.edn` is the machine surface: `read-string` restores the full Diff,
and `drift-report` renders identically from the restored value. The report text is for
humans; never parse it.

## Filtering entries

Diff entries are flat plain-EDN maps, so ordinary seq functions are the filtering API.
There are no filter helpers or ignore knobs.

```clojure
;; Ignore one table the app does not own.
(remove #(= [:table "audit_log"] (take 2 (:path %))) (:entries diff))

;; Only entries that touch indexes.
(filter #(some #{:index} (:path %)) (:entries diff))

;; Group by change kind.
(group-by :kind (:entries diff))
```

A filtered entry seq is consumer data, not the Diff of two Snapshots: `drift?`'s
emptiness guarantee applies only to unfiltered diffs.

## The three Diff surfaces

- `drift?`: true when the Diff has entries, i.e. the live schema is not Equivalent to
  the declared one.
- `drift-report`: single-arity, deterministic Diff-to-string renderer. Changed objects
  print one line per differing fact with both sides' values; added and removed objects
  print their whole verbatim CREATE sql. Renders from a deserialized Diff alone.
- `by-object`: the one nesting view. Flat entries are regrouped as `{:path [<kind> <name>]
  :entries [...]}` per object, a changed table's table-level entry reunited with its
  fine-grained column, constraint, index, and trigger children.

## Documentation

- [Design](doc/design.md): the pipeline and the decisions behind it.
- [Recipes](doc/recipes.md): CI drift check, converge on startup, stage
  then swap.
- [Native image](doc/native-image.md): compiling the library into a
  GraalVM binary.
- API reference on [cljdoc](https://cljdoc.org/d/io.github.unisoma/sqlite-migrate):
  the `sqlite-migrate.protocols` docstrings are the normative adapter-author
  contract.

## License

MIT (see [LICENSE](LICENSE)). Copyright (c) 2026 UniSoma.
