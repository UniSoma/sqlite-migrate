(ns sqlite-migrate.directive-set-test
  "The Directive set (ADR 0020) through `sqlite-migrate.directives`,
  its output fed to the public `sqlite-migrate.core/plan` on real
  in-memory SQLite: each step's contract on a small live/declared
  pair, the rename-before-drop ordering rule in both directions, a
  half-matched rename still suppressing the derived drop, the
  select-list case-fold, and the property that deriving every drop
  leaves no `:destructive-drop` refusal unhandled."
  (:require [clojure.test :refer [deftest is testing]]
    [clojure.test.check.clojure-test :refer [defspec]]
    [clojure.test.check.properties :as prop]
    [sqlite-migrate.core :as m]
    [sqlite-migrate.directives :as d]
    [sqlite-migrate.generators :as g]
    [sqlite-migrate.jdbc :as sql-jdbc]
    [sqlite-migrate.schema :as schema]
    [sqlite-migrate.test-util :refer [thrown-info trials]]))

(defn- snap
  "Snapshot of `declaration` realized into a fresh in-memory pristine
  database."
  [declaration]
  (with-open [conn (sql-jdbc/in-memory)]
    (m/declared-snapshot conn declaration)))

(def ^:private live
  ["CREATE TABLE Users (id INTEGER PRIMARY KEY, Name TEXT)"
   "CREATE TABLE orders (id INTEGER PRIMARY KEY, note TEXT, qty INT)"
   "CREATE TABLE gone (id INTEGER)"])

(def ^:private declared
  ["CREATE TABLE people (id INTEGER PRIMARY KEY, full_name TEXT)"
   "CREATE TABLE orders (id INTEGER PRIMARY KEY)"])

(defn- fixture
  "`{:live :declared :diff}` of the module-level pair."
  []
  (let [live-snap (snap live)
        declared-snap (snap declared)]
    {:live live-snap :declared declared-snap :diff (m/diff live-snap declared-snap)}))

(defn- plan-with
  "Plan the fixture's Diff under `directives`."
  [{:keys [live declared diff]} directives]
  (m/plan live declared diff {:directives directives}))

(defn- refusal-codes
  "The Refusal codes of every unhandled entry, keyed by entry path."
  [pl]
  (into {}
    (map (fn [{:keys [entry refusals]}]
           [(:path entry) (mapv :code refusals)]))
    (:unhandled pl)))

;; ---------------------------------------------------------------------------
;; Each step on its own

(deftest against-opens-a-set-bound-to-one-diff
  (let [{:keys [diff]} (fixture)]
    (testing "an empty set carries the Diff and no Directives"
      (is (= {:diff diff :directives []} (d/against diff))))
    (testing "initial Directives seed the vector verbatim"
      (is (= [{:directive :drop-table :table "x"}]
            (:directives (d/against diff [{:directive :drop-table :table "x"}])))))
    (testing "anything but a Diff is malformed input"
      (is (= :malformed-input
            (:sqlite-migrate/error (ex-data (thrown-info (d/against {:entries []})))))
        "a map without provenance is not a Diff")
      (is (= :malformed-input
            (:sqlite-migrate/error (ex-data (thrown-info (d/against nil)))))))))

(deftest rename-tables-appends-literal-directives-in-folded-name-order
  (let [{:keys [diff]} (fixture)]
    (is (= [{:directive :rename-table :from "Alpha" :to "a2"}
            {:directive :rename-table :from "beta" :to "b2"}]
          (-> (d/against diff)
            (d/rename-tables {"beta" "b2" "Alpha" "a2"})
            (d/build)))
      "entries ride verbatim, ordered by folded live name")))

(deftest rename-columns-appends-literal-directives-in-folded-name-order
  (let [{:keys [diff]} (fixture)]
    (is (= [{:directive :rename-column :table "Orders" :from "Qty" :to "q"}
            {:directive :rename-column :table "users" :from "age" :to "years"}
            {:directive :rename-column :table "users" :from "Name" :to "full_name"}]
          (-> (d/against diff)
            (d/rename-columns {"users" {"Name" "full_name" "age" "years"}
                               "Orders" {"Qty" "q"}})
            (d/build))))))

(deftest drop-tables-derives-one-directive-per-removed-table-in-diff-order
  (let [{:keys [diff]} (fixture)]
    (testing "every removed table, live spelling verbatim"
      (is (= [{:directive :drop-table :table "gone"}
              {:directive :drop-table :table "Users"}]
            (-> (d/against diff) (d/drop-tables) (d/build)))))
    (testing "a select list narrows to the named removed tables, case-folded"
      (is (= [{:directive :drop-table :table "Users"}]
            (-> (d/against diff) (d/drop-tables ["USERS"]) (d/build)))))
    (testing "a name the Diff never removed yields nothing"
      (is (= [] (-> (d/against diff) (d/drop-tables ["orders" "nope"]) (d/build)))))))

(deftest drop-columns-derives-one-directive-per-removed-column-in-diff-order
  (let [{:keys [diff]} (fixture)]
    (testing "every removed column of every changed table"
      (is (= [{:directive :drop-column :table "orders" :column "note"}
              {:directive :drop-column :table "orders" :column "qty"}]
            (-> (d/against diff) (d/drop-columns) (d/build)))))
    (testing "a table select list, case-folded"
      (is (= [{:directive :drop-column :table "orders" :column "note"}
              {:directive :drop-column :table "orders" :column "qty"}]
            (-> (d/against diff) (d/drop-columns ["ORDERS"]) (d/build))))
      (is (= [] (-> (d/against diff) (d/drop-columns ["users"]) (d/build)))
        "a removed table's columns are not removed-column entries"))))

(deftest build-extracts-the-directives-vector-as-plan-takes-it
  (let [{:keys [diff] :as fx} (fixture)
        directives (-> (d/against diff) (d/drop-tables) (d/drop-columns) (d/build))]
    (is (vector? directives))
    (is (= directives (:directives (plan-with fx directives)))
      "the Plan echoes exactly the built vector")))

;; ---------------------------------------------------------------------------
;; Ordering: renames claim, drops derive around them

(deftest rename-then-drop-excludes-the-renamed-objects
  (let [{:keys [diff] :as fx} (fixture)
        directives (-> (d/against diff)
                     (d/rename-tables {"users" "people"})
                     (d/rename-columns {"users" {"name" "full_name"}})
                     (d/drop-tables)
                     (d/drop-columns ["orders"])
                     (d/build))
        pl (plan-with fx directives)]
    (testing "literals first in call order, derived drops after, the renamed table skipped"
      (is (= [{:directive :rename-table :from "users" :to "people"}
              {:directive :rename-column :table "users" :from "name" :to "full_name"}
              {:directive :drop-table :table "gone"}
              {:directive :drop-column :table "orders" :column "note"}
              {:directive :drop-column :table "orders" :column "qty"}]
            directives)))
    (testing "the planner accepts the set and handles every entry"
      (is (= [] (:unhandled pl)))
      (is (= [] (:unused-directives pl))))))

(deftest drop-then-rename-is-the-conflict-the-planner-rejects
  (let [{:keys [diff] :as fx} (fixture)
        directives (-> (d/against diff)
                     (d/drop-tables)
                     (d/rename-tables {"users" "people"})
                     (d/build))]
    (is (some #(= {:directive :drop-table :table "Users"} %) directives)
      "a drop derived before the rename is emitted, not subtracted")
    (is (= :malformed-input
          (:sqlite-migrate/error (ex-data (thrown-info (plan-with fx directives))))))))

(deftest half-matched-rename-still-suppresses-the-derived-drop
  (let [{:keys [diff] :as fx} (fixture)
        directives (-> (d/against diff)
                     (d/rename-tables {"users" "nobody"})
                     (d/drop-tables)
                     (d/build))
        pl (plan-with fx directives)]
    (is (= [{:directive :rename-table :from "users" :to "nobody"}
            {:directive :drop-table :table "gone"}]
          directives)
      "the claim is live-side only: the rename's :from folds to the removed table")
    (is (= [{:directive :rename-table :from "users" :to "nobody"}] (:unused-directives pl))
      "the Plan reports the rename unused")
    (is (= [:destructive-drop] (get (refusal-codes pl) [:table "Users"]))
      "and the table's entry stays unhandled")))

;; ---------------------------------------------------------------------------
;; Property: deriving every drop leaves no :destructive-drop refusal

(defn- snap-of [schema-value]
  (snap (schema/->sql schema-value)))

(defspec derived-drops-lift-every-destructive-drop-refusal trials
  (prop/for-all [scenario g/gen-rowless-scenario]
    (let [live (snap-of (:live scenario))
          declared (snap-of (:target scenario))
          diff (m/diff live declared)
          directives (-> (d/against diff) (d/drop-tables) (d/drop-columns) (d/build))
          pl (m/plan live declared diff {:directives directives})]
      (not-any? (fn [{:keys [refusals]}]
                  (some #(= :destructive-drop (:code %)) refusals))
        (:unhandled pl)))))
