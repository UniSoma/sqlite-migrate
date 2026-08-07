(ns sqlite-migrate.plan-test
  "In-place planning, Refusals, and Capabilities (ADR 0006, 0007, 0011)
  plus the Plan-determinism property (ADR 0010) — through the public
  `sqlite-migrate.core/plan` seam on real in-memory SQLite."
  (:require [clojure.test :refer [deftest is testing]]
    [sqlite-migrate.core :as m]
    [sqlite-migrate.corpus :as corpus]
    [sqlite-migrate.jdbc :as sql-jdbc]
    [sqlite-migrate.impl.plan :as pl]
    [sqlite-migrate.protocols :as p]
    [sqlite-migrate.test-util :refer [thrown-info]]))

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
      (m/plan (m/diff live declared)
        (merge {:live-snapshot live :declared-snapshot declared} opts)))))

(defn- kinds+sql [pl]
  (mapv (juxt :kind :sql) (:ops pl)))

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
            pl (m/plan (m/diff live-snap declared)
                 {:live-snapshot live-snap :declared-snapshot declared})]
        (m/apply! live pl apply-opts)
        (not (m/drift? (m/diff (m/snapshot live) declared)))))))

;; ---------------------------------------------------------------------------
;; Capabilities

(deftest capabilities-default-to-live-version-with-rebuild-allowed
  (let [live (snap ["CREATE TABLE t (a INTEGER)"])
        pl (m/plan (m/diff live (snap ["CREATE TABLE t (a INTEGER)"])))]
    (testing "the Capabilities are a flat map: the live Snapshot's SQLite version plus :rebuild? true"
      (is (= {:sqlite-version (:sqlite-version (meta live)) :rebuild? true}
            (:capabilities pl)))))
  (testing "supplied capabilities merge over the defaults"
    (let [pl (plan-of ["CREATE TABLE t (a INTEGER)"]
               ["CREATE TABLE t (a INTEGER)"]
               {:capabilities {:sqlite-version "3.30.0"}})]
      (is (= "3.30.0" (get-in pl [:capabilities :sqlite-version])))
      (is (true? (get-in pl [:capabilities :rebuild?]))))))

;; ---------------------------------------------------------------------------
;; Add column (append-only)

(deftest appended-columns-plan-as-add-column-ops
  (let [pl (plan-of ["CREATE TABLE t (a INTEGER)"]
             ["CREATE TABLE t (a INTEGER, b TEXT NOT NULL DEFAULT 'x', c INT)"])]
    (testing "one :add-column op per appended column, in declared order"
      (is (= [[:add-column ["ALTER TABLE \"t\" ADD COLUMN \"b\" TEXT NOT NULL DEFAULT 'x'"]]
              [:add-column ["ALTER TABLE \"t\" ADD COLUMN \"c\" INT"]]]
            (kinds+sql pl)))
      (is (= [#{[:table "t" :column "b"]} #{[:table "t" :column "c"]}]
            (mapv :serves (:ops pl))))
      (is (empty? (:unhandled pl))))
    (testing "the planned SQL converges the live database"
      (is (converges? ["CREATE TABLE t (a INTEGER)"]
            ["CREATE TABLE t (a INTEGER, b TEXT NOT NULL DEFAULT 'x', c INT)"])))))

(deftest column-level-constraint-spellings-are-never-silently-dropped
  ;; a column-level UNIQUE or REFERENCES surfaces as its own constraint
  ;; entry, which is rebuild-only and collapses the table — the
  ;; :add-column op (which cannot carry those clauses) never emits
  (testing "an added column with a column-level UNIQUE routes the table to unhandled"
    (let [pl (plan-of ["CREATE TABLE t (a INTEGER)"]
               ["CREATE TABLE t (a INTEGER, b TEXT UNIQUE)"])]
      (is (empty? (:ops pl)))
      (is (= {[:table "t" :column "b"] [[:incapable :rebuild-not-implemented]]
              [:table "t" :unique [:declared 0]] [[:incapable :rebuild-not-implemented]]}
            (refusal-codes pl)))))
  (testing "an added column with a column-level REFERENCES routes the table to unhandled"
    (let [pl (plan-of ["CREATE TABLE p (id INTEGER PRIMARY KEY)" "CREATE TABLE t (a INTEGER)"]
               ["CREATE TABLE p (id INTEGER PRIMARY KEY)"
                "CREATE TABLE t (a INTEGER, b INTEGER REFERENCES p(id))"])]
      (is (empty? (:ops pl)))
      (is (= {[:table "t" :column "b"] [[:incapable :rebuild-not-implemented]]
              [:table "t" :foreign-key [:declared 0]] [[:incapable :rebuild-not-implemented]]}
            (refusal-codes pl))))))

(deftest mid-table-column-insertion-is-rebuild-only
  (let [pl (plan-of ["CREATE TABLE t (a INTEGER, b TEXT)"]
             ["CREATE TABLE t (a INTEGER, x INT, b TEXT)"])]
    (testing "a column inserted mid-table cannot append in place — honestly unhandled"
      (is (empty? (:ops pl)))
      (is (= {[:table "t" :column "x"] [[:incapable :rebuild-not-implemented]]}
            (refusal-codes pl))))
    (testing "with :rebuild? false the code is :rebuild-disabled"
      (is (= {[:table "t" :column "x"] [[:incapable :rebuild-disabled]]}
            (refusal-codes
              (plan-of ["CREATE TABLE t (a INTEGER, b TEXT)"]
                ["CREATE TABLE t (a INTEGER, x INT, b TEXT)"]
                {:capabilities {:rebuild? false}})))))))

;; ---------------------------------------------------------------------------
;; ALTER COLUMN SET/DROP NOT NULL and ADD/DROP CHECK (3.53 gate)

(deftest not-null-changes-plan-behind-the-target-version-gate
  (let [live ["CREATE TABLE t (a INTEGER, b TEXT)"]
        declared ["CREATE TABLE t (a INTEGER, b TEXT NOT NULL)"]]
    (testing "SET NOT NULL plans on a 3.53 target"
      (is (= [[:set-not-null ["ALTER TABLE \"t\" ALTER COLUMN \"b\" SET NOT NULL"]]]
            (kinds+sql (plan-of live declared))))
      (is (converges? live declared)))
    (testing "DROP NOT NULL plans for the reverse direction"
      (is (= [[:drop-not-null ["ALTER TABLE \"t\" ALTER COLUMN \"b\" DROP NOT NULL"]]]
            (kinds+sql (plan-of declared live))))
      (is (converges? declared live)))
    (testing "below 3.53 the only route is a rebuild — honestly unhandled"
      (is (= {[:table "t" :column "b"] [[:incapable :rebuild-not-implemented]]}
            (refusal-codes (plan-of live declared
                             {:capabilities {:sqlite-version "3.45.0"}})))))))

(deftest check-constraints-plan-behind-the-target-version-gate
  (testing "a changed named CHECK plans as drop-then-add, both serving the entry"
    (let [live ["CREATE TABLE t (a INTEGER, CONSTRAINT c1 CHECK (a > 0))"]
          declared ["CREATE TABLE t (a INTEGER, CONSTRAINT c1 CHECK (a >= 0))"]
          pl (plan-of live declared)]
      (is (= [[:drop-check ["ALTER TABLE \"t\" DROP CONSTRAINT \"c1\""]]
              [:add-check ["ALTER TABLE \"t\" ADD CONSTRAINT \"c1\" CHECK (a >= 0)"]]]
            (kinds+sql pl)))
      (is (= [#{[:table "t" :check "c1"]} #{[:table "t" :check "c1"]}]
            (mapv :serves (:ops pl))))
      (is (converges? live declared))))
  (testing "an added unnamed CHECK plans as ADD CHECK"
    (let [live ["CREATE TABLE t (a INTEGER)"]
          declared ["CREATE TABLE t (a INTEGER, CHECK (a > 0))"]]
      (is (= [[:add-check ["ALTER TABLE \"t\" ADD CHECK (a > 0)"]]]
            (kinds+sql (plan-of live declared))))
      (is (converges? live declared))))
  (testing "a removed unnamed CHECK cannot be addressed in place — rebuild only"
    (is (= {[:table "t" :check [:live 0]] [[:incapable :rebuild-not-implemented]]}
          (refusal-codes (plan-of ["CREATE TABLE t (a INTEGER, CHECK (a > 0))"]
                           ["CREATE TABLE t (a INTEGER)"])))))
  (testing "below 3.53 check changes are rebuild-only"
    (is (= {[:table "t" :check "c1"] [[:incapable :rebuild-not-implemented]]}
          (refusal-codes (plan-of ["CREATE TABLE t (a INTEGER, CONSTRAINT c1 CHECK (a > 0))"]
                           ["CREATE TABLE t (a INTEGER)"]
                           {:capabilities {:sqlite-version "3.45.0"}}))))))

;; ---------------------------------------------------------------------------
;; Index, trigger, and view create/drop

(deftest secondary-objects-plan-as-create-and-drop-ops
  (testing "a changed index plans as drop (phase 1) then create (phase 5)"
    (let [live ["CREATE TABLE t (a INTEGER, b INTEGER)"
                "CREATE INDEX idx ON t (a)"]
          declared ["CREATE TABLE t (a INTEGER, b INTEGER)"
                    "CREATE UNIQUE INDEX idx ON t (a DESC)"]
          pl (plan-of live declared)]
      (is (= [[:drop-index ["DROP INDEX \"idx\""]]
              [:create-index ["CREATE UNIQUE INDEX idx ON t (a DESC)"]]]
            (kinds+sql pl)))
      (is (converges? live declared))))
  (testing "removed triggers and views drop freely — no Refusal (they carry no data)"
    (let [pl (plan-of ["CREATE TABLE t (a INTEGER)"
                       "CREATE VIEW v AS SELECT a FROM t"
                       "CREATE TRIGGER trg AFTER INSERT ON t BEGIN SELECT 1; END"]
               ["CREATE TABLE t (a INTEGER)"])]
      (is (= [[:drop-trigger ["DROP TRIGGER \"trg\""]]
              [:drop-view ["DROP VIEW \"v\""]]]
            (kinds+sql pl)))
      (is (empty? (:unhandled pl))))))

(deftest changed-view-recreates-its-retained-triggers
  ;; dropping a view drops its triggers with it — the recreate must put
  ;; the unchanged ones back, serving the view's entry
  (let [live ["CREATE TABLE t (a INTEGER)"
              "CREATE VIEW v AS SELECT a FROM t"
              "CREATE TRIGGER trg INSTEAD OF INSERT ON v BEGIN SELECT 1; END"]
        declared ["CREATE TABLE t (a INTEGER)"
                  "CREATE VIEW v AS SELECT a, a + 1 AS b FROM t"
                  "CREATE TRIGGER trg INSTEAD OF INSERT ON v BEGIN SELECT 1; END"]
        pl (plan-of live declared)]
    (is (= [[:drop-view [:view "v"]]
            [:create-view [:view "v"]]
            [:create-trigger [:view "v" :trigger "trg"]]]
          (mapv (juxt :kind :path) (:ops pl))))
    (is (= #{[:view "v"]} (:serves (peek (:ops pl))))
      "the retained trigger's recreate serves the view entry")
    (is (converges? live declared))))

;; ---------------------------------------------------------------------------
;; Restricted drop column and the legalizing order

(deftest covering-index-drops-before-the-column-it-covers
  ;; a removed VIRTUAL generated column stores no values, so its drop
  ;; plans freely — but only once the plan's earlier phase has removed
  ;; the covering index
  (let [live ["CREATE TABLE t (a INTEGER, g INTEGER GENERATED ALWAYS AS (a * 2) VIRTUAL)"
              "CREATE INDEX idx_g ON t (g)"]
        declared ["CREATE TABLE t (a INTEGER)"]
        pl (plan-of live declared)]
    (testing "the covering index drops first — the drop-column is legal in the intermediate state"
      (is (= [[:drop-index ["DROP INDEX \"idx_g\""]]
              [:drop-column ["ALTER TABLE \"t\" DROP COLUMN \"g\""]]]
            (kinds+sql pl)))
      (is (empty? (:unhandled pl))))
    (testing "the legalized order executes on real SQLite"
      (is (converges? live declared)))))

(deftest dependent-generated-columns-drop-in-reference-order
  ;; h reads g, so h must drop before g
  (let [live ["CREATE TABLE t (a INTEGER, g INTEGER GENERATED ALWAYS AS (a * 2) VIRTUAL, h INTEGER GENERATED ALWAYS AS (g + 1) VIRTUAL)"]
        declared ["CREATE TABLE t (a INTEGER)"]
        pl (plan-of live declared)]
    (is (= [["ALTER TABLE \"t\" DROP COLUMN \"h\""]
            ["ALTER TABLE \"t\" DROP COLUMN \"g\""]]
          (mapv :sql (:ops pl))))
    (is (converges? live declared))))

(deftest surviving-trigger-blocks-drop-column
  ;; SQLite rejects DROP COLUMN when a surviving trigger references the
  ;; column ("error in trigger ... after drop column"); the drop must
  ;; route to the rebuild path instead of failing at apply time
  (let [live ["CREATE TABLE t (a INTEGER, g INTEGER GENERATED ALWAYS AS (a * 2) VIRTUAL)"
              "CREATE TRIGGER trg AFTER INSERT ON t BEGIN SELECT g FROM t; END"]]
    (testing "a trigger that survives the plan's drops blocks the in-place drop"
      (let [pl (plan-of live
                 ["CREATE TABLE t (a INTEGER)"
                  "CREATE TRIGGER trg AFTER INSERT ON t BEGIN SELECT g FROM t; END"])]
        (is (empty? (:ops pl)))
        (is (= {[:table "t" :column "g"] [[:incapable :rebuild-not-implemented]]}
              (refusal-codes pl)))))
    (testing "dropping the trigger in phase 1 legalizes the drop-column"
      (let [declared ["CREATE TABLE t (a INTEGER)"]
            pl (plan-of live declared)]
        (is (= [[:drop-trigger ["DROP TRIGGER \"trg\""]]
                [:drop-column ["ALTER TABLE \"t\" DROP COLUMN \"g\""]]]
              (kinds+sql pl)))
        (is (converges? live declared))))))

(deftest surviving-view-drops-in-phase-one-legalize-drop-column
  ;; SQLite also rejects DROP COLUMN when a view references the column;
  ;; a removed view drops in phase 1, so the drop-column stays legal
  (let [live ["CREATE TABLE t (a INTEGER, g INTEGER GENERATED ALWAYS AS (a * 2) VIRTUAL)"
              "CREATE VIEW v AS SELECT g FROM t"]
        declared ["CREATE TABLE t (a INTEGER)"]
        pl (plan-of live declared)]
    (is (= [[:drop-view ["DROP VIEW \"v\""]]
            [:drop-column ["ALTER TABLE \"t\" DROP COLUMN \"g\""]]]
          (kinds+sql pl)))
    (is (converges? live declared))))

(deftest foreign-key-column-drops-are-rebuild-only
  ;; SQLite rejects DROP COLUMN on a column named in a FOREIGN KEY
  ;; clause; the vanishing FK is its own rebuild-only entry, so the
  ;; whole table honestly collapses to unhandled
  (let [pl (plan-of ["CREATE TABLE p (id INTEGER PRIMARY KEY)"
                     "CREATE TABLE t (a INTEGER, b INTEGER, FOREIGN KEY (b) REFERENCES p(id))"]
             ["CREATE TABLE p (id INTEGER PRIMARY KEY)"
              "CREATE TABLE t (a INTEGER)"])]
    (is (empty? (:ops pl)))
    (is (= {[:table "t" :column "b"]
            [[:needs-intent :destructive-drop] [:incapable :rebuild-not-implemented]]
            [:table "t" :foreign-key [:live 0]]
            [[:incapable :rebuild-not-implemented]]}
          (refusal-codes pl)))))

(deftest data-bearing-column-drops-need-intent
  (testing "a plain column's drop has an in-place route but refuses without intent"
    (let [pl (plan-of ["CREATE TABLE t (a INTEGER, b TEXT)"]
               ["CREATE TABLE t (a INTEGER)"])]
      (is (empty? (:ops pl)))
      (is (= {[:table "t" :column "b"] [[:needs-intent :destructive-drop]]}
            (refusal-codes pl)))))
  (testing "below 3.35 the drop also needs a rebuild — both Refusals ride the entry"
    (is (= {[:table "t" :column "b"]
            [[:needs-intent :destructive-drop] [:incapable :rebuild-not-implemented]]}
          (refusal-codes (plan-of ["CREATE TABLE t (a INTEGER, b TEXT)"]
                           ["CREATE TABLE t (a INTEGER)"]
                           {:capabilities {:sqlite-version "3.30.0"}}))))))

;; ---------------------------------------------------------------------------
;; Refusals: every applicable launch code, plan never throws

(deftest refusal-vectors-carry-every-applicable-code
  (testing "changing a table to STRICT below 3.37: unsupported object AND rebuild-only"
    (is (= {[:table "t"]
            [[:incapable :unsupported-by-target-version] [:incapable :rebuild-not-implemented]]}
          (refusal-codes (plan-of ["CREATE TABLE t (a INTEGER)"]
                           ["CREATE TABLE t (a INTEGER) STRICT"]
                           {:capabilities {:sqlite-version "3.30.0"}})))))
  (testing "an added STRICT table below 3.37 cannot exist on the target — no ops, one Refusal"
    (let [pl (plan-of [] ["CREATE TABLE t (a INTEGER) STRICT"]
               {:capabilities {:sqlite-version "3.30.0"}})]
      (is (empty? (:ops pl)))
      (is (= {[:table "t"] [[:incapable :unsupported-by-target-version]]}
            (refusal-codes pl))))))

(deftest virtual-tables-refuse-changes-but-plan-additions
  (let [vt "CREATE VIRTUAL TABLE notes USING fts5(body)"
        vt2 "CREATE VIRTUAL TABLE notes USING fts5(body, tokenize = 'porter')"]
    (testing "an added virtual table plans its verbatim declared CREATE"
      (is (= [[:create-table [vt]]] (kinds+sql (plan-of [] [vt])))))
    (testing "a changed virtual table is :incapable :virtual-table-changed"
      (is (= {[:table "notes"] [[:incapable :virtual-table-changed]]}
            (refusal-codes (plan-of [vt] [vt2])))))
    (testing "a removed virtual table is a destructive drop"
      (is (= {[:table "notes"] [[:needs-intent :destructive-drop]]}
            (refusal-codes (plan-of [vt] [])))))))

(deftest removed-tables-need-intent
  (let [pl (plan-of ["CREATE TABLE t (a INTEGER)"] [])]
    (is (empty? (:ops pl)))
    (is (= {[:table "t"] [[:needs-intent :destructive-drop]]}
          (refusal-codes pl)))))

(deftest one-rebuild-only-entry-collapses-the-whole-table
  ;; ADR 0006: never mix in-place and rebuild for one table — the
  ;; appendable column goes unhandled too once its sibling needs a rebuild
  (let [pl (plan-of ["CREATE TABLE t (a INTEGER, b TEXT)"]
             ["CREATE TABLE t (a INTEGER, b BLOB, c INT)"])]
    (is (empty? (:ops pl)))
    (is (= {[:table "t" :column "b"] [[:incapable :rebuild-not-implemented]]
            [:table "t" :column "c"] [[:incapable :rebuild-not-implemented]]}
          (refusal-codes pl))))
  (testing "an unrelated table still plans in place — collapse is per table"
    (let [pl (plan-of ["CREATE TABLE t (a INTEGER, b TEXT)" "CREATE TABLE u (x INTEGER)"]
               ["CREATE TABLE t (a INTEGER, b BLOB)" "CREATE TABLE u (x INTEGER, y INT)"])]
      (is (= [[:add-column ["ALTER TABLE \"u\" ADD COLUMN \"y\" INT"]]]
            (kinds+sql pl))))))

(deftest plan-never-throws-for-refusals
  (testing "the whole nasty corpus against an empty declaration plans without throwing"
    (let [pl (plan-of corpus/nasty-declaration [])]
      (is (vector? (:unhandled pl)))
      (is (every? #(seq (:refusals %)) (:unhandled pl))
        "every unhandled entry carries at least one Refusal")))
  (testing "and reversed"
    (is (map? (plan-of [] corpus/nasty-declaration)))))

;; ---------------------------------------------------------------------------
;; The locked phase order, baked into list position

(deftest phase-order-is-baked-into-list-position
  (let [live ["CREATE TABLE gone_idx_owner (a INTEGER)"
              "CREATE INDEX old_idx ON gone_idx_owner (a)"
              "CREATE TRIGGER old_trg AFTER INSERT ON gone_idx_owner BEGIN SELECT 1; END"
              "CREATE VIEW old_view AS SELECT a FROM gone_idx_owner"
              "CREATE TABLE changing (a INTEGER, CONSTRAINT c CHECK (a > 0))"]
        declared ["CREATE TABLE gone_idx_owner (a INTEGER)"
                  "CREATE TABLE changing (a INTEGER, b TEXT)"
                  "CREATE TABLE brand_new (id INTEGER PRIMARY KEY)"
                  "CREATE INDEX new_idx ON changing (a)"
                  "CREATE VIEW new_view AS SELECT a FROM changing"
                  "CREATE TRIGGER new_trg AFTER INSERT ON changing BEGIN SELECT 1; END"]
        pl (plan-of live declared)]
    (is (= [:drop-trigger :drop-index :drop-view ; 1: drops — triggers, indexes, views
            :drop-check :add-column ; 3: per-table change ops
            :create-table ; 4: added tables
            :create-index :create-view :create-trigger] ; 5: creates — indexes, views, triggers
          (mapv :kind (:ops pl))))
    (is (converges? live declared))))

;; ---------------------------------------------------------------------------
;; Completeness invariant (ADR 0006): served ∪ unhandled = all entries

(defn- complete? [diff pl]
  (= (into #{} (map :path) (:entries diff))
    (into (into #{} (mapcat :serves) (:ops pl))
      (map (comp :path :entry) (:unhandled pl)))))

(deftest every-entry-is-served-or-unhandled
  (doseq [[live declared] [[corpus/nasty-declaration []]
                           [[] corpus/nasty-declaration]
                           [["CREATE TABLE t (a INTEGER, b TEXT)"
                             "CREATE INDEX idx ON t (b)"]
                            ["CREATE TABLE t (a INTEGER, c INT)"
                             "CREATE VIEW v AS SELECT a FROM t"]]]]
    (let [l (snap live)
          d (snap declared)
          diff (m/diff l d)]
      (is (complete? diff (m/plan diff {:live-snapshot l :declared-snapshot d}))
        (str "completeness must hold for " (pr-str [live declared]))))))

(deftest plan-throws-malformed-input-without-snapshot-context
  (testing "planning a changed table without the Snapshots in opts is malformed input"
    (let [live (snap ["CREATE TABLE t (a INTEGER)"])
          declared (snap ["CREATE TABLE t (a INTEGER, b TEXT)"])
          ex (thrown-info (m/plan (m/diff live declared) {}))]
      (is (some? ex) "plan must throw when a changed table has no Snapshot context")
      (is (= :malformed-input (:sqlite-migrate/error (ex-data ex))))
      (is (= :live-snapshot (:missing (ex-data ex))))
      (is (= "t" (:table (ex-data ex)))))))

(deftest completeness-violation-throws-internal
  (testing "an entry neither served nor unhandled is a planner bug — :internal"
    (let [ex (thrown-info (#'pl/check-completeness! [{:path [:table "t"]}] [] []))]
      (is (some? ex) "the completeness checker must throw on an uncovered entry")
      (is (= :internal (:sqlite-migrate/error (ex-data ex)))))))

;; ---------------------------------------------------------------------------
;; apply! refuses unhandled plans unless opted in (ADR 0011)

(deftest apply-refuses-unhandled-plans-unless-opted-in
  (with-open [live (sql-jdbc/in-memory)
              pristine (sql-jdbc/in-memory)]
    ;; the `gone` drop needs intent (unhandled); t's added column plans
    (p/execute-batch! live ["CREATE TABLE t (a INTEGER)"
                            "CREATE TABLE gone (z INTEGER)"])
    (let [live-snap (m/snapshot live)
          declared (m/declared-snapshot pristine ["CREATE TABLE t (a INTEGER, c INT)"])
          pl (m/plan (m/diff live-snap declared)
               {:live-snapshot live-snap :declared-snapshot declared})]
      (is (= [:add-column] (mapv :kind (:ops pl))))
      (is (= 1 (count (:unhandled pl))))
      (testing "by default apply! refuses the whole Plan, executing nothing"
        (let [ex (thrown-info (m/apply! live pl))]
          (is (some? ex) "apply! must throw on unhandled entries")
          (is (= :unhandled-refused (:sqlite-migrate/error (ex-data ex))))
          (is (= (:unhandled pl) (:unhandled (ex-data ex)))
            "the unhandled entries ride in the ex-data")
          (is (not (m/drift? (m/diff (m/snapshot live) live-snap)))
            "a refused apply! changed nothing")))
      (testing ":allow-unhandled? true is the partial-convergence opt-in"
        (m/apply! live pl {:allow-unhandled? true})
        (let [residual (m/diff (m/snapshot live) declared)]
          (is (= (mapv :entry (:unhandled pl)) (:entries residual))
            "the residual diff is exactly the Plan's unhandled entries"))))))

;; ---------------------------------------------------------------------------
;; Plan determinism (ADR 0010): same (Diff, Capabilities) => byte-identical Plan

(def ^:private determinism-live
  ["CREATE TABLE t (a INTEGER, b TEXT, g INTEGER GENERATED ALWAYS AS (a * 2) VIRTUAL, CONSTRAINT c1 CHECK (a > 0))"
   "CREATE INDEX idx_g ON t (g)"
   "CREATE INDEX idx_gone ON t (b)"
   "CREATE VIEW v AS SELECT a FROM t"
   "CREATE TRIGGER trg INSTEAD OF INSERT ON v BEGIN SELECT 1; END"
   "CREATE TABLE dead (z INTEGER)"])

(def ^:private determinism-declared
  ["CREATE TABLE t (a INTEGER, b TEXT NOT NULL, c INT DEFAULT 7, CONSTRAINT c1 CHECK (a >= 0), CHECK (b <> ''))"
   "CREATE INDEX idx_c ON t (c)"
   "CREATE VIEW v AS SELECT a, b FROM t"
   "CREATE TRIGGER trg INSTEAD OF INSERT ON v BEGIN SELECT 1; END"
   "CREATE TABLE born (id INTEGER PRIMARY KEY)"
   "CREATE INDEX idx_born ON born (id)"])

(deftest plan-determinism-property
  (testing "planning the same Diff twice is pr-str-identical"
    (let [l (snap determinism-live)
          d (snap determinism-declared)
          diff (m/diff l d)
          opts {:live-snapshot l :declared-snapshot d}]
      (is (= (pr-str (m/plan diff opts)) (pr-str (m/plan diff opts))))))
  (testing "independently rebuilt inputs yield byte-identical Plans (metadata aside)"
    (let [plan-once (fn []
                      (dissoc (plan-of determinism-live determinism-declared
                                {:capabilities {:sqlite-version "3.53.0"}})
                        :live-metadata :declared-metadata))]
      (is (= (pr-str (plan-once)) (pr-str (plan-once)))))))
