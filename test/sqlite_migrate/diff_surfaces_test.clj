(ns sqlite-migrate.diff-surfaces-test
  "The diff-as-product surfaces beyond `drift?` (ADR 0005):
  `drift-report`, the presentation-only Diff-to-string renderer, and
  `by-object`, the one nesting view — through the public
  `sqlite-migrate.core` seam on real in-memory SQLite."
  (:require [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [sqlite-migrate.core :as m]
    [sqlite-migrate.jdbc :as sql-jdbc]))

(defn- snap
  "Snapshot of `declaration` realized into a fresh in-memory pristine
  database."
  [declaration]
  (with-open [conn (sql-jdbc/in-memory)]
    (m/declared-snapshot conn declaration)))

(defn- diff-of
  "Diff of two declarations, each realized into its own pristine
  database."
  [live-decl declared-decl]
  (m/diff (snap live-decl) (snap declared-decl)))

;; ---------------------------------------------------------------------------
;; drift-report

(deftest drift-report-renders-changed-objects-per-fact
  (let [report (m/drift-report
                 (diff-of
                   ["CREATE TABLE t (a INTEGER, b TEXT DEFAULT 'x')"]
                   ["CREATE TABLE t (a INT, b TEXT DEFAULT 'y' NOT NULL) STRICT"]))]
    (testing "each changed entry names its path and each differing fact with both sides' values"
      (is (str/includes? report "[:table \"t\"]"))
      (is (str/includes? report ":strict?"))
      (is (str/includes? report "[:table \"t\" :column \"b\"]"))
      (is (str/includes? report ":default"))
      (is (str/includes? report ":not-null?"))
      (is (str/includes? report "'x'") "the live side's value appears")
      (is (str/includes? report "'y'") "the declared side's value appears"))))

(deftest drift-report-renders-one-sided-objects-as-verbatim-create-sql
  (let [added-sql "CREATE TABLE items (id INTEGER PRIMARY KEY, qty INT)"
        removed-sql "CREATE TABLE legacy (id INTEGER PRIMARY KEY)"
        report (m/drift-report (diff-of [removed-sql] [added-sql]))]
    (testing "an added object prints its whole verbatim CREATE sql, never an EDN dump"
      (is (str/includes? report added-sql)))
    (testing "a removed object prints its whole verbatim CREATE sql"
      (is (str/includes? report removed-sql)))))

(deftest drift-report-renders-sql-less-one-sided-entries-as-their-sub-value
  (let [report (m/drift-report
                 (diff-of
                   ["CREATE TABLE t (a INTEGER, c REAL)"]
                   ["CREATE TABLE t (a INTEGER, d TEXT)"]))]
    (testing "an added or removed column has no CREATE sql of its own — its verbatim sub-value renders instead"
      (is (str/includes? report "[:table \"t\" :column \"d\"]"))
      (is (str/includes? report "[:table \"t\" :column \"c\"]"))
      (is (str/includes? report "\"d\"") "the added column's value appears")
      (is (str/includes? report "\"c\"") "the removed column's value appears"))))

(deftest drift-report-renders-from-a-deserialized-diff-alone
  (let [d (diff-of
            ["CREATE TABLE t (a INTEGER)"
             "CREATE TABLE gone (x INTEGER)"]
            ["CREATE TABLE t (a INT NOT NULL)"
             "CREATE INDEX idx_t_a ON t (a)"])]
    (is (= (m/drift-report d)
          (m/drift-report (read-string (pr-str d))))
      "rendering a pr-str/read-string round-tripped Diff is identical")))

(deftest drift-report-is-deterministic
  (let [d (diff-of
            ["CREATE TABLE t (a INTEGER, b TEXT)"
             "CREATE TABLE gone (x INTEGER)"]
            ["CREATE TABLE t (b TEXT, a INT)"
             "CREATE VIEW v AS SELECT a FROM t"])]
    (is (= (m/drift-report d) (m/drift-report d))
      "the same Diff renders to the same string")))

;; ---------------------------------------------------------------------------
;; by-object

(deftest by-object-reunites-a-changed-table-with-its-children
  (let [d (diff-of
            ["CREATE TABLE t (a INTEGER, b INTEGER, CHECK (a > 0))"]
            ["CREATE TABLE t (b INTEGER, a INT, CHECK (a > 1)) STRICT"])
        groups (m/by-object d)]
    (testing "one group per object, keyed by the object's path"
      (is (= [[:table "t"]] (mapv :path groups))))
    (testing "the table-level entry and its fine-grained children sit in one group, entry order kept"
      (is (= (:entries d) (:entries (first groups)))
        "the group's entries are the flat entries, unchanged and in the locked order")
      (is (= [[:table "t"]
              [:table "t" :column "a"]
              [:table "t" :check [:live 0]]
              [:table "t" :check [:declared 0]]]
            (mapv :path (:entries (first groups))))
        "table-level entry first, then column and constraint children"))))

(deftest by-object-groups-one-sided-and-view-objects
  (let [d (diff-of
            ["CREATE TABLE gone (x INTEGER)"]
            ["CREATE TABLE fresh (y INTEGER)"
             "CREATE VIEW v AS SELECT y FROM fresh"])
        groups (m/by-object d)]
    (is (= [[:table "fresh"] [:table "gone"] [:view "v"]]
          (mapv :path groups))
      "groups appear in the locked entry order")
    (is (every? #(= 1 (count (:entries %))) groups)
      "a one-sided object is one whole-value entry in its own group")))

(deftest by-object-on-an-empty-diff
  (let [d (diff-of ["CREATE TABLE t (a INTEGER)"]
            ["CREATE TABLE t (a INTEGER)"])]
    (is (not (m/drift? d)))
    (is (= [] (m/by-object d)) "no entries, no groups")))
