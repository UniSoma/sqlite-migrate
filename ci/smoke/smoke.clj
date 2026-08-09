(ns smoke
  "Native-image smoke program (ADR 0014): exercise the whole pipeline —
  Snapshot, Declaration, Diff, Plan, Apply — against a real in-memory
  SQLite database inside a Graal-native binary. Prints `ok` and exits 0
  on success; any failure throws and the binary exits non-zero. Not part
  of the library or its test suite; compiled only by the CI smoke job."
  (:require
    [sqlite-migrate.core :as m]
    [sqlite-migrate.jdbc :as jdbc])
  (:gen-class))

(defn -main [& _]
  (let [declaration ["CREATE TABLE person (id INTEGER PRIMARY KEY, name TEXT NOT NULL)"]]
    (with-open [live (jdbc/in-memory)
                pristine (jdbc/in-memory)]
      (let [live-snap (m/snapshot live)
            declared-snap (m/declared-snapshot pristine declaration)
            diff (m/diff live-snap declared-snap)
            plan (m/plan diff {:live-snapshot live-snap
                               :declared-snapshot declared-snap})]
        (m/apply! live plan)
        (when (m/drift? (m/diff (m/snapshot live) declared-snap))
          (throw (ex-info "smoke: live schema still drifts after apply!"
                   {:smoke/diff (m/diff (m/snapshot live) declared-snap)})))))
    (println "ok")))
