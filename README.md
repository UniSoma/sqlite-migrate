# sqlite-migrate

Declarative SQLite schema migration for Clojure: introspect a live database into a
Snapshot, diff it against a Declaration, and turn the Diff into an executable Plan.

## Installation

```clojure
io.github.unisoma/sqlite-migrate {:mvn/version "0.1.0-SNAPSHOT"}
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

## The Diff

`sqlite-migrate.core/diff` compares two Snapshots and returns `{:entries [...] :live-metadata
... :declared-metadata ...}`. Each entry is one self-contained Semantic difference:
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
