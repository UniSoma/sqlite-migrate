(ns sqlite-migrate.rebuild-test
  "The :rebuild-table composite Op (ADR 0006, 0008, 0010) — the 12-step
  generalized ALTER TABLE compiled wholly at plan time — through the
  public `sqlite-migrate.core/plan` and `apply!` seams on real
  in-memory SQLite. Covers the locked internal statement order, the
  per-table selection rule, dependent recreation, copy semantics, and
  the residual-convergence and data-preservation properties."
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
  pristine database, with the Snapshots supplied as planning context."
  ([live-decl declared-decl] (plan-of live-decl declared-decl {}))
  ([live-decl declared-decl opts]
    (let [live (snap live-decl)
          declared (snap declared-decl)]
      (m/plan live declared (m/diff live declared) opts))))

(defn- refusal-codes
  "The `[class code]` pairs of every Refusal on every unhandled entry,
  keyed by the entry's path."
  [pl]
  (into {}
    (map (fn [{:keys [entry refusals]}]
           [(:path entry) (mapv (juxt :class :code) refusals)]))
    (:unhandled pl)))

(defn- converges?
  "Apply `live-decl`'s statements to a fresh live database, plan against
  `declared-decl`, apply!, and report whether the residual diff is
  empty. Exercises the planned SQL against real SQLite."
  ([live-decl declared-decl] (converges? live-decl declared-decl {}))
  ([live-decl declared-decl apply-opts]
    (with-open [live (sql-jdbc/in-memory)
                pristine (sql-jdbc/in-memory)]
      (when (seq live-decl)
        (p/execute-batch! live (vec live-decl)))
      (let [live-snap (m/snapshot live)
            declared (m/declared-snapshot pristine declared-decl)
            pl (m/plan live-snap declared (m/diff live-snap declared))]
        (m/apply! live pl apply-opts)
        (not (m/drift? (m/diff (m/snapshot live) declared)))))))

;; ---------------------------------------------------------------------------
;; The composite op and its locked internal statement order (ADR 0006)

(deftest mid-table-column-insertion-plans-one-rebuild
  (let [live ["CREATE TABLE t (a INTEGER, b TEXT)"]
        declared ["CREATE TABLE t (a INTEGER, x INT, b TEXT)"]
        pl (plan-of live declared)]
    (testing "one :rebuild-table op serves the entry, its statement order locked: create under temp name, copy, drop old, rename new — never rename-first"
      (is (= [{:kind :rebuild-table
               :path [:table "t"]
               :serves #{[:table "t" :column "x"]}
               :sql ["CREATE TABLE \"t__sqm_rebuild\" (a INTEGER, x INT, b TEXT)"
                     "INSERT INTO \"t__sqm_rebuild\" (rowid, \"a\", \"b\") SELECT rowid, \"a\", \"b\" FROM \"t\""
                     "DROP TABLE \"t\""
                     "ALTER TABLE \"t__sqm_rebuild\" RENAME TO \"t\""]}]
            (:ops pl)))
      (is (empty? (:unhandled pl))))
    (testing "planning twice is pr-str-identical — rebuild SQL is under the determinism contract (ADR 0010)"
      (is (= (pr-str pl) (pr-str (plan-of live declared)))))
    (testing "the planned rebuild converges the live database"
      (is (converges? live declared)))))

;; ---------------------------------------------------------------------------
;; Per-table selection rule: all in-place or one rebuild, never mixed

(deftest one-rebuild-subsumes-the-tables-whole-change-set
  ;; a retyped column is rebuild-only; the appendable sibling column
  ;; must ride the same rebuild instead of planning an :add-column
  (let [live ["CREATE TABLE t (a INTEGER, b TEXT)"]
        declared ["CREATE TABLE t (a INTEGER, b BLOB, c INT)"]
        pl (plan-of live declared)]
    (is (= [:rebuild-table] (mapv :kind (:ops pl)))
      "never mix in-place and rebuild for one table")
    (is (= [#{[:table "t" :column "b"] [:table "t" :column "c"]}]
          (mapv :serves (:ops pl)))
      "the one rebuild serves every entry of the table")
    (is (empty? (:unhandled pl)))
    (is (converges? live declared)))
  (testing "an unrelated table still plans in place — the rule is per table"
    (let [pl (plan-of ["CREATE TABLE t (a INTEGER, b TEXT)" "CREATE TABLE u (x INTEGER)"]
               ["CREATE TABLE t (a INTEGER, b BLOB)" "CREATE TABLE u (x INTEGER, y INT)"])]
      (is (= [:rebuild-table :add-column] (mapv :kind (:ops pl)))))))

(deftest rebuild-disabled-capability-keeps-the-refusal
  (let [pl (plan-of ["CREATE TABLE t (a INTEGER, b TEXT)"]
             ["CREATE TABLE t (a INTEGER, x INT, b TEXT)"]
             {:capabilities {:rebuild? false}})]
    (is (empty? (:ops pl)))
    (is (= {[:table "t" :column "x"] [[:incapable :rebuild-disabled]]}
          (refusal-codes pl)))))

(deftest destructive-drop-blocks-the-rebuild
  ;; ADR 0007: column drops never auto-plan — a rebuild that would
  ;; silently skip the dropped column's data is still a destructive
  ;; drop, so the whole table stays honestly unhandled and every entry
  ;; carries the blocking :needs-intent Refusal
  (let [pl (plan-of ["CREATE TABLE t (a INTEGER, gone TEXT)"]
             ["CREATE TABLE t (a BLOB)"])]
    (is (empty? (:ops pl)))
    (is (= {[:table "t" :column "a"] [[:needs-intent :destructive-drop]]
            [:table "t" :column "gone"] [[:needs-intent :destructive-drop]]}
          (refusal-codes pl)))))

(deftest unsupported-declared-shape-blocks-the-rebuild
  ;; the rebuild would CREATE the declared table on the target, so a
  ;; declared shape the target version cannot hold blocks it
  (let [pl (plan-of ["CREATE TABLE t (a INTEGER, b TEXT)"]
             ["CREATE TABLE t (a INTEGER, x INT, b TEXT) STRICT"]
             {:capabilities {:sqlite-version "3.30.0"}})]
    (is (empty? (:ops pl)))
    (is (= [[:incapable :unsupported-by-target-version]]
          (get (refusal-codes pl) [:table "t"])))
    (is (= [[:incapable :unsupported-by-target-version]]
          (get (refusal-codes pl) [:table "t" :column "x"]))
      "the sibling entry carries the blocking Refusal too")))

;; ---------------------------------------------------------------------------
;; Dependent indexes, triggers, and views recreate after the rebuild

(deftest rebuild-recreates-dependent-indexes-triggers-and-views
  ;; the table's own index and trigger vanish with DROP TABLE; the
  ;; surviving view and the other table's trigger reference the table
  ;; and would fail the rename while it is gone — all four must be
  ;; standing again after apply!
  (let [shared ["CREATE TABLE watcher (n INTEGER)"
                "CREATE INDEX t_idx ON t (b)"
                "CREATE TRIGGER t_trg AFTER INSERT ON t BEGIN SELECT 1; END"
                "CREATE VIEW t_view AS SELECT a, b FROM t"
                "CREATE TRIGGER watcher_trg AFTER INSERT ON watcher BEGIN SELECT count(*) FROM t; END"]
        live (into ["CREATE TABLE t (a INTEGER, b TEXT)"] shared)
        declared (into ["CREATE TABLE t (a INTEGER, x INT, b TEXT)"] shared)
        pl (plan-of live declared)]
    (testing "one rebuild op carries the whole procedure"
      (is (= [:rebuild-table] (mapv :kind (:ops pl))))
      (is (empty? (:unhandled pl))))
    (testing "dependents drop before the old table and recreate after the rename"
      (let [sql (:sql (first (:ops pl)))
            pos (fn [needle] (first (keep-indexed
                                      (fn [i ^String s] (when (.contains s needle) i))
                                      sql)))]
        (is (< (pos "DROP VIEW \"t_view\"") (pos "DROP TABLE \"t\"")))
        (is (< (pos "DROP TRIGGER \"watcher_trg\"") (pos "DROP TABLE \"t\"")))
        (is (< (pos "RENAME TO \"t\"") (pos "CREATE INDEX t_idx")))
        (is (< (pos "CREATE INDEX t_idx") (pos "CREATE TRIGGER t_trg")))
        (is (< (pos "CREATE TRIGGER t_trg") (pos "CREATE VIEW t_view")))
        (is (some? (pos "CREATE TRIGGER watcher_trg")))))
    (testing "the rebuild executes and every dependent is standing afterwards"
      (is (converges? live declared)))))

;; ---------------------------------------------------------------------------
;; Data preservation (ADR 0010): multiset row survival, rowid stability,
;; AUTOINCREMENT continuity

(defn- apply-rebuild!
  "Plan `live` against `declared-decl` and apply!, asserting the plan
  is exactly one :rebuild-table op. Returns the Plan."
  [live declared-decl]
  (with-open [pristine (sql-jdbc/in-memory)]
    (let [live-snap (m/snapshot live)
          declared (m/declared-snapshot pristine declared-decl)
          pl (m/plan live-snap declared (m/diff live-snap declared))]
      (is (= [:rebuild-table] (mapv :kind (:ops pl)))
        "the scenario must plan as exactly one rebuild")
      (m/apply! live pl)
      pl)))

(deftest rebuild-preserves-rows-as-multisets-with-declared-defaults
  ;; reorder forces the rebuild; the copy maps columns strictly by
  ;; name, so the reorder must not shear values across columns, the
  ;; new column takes its declared default, and duplicate rows survive
  ;; as a multiset
  (with-open [live (sql-jdbc/in-memory)]
    (p/execute-batch! live
      ["CREATE TABLE t (a INTEGER, b TEXT)"
       "INSERT INTO t (a, b) VALUES (1, 'x'), (2, 'y'), (2, 'y'), (NULL, 'z')"])
    (apply-rebuild! live ["CREATE TABLE t (b TEXT, a INTEGER, c INT DEFAULT 7)"])
    (is (= (frequencies [{:a 1 :b "x" :c 7} {:a 2 :b "y" :c 7}
                         {:a 2 :b "y" :c 7} {:a nil :b "z" :c 7}])
          (frequencies (p/execute-query live "SELECT a, b, c FROM t" []))))))

(deftest rebuild-copies-rowid-explicitly-for-plain-rowid-tables
  ;; no INTEGER PRIMARY KEY alias on either side: only an explicit
  ;; rowid copy keeps row identities stable across the rebuild
  (with-open [live (sql-jdbc/in-memory)]
    (p/execute-batch! live
      ["CREATE TABLE t (a TEXT, dead INTEGER GENERATED ALWAYS AS (1) VIRTUAL)"
       "INSERT INTO t (a) VALUES ('x'), ('y'), ('z')"
       "DELETE FROM t WHERE rowid = 2"])
    (apply-rebuild! live ["CREATE TABLE t (n INT, a TEXT)"])
    (is (= [{:rowid 1 :a "x"} {:rowid 3 :a "z"}]
          (p/execute-query live "SELECT rowid, a FROM t ORDER BY rowid" []))
      "row identities survive, not just values")))

(deftest rebuild-restores-the-autoincrement-counter
  ;; AUTOINCREMENT on both sides: the next inserted id must stay
  ;; greater than any id ever issued, even after the highest row died
  (with-open [live (sql-jdbc/in-memory)]
    (p/execute-batch! live
      ["CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT, a TEXT)"
       "INSERT INTO t (a) VALUES ('x'), ('y'), ('z')"
       "DELETE FROM t WHERE id > 1"])
    (apply-rebuild! live ["CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT, n INT, a TEXT)"])
    (p/execute-batch! live ["INSERT INTO t (a) VALUES ('w')"])
    (is (= [{:id 1 :a "x"} {:id 4 :a "w"}]
          (p/execute-query live "SELECT id, a FROM t ORDER BY id" []))
      "ids 2 and 3 were issued and must never be reissued")))

(deftest rebuild-preserves-shared-rows-into-a-without-rowid-shape
  ;; rowid stability is explicitly void when either side is WITHOUT
  ;; ROWID (ADR 0010) — but the values still survive as a multiset
  (with-open [live (sql-jdbc/in-memory)]
    (p/execute-batch! live
      ["CREATE TABLE t (k TEXT PRIMARY KEY, v INT)"
       "INSERT INTO t (k, v) VALUES ('a', 1), ('b', 2)"])
    (apply-rebuild! live ["CREATE TABLE t (k TEXT PRIMARY KEY, v INT) WITHOUT ROWID"])
    (is (= [{:k "a" :v 1} {:k "b" :v 2}]
          (p/execute-query live "SELECT k, v FROM t ORDER BY k" [])))))

;; ---------------------------------------------------------------------------
;; Residual convergence (ADR 0010): the post-apply diff equals exactly
;; the Plan's unhandled entries; re-planning reaches the fixpoint

(deftest residual-convergence-holds-through-a-rebuild
  ;; the plan mixes a rebuild for t with an unhandled destructive table
  ;; drop — after apply! the residual diff must be exactly that entry,
  ;; no more, no less
  (with-open [live (sql-jdbc/in-memory)
              pristine (sql-jdbc/in-memory)]
    (p/execute-batch! live
      ["CREATE TABLE t (a INTEGER, b TEXT)"
       "CREATE TABLE gone (z INTEGER)"
       "INSERT INTO t (a, b) VALUES (1, 'x'), (2, 'y')"])
    (let [declared-decl ["CREATE TABLE t (a INTEGER, x INT, b TEXT)"]
          live-snap (m/snapshot live)
          declared (m/declared-snapshot pristine declared-decl)
          pl (m/plan live-snap declared (m/diff live-snap declared))]
      (is (= [:rebuild-table] (mapv :kind (:ops pl))))
      (is (= [[:table "gone"]] (mapv (comp :path :entry) (:unhandled pl))))
      (m/apply! live pl {:allow-unhandled? true})
      (testing "the residual diff is exactly the Plan's unhandled entries"
        (is (= (mapv :entry (:unhandled pl))
              (:entries (m/diff (m/snapshot live) declared)))))
      (testing "fixpoint corollary: re-planning yields zero ops and the same unhandled entries"
        (let [live-snap2 (m/snapshot live)
              pl2 (m/plan live-snap2 declared (m/diff live-snap2 declared))]
          (is (empty? (:ops pl2)))
          (is (= (mapv :entry (:unhandled pl)) (mapv :entry (:unhandled pl2))))))
      (testing "and the rebuilt table's rows survived alongside"
        (is (= [{:a 1 :b "x" :x nil} {:a 2 :b "y" :x nil}]
              (p/execute-query live "SELECT a, b, x FROM t ORDER BY a" [])))))))

(deftest empty-unhandled-rebuild-plans-reach-full-equivalence
  ;; corollary of residual convergence with an empty unhandled
  ;; collection: apply! converges the schema completely, rebuild included
  (is (converges? ["CREATE TABLE t (a INTEGER, b TEXT, CONSTRAINT c CHECK (a > 0))"
                   "CREATE INDEX idx ON t (b)"]
        ["CREATE TABLE t (a INTEGER, x INT, b BLOB NOT NULL DEFAULT '')"
         "CREATE INDEX idx ON t (b)"])))
