(ns sqlite-migrate.diff-test
  "The full Diff model (ADR 0004) plus the no-op and round-trip
  properties (ADR 0003, 0010) against the nasty corpus — through the
  public `sqlite-migrate.core/diff` seam on real in-memory SQLite."
  (:require [clojure.test :refer [deftest is testing]]
    [sqlite-migrate.core :as m]
    [sqlite-migrate.corpus :as corpus]
    [sqlite-migrate.jdbc :as sql-jdbc]))

(defn- snap
  "Snapshot of `declaration` realized into a fresh in-memory pristine
  database."
  [declaration]
  (with-open [conn (sql-jdbc/in-memory)]
    (m/declared-snapshot conn declaration)))

(defn- empty-snap []
  (with-open [conn (sql-jdbc/in-memory)]
    (m/snapshot conn)))

(defn- diff-of
  "Diff of two declarations, each realized into its own pristine
  database."
  [live-decl declared-decl]
  (m/diff (snap live-decl) (snap declared-decl)))

(defn- kinds+paths [d]
  (mapv (juxt :kind :path) (:entries d)))

;; ---------------------------------------------------------------------------
;; One-sided objects: one whole-value entry, verbatim sub-value embedded

(deftest one-sided-objects-are-single-whole-entries
  (let [d (m/diff (empty-snap) (snap corpus/nasty-declaration))]
    (testing "one entry per declared-only object — never per-column entries"
      (is (= [[:added [:table "items"]]
              [:added [:table "notes"]]
              [:added [:table "order"]]
              [:added [:table "shipments"]]
              [:added [:view "v_totals"]]]
            (kinds+paths d))))
    (testing "the whole verbatim sub-value rides on the entry, stored CREATE sql included"
      (let [order (some #(when (= [:table "order"] (:path %)) (:declared %)) (:entries d))]
        (is (= (nth corpus/nasty-declaration 0) (:sql order)))
        (is (= ["id" "group" "total"] (mapv :name (:columns order))))
        (is (= (nth corpus/nasty-declaration 6)
              (get-in order [:triggers "trg_order_touch" :sql]))
          "nested trigger sql is embedded in the value, not left in meta"))
      (let [items (some #(when (= [:table "items"] (:path %)) (:declared %)) (:entries d))]
        (is (= (nth corpus/nasty-declaration 2)
              (get-in items [:indexes "idx_items_qty" :sql]))
          "nested index sql is embedded in the value, not left in meta")))
    (testing "the mirror-image diff reports the same objects as :removed"
      (is (= [[:removed [:table "items"]]
              [:removed [:table "notes"]]
              [:removed [:table "order"]]
              [:removed [:table "shipments"]]
              [:removed [:view "v_totals"]]]
            (kinds+paths (m/diff (snap corpus/nasty-declaration) (empty-snap))))))))

;; ---------------------------------------------------------------------------
;; Fine-grained entries inside a changed table

(deftest changed-table-yields-fine-grained-column-entries
  (let [d (diff-of
            ["CREATE TABLE t (a INTEGER, b TEXT DEFAULT 'x', c REAL)"]
            ["CREATE TABLE t (a INT, b TEXT DEFAULT 'y' NOT NULL, d TEXT)"])]
    (testing "changed columns carry their differing-fact keywords, 1:1 with the compared facts"
      (is (= [[:changed [:table "t" :column "a"] #{:type}]
              [:changed [:table "t" :column "b"] #{:default :not-null?}]
              [:added [:table "t" :column "d"] nil]
              [:removed [:table "t" :column "c"] nil]]
            (mapv (juxt :kind :path :facts) (:entries d)))
        "added/changed columns in declared order, removed columns after, in live order"))
    (testing "both sides' verbatim column values ride on a changed entry"
      (let [b (some #(when (= [:table "t" :column "b"] (:path %)) %) (:entries d))]
        (is (= "'x'" (get-in b [:live :default])))
        (is (= "'y'" (get-in b [:declared :default])))))))

(deftest table-scoped-facts-yield-one-table-level-entry
  (testing "column order and primary-key order are table-scoped facts, not column entries"
    (is (= [[:changed [:table "t"] #{:column-order :primary-key}]]
          (mapv (juxt :kind :path :facts)
            (:entries (diff-of
                        ["CREATE TABLE t (a INTEGER, b INTEGER, PRIMARY KEY (a, b))"]
                        ["CREATE TABLE t (b INTEGER, a INTEGER, PRIMARY KEY (b, a))"]))))))
  (testing "STRICT and WITHOUT ROWID are table-scoped facts"
    (is (= [[:changed [:table "s"] #{:strict?}]]
          (mapv (juxt :kind :path :facts)
            (:entries (diff-of
                        ["CREATE TABLE s (a INTEGER PRIMARY KEY)"]
                        ["CREATE TABLE s (a INTEGER PRIMARY KEY) STRICT"])))))))

(deftest changed-index-carries-differing-fact-keywords
  (let [d (diff-of
            ["CREATE TABLE t (a INTEGER, b INTEGER)"
             "CREATE INDEX idx ON t (a)"]
            ["CREATE TABLE t (a INTEGER, b INTEGER)"
             "CREATE UNIQUE INDEX idx ON t (a DESC) WHERE a > 0"])]
    (is (= [[:changed [:table "t" :index "idx"] #{:unique? :partial? :columns :where}]]
          (mapv (juxt :kind :path :facts) (:entries d))))))

;; ---------------------------------------------------------------------------
;; Constraint pairing

(deftest named-constraints-pair-by-folded-name
  (let [d (diff-of
            ["CREATE TABLE t (x INTEGER, CONSTRAINT C1 CHECK (x > 0))"]
            ["CREATE TABLE t (x INTEGER, CONSTRAINT c1 CHECK (x >= 0))"])]
    (is (= [[:changed [:table "t" :check "c1"] #{:expr}]]
          (mapv (juxt :kind :path :facts) (:entries d)))
      "name case is Noise; the expr difference is the reported fact")))

(deftest unnamed-constraints-pair-by-token-equality
  (testing "token-equal unnamed constraints pair and vanish; the remainder is added/removed"
    (is (= [[:removed [:table "t" :check [:live 0]]]
            [:added [:table "t" :check [:declared 1]]]]
          (kinds+paths (diff-of
                         ["CREATE TABLE t (x INTEGER, y INTEGER, CHECK (x > 0), CHECK (y > 0))"]
                         ["CREATE TABLE t (x INTEGER, y INTEGER, CHECK (y>0), CHECK (x < 5))"])))))
  (testing "one inserted unnamed constraint does not misalign the later pairings"
    (is (= [[:added [:table "t" :check [:declared 0]]]]
          (kinds+paths (diff-of
                         ["CREATE TABLE t (a INTEGER, b INTEGER, CHECK (a > 0), CHECK (b > 0))"]
                         ["CREATE TABLE t (a INTEGER, b INTEGER, CHECK (a < 9), CHECK (a > 0), CHECK (b > 0))"]))))))

(deftest unpaired-unnamed-constraint-paths-are-unique-across-sides
  ;; a live and a declared unnamed CHECK can share source index 0; their
  ;; entry paths must still differ — :serves sets and the completeness
  ;; invariant match entries by path
  (let [d (diff-of ["CREATE TABLE t (x INTEGER, CHECK (x > 0))"]
            ["CREATE TABLE t (x INTEGER, CHECK (x < 5))"])]
    (is (= [[:removed [:table "t" :check [:live 0]]]
            [:added [:table "t" :check [:declared 0]]]]
          (kinds+paths d)))
    (is (= 2 (count (into #{} (map :path) (:entries d))))
      "the two entries' paths are distinct")))

;; ---------------------------------------------------------------------------
;; Changed views collapse to one whole-value entry (ADR 0004)

(deftest changed-view-is-one-whole-value-entry
  (testing "a changed view body is one :changed entry — fine-grained entries exist only inside a changed table"
    (is (= [[:changed [:view "v"] #{:sql}]]
          (mapv (juxt :kind :path :facts)
            (:entries (diff-of
                        ["CREATE TABLE t (a INTEGER)"
                         "CREATE VIEW v AS SELECT a FROM t"]
                        ["CREATE TABLE t (a INTEGER)"
                         "CREATE VIEW v AS SELECT a, a + 1 AS b FROM t"]))))))
  (testing "a trigger difference on an otherwise-identical view is the view's :triggers fact"
    (is (= [[:changed [:view "v"] #{:triggers}]]
          (mapv (juxt :kind :path :facts)
            (:entries (diff-of
                        ["CREATE TABLE t (a INTEGER)"
                         "CREATE VIEW v AS SELECT a FROM t"]
                        ["CREATE TABLE t (a INTEGER)"
                         "CREATE VIEW v AS SELECT a FROM t"
                         "CREATE TRIGGER trg INSTEAD OF INSERT ON v BEGIN SELECT 1; END"])))))))

;; ---------------------------------------------------------------------------
;; Determinism and serialization

(deftest diff-is-deterministic-and-round-trips-through-pr-str
  (let [a (m/diff (snap corpus/nasty-declaration) (snap [(first corpus/nasty-declaration)]))
        b (m/diff (snap corpus/nasty-declaration) (snap [(first corpus/nasty-declaration)]))]
    (testing "identical Snapshot pairs yield byte-identical serialized Diffs"
      (is (= (pr-str (dissoc a :live-metadata :declared-metadata))
            (pr-str (dissoc b :live-metadata :declared-metadata)))))
    (testing "the whole Diff is plain EDN and survives pr-str/read-string"
      (is (= a (read-string (pr-str a)))))))

;; ---------------------------------------------------------------------------
;; Properties on the corpus (ADR 0003, 0010)

(deftest no-op-property-on-the-corpus
  (testing "diff is empty iff the Snapshots are Equivalent"
    (let [a (snap corpus/nasty-declaration)
          b (snap corpus/nasty-declaration)]
      (is (m/equivalent? a b))
      (is (= [] (:entries (m/diff a b))))
      (is (not (m/drift? (m/diff a b)))))
    (let [a (snap corpus/nasty-declaration)
          b (empty-snap)]
      (is (not (m/equivalent? a b)))
      (is (seq (:entries (m/diff a b))))))
  (testing "perturbing one Semantic fact makes the diff non-empty"
    (let [a (snap ["CREATE TABLE t (a INT, b TEXT)"])
          b (snap ["CREATE TABLE t (a INTEGER, b TEXT)"])]
      (is (not (m/equivalent? a b)) "declared type text is Semantic")
      (is (seq (:entries (m/diff a b))))))
  (testing "perturbing only Noise facts keeps the diff empty"
    (let [a (snap ["CREATE TABLE t (a INT, b TEXT, CHECK (a > 0))"])
          b (snap ["CREATE TABLE \"T\" (\"A\" INT, b TEXT, CHECK ( A>0 ))"])]
      (is (m/equivalent? a b) "identifier case, quoting, and expression whitespace are Noise")
      (is (= [] (:entries (m/diff a b)))))))

(defn- emit-stored-sql
  "Every stored CREATE sql in `snapshot`, in a dependency-safe order:
  tables, then indexes, then views, then triggers."
  [snapshot]
  (let [sql (comp :sql meta)
        tables (map val (sort-by key (:tables snapshot)))
        views (map val (sort-by key (:views snapshot)))
        nested (fn [k objects]
                 (map sql (mapcat #(map val (sort-by key (k %))) objects)))]
    (concat
      (map sql tables)
      (nested :indexes (remove :virtual? tables))
      (map sql views)
      (nested :triggers (concat (remove :virtual? tables) views)))))

(deftest round-trip-property-on-the-corpus
  (testing "introspect, emit stored CREATE sql into a pristine database, introspect: Equivalent"
    (let [original (snap corpus/nasty-declaration)
          re-introspected (snap (vec (emit-stored-sql original)))]
      (is (m/equivalent? original re-introspected))
      (is (= [] (:entries (m/diff original re-introspected)))))))
