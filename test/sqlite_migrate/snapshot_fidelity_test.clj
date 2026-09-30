(ns sqlite-migrate.snapshot-fidelity-test
  "Full Snapshot fidelity (ticket sqm-01kzcv5g8zb3): the nasty-schema
  corpus introspects to expected values through the public snapshot /
  declared-snapshot seam, on real in-memory SQLite."
  (:require [clojure.test :refer [are deftest is testing]]
    [sqlite-migrate.core :as m]
    [sqlite-migrate.corpus :as corpus]
    [sqlite-migrate.jdbc :as sql-jdbc]
    [sqlite-migrate.protocols :as p]
    [sqlite-migrate.test-util :refer [thrown-info]]))

(defn- corpus-snapshot []
  (with-open [conn (sql-jdbc/in-memory)]
    (m/declared-snapshot conn corpus/nasty-declaration)))

(deftest snapshot-mirrors-the-live-schemas-structure
  (let [snap (corpus-snapshot)]
    (testing "main-schema tables only — no shadow tables, no sqlite_* internals"
      (is (= #{"order" "items" "notes" "shipments"} (set (keys (:tables snap))))))
    (testing "a virtual table is opaque: name + verbatim stored CREATE sql as meta"
      (is (= {:name "notes" :virtual? true}
            (get-in snap [:tables "notes"])))
      (is (= "CREATE VIRTUAL TABLE notes USING fts5(body, tokenize = 'porter')"
            (:sql (meta (get-in snap [:tables "notes"]))))))
    (testing "every object carries its stored CREATE sql verbatim as Clojure meta"
      (is (= (nth corpus/nasty-declaration 0)
            (:sql (meta (get-in snap [:tables "order"])))))
      (is (= (nth corpus/nasty-declaration 2)
            (:sql (meta (get-in snap [:tables "items" :indexes "idx_items_qty"]))))))
    (testing "table options come from pragmas, not parsing"
      (is (true? (get-in snap [:tables "items" :strict?])))
      (is (true? (get-in snap [:tables "items" :without-rowid?])))
      (is (false? (get-in snap [:tables "order" :strict?])))
      (is (false? (get-in snap [:tables "order" :without-rowid?]))))
    (testing "columns in cid order, generated columns included"
      (is (= ["sku" "qty" "price" "subtotal" "big" "order_id"]
            (mapv :name (get-in snap [:tables "items" :columns]))))
      (is (= ["id" "group" "total"]
            (mapv :name (get-in snap [:tables "order" :columns])))))
    (testing "not-null and pk facts ride on columns"
      (let [items-cols (into {} (map (juxt :name identity))
                         (get-in snap [:tables "items" :columns]))]
        (is (true? (get-in items-cols ["qty" :not-null?])))
        (is (false? (get-in items-cols ["price" :not-null?])))
        (is (= 1 (get-in items-cols ["sku" :pk])))
        (is (= 0 (get-in items-cols ["qty" :pk])))))
    (testing "indexes nest under their table; automatic indexes are excluded"
      (is (= #{"idx_order_expr" "idx_order_group"}
            (set (keys (get-in snap [:tables "order" :indexes])))))
      (is (= #{"idx_items_qty"}
            (set (keys (get-in snap [:tables "items" :indexes])))))
      (is (true? (get-in snap [:tables "order" :indexes "idx_order_group" :unique?])))
      (is (true? (get-in snap [:tables "items" :indexes "idx_items_qty" :partial?])))
      (is (false? (get-in snap [:tables "order" :indexes "idx_order_expr" :partial?]))))
    (testing "triggers nest under the table or view they fire on"
      (is (= #{"trg_order_touch"}
            (set (keys (get-in snap [:tables "order" :triggers])))))
      (is (= (nth corpus/nasty-declaration 6)
            (:sql (meta (get-in snap [:tables "order" :triggers "trg_order_touch"])))))
      (is (= #{"trg_view_insert"}
            (set (keys (get-in snap [:views "v_totals" :triggers]))))))
    (testing "views are top-level with ordered column names and verbatim sql as meta"
      (is (= #{"v_totals"} (set (keys (:views snap)))))
      (is (= ["id" "total"] (get-in snap [:views "v_totals" :columns])))
      (is (= (nth corpus/nasty-declaration 5)
            (:sql (meta (get-in snap [:views "v_totals"]))))))))

(deftest extractor-lifts-pragma-invisible-facts
  (let [snap (corpus-snapshot)
        order (get-in snap [:tables "order"])
        items (get-in snap [:tables "items"])
        col (fn [table name] (some #(when (= name (:name %)) %) (:columns table)))]
    (testing "DEFAULT spellings verbatim — literal, signed number, parenthesized expression"
      (is (= "'none'" (:default (col order "group"))))
      (is (= "-1" (:default (col items "qty"))))
      (is (= "(1.0 + 2.0)" (:default (col order "total"))))
      (is (nil? (:default (col items "price")))))
    (testing "per-column COLLATE"
      (is (= "NOCASE" (:collate (col order "group"))))
      (is (nil? (:collate (col items "sku")))))
    (testing "generated-column expressions verbatim, storage from the pragma"
      (is (= {:expr "qty * price" :storage :virtual} (:generated (col items "subtotal"))))
      (is (= {:expr "qty * 100" :storage :stored} (:generated (col items "big"))))
      (is (nil? (:generated (col items "qty")))))
    (testing "CHECK bodies verbatim with constraint names, source order"
      (is (= [{:name "total_positive" :expr "total > 0"}] (:checks order)))
      (is (= [{:name nil :expr "qty <> 0"}] (:checks items))))
    (testing "primary key with constraint name and AUTOINCREMENT"
      (is (= {:name nil :columns ["id"]} (:primary-key order)))
      (is (true? (:autoincrement? order)))
      (is (= {:name "sku_pk" :columns ["sku"]} (:primary-key items)))
      (is (false? (:autoincrement? items))))
    (testing "UNIQUE table constraints with dequoted column names"
      (is (= [{:name nil :columns ["sku" "qty"]}] (:uniques items)))
      (is (= [] (:uniques order))))
    (testing "foreign keys: pragma facts plus extracted name and deferrability"
      (is (= [{:name "fk_order"
               :columns ["order_id"]
               :ref-table "order"
               :ref-columns ["id"]
               :on-update "NO ACTION"
               :on-delete "CASCADE"
               :match "NONE"
               :deferrable "DEFERRABLE INITIALLY DEFERRED"}]
            (:foreign-keys items)))
      (is (= [] (:foreign-keys order))))
    (let [shipments (get-in snap [:tables "shipments"])]
      (testing "multiple FKs pair name and deferrability by referenced table + from-columns"
        (is (= [{:name nil
                 :columns ["note_ref"]
                 :ref-table "order"
                 :ref-columns ["id"]
                 :on-update "NO ACTION"
                 :on-delete "SET NULL"
                 :match "NONE"
                 :deferrable nil}
                {:name "fk_ship_order"
                 :columns ["order_id"]
                 :ref-table "order"
                 :ref-columns ["id"]
                 :on-update "CASCADE"
                 :on-delete "NO ACTION"
                 :match "NONE"
                 :deferrable nil}
                {:name nil
                 :columns ["item_sku"]
                 :ref-table "items"
                 :ref-columns ["sku"]
                 :on-update "NO ACTION"
                 :on-delete "NO ACTION"
                 :match "NONE"
                 :deferrable "DEFERRABLE INITIALLY DEFERRED"}]
              (:foreign-keys shipments))))
      (testing "a CHECK after a column-level REFERENCES clause is still captured"
        (is (= [{:name nil :expr "note_ref <> 0"}] (:checks shipments)))))
    (testing "index expressions and partial WHERE clauses verbatim"
      (is (= "qty > 0" (get-in items [:indexes "idx_items_qty" :where])))
      (is (= [{:name "qty" :collate "BINARY" :desc? true}]
            (get-in items [:indexes "idx_items_qty" :columns])))
      (is (= [{:expr "total * 2" :collate "BINARY" :desc? false}
              {:name "group" :collate "RTRIM" :desc? false}]
            (get-in order [:indexes "idx_order_expr" :columns]))))))

(defn- declared-snapshot-exception [declaration]
  (with-open [conn (sql-jdbc/in-memory)]
    (thrown-info (m/declared-snapshot conn declaration))))

(defn- declared-snapshot-error [declaration]
  (ex-data (declared-snapshot-exception declaration)))

(deftest declaration-with-invisible-effects-errors-loudly
  (testing "statements introspection cannot capture error with which-statement context"
    (are [declaration bad-statement bad-index]
      (let [data (declared-snapshot-error declaration)]
        (and (= :malformed-input (:sqlite-migrate/error data))
          (= bad-statement (:statement data))
          (= bad-index (:statement-index data))))
      ;; DML
      ["CREATE TABLE t (x INT)" "INSERT INTO t VALUES (1)"]
      "INSERT INTO t VALUES (1)" 1
      ;; PRAGMA side effect
      ["PRAGMA user_version = 5" "CREATE TABLE t (x INT)"]
      "PRAGMA user_version = 5" 0
      ;; ATTACH
      ["CREATE TABLE t (x INT)" "ATTACH ':memory:' AS aux1"]
      "ATTACH ':memory:' AS aux1" 1
      ;; temp objects live outside the main schema
      ["CREATE TEMP TABLE tt (x INT)"]
      "CREATE TEMP TABLE tt (x INT)" 0
      ;; CREATE TABLE AS SELECT smuggles rows in
      ["CREATE TABLE t2 AS SELECT 1 AS x"]
      "CREATE TABLE t2 AS SELECT 1 AS x" 0
      ;; ANALYZE creates/writes engine-internal sqlite_stat* tables the
      ;; Snapshot excludes
      ["CREATE TABLE t (x INT)" "ANALYZE"]
      "ANALYZE" 1))
  (testing "a pure-DDL declaration still passes"
    (with-open [conn (sql-jdbc/in-memory)]
      (is (map? (m/declared-snapshot conn corpus/nasty-declaration))))))

(deftest provenance-rides-in-clojure-meta-without-changing-snapshot-equality
  ;; Same schema reached through different histories: the Snapshot values
  ;; are = outright; provenance (the schema_version fingerprint) lives in
  ;; Clojure meta and differs without touching the value.
  (with-open [pristine (sql-jdbc/in-memory)
              detoured (sql-jdbc/in-memory)]
    (let [a (m/declared-snapshot pristine corpus/nasty-declaration)
          _ (p/execute-batch! detoured ["CREATE TABLE scratch (x INT)"
                                        "DROP TABLE scratch"])
          _ (p/execute-batch! detoured corpus/nasty-declaration)
          b (m/snapshot detoured)]
      (is (= a b))
      (is (not= (:schema-version (meta a))
            (:schema-version (meta b))))
      (is (string? (:sqlite-version (meta a))))
      (is (integer? (:schema-version (meta a)))))))

(deftest whitespace-variant-ddl-yields-equal-snapshots-with-differing-provenance
  ;; Byte-different but shape-identical DDL: the Snapshots are =, while
  ;; the per-object stored CREATE sql (Clojure meta) still differs.
  (with-open [ca (sql-jdbc/in-memory)
              cb (sql-jdbc/in-memory)]
    (let [a (m/declared-snapshot ca ["CREATE TABLE t (x INTEGER, y TEXT)"])
          b (m/declared-snapshot cb ["CREATE TABLE t (x INTEGER,   y TEXT)"])]
      (is (= a b))
      (is (not= (:sql (meta (get-in a [:tables "t"])))
            (:sql (meta (get-in b [:tables "t"]))))))))

(deftest a-multi-statement-string-realizes-every-statement
  (testing "one string of three statements gives an empty Diff against the live schema they build"
    (let [statements ["CREATE TABLE t (a INTEGER)"
                      "CREATE VIEW v AS SELECT a FROM t"
                      "CREATE INDEX t_a ON t(a)"]]
      (with-open [live (sql-jdbc/in-memory)
                  pristine (sql-jdbc/in-memory)]
        (p/execute-batch! live statements)
        (is (= [] (:entries (m/diff (m/snapshot live)
                              (m/declared-snapshot pristine
                                "CREATE TABLE t (a INTEGER); CREATE VIEW v AS SELECT a FROM t; CREATE INDEX t_a ON t(a)")))))))))

(deftest a-statement-later-in-a-string-is-refused-with-its-declaration-index
  (testing "invisible effects after the first statement of a string are refused, indexed across the whole Declaration"
    (are [declaration bad-statement bad-index]
      (let [data (declared-snapshot-error declaration)]
        (and (= :malformed-input (:sqlite-migrate/error data))
          (= bad-statement (:statement data))
          (= bad-index (:statement-index data))))
      ;; DML
      "CREATE TABLE t (a); INSERT INTO t VALUES (1)"
      "INSERT INTO t VALUES (1)" 1
      ;; ATTACH
      "CREATE TABLE t (a); ATTACH ':memory:' AS aux1;"
      "ATTACH ':memory:' AS aux1;" 1
      ;; PRAGMA side effect
      "CREATE TABLE t (a);\nPRAGMA user_version = 5;"
      "PRAGMA user_version = 5;" 1
      ;; temp objects live outside the main schema
      "CREATE TABLE t (a); CREATE TEMP TABLE tt (x)"
      "CREATE TEMP TABLE tt (x)" 1
      ;; the index counts every statement of every string before it
      ["CREATE TABLE t (a); CREATE TABLE u (b)" "CREATE TABLE w (c); DELETE FROM t"]
      "DELETE FROM t" 3)))

(deftest a-statement-sqlite-rejects-carries-its-declaration-index-and-text
  (testing "a rejected statement throws :sqlite-error naming its index across the whole Declaration, with its trimmed text"
    (are [declaration bad-statement bad-index]
      (let [e (declared-snapshot-exception declaration)
            data (ex-data e)]
        (and (= :sqlite-error (:sqlite-migrate/error data))
          (= bad-statement (:statement data))
          (= bad-index (:statement-index data))
          (= (str "SQLite rejected Declaration statement " bad-index) (ex-message e))
          (instance? java.sql.SQLException (ex-cause e))))
      ["CREATE TABLE a (x)" "CREATE TABLE b (y)" "CREATE TABLE a (z)"]
      "CREATE TABLE a (z)" 2
      "CREATE TABLE a (x);\n  CREATE TABLE a (z);\n"
      "CREATE TABLE a (z);" 1)))

(deftest a-semicolon-inside-a-statement-does-not-end-it
  (testing "a semicolon in a string literal, a comment, or a trigger body stays inside its statement, as SQLite's prepare loop reads it"
    (with-open [conn (sql-jdbc/in-memory)]
      (let [snap (m/declared-snapshot conn
                   (str "CREATE TABLE t (a TEXT DEFAULT ';', b TEXT);\n"
                     "-- a comment; with a semicolon\n"
                     "CREATE TABLE u (c /* inline; comment */ TEXT);\n"
                     "CREATE TRIGGER t_ai AFTER INSERT ON t BEGIN\n"
                     "  INSERT INTO u VALUES (new.a);\n"
                     "  DELETE FROM u WHERE c = ';';\n"
                     "END;\n"
                     "CREATE INDEX u_c ON u(c);\n"
                     "-- trailing; comment"))]
        (is (= #{"t" "u"} (set (keys (:tables snap)))))
        (is (= "';'" (get-in snap [:tables "t" :columns 0 :default])))
        (is (= (str "CREATE TRIGGER t_ai AFTER INSERT ON t BEGIN\n"
                 "  INSERT INTO u VALUES (new.a);\n"
                 "  DELETE FROM u WHERE c = ';';\n"
                 "END")
              (:sql (meta (get-in snap [:tables "t" :triggers "t_ai"])))))
        (is (contains? (get-in snap [:tables "u" :indexes]) "u_c")
          "the statement after the trigger is realized too")))))

(deftest an-unclosed-last-statement-is-an-error-not-dropped
  (testing "a last statement left open by a literal, a quoted identifier or a trigger body fails loudly"
    (are [declaration]
      (= :sqlite-error (:sqlite-migrate/error (declared-snapshot-error declaration)))
      "CREATE TABLE t (a); CREATE TABLE u (b TEXT DEFAULT 'x)"
      "CREATE TABLE t (a); CREATE TABLE \"u (b)"
      "CREATE TABLE t (a); CREATE TRIGGER tr AFTER INSERT ON t BEGIN SELECT 1")))

(deftest a-declaration-element-that-is-not-a-string-is-refused
  (let [data (declared-snapshot-error ["CREATE TABLE t (a)" nil])]
    (is (= :malformed-input (:sqlite-migrate/error data)))
    (is (= 1 (:element-index data)))))

(defn- snapshot-exception [statements]
  (with-open [conn (sql-jdbc/in-memory)]
    (p/execute-batch! conn statements)
    (thrown-info (m/snapshot conn))))

(deftest a-view-sqlite-cannot-resolve-is-a-sqlite-error-naming-the-view
  (testing "snapshot of a view reading a missing column, or a table dropped under it, throws :sqlite-error with :view and SQLite's exception as the cause"
    (are [statements]
      (let [e (snapshot-exception statements)]
        (and (= {:sqlite-migrate/error :sqlite-error :view "v"} (ex-data e))
          (instance? org.sqlite.SQLiteException (ex-cause e))))
      ["CREATE TABLE a (x)" "CREATE VIEW v AS SELECT nope FROM a"]
      ["CREATE TABLE a (x)" "CREATE VIEW v AS SELECT x FROM a" "DROP TABLE a"])))

(deftest a-declaration-holding-a-view-sqlite-cannot-resolve-is-a-sqlite-error-naming-the-view
  (let [e (declared-snapshot-exception ["CREATE TABLE a (x)" "CREATE VIEW v AS SELECT nope FROM a"])]
    (is (= {:sqlite-migrate/error :sqlite-error :view "v"} (ex-data e))
      "the error names the view, not a Declaration statement")
    (is (instance? org.sqlite.SQLiteException (ex-cause e)))))

(deftest a-declaration-may-create-a-view-before-the-table-it-reads
  (with-open [conn (sql-jdbc/in-memory)]
    (is (= ["x"] (get-in (m/declared-snapshot conn ["CREATE VIEW v AS SELECT x FROM a"
                                                    "CREATE TABLE a (x)"])
                   [:views "v" :columns])))))
