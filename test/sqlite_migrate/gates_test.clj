(ns sqlite-migrate.gates-test
  "Gates and Check (ADR 0008, 0010, 0011): data preconditions as
  plan-compiled sampling SELECTs on the Op that needs them, the public
  `check` surface at the effectful edge, and apply!'s default up-front
  gate-check inside the Frame. Covers the launch gate inventory's
  compilation (SQL shape, determinism), Check's report, apply!'s
  default and opt-out, and the Gate bidirectionality property on real
  in-memory SQLite."
  (:require [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
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
      (m/plan (m/diff live declared)
        (merge {:live-snapshot live :declared-snapshot declared} opts)))))

(defn- gates-of
  "Every Gate in the Plan as `[op-kind gate]` pairs, in op order."
  [pl]
  (vec (for [op (:ops pl) g (:gates op)] [(:kind op) g])))

;; ---------------------------------------------------------------------------
;; NOT NULL added/tightened

(deftest not-null-tightening-carries-a-not-null-gate
  (testing "the in-place :set-not-null op carries the gate"
    (let [pl (plan-of ["CREATE TABLE t (a INTEGER, b TEXT)"]
               ["CREATE TABLE t (a INTEGER, b TEXT NOT NULL)"])]
      (is (= [[:set-not-null
               {:code :not-null
                :path [:table "t" :column "b"]
                :explanation "column b of table t becomes NOT NULL; a stored NULL there would be rejected"
                :sql "SELECT * FROM \"t\" WHERE \"b\" IS NULL LIMIT 10"
                :limit 10}]]
            (gates-of pl)))
      (is (empty? (:unhandled pl)))))
  (testing "the same tightening collapsed onto a rebuild rides the :rebuild-table op"
    (let [pl (plan-of ["CREATE TABLE t (a INTEGER, b TEXT)"]
               ["CREATE TABLE t (a INTEGER, b BLOB NOT NULL)"])]
      (is (= [[:rebuild-table
               {:code :not-null
                :path [:table "t" :column "b"]
                :explanation "column b of table t becomes NOT NULL; a stored NULL there would be rejected"
                :sql "SELECT * FROM \"t\" WHERE \"b\" IS NULL LIMIT 10"
                :limit 10}]]
            (gates-of pl)))))
  (testing "dropping NOT NULL carries no gate — relaxation destroys nothing"
    (is (= [] (gates-of (plan-of ["CREATE TABLE t (a INTEGER, b TEXT NOT NULL)"]
                          ["CREATE TABLE t (a INTEGER, b TEXT)"]))))))

;; ---------------------------------------------------------------------------
;; CHECK added/changed — the opaque expression embedded verbatim

(deftest check-gates-embed-the-declared-expression-verbatim
  (testing "an added CHECK gates on the in-place :add-check op — ALTER validation also rejects NULL results"
    (let [pl (plan-of ["CREATE TABLE t (a INTEGER)"]
               ["CREATE TABLE t (a INTEGER, CONSTRAINT c1 CHECK (a > 0))"])]
      (is (= [[:add-check
               {:code :check
                :path [:table "t" :check "c1"]
                :explanation "table t adds CHECK (a > 0); rows where the expression is false or NULL would be rejected"
                :sql "SELECT * FROM \"t\" WHERE NOT (a > 0) OR (a > 0) IS NULL LIMIT 10"
                :limit 10}]]
            (gates-of pl)))))
  (testing "a changed CHECK gates on the recreating :add-check op, not the drop"
    (let [pl (plan-of ["CREATE TABLE t (a INTEGER, CONSTRAINT c1 CHECK (a > 0))"]
               ["CREATE TABLE t (a INTEGER, CONSTRAINT c1 CHECK (a >= 10))"])]
      (is (= [[:add-check "SELECT * FROM \"t\" WHERE NOT (a >= 10) OR (a >= 10) IS NULL LIMIT 10"]]
            (mapv (fn [[k g]] [k (:sql g)]) (gates-of pl))))))
  (testing "the same CHECK collapsed onto a rebuild gates insert-time semantics — NULL passes"
    (let [pl (plan-of ["CREATE TABLE t (a INTEGER, b TEXT)"]
               ["CREATE TABLE t (a INTEGER, b BLOB, CONSTRAINT c1 CHECK (a > 0))"])]
      (is (= [[:rebuild-table "SELECT * FROM \"t\" WHERE NOT (a > 0) LIMIT 10"]]
            (mapv (fn [[k g]] [k (:sql g)]) (gates-of pl))))))
  (testing "a removed CHECK carries no gate"
    (is (= [] (gates-of (plan-of ["CREATE TABLE t (a INTEGER, CONSTRAINT c1 CHECK (a > 0))"]
                          ["CREATE TABLE t (a INTEGER)"]))))))

;; ---------------------------------------------------------------------------
;; Column added NOT NULL with no default — the table must be empty

(deftest not-null-no-default-column-addition-requires-an-empty-table
  (testing "the in-place :add-column op carries the :empty-table gate"
    (let [pl (plan-of ["CREATE TABLE t (a INTEGER)"]
               ["CREATE TABLE t (a INTEGER, b TEXT NOT NULL)"])]
      (is (= [[:add-column
               {:code :empty-table
                :path [:table "t" :column "b"]
                :explanation "column b is added NOT NULL with no default; table t must be empty"
                :sql "SELECT * FROM \"t\" LIMIT 10"
                :limit 10}]]
            (gates-of pl)))))
  (testing "the same addition collapsed onto a rebuild carries the same gate"
    (let [pl (plan-of ["CREATE TABLE t (a INTEGER, z TEXT)"]
               ["CREATE TABLE t (a INTEGER, b TEXT NOT NULL, z BLOB)"])]
      (is (= [[:rebuild-table [:empty-table]]]
            (mapv (fn [op] [(:kind op) (mapv :code (:gates op))]) (:ops pl))))))
  (testing "a NOT NULL addition with a default carries no gate — every row takes the default"
    (is (= [] (gates-of (plan-of ["CREATE TABLE t (a INTEGER)"]
                          ["CREATE TABLE t (a INTEGER, b TEXT NOT NULL DEFAULT 'x')"])))))
  (testing "an explicit DEFAULT NULL is no default — SQLite rejects the addition just the same"
    (is (= [[:add-column [:empty-table]]]
          (mapv (fn [[k g]] [k [(:code g)]])
            (gates-of (plan-of ["CREATE TABLE t (a INTEGER)"]
                        ["CREATE TABLE t (a INTEGER, b TEXT NOT NULL DEFAULT NULL)"])))))))

;; ---------------------------------------------------------------------------
;; UNIQUE constraint or unique index created — no duplicate key groups,
;; NULL-containing keys excluded (SQLite treats them as distinct)

(deftest unique-index-creation-gates-on-duplicate-key-groups
  (let [pl (plan-of ["CREATE TABLE t (a INTEGER, b TEXT)"]
             ["CREATE TABLE t (a INTEGER, b TEXT)" "CREATE UNIQUE INDEX ux ON t (a)"])]
    (is (= [[:create-index
             {:code :unique
              :path [:table "t" :index "ux"]
              :explanation "unique index ux on table t; duplicate key groups would be rejected"
              :sql (str "SELECT \"a\", COUNT(*) AS \"sqm_count\" FROM \"t\""
                     " WHERE \"a\" IS NOT NULL"
                     " GROUP BY \"a\" COLLATE BINARY"
                     " HAVING COUNT(*) > 1 LIMIT 10")
              :limit 10}]]
          (gates-of pl))))
  (testing "a non-unique index carries no gate"
    (is (= [] (gates-of (plan-of ["CREATE TABLE t (a INTEGER)"]
                          ["CREATE TABLE t (a INTEGER)" "CREATE INDEX ix ON t (a)"])))))
  (testing "a partial unique index restricts the gate to its WHERE subset"
    (let [pl (plan-of ["CREATE TABLE t (a INTEGER)"]
               ["CREATE TABLE t (a INTEGER)"
                "CREATE UNIQUE INDEX ux ON t (a) WHERE a > 0"])]
      (is (= [(str "SELECT \"a\", COUNT(*) AS \"sqm_count\" FROM \"t\""
                " WHERE (a > 0) AND \"a\" IS NOT NULL"
                " GROUP BY \"a\" COLLATE BINARY"
                " HAVING COUNT(*) > 1 LIMIT 10")]
            (mapv (comp :sql second) (gates-of pl))))))
  (testing "a new constant-defaulted key column samples its constant — every copied row takes it (ADR 0015)"
    (let [pl (plan-of ["CREATE TABLE t (a INTEGER)"]
               ["CREATE TABLE t (a INTEGER, b TEXT DEFAULT 'x')"
                "CREATE UNIQUE INDEX ux ON t (b)"])]
      (is (= [[:create-index
               {:code :unique
                :path [:table "t" :index "ux"]
                :explanation (str "unique index ux on table t; duplicate key groups"
                               " would be rejected; new column b takes a constant"
                               " default on every copied row")
                :sql (str "SELECT ('x'), COUNT(*) AS \"sqm_count\" FROM \"t\""
                       " WHERE ('x') IS NOT NULL"
                       " GROUP BY ('x') COLLATE BINARY"
                       " HAVING COUNT(*) > 1 LIMIT 10")
                :limit 10}]]
            (gates-of pl)))))
  (testing "a new NULL-defaulted key column keeps every key distinct — no gate"
    (is (= [] (gates-of (plan-of ["CREATE TABLE t (a INTEGER)"]
                          ["CREATE TABLE t (a INTEGER, b TEXT DEFAULT NULL)"
                           "CREATE UNIQUE INDEX ux ON t (b)"])))))
  (testing "a new key column with an opaque expression default is undecidable at plan time — no gate (ADR 0015)"
    (is (= [] (gates-of (plan-of ["CREATE TABLE t (a INTEGER)"]
                          ["CREATE TABLE t (a INTEGER, b TEXT DEFAULT (hex(randomblob(4))))"
                           "CREATE UNIQUE INDEX ux ON t (b)"]))))))

(deftest unique-table-constraint-gates-on-the-rebuild
  (let [pl (plan-of ["CREATE TABLE t (a INTEGER, b TEXT)"]
             ["CREATE TABLE t (a INTEGER, b TEXT, UNIQUE (a, b))"])]
    (is (= [[:rebuild-table
             {:code :unique
              :path [:table "t" :unique [:declared 0]]
              :explanation "unique key (a, b) of table t; duplicate key groups would be rejected"
              :sql (str "SELECT \"a\", \"b\", COUNT(*) AS \"sqm_count\" FROM \"t\""
                     " WHERE \"a\" IS NOT NULL AND \"b\" IS NOT NULL"
                     " GROUP BY \"a\", \"b\""
                     " HAVING COUNT(*) > 1 LIMIT 10")
              :limit 10}]]
          (gates-of pl))))
  (testing "a part-new key gates the live subset with the new column's constant substituted (ADR 0015)"
    (let [pl (plan-of ["CREATE TABLE t (a INTEGER)"]
               ["CREATE TABLE t (a INTEGER, b TEXT DEFAULT 'x', UNIQUE (a, b))"])]
      (is (= [[:rebuild-table
               (str "SELECT \"a\", ('x'), COUNT(*) AS \"sqm_count\" FROM \"t\""
                 " WHERE \"a\" IS NOT NULL AND ('x') IS NOT NULL"
                 " GROUP BY \"a\", ('x')"
                 " HAVING COUNT(*) > 1 LIMIT 10")]]
            (mapv (fn [[k g]] [k (:sql g)]) (gates-of pl)))))))

;; ---------------------------------------------------------------------------
;; PK added/changed — no duplicates; NULLs gated only where SQLite
;; rejects them (WITHOUT ROWID / STRICT non-alias PKs; a plain rowid
;; table tolerates NULL PK values and an INTEGER PRIMARY KEY
;; auto-assigns)

(deftest pk-addition-gates-on-duplicates
  (testing "a non-alias PK on a plain rowid table gates duplicates only"
    (let [pl (plan-of ["CREATE TABLE t (a INTEGER, b TEXT)"]
               ["CREATE TABLE t (a INTEGER, b TEXT, PRIMARY KEY (b))"])]
      (is (= [[:rebuild-table
               {:code :primary-key
                :path [:table "t"]
                :explanation "primary key (b) of table t; duplicate keys would be rejected"
                :sql (str "SELECT * FROM \"t\" WHERE \"b\" IN"
                       " (SELECT \"b\" FROM \"t\" WHERE \"b\" IS NOT NULL"
                       " GROUP BY \"b\" HAVING COUNT(*) > 1) LIMIT 10")
                :limit 10}]]
            (gates-of pl)))))
  (testing "an added INTEGER PRIMARY KEY alias gates duplicates only — NULLs auto-assign"
    (let [pl (plan-of ["CREATE TABLE t (a INTEGER, b TEXT)"]
               ["CREATE TABLE t (a INTEGER PRIMARY KEY, b TEXT)"])]
      (is (= [(str "SELECT * FROM \"t\" WHERE \"a\" IN"
                " (SELECT \"a\" FROM \"t\" WHERE \"a\" IS NOT NULL"
                " GROUP BY \"a\" HAVING COUNT(*) > 1) LIMIT 10")]
            (mapv (comp :sql second) (gates-of pl))))))
  (testing "a PK change into a WITHOUT ROWID shape gates NULLs too (the widened column also carries its own :not-null gate)"
    (let [pl (plan-of ["CREATE TABLE t (a TEXT PRIMARY KEY, b TEXT) WITHOUT ROWID"]
               ["CREATE TABLE t (a TEXT, b TEXT, PRIMARY KEY (a, b)) WITHOUT ROWID"])]
      (is (= [[:primary-key
               (str "SELECT * FROM \"t\" WHERE \"a\" IS NULL OR \"b\" IS NULL"
                 " OR (\"a\", \"b\") IN"
                 " (SELECT \"a\", \"b\" FROM \"t\""
                 " WHERE \"a\" IS NOT NULL AND \"b\" IS NOT NULL"
                 " GROUP BY \"a\", \"b\" HAVING COUNT(*) > 1) LIMIT 10")]
              [:not-null "SELECT * FROM \"t\" WHERE \"b\" IS NULL LIMIT 10"]]
            (mapv (comp (juxt :code :sql) second) (gates-of pl))))))
  (testing "a PK over a new constant-defaulted column gates on the row count — every copied row takes the constant (ADR 0015)"
    (let [pl (plan-of ["CREATE TABLE t (a INTEGER)"]
               ["CREATE TABLE t (a INTEGER, b TEXT DEFAULT 'x', PRIMARY KEY (b))"])]
      (is (= [[:rebuild-table
               {:code :primary-key
                :path [:table "t"]
                :explanation (str "primary key (b) of table t; duplicate keys would be"
                               " rejected; new column b takes a constant default on"
                               " every copied row")
                :sql (str "SELECT * FROM \"t\" WHERE ('x') IN"
                       " (SELECT ('x') FROM \"t\" WHERE ('x') IS NOT NULL"
                       " GROUP BY ('x') HAVING COUNT(*) > 1) LIMIT 10")
                :limit 10}]]
            (gates-of pl)))))
  (testing "a new INTEGER PRIMARY KEY alias column carries no gate — the copy auto-assigns rowids"
    (is (= [] (gates-of (plan-of ["CREATE TABLE t (b TEXT)"]
                          ["CREATE TABLE t (a INTEGER PRIMARY KEY, b TEXT)"])))))
  (testing "a new NULL-defaulted PK column on a plain rowid table carries no gate — SQLite stores the NULLs as distinct keys"
    (is (= [] (gates-of (plan-of ["CREATE TABLE t (a INTEGER)"]
                          ["CREATE TABLE t (a INTEGER, b TEXT, PRIMARY KEY (b))"]))))))

;; ---------------------------------------------------------------------------
;; WITHOUT ROWID conversion — PK columns non-NULL

(deftest without-rowid-conversion-gates-null-pk-columns
  ;; the PK column's notnull also tightens under WITHOUT ROWID, so its
  ;; own :not-null gate rides alongside the conversion gate
  (let [pl (plan-of ["CREATE TABLE t (k TEXT PRIMARY KEY, v INTEGER)"]
             ["CREATE TABLE t (k TEXT PRIMARY KEY, v INTEGER) WITHOUT ROWID"])]
    (is (= [{:code :without-rowid
             :path [:table "t"]
             :explanation "table t converts to WITHOUT ROWID; primary-key columns must be non-NULL"
             :sql "SELECT * FROM \"t\" WHERE \"k\" IS NULL LIMIT 10"
             :limit 10}
            {:code :not-null
             :path [:table "t" :column "k"]
             :explanation "column k of table t becomes NOT NULL; a stored NULL there would be rejected"
             :sql "SELECT * FROM \"t\" WHERE \"k\" IS NULL LIMIT 10"
             :limit 10}]
          (mapv second (gates-of pl))))
    (is (= [:rebuild-table] (mapv :kind (:ops pl))))))

;; ---------------------------------------------------------------------------
;; STRICT conversion — every stored value must match its column's
;; declared type (lossless numeric coercions allowed, as SQLite allows)

(deftest strict-conversion-gates-on-stored-value-types
  ;; the text arm's full grammar-decomposition SQL is asserted
  ;; semantically, value by value, in
  ;; strict-text-gate-matches-sqlite-acceptance below
  (let [pl (plan-of ["CREATE TABLE t (i INT)"]
             ["CREATE TABLE t (i INT) STRICT"])
        [[kind g]] (gates-of pl)]
    (is (= 1 (count (gates-of pl))))
    (is (= :rebuild-table kind))
    (is (= {:code :strict
            :path [:table "t"]
            :explanation "table t converts to STRICT; every stored value must match its column's declared type"
            :limit 10}
          (dissoc g :sql)))
    (testing "the non-text arms keep their storage-class checks; the text arm decomposes the literal grammar over the whitespace-trimmed value"
      (is (str/starts-with? (:sql g)
            (str "SELECT * FROM \"t\" WHERE CASE typeof(\"i\")"
              " WHEN 'null' THEN 0 WHEN 'integer' THEN 0"
              " WHEN 'real' THEN \"i\" <> CAST(\"i\" AS INTEGER)"
              " WHEN 'text' THEN NOT ((CASE WHEN instr(upper(trim(\"i\", ' ' || char(9,10,11,12,13))), 'E') = 0")))
      (is (str/ends-with? (:sql g) " ELSE 1 END LIMIT 10"))))
  (testing "an ANY column contributes no condition; an all-ANY table carries no gate"
    (let [pl (plan-of ["CREATE TABLE t (a BLOB)"]
               ["CREATE TABLE t (a ANY) STRICT"])]
      (is (= [] (gates-of pl))))))

;; ---------------------------------------------------------------------------
;; FK added/retargeted — no orphan child rows (the Frame's
;; foreign_key_check remains the backstop; the gate reports before any
;; work runs — ADR 0008)

(deftest fk-addition-gates-on-orphan-child-rows
  (let [shared "CREATE TABLE p (id INTEGER PRIMARY KEY)"
        pl (plan-of [shared "CREATE TABLE c (pid INTEGER)"]
             [shared "CREATE TABLE c (pid INTEGER REFERENCES p(id))"])]
    (is (= [[:rebuild-table
             {:code :foreign-key
              :path [:table "c" :foreign-key [:declared 0]]
              :explanation "rows of table c referencing p must have a matching parent row"
              :sql (str "SELECT * FROM \"c\" WHERE \"pid\" IS NOT NULL"
                     " AND NOT EXISTS (SELECT 1 FROM \"p\" AS \"sqm_parent\""
                     " WHERE \"sqm_parent\".\"id\" = \"c\".\"pid\") LIMIT 10")
              :limit 10}]]
          (gates-of pl))))
  (testing "an FK over a new constant-defaulted child column looks the constant up in the parent (ADR 0015)"
    (let [shared "CREATE TABLE p (id INTEGER PRIMARY KEY)"
          pl (plan-of [shared "CREATE TABLE c (x INTEGER)"]
               [shared "CREATE TABLE c (x INTEGER, pid INTEGER DEFAULT 99 REFERENCES p(id))"])]
      (is (= [[:rebuild-table
               {:code :foreign-key
                :path [:table "c" :foreign-key [:declared 0]]
                :explanation (str "rows of table c referencing p must have a matching"
                               " parent row; new column pid takes a constant default"
                               " on every copied row")
                :sql (str "SELECT * FROM \"c\" WHERE NOT EXISTS"
                       " (SELECT 1 FROM \"p\" AS \"sqm_parent\""
                       " WHERE \"sqm_parent\".\"id\" = (99)) LIMIT 10")
                :limit 10}]]
            (gates-of pl)))))
  (testing "a named FK whose action alone changes carries no gate — no row precondition"
    (let [shared "CREATE TABLE p (id INTEGER PRIMARY KEY)"
          pl (plan-of
               [shared (str "CREATE TABLE c (pid INTEGER,"
                         " CONSTRAINT fk1 FOREIGN KEY (pid) REFERENCES p(id))")]
               [shared (str "CREATE TABLE c (pid INTEGER,"
                         " CONSTRAINT fk1 FOREIGN KEY (pid) REFERENCES p(id)"
                         " ON DELETE CASCADE)")])]
      (is (= [] (gates-of pl))))))

;; ---------------------------------------------------------------------------
;; Determinism: gate SQL is under the plan determinism contract

(deftest gated-plans-are-byte-identical-across-plannings
  (let [live ["CREATE TABLE p (id INTEGER PRIMARY KEY)"
              "CREATE TABLE t (a INT, b TEXT, pid INTEGER)"]
        declared ["CREATE TABLE p (id INTEGER PRIMARY KEY)"
                  (str "CREATE TABLE t (a INT NOT NULL, b TEXT, pid INTEGER"
                    " REFERENCES p(id), CHECK (a > 0), UNIQUE (b)) STRICT")]]
    (is (= (pr-str (plan-of live declared)) (pr-str (plan-of live declared))))))

(deftest retype-alone-carries-no-gate
  ;; ADR 0008: affinity coercion on a non-STRICT retype is SQLite's
  ;; normal insert semantics — nothing fails, so nothing is gated
  (is (= [] (gates-of (plan-of ["CREATE TABLE t (a INTEGER, b TEXT)"]
                        ["CREATE TABLE t (a INTEGER, b BLOB)"])))))

;; ---------------------------------------------------------------------------
;; The public Check surface: Plan + connection, read-only, every gate
;; verbatim, structured report (ADR 0008)

(defn- live-plan
  "Snapshot `live`'s current schema and plan it against `declared-decl`."
  [live declared-decl]
  (with-open [pristine (sql-jdbc/in-memory)]
    (let [live-snap (m/snapshot live)
          declared (m/declared-snapshot pristine declared-decl)]
      (m/plan (m/diff live-snap declared)
        {:live-snapshot live-snap :declared-snapshot declared}))))

(deftest check-reports-pass-and-fail-per-gate
  (with-open [live (sql-jdbc/in-memory)]
    (p/execute-batch! live
      ["CREATE TABLE t (a INTEGER, b TEXT)"
       "INSERT INTO t (a, b) VALUES (1, 'x'), (2, NULL), (3, NULL)"])
    (let [pl (live-plan live ["CREATE TABLE t (a INTEGER, b TEXT NOT NULL)"])
          result (m/check live pl)]
      (testing "a failing gate reports its exact violation count and sample rows"
        (is (false? (:pass? result)))
        (is (= [{:op-index 0
                 :pass? false
                 :violations 2
                 :more? false
                 :sample-rows [{:a 2 :b nil} {:a 3 :b nil}]}]
              (mapv #(dissoc % :gate) (:gates result))))
        (is (= [:not-null] (mapv (comp :code :gate) (:gates result)))))
      (testing "check is read-only — the rows and the schema fingerprint survive it"
        (is (= 3 (:n (first (p/execute-query live "SELECT COUNT(*) AS n FROM t" [])))))))
    (p/execute-batch! live ["UPDATE t SET b = 'filled' WHERE b IS NULL"])
    (testing "the same plan passes once the data conforms"
      (let [result (m/check live (live-plan live ["CREATE TABLE t (a INTEGER, b TEXT NOT NULL)"]))]
        (is (true? (:pass? result)))
        (is (= [{:pass? true :violations 0 :more? false :sample-rows []}]
              (mapv #(dissoc % :gate :op-index) (:gates result))))))))

(deftest check-caps-samples-at-the-baked-limit
  (with-open [live (sql-jdbc/in-memory)]
    (p/execute-batch! live
      (into ["CREATE TABLE t (a INTEGER, b TEXT)"]
        (map #(str "INSERT INTO t (a, b) VALUES (" % ", NULL)") (range 12))))
    (let [result (m/check live (live-plan live ["CREATE TABLE t (a INTEGER, b TEXT NOT NULL)"]))
          g (first (:gates result))]
      (is (false? (:pass? result)))
      (is (= 10 (:violations g)))
      (is (true? (:more? g)) "limit rows report as \"limit or more\"")
      (is (= 10 (count (:sample-rows g)))))))

(deftest check-refuses-on-fingerprint-drift
  (with-open [live (sql-jdbc/in-memory)]
    (p/execute-batch! live ["CREATE TABLE t (a INTEGER, b TEXT)"])
    (let [pl (live-plan live ["CREATE TABLE t (a INTEGER, b TEXT NOT NULL)"])]
      (p/execute-batch! live ["CREATE TABLE drifted (x INTEGER)"])
      (let [ex (try (m/check live pl) nil (catch Exception e e))]
        (is (some? ex) "check must throw on fingerprint mismatch")
        (is (= :drift-refused (:sqlite-migrate/error (ex-data ex))))))))

;; ---------------------------------------------------------------------------
;; apply! gate-checks by default — up-front once the Frame's transaction
;; is open, rolling back with the Check result on failure (ADR 0008, 0011)

(deftest apply-gate-checks-by-default-and-rolls-back-with-the-check-result
  (with-open [live (sql-jdbc/in-memory)]
    (p/execute-batch! live
      ["CREATE TABLE t (a INTEGER, b TEXT)"
       "INSERT INTO t (a, b) VALUES (1, 'x'), (2, NULL)"])
    (let [pl (live-plan live ["CREATE TABLE t (a INTEGER, b TEXT NOT NULL)"])
          before (m/snapshot live)
          ex (try (m/apply! live pl) nil (catch Exception e e))]
      (is (some? ex) "apply! must throw when a Gate fails")
      (testing "the throw carries the Check result verbatim"
        (is (= :gate-failed (:sqlite-migrate/error (ex-data ex))))
        (let [result (:check (ex-data ex))]
          (is (false? (:pass? result)))
          (is (= [[:not-null 1]]
                (mapv (juxt (comp :code :gate) :violations) (:gates result))))
          (is (= [{:a 2 :b nil}] (:sample-rows (first (:gates result)))))))
      (testing "nothing was applied — schema and rows survive the rollback"
        (is (= before (m/snapshot live)))
        (is (= [{:a 1 :b "x"} {:a 2 :b nil}]
              (p/execute-query live "SELECT a, b FROM t ORDER BY a" [])))))))

(deftest check-gates-opt-out-skips-the-pre-check
  (with-open [live (sql-jdbc/in-memory)]
    (p/execute-batch! live
      ["CREATE TABLE t (a INTEGER, b TEXT)"
       "INSERT INTO t (a, b) VALUES (1, NULL)"])
    (let [pl (live-plan live ["CREATE TABLE t (a INTEGER, b TEXT NOT NULL)"])
          ex (try (m/apply! live pl {:check-gates? false}) nil (catch Exception e e))]
      (is (some? ex))
      (is (= :sqlite-error (:sqlite-migrate/error (ex-data ex)))
        "with gates skipped the raw SQLite failure surfaces instead")
      (is (= [{:a 1 :b nil}] (p/execute-query live "SELECT a, b FROM t" []))
        "the Frame still rolled everything back"))))

(deftest apply-report-carries-the-check-result
  (with-open [live (sql-jdbc/in-memory)]
    (p/execute-batch! live
      ["CREATE TABLE t (a INTEGER, b TEXT)"
       "INSERT INTO t (a, b) VALUES (1, 'x')"])
    (testing "the default pre-check's Check result rides the Apply report"
      (let [report (m/apply! live (live-plan live ["CREATE TABLE t (a INTEGER, b TEXT NOT NULL)"]))]
        (is (true? (get-in report [:check :pass?])))
        (is (= [:not-null] (mapv (comp :code :gate) (get-in report [:check :gates]))))))
    (testing "absent when gate-checking was skipped (ADR 0011)"
      (let [report (m/apply! live (live-plan live ["CREATE TABLE t (a INTEGER, b BLOB NOT NULL)"])
                     {:check-gates? false})]
        (is (not (contains? report :check)))))))

;; ---------------------------------------------------------------------------
;; The no-gate decisions for new PK columns (ADR 0015) pinned to real
;; SQLite behavior — the quirks the skips rely on must keep holding

(deftest new-pk-column-no-gate-decisions-hold-on-real-sqlite
  (testing "a plain rowid table stores NULL values of a new PK column as distinct keys"
    (with-open [conn (sql-jdbc/in-memory)]
      (p/execute-batch! conn ["CREATE TABLE t (a INTEGER)"
                              "INSERT INTO t (a) VALUES (1), (2)"])
      (let [pl (live-plan conn ["CREATE TABLE t (a INTEGER, b TEXT, PRIMARY KEY (b))"])]
        (is (= [] (vec (mapcat :gates (:ops pl)))))
        (is (map? (m/apply! conn pl))))))
  (testing "an omitted INTEGER PRIMARY KEY alias column auto-assigns fresh rowids even over a constant DEFAULT"
    (with-open [conn (sql-jdbc/in-memory)]
      (p/execute-batch! conn ["CREATE TABLE t (a INTEGER)"
                              "INSERT INTO t (a) VALUES (1), (2)"])
      (let [pl (live-plan conn ["CREATE TABLE t (a INTEGER, b INTEGER DEFAULT 5, PRIMARY KEY (b))"])]
        (is (= [] (vec (mapcat :gates (:ops pl)))))
        (is (map? (m/apply! conn pl)))
        (is (= [1 2] (mapv :b (p/execute-query conn "SELECT b FROM t ORDER BY b" [])))
          "the DEFAULT did not win over rowid auto-assignment")))))

;; ---------------------------------------------------------------------------
;; The STRICT text gate against the real acceptance rule (ADR 0015):
;; for every spelling in the corpus, the compiled gate's verdict must
;; equal what a real STRICT insert does

(deftest strict-text-gate-matches-sqlite-acceptance
  (let [corpus ["0123" "1e2" "1E2" "12." ".5" "+12" "-0" "1.e2" "00.5"
                "1.5" "-1.5e-3" "1e+2"
                " 12 " "\t12\n" "12\f" "\r12" "\u00a012"
                "0x1A" "1_000" "Inf" "NaN" "" "  " "12abc" "1.2.3" "."
                "+" "5e" "5e+" ".e2" "e5"
                "9223372036854775807" "9223372036854775808"
                "-9223372036854775808" "-9223372036854775809"
                "9223372036854775806.0" "1e999" "-1e999"]]
    (doseq [v corpus
            ftype ["INT" "REAL"]]
      (let [accepted? (with-open [conn (sql-jdbc/in-memory)]
                        (p/execute-batch! conn
                          [(str "CREATE TABLE t (v " ftype ") STRICT")])
                        (try (p/execute-query conn
                               "INSERT INTO t (v) VALUES (?) RETURNING 1" [v])
                          true
                          (catch Exception _ false)))
            ;; the live column carries no affinity so the value stays text
            gate-pass? (with-open [conn (sql-jdbc/in-memory)]
                         (p/execute-batch! conn ["CREATE TABLE t (v)"])
                         (p/execute-query conn
                           "INSERT INTO t (v) VALUES (?) RETURNING 1" [v])
                         (:pass? (m/check conn
                                   (live-plan conn
                                     [(str "CREATE TABLE t (v " ftype ") STRICT")]))))]
        (is (= accepted? gate-pass?)
          (str ftype " column, value " (pr-str v)))))))

;; ---------------------------------------------------------------------------
;; Gate bidirectionality (ADR 0010), scoped to the inventoried codes:
;; Check pass => no data-dependent apply failure; Gate fail => apply
;; would abort. Gates are a predicate, not advice.

(def ^:private bidirectionality-scenarios
  [{:scenario "NOT NULL tightened in place"
    :live ["CREATE TABLE t (a INTEGER, b TEXT)"]
    :declared ["CREATE TABLE t (a INTEGER, b TEXT NOT NULL)"]
    :conforming ["INSERT INTO t (a, b) VALUES (1, 'x')"]
    :violating ["INSERT INTO t (a, b) VALUES (1, NULL)"]}
   {:scenario "NOT NULL tightened through a rebuild"
    :live ["CREATE TABLE t (a INTEGER, b TEXT)"]
    :declared ["CREATE TABLE t (a INTEGER, b BLOB NOT NULL)"]
    :conforming ["INSERT INTO t (a, b) VALUES (1, 'x')"]
    :violating ["INSERT INTO t (a, b) VALUES (1, NULL)"]}
   {:scenario "unique index created"
    :live ["CREATE TABLE t (a INTEGER)"]
    :declared ["CREATE TABLE t (a INTEGER)" "CREATE UNIQUE INDEX ux ON t (a)"]
    :conforming ["INSERT INTO t (a) VALUES (1), (2), (NULL), (NULL)"]
    :violating ["INSERT INTO t (a) VALUES (1), (1)"]}
   {:scenario "UNIQUE table constraint through a rebuild"
    :live ["CREATE TABLE t (a INTEGER, b TEXT)"]
    :declared ["CREATE TABLE t (a INTEGER, b TEXT, UNIQUE (a, b))"]
    :conforming ["INSERT INTO t (a, b) VALUES (1, 'x'), (1, 'y'), (NULL, 'x'), (NULL, 'x')"]
    :violating ["INSERT INTO t (a, b) VALUES (1, 'x'), (1, 'x')"]}
   {:scenario "PK added through a rebuild"
    :live ["CREATE TABLE t (a INTEGER, b TEXT)"]
    :declared ["CREATE TABLE t (a INTEGER, b TEXT, PRIMARY KEY (b))"]
    :conforming ["INSERT INTO t (a, b) VALUES (1, 'x'), (2, 'y')"]
    :violating ["INSERT INTO t (a, b) VALUES (1, 'x'), (2, 'x')"]}
   {:scenario "CHECK added in place"
    :live ["CREATE TABLE t (a INTEGER)"]
    :declared ["CREATE TABLE t (a INTEGER, CONSTRAINT c1 CHECK (a > 0))"]
    :conforming ["INSERT INTO t (a) VALUES (1)"]
    :violating ["INSERT INTO t (a) VALUES (0)"]}
   {:scenario "CHECK added in place rejects NULL results too (ALTER validation semantics)"
    :live ["CREATE TABLE t (a INTEGER)"]
    :declared ["CREATE TABLE t (a INTEGER, CONSTRAINT c1 CHECK (a > 0))"]
    :conforming ["INSERT INTO t (a) VALUES (2)"]
    :violating ["INSERT INTO t (a) VALUES (NULL)"]}
   {:scenario "CHECK changed through a rebuild tolerates NULL results (insert-time semantics)"
    :live ["CREATE TABLE t (a INTEGER, b TEXT, CHECK (a > 0))"]
    :declared ["CREATE TABLE t (a INTEGER, b BLOB, CHECK (a > 10))"]
    :conforming ["INSERT INTO t (a) VALUES (11), (NULL)"]
    :violating ["INSERT INTO t (a) VALUES (5)"]}
   {:scenario "FK added through a rebuild"
    :live ["CREATE TABLE p (id INTEGER PRIMARY KEY)"
           "CREATE TABLE c (pid INTEGER)"]
    :declared ["CREATE TABLE p (id INTEGER PRIMARY KEY)"
               "CREATE TABLE c (pid INTEGER REFERENCES p(id))"]
    :conforming ["INSERT INTO p (id) VALUES (1)"
                 "INSERT INTO c (pid) VALUES (1), (NULL)"]
    :violating ["INSERT INTO c (pid) VALUES (99)"]}
   {:scenario "STRICT conversion"
    :live ["CREATE TABLE t (i INT, s TEXT)"]
    :declared ["CREATE TABLE t (i INT, s TEXT) STRICT"]
    :conforming ["INSERT INTO t (i, s) VALUES (1, 'x'), (NULL, NULL)"]
    :violating ["INSERT INTO t (i, s) VALUES ('12abc', 'x')"]}
   {:scenario "WITHOUT ROWID conversion"
    :live ["CREATE TABLE t (k TEXT PRIMARY KEY, v INTEGER)"]
    :declared ["CREATE TABLE t (k TEXT PRIMARY KEY, v INTEGER) WITHOUT ROWID"]
    :conforming ["INSERT INTO t (k, v) VALUES ('a', 1)"]
    :violating ["INSERT INTO t (k, v) VALUES (NULL, 1)"]}
   {:scenario "column added NOT NULL with no default"
    :live ["CREATE TABLE t (a INTEGER)"]
    :declared ["CREATE TABLE t (a INTEGER, b TEXT NOT NULL)"]
    :conforming []
    :violating ["INSERT INTO t (a) VALUES (1)"]}
   {:scenario "column added NOT NULL with an explicit DEFAULT NULL"
    :live ["CREATE TABLE t (a INTEGER)"]
    :declared ["CREATE TABLE t (a INTEGER, b TEXT NOT NULL DEFAULT NULL)"]
    :conforming []
    :violating ["INSERT INTO t (a) VALUES (1)"]}
   {:scenario "unique index over a new defaulted column"
    :live ["CREATE TABLE t (a INTEGER)"]
    :declared ["CREATE TABLE t (a INTEGER, b TEXT DEFAULT 'x')"
               "CREATE UNIQUE INDEX ux ON t (b)"]
    :conforming ["INSERT INTO t (a) VALUES (1)"]
    :violating ["INSERT INTO t (a) VALUES (1), (2)"]}
   {:scenario "UNIQUE table constraint over a part-new key through a rebuild"
    :live ["CREATE TABLE t (a INTEGER)"]
    :declared ["CREATE TABLE t (a INTEGER, b TEXT DEFAULT 'x', UNIQUE (a, b))"]
    :conforming ["INSERT INTO t (a) VALUES (1), (2)"]
    :violating ["INSERT INTO t (a) VALUES (1), (1)"]}
   {:scenario "PK over a new defaulted column through a rebuild"
    :live ["CREATE TABLE t (a INTEGER)"]
    :declared ["CREATE TABLE t (a INTEGER, b TEXT DEFAULT 'x', PRIMARY KEY (b))"]
    :conforming ["INSERT INTO t (a) VALUES (1)"]
    :violating ["INSERT INTO t (a) VALUES (1), (2)"]}
   {:scenario "FK over a new defaulted column through a rebuild"
    :live ["CREATE TABLE p (id INTEGER PRIMARY KEY)"
           "CREATE TABLE c (x INTEGER)"]
    :declared ["CREATE TABLE p (id INTEGER PRIMARY KEY)"
               "CREATE TABLE c (x INTEGER, pid INTEGER DEFAULT 99 REFERENCES p(id))"]
    :conforming ["INSERT INTO p (id) VALUES (99)"
                 "INSERT INTO c (x) VALUES (1)"]
    :violating ["INSERT INTO c (x) VALUES (1)"]}
   {:scenario "STRICT conversion accepts non-canonical numeric text"
    ;; the live columns carry no affinity so the inserted text stays text
    :live ["CREATE TABLE t (i, r)"]
    :declared ["CREATE TABLE t (i INT, r REAL) STRICT"]
    :conforming ["INSERT INTO t (i, r) VALUES ('0123', '00.5'), (' 1e2 ', '1e999'), ('+12', '.5')"]
    :violating ["INSERT INTO t (i, r) VALUES ('9223372036854775808', 1)"]}])

(deftest gate-bidirectionality-property
  (doseq [{:keys [scenario live declared conforming violating]} bidirectionality-scenarios]
    (testing (str scenario ": Check pass implies no data-dependent apply failure")
      (with-open [conn (sql-jdbc/in-memory)]
        (p/execute-batch! conn (into (vec live) conforming))
        (let [pl (live-plan conn declared)]
          (is (seq (mapcat :gates (:ops pl))) "the scenario must compile a Gate")
          (is (true? (:pass? (m/check conn pl))))
          (is (map? (m/apply! conn pl))))))
    (testing (str scenario ": a failing Gate means apply would abort")
      (with-open [conn (sql-jdbc/in-memory)]
        (p/execute-batch! conn (into (vec live) violating))
        (let [pl (live-plan conn declared)]
          (is (false? (:pass? (m/check conn pl))))
          (let [ex (try (m/apply! conn pl {:check-gates? false}) nil (catch Exception e e))]
            (is (some? ex) "running apply anyway aborts on SQLite's own enforcement")
            (is (= :sqlite-error (:sqlite-migrate/error (ex-data ex))))))))))
