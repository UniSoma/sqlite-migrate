(ns sqlite-migrate.plan-test
  "In-place planning, Refusals, and Capabilities (ADR 0006, 0007, 0011)
  plus the Plan-determinism property (ADR 0010) — through the public
  `sqlite-migrate.core/plan` seam on real in-memory SQLite."
  (:require [clojure.test :refer [deftest is testing]]
    [sqlite-migrate.core :as m]
    [sqlite-migrate.corpus :as corpus]
    [sqlite-migrate.impl.plan :as pl]
    [sqlite-migrate.jdbc :as sql-jdbc]
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
      (m/plan live declared (m/diff live declared) opts))))

(defn- kinds+sql [pl]
  (mapv (juxt :kind :sql) (:ops pl)))

(defn- explanation-of
  "The explanation of the single Refusal on the unhandled entry at
  `path`."
  [pl path]
  (->> (:unhandled pl)
    (some (fn [{:keys [entry refusals]}]
            (when (= path (:path entry)) refusals)))
    first
    :explanation))

(defn- refusal-codes
  "The `[class code]` pairs of every Refusal on every unhandled entry,
  keyed by the entry's path."
  [pl]
  (into {}
    (map (fn [{:keys [entry refusals]}]
           [(:path entry) (mapv (juxt :class :code) refusals)]))
    (:unhandled pl)))

(defn- applied
  "Apply `live-decl`'s statements to a fresh live database, plan against
  `declared-decl` under `plan-opts`, apply!, and return `(f live-conn
  declared-snapshot)` while the live database is still open."
  [live-decl declared-decl plan-opts f]
  (with-open [live (sql-jdbc/in-memory)
              pristine (sql-jdbc/in-memory)]
    (when (seq live-decl)
      (p/execute-batch! live (vec live-decl)))
    (let [live-snap (m/snapshot live)
          declared (m/declared-snapshot pristine declared-decl)
          pl (m/plan live-snap declared (m/diff live-snap declared) plan-opts)]
      (m/apply! live pl)
      (f live declared))))

(defn- converges?
  "Apply `live-decl` to a fresh live database, plan against
  `declared-decl` under `plan-opts`, apply!, and report whether the
  residual diff is empty. Exercises the planned SQL against real
  SQLite."
  ([live-decl declared-decl] (converges? live-decl declared-decl {}))
  ([live-decl declared-decl plan-opts]
    (applied live-decl declared-decl plan-opts
      (fn [live declared] (not (m/drift? (m/diff (m/snapshot live) declared)))))))

;; ---------------------------------------------------------------------------
;; Capabilities

(deftest capabilities-default-to-live-version-with-rebuild-allowed
  (let [live (snap ["CREATE TABLE t (a INTEGER)"])
        declared (snap ["CREATE TABLE t (a INTEGER)"])
        pl (m/plan live declared (m/diff live declared))]
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

(def ^:private populated-table-rule-rows
  "Added-column clauses and the Op kind each plans as under target
  versions 3.31.0, 3.32.0 and 3.53.0 (ADR 0022). `:gate` names the Gate
  every route carries and Apply fails on a one-row table; nil means
  no Gate and Apply succeeds."
  [{:clause "TEXT DEFAULT CURRENT_TIMESTAMP" :kinds [:rebuild-table :rebuild-table :rebuild-table]}
   {:clause "TEXT DEFAULT (CURRENT_TIMESTAMP)" :kinds [:rebuild-table :rebuild-table :rebuild-table]}
   {:clause "INTEGER DEFAULT (random())" :kinds [:rebuild-table :rebuild-table :rebuild-table]}
   {:clause "INTEGER DEFAULT (1 + 2)" :kinds [:rebuild-table :rebuild-table :rebuild-table]}
   {:clause "INTEGER DEFAULT (0)" :kinds [:add-column :add-column :add-column]}
   {:clause "INTEGER DEFAULT -1" :kinds [:add-column :add-column :add-column]}
   {:clause "TEXT DEFAULT 'x'" :kinds [:add-column :add-column :add-column]}
   {:clause "INTEGER NOT NULL" :kinds [:rebuild-table :add-column :add-column] :gate :empty-table}
   {:clause "INTEGER NOT NULL DEFAULT NULL" :kinds [:rebuild-table :add-column :add-column] :gate :empty-table}
   {:clause "INTEGER NOT NULL DEFAULT 0" :kinds [:add-column :add-column :add-column]}
   {:clause "INTEGER NOT NULL DEFAULT (random())" :kinds [:rebuild-table :rebuild-table :rebuild-table]}
   {:clause "INTEGER AS (a+1) STORED" :kinds [:rebuild-table :rebuild-table :rebuild-table]}
   {:clause "INTEGER AS (a+1) VIRTUAL" :kinds [:add-column :add-column :add-column]}])

(deftest added-column-routes-by-the-populated-table-rule
  (doseq [{:keys [clause kinds gate]} populated-table-rule-rows
          :let [live ["CREATE TABLE t (a INTEGER)"]
                declared [(str "CREATE TABLE t (a INTEGER, b " clause ")")]]]
    (testing (str "adding b " clause)
      (testing "plans the Op kind a populated table accepts per target version, every route behind the same Gates"
        (is (= (map vector kinds (repeat (if gate [gate] [])))
              (for [v ["3.31.0" "3.32.0" "3.53.0"]
                    :let [[op] (:ops (plan-of live declared {:capabilities {:sqlite-version v}}))]]
                [(:kind op) (mapv :code (:gates op))]))))
      (testing "and its default-capabilities Plan applies to a one-row table unless a Gate forbids it"
        (with-open [conn (sql-jdbc/in-memory)
                    pristine (sql-jdbc/in-memory)]
          (p/execute-batch! conn (conj live "INSERT INTO t (a) VALUES (1)"))
          (let [live-snap (m/snapshot conn)
                target (m/declared-snapshot pristine declared)
                pl (m/plan live-snap target (m/diff live-snap target))
                ex (thrown-info (m/apply! conn pl))]
            (if gate
              (is (= [:gate-failed [gate]]
                    [(:sqlite-migrate/error (ex-data ex))
                     (->> (:gates (:check (ex-data ex)))
                       (filter (comp pos? :violations))
                       (mapv (comp :code :gate)))]))
              (do (is (nil? ex) (str "apply! must succeed, threw " (ex-message ex)))
                (is (not (m/drift? (m/diff (m/snapshot conn) target)))
                  "the applied Plan must converge the live table on the declared one")))))))))

(deftest opaque-default-column-addition-with-rebuild-off-is-rebuild-disabled
  (is (= {[:table "t" :column "b"] [[:incapable :rebuild-disabled]]}
        (refusal-codes
          (plan-of ["CREATE TABLE t (a INTEGER)"]
            ["CREATE TABLE t (a INTEGER, b INTEGER DEFAULT (random()))"]
            {:capabilities {:rebuild? false}})))))

(deftest column-level-constraint-spellings-are-never-silently-dropped
  ;; a column-level UNIQUE or REFERENCES surfaces as its own constraint
  ;; entry, which is rebuild-only and collapses the table — the
  ;; :add-column op (which cannot carry those clauses) never emits;
  ;; the rebuild's CREATE carries the constraint spelling verbatim
  (testing "an added column with a column-level UNIQUE routes the table to a rebuild"
    (let [pl (plan-of ["CREATE TABLE t (a INTEGER)"]
               ["CREATE TABLE t (a INTEGER, b TEXT UNIQUE)"])]
      (is (= [:rebuild-table] (mapv :kind (:ops pl))))
      (is (= [#{[:table "t" :column "b"] [:table "t" :unique [:declared 0]]}]
            (mapv :serves (:ops pl))))
      (is (empty? (:unhandled pl)))))
  (testing "an added column with a column-level REFERENCES routes the table to a rebuild"
    (let [pl (plan-of ["CREATE TABLE p (id INTEGER PRIMARY KEY)" "CREATE TABLE t (a INTEGER)"]
               ["CREATE TABLE p (id INTEGER PRIMARY KEY)"
                "CREATE TABLE t (a INTEGER, b INTEGER REFERENCES p(id))"])]
      (is (= [:rebuild-table] (mapv :kind (:ops pl))))
      (is (= [#{[:table "t" :column "b"] [:table "t" :foreign-key [:declared 0]]}]
            (mapv :serves (:ops pl))))
      (is (empty? (:unhandled pl))))))

(deftest mid-table-column-insertion-is-rebuild-only
  (let [pl (plan-of ["CREATE TABLE t (a INTEGER, b TEXT)"]
             ["CREATE TABLE t (a INTEGER, x INT, b TEXT)"])]
    (testing "a column inserted mid-table cannot append in place — it plans as a rebuild"
      (is (= [:rebuild-table] (mapv :kind (:ops pl))))
      (is (empty? (:unhandled pl))))
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
    (testing "below 3.53 the version gap is absorbed by a rebuild — no refusal (ADR 0007)"
      (is (= [:rebuild-table]
            (mapv :kind (:ops (plan-of live declared
                                {:capabilities {:sqlite-version "3.45.0"}}))))))))

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
  (testing "a removed unnamed CHECK cannot be addressed in place — it plans as a rebuild"
    (is (= [:rebuild-table]
          (mapv :kind (:ops (plan-of ["CREATE TABLE t (a INTEGER, CHECK (a > 0))"]
                              ["CREATE TABLE t (a INTEGER)"]))))))
  (testing "below 3.53 check changes rebuild — the version gap is absorbed (ADR 0007)"
    (is (= [:rebuild-table]
          (mapv :kind (:ops (plan-of ["CREATE TABLE t (a INTEGER, CONSTRAINT c1 CHECK (a > 0))"]
                              ["CREATE TABLE t (a INTEGER)"]
                              {:capabilities {:sqlite-version "3.45.0"}})))))))

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
;; Readers of a changed view (ADR 0026): SQLite checks the whole schema
;; again during ALTER TABLE RENAME, RENAME COLUMN and DROP COLUMN, so no
;; view or trigger may read a changed view between phases 1 and 5

(deftest rebuild-applies-while-a-surviving-view-reads-a-changed-view
  (let [live ["CREATE TABLE t (a INTEGER NOT NULL, PRIMARY KEY (a))"
              "CREATE VIEW v1 AS SELECT a FROM t"
              "CREATE VIEW v2 AS SELECT a FROM v1"]
        declared ["CREATE TABLE t (a INTEGER, UNIQUE (a))"
                  "CREATE VIEW v1 AS SELECT a, a AS b FROM t"
                  "CREATE VIEW v2 AS SELECT a FROM v1"]]
    (is (converges? live declared)
      "apply! succeeds and v1 and v2 stand Equivalent to the Declaration")))

(deftest rename-table-applies-while-a-surviving-view-reads-a-changed-view
  (let [live ["CREATE TABLE t (a INTEGER)"
              "CREATE VIEW v1 AS SELECT a FROM t"
              "CREATE VIEW v2 AS SELECT a FROM v1"]
        declared ["CREATE TABLE t2 (a INTEGER)"
                  "CREATE VIEW v1 AS SELECT a FROM t2"
                  "CREATE VIEW v2 AS SELECT a FROM v1"]]
    (is (converges? live declared {:directives [{:directive :rename-table :from "t" :to "t2"}]})
      "apply! succeeds and v1 and v2 stand Equivalent to the Declaration")))

(deftest column-alters-apply-while-a-surviving-view-reads-a-changed-view
  (let [shared ["CREATE VIEW v2 AS SELECT a FROM v1"]]
    (testing "an authorized :rename-column applies and converges"
      (is (converges? (into ["CREATE TABLE t (a INTEGER, b INTEGER)"
                             "CREATE VIEW v1 AS SELECT a FROM t"] shared)
            (into ["CREATE TABLE t (a INTEGER, c INTEGER)"
                   "CREATE VIEW v1 AS SELECT a, c FROM t"] shared)
            {:directives [{:directive :rename-column :table "t" :from "b" :to "c"}]})))
    (testing "an authorized :drop-column applies and converges"
      (is (converges? (into ["CREATE TABLE t (a INTEGER, b INTEGER)"
                             "CREATE VIEW v1 AS SELECT a, b FROM t"] shared)
            (into ["CREATE TABLE t (a INTEGER)"
                   "CREATE VIEW v1 AS SELECT a FROM t"] shared)
            {:directives [{:directive :drop-column :table "t" :column "b"}]})))))

(deftest rename-table-keeps-the-triggers-that-read-a-changed-view
  ;; neither trigger names t, so only the reader rule keeps the rename
  ;; from reparsing them while v1 is missing
  (let [shared ["CREATE TABLE w (n INTEGER)"
                "CREATE VIEW v2 AS SELECT a FROM v1"
                "CREATE TRIGGER w_trg AFTER INSERT ON w BEGIN SELECT a FROM v1; END"
                "CREATE TRIGGER v2_ins INSTEAD OF INSERT ON v2 BEGIN INSERT INTO w VALUES (new.a); END"]
        live (into ["CREATE TABLE t (a INTEGER)" "CREATE VIEW v1 AS SELECT a FROM t"] shared)
        declared (into ["CREATE TABLE t2 (a INTEGER)" "CREATE VIEW v1 AS SELECT a FROM t2"] shared)
        opts {:directives [{:directive :rename-table :from "t" :to "t2"}]}]
    (is (converges? live declared opts)
      "apply! succeeds and both triggers stand Equivalent to the Declaration")
    (is (= [{:n 7}]
          (applied live declared opts
            (fn [conn _]
              (p/execute-batch! conn ["INSERT INTO v2 VALUES (7)"])
              (p/execute-query conn "SELECT n FROM w" []))))
      "the INSTEAD OF trigger fires, and so does the trigger it sets off")))

(deftest a-later-rebuild-applies-after-a-rebuilt-tables-trigger-reads-a-changed-view
  ;; w rebuilds before x; were w's Rebuild to create w_trg again, x's
  ;; rename would fail on it while v1 is missing
  (let [live ["CREATE TABLE w (n INTEGER NOT NULL, PRIMARY KEY (n))"
              "CREATE TABLE x (m INTEGER NOT NULL, PRIMARY KEY (m))"
              "CREATE VIEW v1 AS SELECT n FROM w"
              "CREATE TRIGGER w_trg AFTER INSERT ON w BEGIN SELECT n FROM v1; END"]
        tables ["CREATE TABLE w (n INTEGER, UNIQUE (n))"
                "CREATE TABLE x (m INTEGER, UNIQUE (m))"
                "CREATE VIEW v1 AS SELECT n, 1 AS k FROM w"]]
    (testing "a surviving trigger drops in phase 1 and is created again in phase 5, once"
      (let [declared (conj tables "CREATE TRIGGER w_trg AFTER INSERT ON w BEGIN SELECT n FROM v1; END")
            pl (plan-of live declared)]
        (is (= [[:drop-trigger [:table "w" :trigger "w_trg"]]
                [:drop-view [:view "v1"]]
                [:rebuild-table [:table "w"]]
                [:rebuild-table [:table "x"]]
                [:create-view [:view "v1"]]
                [:create-trigger [:table "w" :trigger "w_trg"]]]
              (mapv (juxt :kind :path) (:ops pl))))
        (is (not-any? #(re-find #"CREATE TRIGGER" %) (:sql (nth (:ops pl) 2)))
          "w's Rebuild leaves the trigger out")
        (is (converges? live declared))))
    (testing "a changed trigger: the Rebuild drops it in phase 1 and leaves its create to phase 5"
      (let [declared (conj tables "CREATE TRIGGER w_trg AFTER INSERT ON w BEGIN SELECT n FROM v1 WHERE n > 0; END")
            pl (plan-of live declared)]
        (is (= [:drop-trigger :drop-view :rebuild-table :rebuild-table :create-view :create-trigger]
              (mapv :kind (:ops pl))))
        (is (= #{[:view "v1"] [:table "w" :trigger "w_trg"]}
              (:serves (first (:ops pl))) (:serves (peek (:ops pl))))
          "the drop and the create each serve the changed view and the trigger's own entry")
        (is (converges? live declared))))))

(deftest an-earlier-rebuild-applies-while-a-rebuilt-tables-trigger-reads-a-changed-view
  ;; a rebuilds before w, whose live w_trg the Declaration changes or
  ;; removes: it is no survivor, yet it stands until w's Rebuild drops
  ;; w, so a's rename would reparse it while v1 is missing
  (let [live ["CREATE TABLE a (m INTEGER NOT NULL, PRIMARY KEY (m))"
              "CREATE TABLE w (n INTEGER NOT NULL, PRIMARY KEY (n))"
              "CREATE VIEW v1 AS SELECT n FROM w"
              "CREATE TRIGGER w_trg AFTER INSERT ON w BEGIN SELECT n FROM v1; END"]
        tables ["CREATE TABLE a (m INTEGER, UNIQUE (m))"
                "CREATE TABLE w (n INTEGER, UNIQUE (n))"
                "CREATE VIEW v1 AS SELECT n, 1 AS k FROM w"]]
    (testing "a changed trigger applies and converges"
      (is (converges? live (conj tables "CREATE TRIGGER w_trg AFTER INSERT ON w BEGIN SELECT n FROM v1 WHERE n > 0; END"))))
    (testing "a removed trigger applies and converges"
      (is (converges? live tables)))))

(deftest rename-table-applies-while-the-renamed-tables-trigger-reads-a-changed-view
  ;; t_trg's text names t, so the Diff pairs it as a changed trigger of
  ;; the renamed table; the rename plan drops it and creates its
  ;; declared text, and the reader pass must leave it alone
  (let [live ["CREATE TABLE w (n INTEGER)"
              "CREATE VIEW v1 AS SELECT n FROM w"
              "CREATE TABLE t (a INTEGER NOT NULL, PRIMARY KEY (a))"
              "CREATE TRIGGER t_trg AFTER INSERT ON t BEGIN SELECT n FROM v1; END"]
        declared-with (fn [t2]
                        ["CREATE TABLE w (n INTEGER)"
                         "CREATE VIEW v1 AS SELECT n, 1 AS k FROM w"
                         t2
                         "CREATE TRIGGER t_trg AFTER INSERT ON t2 BEGIN SELECT n FROM v1; END"])
        opts {:directives [{:directive :rename-table :from "t" :to "t2"}]}]
    (testing "renamed in place, it applies and converges"
      (is (converges? live (declared-with "CREATE TABLE t2 (a INTEGER NOT NULL, PRIMARY KEY (a))") opts)))
    (testing "renamed by a Rebuild, it applies and converges"
      (is (converges? live (declared-with "CREATE TABLE t2 (a INTEGER, UNIQUE (a))") opts)))))

(deftest a-changed-views-reader-drops-in-phase-one-and-is-created-in-phase-five
  (let [pl (plan-of ["CREATE TABLE t (a INTEGER)"
                     "CREATE VIEW v1 AS SELECT a FROM t"
                     "CREATE VIEW v2 AS SELECT a FROM v1"]
             ["CREATE TABLE t (a INTEGER)"
              "CREATE VIEW v1 AS SELECT a, 1 AS k FROM t"
              "CREATE VIEW v2 AS SELECT a FROM v1"])]
    (is (= [[:drop-view [:view "v1"] #{[:view "v1"]}]
            [:drop-view [:view "v2"] #{[:view "v1"]}]
            [:create-view [:view "v1"] #{[:view "v1"]}]
            [:create-view [:view "v2"] #{[:view "v1"]}]]
          (mapv (juxt :kind :path :serves) (:ops pl))))
    (is (= ["CREATE VIEW v2 AS SELECT a FROM v1"] (:sql (peek (:ops pl))))
      "the reader is created again from its live stored sql")))

(deftest rename-table-applies-under-a-chain-of-readers
  (let [shared ["CREATE VIEW v2 AS SELECT a FROM v1"
                "CREATE VIEW v3 AS SELECT a FROM v2"]
        live (into ["CREATE TABLE t (a INTEGER)" "CREATE VIEW v1 AS SELECT a FROM t"] shared)
        declared (into ["CREATE TABLE t2 (a INTEGER)" "CREATE VIEW v1 AS SELECT a FROM t2"] shared)
        opts {:directives [{:directive :rename-table :from "t" :to "t2"}]}]
    (is (= [[:drop-view [:view "v1"]] [:drop-view [:view "v2"]] [:drop-view [:view "v3"]]
            [:rename-table [:table "t"]]
            [:create-view [:view "v1"]] [:create-view [:view "v2"]] [:create-view [:view "v3"]]]
          (mapv (juxt :kind :path) (:ops (plan-of live declared opts)))))
    (is (converges? live declared opts))))

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
    (testing "a trigger that survives the plan's drops blocks the in-place drop — the table rebuilds"
      (let [pl (plan-of live
                 ["CREATE TABLE t (a INTEGER)"
                  "CREATE TRIGGER trg AFTER INSERT ON t BEGIN SELECT g FROM t; END"])]
        (is (= [:rebuild-table] (mapv :kind (:ops pl))))
        (is (empty? (:unhandled pl)))))
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

(deftest dropped-tables-trigger-leaves-drop-column-legal
  ;; ADR 0023: a trigger on a table dropped under a :drop-table
  ;; Directive leaves with its table in phase 2, before the phase-3
  ;; drop-column, so its mention of the column blocks nothing
  (let [live ["CREATE TABLE t (a INTEGER, g INTEGER GENERATED ALWAYS AS (a * 2) VIRTUAL)"
              "CREATE TABLE gone (y)"
              "CREATE TRIGGER gone_t AFTER INSERT ON gone BEGIN SELECT g FROM t; END"]
        declared ["CREATE TABLE t (a INTEGER)"]
        pl (plan-of live declared {:directives [{:directive :drop-table :table "gone"}]})]
    (is (= [[:drop-table ["DROP TABLE \"gone\""]]
            [:drop-column ["ALTER TABLE \"t\" DROP COLUMN \"g\""]]]
          (kinds+sql pl)))
    (with-open [conn (sql-jdbc/in-memory)]
      (p/execute-batch! conn live)
      (let [live-snap (m/snapshot conn)
            declared-snap (snap declared)]
        (m/apply! conn (m/plan live-snap declared-snap (m/diff live-snap declared-snap)
                         {:directives [{:directive :drop-table :table "gone"}]}))
        (is (not (m/drift? (m/diff (m/snapshot conn) declared-snap))))))))

(deftest foreign-key-column-drops-are-rebuild-only
  ;; SQLite rejects DROP COLUMN on a column named in a FOREIGN KEY
  ;; clause; the vanishing FK routes the table to a rebuild, but the
  ;; destructive column drop still awaits intent — so the whole table
  ;; honestly collapses to unhandled and both entries carry the
  ;; blocking Refusal
  (let [pl (plan-of ["CREATE TABLE p (id INTEGER PRIMARY KEY)"
                     "CREATE TABLE t (a INTEGER, b INTEGER, FOREIGN KEY (b) REFERENCES p(id))"]
             ["CREATE TABLE p (id INTEGER PRIMARY KEY)"
              "CREATE TABLE t (a INTEGER)"])]
    (is (empty? (:ops pl)))
    (is (= {[:table "t" :column "b"]
            [[:needs-intent :destructive-drop]]
            [:table "t" :foreign-key [:live 0]]
            [[:needs-intent :destructive-drop]]}
          (refusal-codes pl)))))

(deftest data-bearing-column-drops-need-intent
  (testing "a plain column's drop has an in-place route but refuses without intent"
    (let [pl (plan-of ["CREATE TABLE t (a INTEGER, b TEXT)"]
               ["CREATE TABLE t (a INTEGER)"])]
      (is (empty? (:ops pl)))
      (is (= {[:table "t" :column "b"] [[:needs-intent :destructive-drop]]}
            (refusal-codes pl)))
      (is (= (str "dropping column b of table t would discard stored values;"
               " it plans only with explicit intent (a directive)")
            (explanation-of pl [:table "t" :column "b"])))))
  (testing "below 3.35 the drop routes through a rebuild but the intent is still owed"
    (is (= {[:table "t" :column "b"]
            [[:needs-intent :destructive-drop]]}
          (refusal-codes (plan-of ["CREATE TABLE t (a INTEGER, b TEXT)"]
                           ["CREATE TABLE t (a INTEGER)"]
                           {:capabilities {:sqlite-version "3.30.0"}}))))))

;; ---------------------------------------------------------------------------
;; Refusals: every applicable launch code, plan never throws

(deftest refusal-vectors-carry-every-applicable-code
  (testing "changing a table to STRICT below 3.37: the rebuild would create an unsupported object"
    (is (= {[:table "t"]
            [[:incapable :unsupported-by-target-version]]}
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
          (refusal-codes pl)))
    (is (= (str "dropping table t would discard stored values;"
             " it plans only with explicit intent (a directive)")
          (explanation-of pl [:table "t"])))))

(deftest one-rebuild-only-entry-collapses-the-whole-table
  ;; ADR 0006: never mix in-place and rebuild for one table — the
  ;; appendable column rides the same rebuild as its rebuild-only sibling
  (let [pl (plan-of ["CREATE TABLE t (a INTEGER, b TEXT)"]
             ["CREATE TABLE t (a INTEGER, b BLOB, c INT)"])]
    (is (= [:rebuild-table] (mapv :kind (:ops pl))))
    (is (= [#{[:table "t" :column "b"] [:table "t" :column "c"]}]
          (mapv :serves (:ops pl))))
    (is (empty? (:unhandled pl))))
  (testing "an unrelated table still plans in place — collapse is per table"
    (let [pl (plan-of ["CREATE TABLE t (a INTEGER, b TEXT)" "CREATE TABLE u (x INTEGER)"]
               ["CREATE TABLE t (a INTEGER, b BLOB)" "CREATE TABLE u (x INTEGER, y INT)"])]
      (is (= [:rebuild-table :add-column] (mapv :kind (:ops pl)))))))

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
      (is (complete? diff (m/plan l d diff))
        (str "completeness must hold for " (pr-str [live declared]))))))

(deftest plan-guards-its-snapshot-context-at-the-entry
  (let [live (snap ["CREATE TABLE t (a INTEGER)"])
        declared (snap ["CREATE TABLE t (a INTEGER, b TEXT)"])
        diff (m/diff live declared)
        error-of (fn [ex] (select-keys (ex-data ex) [:sqlite-migrate/error :side]))]
    (testing "a missing Snapshot is malformed input, named by side"
      (is (= {:sqlite-migrate/error :malformed-input :side :live}
            (error-of (thrown-info (m/plan nil declared diff)))))
      (is (= {:sqlite-migrate/error :malformed-input :side :declared}
            (error-of (thrown-info (m/plan live nil diff))))))
    (testing "a value that is not Snapshot-shaped is malformed input too"
      (is (= {:sqlite-migrate/error :malformed-input :side :live}
            (error-of (thrown-info (m/plan {:tables {}} declared diff))))))
    (testing "the guard is at the entry: an empty Diff throws just the same"
      (is (= {:sqlite-migrate/error :malformed-input :side :live}
            (error-of (thrown-info (m/plan nil live (m/diff live live)))))))))

(deftest plan-refuses-snapshots-the-diff-was-not-computed-from
  (let [live (snap ["CREATE TABLE t (a INTEGER)"])
        declared (snap ["CREATE TABLE t (a INTEGER, b TEXT)"])
        diff (m/diff live declared)
        error-of (fn [ex] (select-keys (ex-data ex) [:sqlite-migrate/error :side]))]
    (testing "the whole live provenance must match the Diff's"
      (is (= {:sqlite-migrate/error :malformed-input :side :live}
            (error-of (thrown-info
                        (m/plan (vary-meta live assoc :schema-version 999) declared diff)))))
      (is (= {:sqlite-migrate/error :malformed-input :side :live}
            (error-of (thrown-info
                        (m/plan (vary-meta live assoc :sqlite-version "3.0.0") declared diff))))))
    (testing "on the declared side only the SQLite version is compared"
      (is (= {:sqlite-migrate/error :malformed-input :side :declared}
            (error-of (thrown-info
                        (m/plan live (vary-meta declared assoc :sqlite-version "3.0.0") diff)))))
      (is (some? (m/plan live (vary-meta declared assoc :schema-version 999) diff))
        (str "a declared schema_version is a pristine database's mutation counter,"
          " not identity (ADR 0017)")))))

(deftest rename-table-directive-plans-without-a-changed-entry
  (testing "a fused rename reads whole table values though no entry is :changed (ADR 0017)"
    (let [live (snap ["CREATE TABLE users (id INTEGER PRIMARY KEY)"])
          declared (snap ["CREATE TABLE people (id INTEGER PRIMARY KEY)"])
          diff (m/diff live declared)
          pl (m/plan live declared diff
               {:directives [{:directive :rename-table :from "users" :to "people"}]})]
      (is (= #{:added :removed} (into #{} (map :kind) (:entries diff)))
        "the Diff has no :changed entry, so the retired opts trigger would have missed it")
      (is (= [:rename-table] (mapv :kind (:ops pl))))
      (is (empty? (:unhandled pl))))))

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
          pl (m/plan live-snap declared (m/diff live-snap declared))]
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

(deftest planning-the-same-diff-twice-yields-identical-plans
  (testing "planning the same Diff twice is pr-str-identical"
    (let [l (snap determinism-live)
          d (snap determinism-declared)
          diff (m/diff l d)]
      (is (= (pr-str (m/plan l d diff)) (pr-str (m/plan l d diff))))))
  (testing "independently rebuilt inputs yield byte-identical Plans (provenance aside)"
    (let [plan-once (fn []
                      (dissoc (plan-of determinism-live determinism-declared
                                {:capabilities {:sqlite-version "3.53.0"}})
                        :live-provenance :declared-provenance))]
      (is (= (pr-str (plan-once)) (pr-str (plan-once)))))))
