(ns sqlite-migrate.directives-test
  "The Directives layer (ADR 0009, 0007) through the public
  `sqlite-migrate.core/plan` and `apply!` seams on real in-memory
  SQLite: structural validation of the directive set, the conditional
  inert-but-reported contract (`:unused-directives`, the `:directives`
  echo), rename fusion feeding the in-place-vs-rebuild decision,
  collision-forced rebuilds with the copy mapping old names to new,
  and drop authorization lifting `:needs-intent` refusals."
  (:require [clojure.test :refer [deftest is testing]]
    [sqlite-migrate.core :as m]
    [sqlite-migrate.jdbc :as sql-jdbc]
    [sqlite-migrate.protocols :as p]))

(defn- snap
  "Snapshot of `declaration` realized into a fresh in-memory pristine
  database."
  [declaration]
  (with-open [conn (sql-jdbc/in-memory)]
    (m/declared-snapshot conn declaration)))

(defn- plan-of
  "Plan the Diff of two declarations, each realized into its own
  pristine database, with the Snapshots supplied as planning context
  and `directives` riding in opts."
  ([live-decl declared-decl directives]
    (plan-of live-decl declared-decl directives {}))
  ([live-decl declared-decl directives opts]
    (let [live (snap live-decl)
          declared (snap declared-decl)]
      (m/plan live declared (m/diff live declared)
        (merge {:directives directives}
          opts)))))

(defn- refusal-codes
  "The `[class code]` pairs of every Refusal on every unhandled entry,
  keyed by the entry's path."
  [pl]
  (into {}
    (map (fn [{:keys [entry refusals]}]
           [(:path entry) (mapv (juxt :class :code) refusals)]))
    (:unhandled pl)))

(defn- error-key
  "The `:sqlite-migrate/error` value of the throw `thunk` produces, or
  nil when it returns."
  [thunk]
  (try (thunk) nil
    (catch clojure.lang.ExceptionInfo e
      (:sqlite-migrate/error (ex-data e)))))

;; ---------------------------------------------------------------------------
;; Structural validation: a conflicting or malformed directive set
;; throws as malformed input, before planning proper (ADR 0009)

(deftest conflicting-directive-sets-throw-as-malformed-input
  (let [live ["CREATE TABLE users (id INTEGER, name TEXT)"]
        declared ["CREATE TABLE people (id INTEGER, name TEXT)"]]
    (testing "the same live path claimed twice — a rename and a drop over one object, folded-name matching"
      (is (= :malformed-input
            (error-key #(plan-of live declared
                          [{:directive :rename-table :from "users" :to "people"}
                           {:directive :drop-table :table "USERS"}])))))
    (testing "the same declared target claimed twice"
      (is (= :malformed-input
            (error-key #(plan-of live declared
                          [{:directive :rename-table :from "users" :to "people"}
                           {:directive :rename-table :from "extinct" :to "PEOPLE"}])))))
    (testing "the same live column claimed twice"
      (is (= :malformed-input
            (error-key #(plan-of live declared
                          [{:directive :rename-column :table "t" :from "a" :to "b"}
                           {:directive :drop-column :table "T" :column "A"}])))))
    (testing "the same declared column target claimed twice"
      (is (= :malformed-input
            (error-key #(plan-of live declared
                          [{:directive :rename-column :table "t" :from "a" :to "c"}
                           {:directive :rename-column :table "t" :from "b" :to "C"}])))))
    (testing "a rename-column inside a table claimed by drop-table — a rename and a drop over one object"
      (is (= :malformed-input
            (error-key #(plan-of live declared
                          [{:directive :drop-table :table "users"}
                           {:directive :rename-column :table "users" :from "name" :to "full_name"}])))))))

(deftest malformed-directive-maps-throw-before-planning
  (let [live ["CREATE TABLE t (a INTEGER)"]
        declared ["CREATE TABLE t (a INTEGER)"]]
    (testing "an unknown directive kind"
      (is (= :malformed-input
            (error-key #(plan-of live declared
                          [{:directive :truncate-table :table "t"}])))))
    (testing "a missing required key"
      (is (= :malformed-input
            (error-key #(plan-of live declared
                          [{:directive :rename-table :from "users"}])))))
    (testing "a non-string identifier"
      (is (= :malformed-input
            (error-key #(plan-of live declared
                          [{:directive :drop-table :table :users}])))))))

;; ---------------------------------------------------------------------------
;; Conditional and durable: unmatched directives are inert but
;; reported; the Plan echoes the full input set (ADR 0009)

(deftest unmatched-directives-are-inert-and-reported-in-input-order
  (let [ds [{:directive :rename-table :from "ghost" :to "phantom"}
            {:directive :drop-table :table "not_there"}
            {:directive :rename-column :table "t" :from "gone" :to "still_gone"}]
        pl (plan-of ["CREATE TABLE t (a INTEGER)"]
             ["CREATE TABLE t (a INTEGER, b TEXT)"]
             ds)]
    (testing "the Plan echoes the full input directive set"
      (is (= ds (:directives pl))))
    (testing "every unmatched directive is reported, in input order, never an error"
      (is (= ds (:unused-directives pl))))
    (testing "planning proceeds untouched alongside"
      (is (= [:add-column] (mapv :kind (:ops pl)))))))

(deftest half-matched-rename-is-inert-and-the-drop-refusal-stands
  ;; live has the removed column but the declaration lacks the target:
  ;; the rename must not lift the drop refusal (a half match lifting it
  ;; would be a drop authorized by a directive claiming to preserve
  ;; data) — and the typo surfaces twice: unused and un-lifted refusal
  (let [pl (plan-of ["CREATE TABLE t (a INTEGER, old_name TEXT)"]
             ["CREATE TABLE t (a INTEGER, new_name TEXT)"]
             [{:directive :rename-column :table "t" :from "old_name" :to "new_nmae"}])]
    (is (= [{:directive :rename-column :table "t" :from "old_name" :to "new_nmae"}]
          (:unused-directives pl)))
    (is (= [[:needs-intent :destructive-drop]]
          (get (refusal-codes pl) [:table "t" :column "old_name"]))
      "the surviving removed entry refuses on its own merits")))

;; ---------------------------------------------------------------------------
;; Table renames: the fused pair is just a changed object whose sides
;; differ in name, feeding the in-place-vs-rebuild decision (ADR 0009)

(deftest table-rename-fuses-into-an-in-place-rename
  (let [ds [{:directive :rename-table :from "users" :to "people"}]
        pl (plan-of ["CREATE TABLE users (id INTEGER, name TEXT)"]
             ["CREATE TABLE people (id INTEGER, name TEXT)"]
             ds)]
    (testing "one :rename-table op serves both the removed and the added entry"
      (is (= [{:kind :rename-table
               :path [:table "users"]
               :serves #{[:table "users"] [:table "people"]}
               :sql ["ALTER TABLE \"users\" RENAME TO \"people\""]}]
            (:ops pl)))
      (is (empty? (:unhandled pl))))
    (testing "the directive is used: echoed in :directives, absent from :unused-directives"
      (is (= ds (:directives pl)))
      (is (= [] (:unused-directives pl))))))

(deftest table-rename-plus-appended-column-stays-in-place
  (let [pl (plan-of ["CREATE TABLE users (id INTEGER, name TEXT)"]
             ["CREATE TABLE people (id INTEGER, name TEXT, email TEXT)"]
             [{:directive :rename-table :from "users" :to "people"}])]
    (is (= [[:rename-table ["ALTER TABLE \"users\" RENAME TO \"people\""]]
            [:add-column ["ALTER TABLE \"people\" ADD COLUMN \"email\" TEXT"]]]
          (mapv (juxt :kind :sql) (:ops pl)))
      "the rename runs first; the add targets the declared name")
    (is (= [#{[:table "users"] [:table "people"]}
            #{[:table "users"] [:table "people"]}]
          (mapv :serves (:ops pl)))
      "every fused op serves both whole-table entries")
    (is (empty? (:unhandled pl)))
    (is (= [] (:unused-directives pl)))))

(deftest table-rename-with-retype-rides-one-rebuild
  (let [pl (plan-of ["CREATE TABLE users (id INTEGER, name TEXT)"]
             ["CREATE TABLE people (id INTEGER, name BLOB)"]
             [{:directive :rename-table :from "users" :to "people"}])]
    (is (= [{:kind :rebuild-table
             :path [:table "users"]
             :serves #{[:table "users"] [:table "people"]}
             :sql ["CREATE TABLE \"people__sqm_rebuild\" (id INTEGER, name BLOB)"
                   "INSERT INTO \"people__sqm_rebuild\" (rowid, \"id\", \"name\") SELECT rowid, \"id\", \"name\" FROM \"users\""
                   "DROP TABLE \"users\""
                   "ALTER TABLE \"people__sqm_rebuild\" RENAME TO \"people\""]}]
          (:ops pl))
      "the rebuild copies from the live name into the declared name")
    (is (empty? (:unhandled pl)))
    (is (= [] (:unused-directives pl)))))

;; ---------------------------------------------------------------------------
;; Column renames: fused pairs feed the in-place-vs-rebuild decision;
;; colliding rename sets force the rebuild path (ADR 0009)

(deftest column-rename-fuses-into-an-in-place-rename
  (let [pl (plan-of ["CREATE TABLE t (a INTEGER, old_name TEXT)"]
             ["CREATE TABLE t (a INTEGER, new_name TEXT)"]
             [{:directive :rename-column :table "t" :from "old_name" :to "new_name"}])]
    (is (= [{:kind :rename-column
             :path [:table "t" :column "old_name"]
             :serves #{[:table "t" :column "old_name"] [:table "t" :column "new_name"]}
             :sql ["ALTER TABLE \"t\" RENAME COLUMN \"old_name\" TO \"new_name\""]}]
          (:ops pl))
      "one :rename-column op serves both the removed and the added entry")
    (is (empty? (:unhandled pl)))
    (is (= [] (:unused-directives pl)))))

(deftest column-rename-with-retype-rides-one-rebuild
  (let [pl (plan-of ["CREATE TABLE t (a INTEGER, old_name TEXT)"]
             ["CREATE TABLE t (a INTEGER, new_name BLOB)"]
             [{:directive :rename-column :table "t" :from "old_name" :to "new_name"}])]
    (is (= [:rebuild-table] (mapv :kind (:ops pl)))
      "a fused pair whose sides differ beyond the name collapses to the rebuild")
    (is (= [#{[:table "t" :column "old_name"] [:table "t" :column "new_name"]}]
          (mapv :serves (:ops pl))))
    (is (some #{"INSERT INTO \"t__sqm_rebuild\" (rowid, \"a\", \"new_name\") SELECT rowid, \"a\", \"old_name\" FROM \"t\""}
          (:sql (first (:ops pl))))
      "the rebuild copy maps the old name to the new one")
    (is (empty? (:unhandled pl)))
    (is (= [] (:unused-directives pl)))))

(deftest swapped-column-renames-force-the-rebuild-path
  ;; sequential in-place RENAME COLUMN steps would collide, so the
  ;; table rides one rebuild whose copy maps old names to new
  (let [pl (plan-of ["CREATE TABLE t (a INTEGER, b TEXT)"]
             ["CREATE TABLE t (a TEXT, b INTEGER)"]
             [{:directive :rename-column :table "t" :from "a" :to "b"}
              {:directive :rename-column :table "t" :from "b" :to "a"}])]
    (is (= [:rebuild-table] (mapv :kind (:ops pl))))
    (is (= [#{[:table "t" :column "a"] [:table "t" :column "b"]}]
          (mapv :serves (:ops pl))))
    (is (some #{"INSERT INTO \"t__sqm_rebuild\" (rowid, \"a\", \"b\") SELECT rowid, \"b\", \"a\" FROM \"t\""}
          (:sql (first (:ops pl))))
      "the copy crosses the values over")
    (is (empty? (:unhandled pl)))
    (is (= [] (:unused-directives pl)))))

(deftest chained-column-renames-force-the-rebuild-path
  (let [pl (plan-of ["CREATE TABLE t (a INTEGER, b TEXT)"]
             ["CREATE TABLE t (b INTEGER, c TEXT)"]
             [{:directive :rename-column :table "t" :from "a" :to "b"}
              {:directive :rename-column :table "t" :from "b" :to "c"}])]
    (is (= [:rebuild-table] (mapv :kind (:ops pl))))
    (is (= [#{[:table "t" :column "a"] [:table "t" :column "b"] [:table "t" :column "c"]}]
          (mapv :serves (:ops pl)))
      "the changed middle column rides the same rebuild")
    (is (some #{"INSERT INTO \"t__sqm_rebuild\" (rowid, \"b\", \"c\") SELECT rowid, \"a\", \"b\" FROM \"t\""}
          (:sql (first (:ops pl))))
      "the copy shifts every value one name down the chain")
    (is (empty? (:unhandled pl)))
    (is (= [] (:unused-directives pl)))))

(deftest column-rename-inside-a-renamed-table-stays-in-place
  ;; the column directive carries the live table name; the table-rename
  ;; directive alone owns the table mapping (ADR 0009)
  (let [pl (plan-of ["CREATE TABLE users (id INTEGER, name TEXT)"]
             ["CREATE TABLE people (id INTEGER, full_name TEXT)"]
             [{:directive :rename-table :from "users" :to "people"}
              {:directive :rename-column :table "users" :from "name" :to "full_name"}])]
    (is (= [[:rename-table ["ALTER TABLE \"users\" RENAME TO \"people\""]]
            [:rename-column ["ALTER TABLE \"people\" RENAME COLUMN \"name\" TO \"full_name\""]]]
          (mapv (juxt :kind :sql) (:ops pl)))
      "the rename runs first; the column rename targets the declared table name")
    (is (= [#{[:table "users"] [:table "people"]}
            #{[:table "users"] [:table "people"]}]
          (mapv :serves (:ops pl))))
    (is (empty? (:unhandled pl)))
    (is (= [] (:unused-directives pl)))))

;; ---------------------------------------------------------------------------
;; Drop authorization: a drop only happens because the author said so
;; (ADR 0007, 0009)

(deftest drop-table-directive-lifts-the-refusal
  (let [pl (plan-of ["CREATE TABLE old_stuff (a INTEGER)" "CREATE TABLE kept (b INTEGER)"]
             ["CREATE TABLE kept (b INTEGER)"]
             [{:directive :drop-table :table "OLD_STUFF"}])]
    (is (= [{:kind :drop-table
             :path [:table "old_stuff"]
             :serves #{[:table "old_stuff"]}
             :sql ["DROP TABLE \"old_stuff\""]}]
          (:ops pl)))
    (is (empty? (:unhandled pl)))
    (is (= [] (:unused-directives pl)))))

(deftest drop-column-directive-plans-the-in-place-drop
  (let [pl (plan-of ["CREATE TABLE t (a INTEGER, legacy TEXT)"]
             ["CREATE TABLE t (a INTEGER)"]
             [{:directive :drop-column :table "t" :column "legacy"}])]
    (is (= [{:kind :drop-column
             :path [:table "t" :column "legacy"]
             :serves #{[:table "t" :column "legacy"]}
             :sql ["ALTER TABLE \"t\" DROP COLUMN \"legacy\""]}]
          (:ops pl)))
    (is (empty? (:unhandled pl)))
    (is (= [] (:unused-directives pl)))))

(deftest drop-column-directive-rides-the-rebuild-when-in-place-is-illegal
  ;; the column sits in a UNIQUE constraint, so DROP COLUMN is illegal;
  ;; the authorized drop rides the rebuild and its data is not copied
  (let [pl (plan-of ["CREATE TABLE t (a INTEGER, legacy TEXT UNIQUE)"]
             ["CREATE TABLE t (a INTEGER)"]
             [{:directive :drop-column :table "t" :column "legacy"}])]
    (is (= [:rebuild-table] (mapv :kind (:ops pl))))
    (is (empty? (:unhandled pl)))
    (is (= [] (:unused-directives pl)))))

;; ---------------------------------------------------------------------------
;; End-to-end demos: a rename is a rename (data survives); a drop only
;; happens because the author said so

(defn- plan-live
  "Plan the live database against `declared-decl` (realized into its
  own pristine database) with `directives`."
  [live declared-decl directives]
  (let [live-snap (m/snapshot live)
        declared (snap declared-decl)]
    (m/plan live-snap declared (m/diff live-snap declared)
      {:directives directives})))

(defn- converged?
  "True when the live database no longer drifts from `declared-decl`."
  [live declared-decl]
  (not (m/drift? (m/diff (m/snapshot live) (snap declared-decl)))))

(deftest populated-table-renames-with-data-intact
  (with-open [live (sql-jdbc/in-memory)]
    (p/execute-batch! live
      ["CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)"
       "INSERT INTO users (id, name) VALUES (1, 'ada'), (2, 'linus')"])
    (let [declared-decl ["CREATE TABLE people (id INTEGER PRIMARY KEY, full_name TEXT)"]
          directives [{:directive :rename-table :from "users" :to "people"}
                      {:directive :rename-column :table "users" :from "name" :to "full_name"}]
          pl (plan-live live declared-decl directives)]
      (is (empty? (:unhandled pl)))
      (is (= [] (:unused-directives pl)))
      (m/apply! live pl)
      (is (= [{:id 1 :full_name "ada"} {:id 2 :full_name "linus"}]
            (p/execute-query live "SELECT id, full_name FROM people ORDER BY id" []))
        "every row survives under the new names")
      (is (converged? live declared-decl)
        "the live schema converged to the declaration"))))

(deftest swapped-renames-carry-the-data-across-the-rebuild
  (with-open [live (sql-jdbc/in-memory)]
    (p/execute-batch! live
      ["CREATE TABLE t (a INTEGER, b TEXT)"
       "INSERT INTO t (a, b) VALUES (1, 'x'), (2, 'y')"])
    (let [declared-decl ["CREATE TABLE t (a TEXT, b INTEGER)"]
          pl (plan-live live declared-decl
               [{:directive :rename-column :table "t" :from "a" :to "b"}
                {:directive :rename-column :table "t" :from "b" :to "a"}])]
      (is (empty? (:unhandled pl)))
      (m/apply! live pl)
      (is (= [{:a "x" :b 1} {:a "y" :b 2}]
            (p/execute-query live "SELECT a, b FROM t ORDER BY b" []))
        "the values crossed over with their columns")
      (is (converged? live declared-decl)))))

(deftest unauthorized-drop-blocks-apply-until-directed
  (with-open [live (sql-jdbc/in-memory)]
    (p/execute-batch! live
      ["CREATE TABLE keep (a INTEGER)"
       "CREATE TABLE old_stuff (z TEXT)"
       "INSERT INTO old_stuff (z) VALUES ('precious')"])
    (let [declared-decl ["CREATE TABLE keep (a INTEGER)"]]
      (testing "without a directive the plan refuses the drop and apply! blocks by default"
        (let [pl (plan-live live declared-decl [])]
          (is (= [[:needs-intent :destructive-drop]]
                (get (refusal-codes pl) [:table "old_stuff"])))
          (is (= :unhandled-refused
                (error-key #(m/apply! live pl)))
            "apply! must refuse a plan with unhandled entries by default")
          (is (= [{:z "precious"}]
                (p/execute-query live "SELECT z FROM old_stuff" []))
            "the data is untouched")))
      (testing "with the directive the drop plans and apply! proceeds"
        (let [pl (plan-live live declared-decl
                   [{:directive :drop-table :table "old_stuff"}])]
          (is (empty? (:unhandled pl)))
          (m/apply! live pl)
          (is (= [] (p/execute-query live
                      "SELECT name FROM sqlite_master WHERE name = 'old_stuff'" []))
            "the table is gone once directed")
          (is (converged? live declared-decl)))))))
