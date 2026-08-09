# sqlite-migrate

Declarative SQLite schema migration for Clojure: introspect a live database into a
Snapshot, diff it against a Declaration, and turn the Diff into an executable Plan.
Every stage is a plain data value.

The Diff is a product surface of its own. Exactly three pure functions operate on it —
`drift?`, `drift-report`, and `by-object` — and the two recipes below are documented
patterns, not API (ADR 0005).

## The Diff in one paragraph

`sqlite-migrate.core/diff` compares two Snapshots into `{:entries [...] :live-metadata
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

- `drift?` — true when the Diff has entries, i.e. the live schema is not Equivalent to
  the declared one.
- `drift-report` — single-arity, deterministic Diff-to-string renderer. Changed objects
  print one line per differing fact with both sides' values; added and removed objects
  print their whole verbatim CREATE sql. Renders from a deserialized Diff alone.
- `by-object` — the one nesting view: flat entries regrouped as `{:path [<kind> <name>]
  :entries [...]}` per object, a changed table's table-level entry reunited with its
  fine-grained column, constraint, index, and trigger children.

## CI

`.github/workflows/ci.yml` runs a two-point sqlite-jdbc version matrix (ADR 0010)
plus a GraalVM native-image smoke job (ADR 0014):

- **Floor — sqlite-jdbc 3.40.1.0**: the oldest version conveniently pinnable, and
  the release where upstream native-image testing begins. The `:sqlite-floor`
  deps alias overrides the pin, so `clojure -X:test:sqlite-floor` runs against it.
  This leg runs the generative property suite (`sqlite-migrate.properties-test`),
  version honesty included. The floor bundles SQLite 3.40.1 — below the 3.53
  in-place ALTER-constraint gate — so the deterministic unit tests that pin
  latest-version plan shapes are out of scope on this leg by design.
- **Latest — the deps.edn pin**: the full suite plus `clj-kondo` lint.
- **Native-image smoke**: AOT-compiles `ci/smoke/smoke.clj` (snapshot → declare →
  diff → plan → apply against a real in-memory database), builds it with
  `native-image`, and requires the binary to print `ok`.
