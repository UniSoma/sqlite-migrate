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

(deftest structural-fidelity
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

(defn- declared-snapshot-error [declaration]
  (with-open [conn (sql-jdbc/in-memory)]
    (ex-data (thrown-info (m/declared-snapshot conn declaration)))))

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

(deftest metadata-rides-equality-neutral
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
