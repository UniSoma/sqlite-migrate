# Recipes

These are documented patterns, not API (ADR 0005, 0011). The library ships no
CI framework, no startup hook, and no file-swapping Apply mode; each recipe
composes the public surface with ordinary code you own.

## CI drift check

Introspect the live file, diff it against the Declaration, fail the build on
`drift?`, and archive the printed Diff as a build artifact.

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

The archived `drift.edn` is the machine surface: `read-string` restores the
full Diff, and `drift-report` renders identically from the restored value.
The report text is for humans; never parse it.

## Converge on startup

The application converges its own database when it boots: snapshot, diff,
plan, apply. Directives live in a checked-in EDN file beside the Declaration,
so a rename shipped in version N converges every install whenever it starts —
Directives are conditional, and unmatched ones are inert.

```clojure
(ns app.migrate
  (:require [sqlite-migrate.core :as m]
            [sqlite-migrate.jdbc :as jdbc]))

(defn converge!
  "Bring the database at `path` to the declared schema. Returns the
  Apply report, or nil when the schema was already Equivalent. Throws
  on any refusal — an unhandled entry means the Declaration needs a
  Directive (or can't be planned), and starting anyway would run the
  app against a schema it doesn't expect."
  [path declaration directives]
  (with-open [live (jdbc/connect path)
              pristine (jdbc/in-memory)]
    (let [live-snap (m/snapshot live)
          declared-snap (m/declared-snapshot pristine declaration)
          diff (m/diff live-snap declared-snap)]
      (when (m/drift? diff)
        (let [plan (m/plan diff {:live-snapshot live-snap
                                 :declared-snapshot declared-snap
                                 :directives directives})]
          (m/apply! live plan))))))
```

Notes:

- `apply!` is all-or-nothing: a failure (gate violation, SQL error) leaves
  the database exactly as it was and throws with the `:sqlite-migrate/error`
  envelope — let it crash the boot rather than start against unknown state.
- Two instances racing at startup are serialized by SQLite's own locking, and
  the `schema_version` fingerprint refusal makes the loser's stale Plan
  refuse rather than reapply: catch `:drift-refused`, re-snapshot, and find
  the schema already converged.
- Keep the Directive file append-only until every deployed database has
  passed the rename; the entries are inert once no live schema matches them.

## Stage then swap

For operators who want to rehearse a migration against a copy before touching
the real file. Apply deliberately has no stage-then-swap mode — a connection
cannot swap files out from under other connections (ADR 0011) — so the file
handling is yours, and the sequence below only makes sense inside a
maintenance window where nothing else has the database open.

```clojure
(ns ops.stage-then-swap
  (:require [clojure.java.io :as io]
            [sqlite-migrate.core :as m]
            [sqlite-migrate.jdbc :as jdbc])
  (:import [java.nio.file Files Paths StandardCopyOption]))

(defn- copy-of [path staged]
  ;; Copy only a quiescent database: no writers, no live -wal/-shm sidecars.
  (Files/copy (Paths/get path (make-array String 0))
              (Paths/get staged (make-array String 0))
              (into-array [StandardCopyOption/REPLACE_EXISTING]))
  staged)

(defn stage! [path declaration directives]
  (let [staged (copy-of path (str path ".staged"))]
    (with-open [conn (jdbc/connect staged)
                pristine (jdbc/in-memory)]
      (let [live-snap (m/snapshot conn)
            declared-snap (m/declared-snapshot pristine declaration)
            plan (m/plan (m/diff live-snap declared-snap)
                   {:live-snapshot live-snap
                    :declared-snapshot declared-snap
                    :directives directives})]
        (println (m/plan-report plan))   ; the pre-apply review artifact
        (m/apply! conn plan)))           ; rehearsed on the copy
    staged))

;; After stage! succeeds and the report has been reviewed:
;;   1. stop every writer;
;;   2. atomically rename the staged file over the original
;;      (java.nio.file.Files/move with ATOMIC_MOVE);
;;   3. restart.
;; Any write to the original between copy and swap is lost — that's the
;; cost of the pattern, which is why Apply's in-place transaction is the
;; default and this is a rehearsal workflow.
```

Because `apply!` already ran on the copy, the swap installs a file whose
schema is known-Equivalent to the Declaration; a startup drift check
(`drift?`) after the swap is a cheap belt-and-braces.
