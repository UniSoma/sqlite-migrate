(ns sqlite-migrate.schema-test
  "The EDN Schema value sugar (ADR 0002): `->sql` compiles the
  documented subset to SQL statement strings, and the compiled sugar
  introspects Equivalent to the hand-written SQL it mirrors."
  (:require [clojure.test :refer [deftest is testing]]
    [sqlite-migrate.core :as m]
    [sqlite-migrate.jdbc :as sql-jdbc]
    [sqlite-migrate.schema :as schema]))

(defn- snap
  "Snapshot of `declaration` realized into a fresh in-memory pristine
  database."
  [declaration]
  (with-open [conn (sql-jdbc/in-memory)]
    (m/declared-snapshot conn declaration)))

(defn- equivalent-declarations? [a b]
  (not (m/drift? (m/diff (snap a) (snap b)))))

;; ---------------------------------------------------------------------------
;; Pure compilation

(deftest compiles-a-minimal-table-with-verbatim-quoted-identifiers
  (testing "identifiers compile to quoted verbatim spelling — no munging, no case folding"
    (is (= ["CREATE TABLE \"Users\" (\"user-id\" INTEGER, \"Name\" TEXT)"]
          (schema/->sql
            {:tables [{:name :Users
                       :columns [{:name :user-id :type :integer}
                                 {:name "Name" :type :text}]}]})))))

(deftest column-types-compile-canonically-or-pass-through
  (testing "STRICT-legal keywords compile to canonical uppercase"
    (is (= ["CREATE TABLE \"t\" (\"a\" INT, \"b\" INTEGER, \"c\" REAL, \"d\" TEXT, \"e\" BLOB, \"f\" ANY)"]
          (schema/->sql
            {:tables [{:name :t
                       :columns [{:name :a :type :int}
                                 {:name :b :type :integer}
                                 {:name :c :type :real}
                                 {:name :d :type :text}
                                 {:name :e :type :blob}
                                 {:name :f :type :any}]}]}))))
  (testing "any string passes through verbatim as the unchecked escape hatch"
    (is (= ["CREATE TABLE \"t\" (\"a\" VARCHAR(10))"]
          (schema/->sql
            {:tables [{:name :t :columns [{:name :a :type "VARCHAR(10)"}]}]}))))
  (testing "an omitted type leaves the column typeless"
    (is (= ["CREATE TABLE \"t\" (\"a\")"]
          (schema/->sql {:tables [{:name :t :columns [{:name :a}]}]}))))
  (testing "an unknown type keyword is rejected as malformed input"
    (let [ex (try (schema/->sql {:tables [{:name :t :columns [{:name :a :type :varchar}]}]})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "->sql must throw on an unknown type keyword")
      (is (= :malformed-input (:sqlite-migrate/error (ex-data ex))))
      (is (= :varchar (:type (ex-data ex)))))))

(deftest compiles-column-constraints
  (testing "primary key, autoincrement, not null, unique, collate"
    (is (= ["CREATE TABLE \"t\" (\"id\" INTEGER PRIMARY KEY AUTOINCREMENT, \"email\" TEXT NOT NULL UNIQUE COLLATE NOCASE)"]
          (schema/->sql
            {:tables [{:name :t
                       :columns [{:name :id :type :integer :primary-key? true :autoincrement? true}
                                 {:name :email :type :text :not-null? true :unique? true :collate "NOCASE"}]}]}))))
  (testing "defaults: numbers verbatim, strings as SQL string literals, [:raw ...] verbatim"
    (is (= ["CREATE TABLE \"t\" (\"a\" INTEGER DEFAULT 0, \"b\" TEXT DEFAULT 'it''s', \"c\" TEXT DEFAULT (datetime('now')))"]
          (schema/->sql
            {:tables [{:name :t
                       :columns [{:name :a :type :integer :default 0}
                                 {:name :b :type :text :default "it's"}
                                 {:name :c :type :text :default [:raw "(datetime('now'))"]}]}]}))))
  (testing "a column CHECK takes [:raw ...] in expression position"
    (is (= ["CREATE TABLE \"t\" (\"x\" INTEGER CHECK (x > 0))"]
          (schema/->sql
            {:tables [{:name :t
                       :columns [{:name :x :type :integer :check [:raw "x > 0"]}]}]})))))

(deftest compiles-table-constraints-and-flags
  (testing "table-level primary key, uniques, named checks, and flags"
    (is (= [(str "CREATE TABLE \"t\" (\"a\" INTEGER, \"b\" TEXT, "
              "PRIMARY KEY (\"a\", \"b\"), "
              "UNIQUE (\"b\"), "
              "CONSTRAINT \"positive\" CHECK (a > 0)"
              ") STRICT, WITHOUT ROWID")]
          (schema/->sql
            {:tables [{:name :t
                       :columns [{:name :a :type :integer}
                                 {:name :b :type :text}]
                       :primary-key [:a :b]
                       :uniques [[:b]]
                       :checks [{:name :positive :check [:raw "a > 0"]}]
                       :strict? true
                       :without-rowid? true}]}))))
  (testing "an unnamed table CHECK is a bare [:raw ...]"
    (is (= ["CREATE TABLE \"t\" (\"a\" INTEGER, CHECK (a > 0))"]
          (schema/->sql
            {:tables [{:name :t
                       :columns [{:name :a :type :integer}]
                       :checks [[:raw "a > 0"]]}]}))))
  (testing "a named primary key uses the map form"
    (is (= ["CREATE TABLE \"t\" (\"a\" INTEGER, CONSTRAINT \"pk\" PRIMARY KEY (\"a\"))"]
          (schema/->sql
            {:tables [{:name :t
                       :columns [{:name :a :type :integer}]
                       :primary-key {:name :pk :columns [:a]}}]})))))

(deftest compiles-foreign-keys
  (testing "columns, referenced table and columns, actions as keywords or strings"
    (is (= ["CREATE TABLE \"users\" (\"id\" INTEGER PRIMARY KEY)"
            (str "CREATE TABLE \"posts\" (\"id\" INTEGER PRIMARY KEY, \"author-id\" INTEGER, "
              "FOREIGN KEY (\"author-id\") REFERENCES \"users\" (\"id\") "
              "ON DELETE CASCADE ON UPDATE SET NULL)")]
          (schema/->sql
            {:tables [{:name :users
                       :columns [{:name :id :type :integer :primary-key? true}]}
                      {:name :posts
                       :columns [{:name :id :type :integer :primary-key? true}
                                 {:name :author-id :type :integer}]
                       :foreign-keys [{:columns [:author-id]
                                       :ref-table :users
                                       :ref-columns [:id]
                                       :on-delete :cascade
                                       :on-update "SET NULL"}]}]}))))
  (testing "an unknown action keyword is rejected as malformed input"
    (let [ex (try (schema/->sql
                    {:tables [{:name :t
                               :columns [{:name :a :type :integer}]
                               :foreign-keys [{:columns [:a]
                                               :ref-table :u
                                               :ref-columns [:b]
                                               :on-delete :bogus}]}]})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "->sql must throw on an unknown foreign-key action keyword")
      (is (= :malformed-input (:sqlite-migrate/error (ex-data ex)))))))

(deftest compiles-indexes-nested-under-their-table
  (testing "index columns are identifiers or [:raw ...]; :unique? and :where supported"
    (is (= ["CREATE TABLE \"t\" (\"a\" INTEGER, \"b\" INTEGER)"
            "CREATE UNIQUE INDEX \"idx_a\" ON \"t\" (\"a\") WHERE a > 0"
            "CREATE INDEX \"idx_expr\" ON \"t\" ((a + b))"]
          (schema/->sql
            {:tables [{:name :t
                       :columns [{:name :a :type :integer}
                                 {:name :b :type :integer}]
                       :indexes [{:name :idx_a :columns [:a] :unique? true :where [:raw "a > 0"]}
                                 {:name :idx_expr :columns [[:raw "(a + b)"]]}]}]})))))

(deftest raw-escape-hatches-at-statement-positions
  (testing "triggers and views are raw-only at launch; top-level :raw statements come last"
    (is (= ["CREATE TABLE \"t\" (\"a\" INTEGER)"
            "CREATE TRIGGER trg AFTER INSERT ON t BEGIN SELECT 1; END"
            "CREATE VIEW v AS SELECT a FROM t"
            "CREATE TABLE misc (x)"]
          (schema/->sql
            {:tables [{:name :t
                       :columns [{:name :a :type :integer}]
                       :triggers ["CREATE TRIGGER trg AFTER INSERT ON t BEGIN SELECT 1; END"]}]
             :views ["CREATE VIEW v AS SELECT a FROM t"]
             :raw ["CREATE TABLE misc (x)"]}))))
  (testing "[:raw ...] is also accepted at statement positions"
    (is (= ["CREATE VIEW v AS SELECT 1"]
          (schema/->sql {:views [[:raw "CREATE VIEW v AS SELECT 1"]]}))))
  (testing "a non-raw view value is rejected as malformed input"
    (let [ex (try (schema/->sql {:views [{:name :v :columns [:a]}]})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "->sql must throw on a structured view — views are raw-only at launch")
      (is (= :malformed-input (:sqlite-migrate/error (ex-data ex)))))))

(deftest tables-compile-in-declaration-order-with-their-indexes
  (is (= ["CREATE TABLE \"b\" (\"x\" INTEGER)"
          "CREATE INDEX \"idx_b\" ON \"b\" (\"x\")"
          "CREATE TABLE \"a\" (\"y\" INTEGER)"]
        (schema/->sql
          {:tables [{:name :b
                     :columns [{:name :x :type :integer}]
                     :indexes [{:name :idx_b :columns [:x]}]}
                    {:name :a
                     :columns [{:name :y :type :integer}]}]}))))

;; ---------------------------------------------------------------------------
;; Round trip: compiled sugar introspects Equivalent to hand-written SQL

(deftest compiled-sugar-introspects-equivalent-to-hand-written-sql
  (testing "a full-subset Schema value round-trips through declared-snapshot without drift"
    (is (equivalent-declarations?
          (schema/->sql
            {:tables [{:name :users
                       :columns [{:name :id :type :integer :primary-key? true :autoincrement? true}
                                 {:name :email :type :text :not-null? true :unique? true :collate "NOCASE"}
                                 {:name :age :type :integer :check [:raw "age >= 0"]}
                                 {:name :bio :type "VARCHAR(200)"}
                                 {:name :joined :type :text :default [:raw "(datetime('now'))"]}
                                 {:name :nickname :type :text :default "it's me"}]
                       :indexes [{:name :idx_users_email :columns [:email] :unique? true
                                  :where [:raw "age >= 18"]}]
                       :triggers ["CREATE TRIGGER trg_users AFTER INSERT ON users BEGIN SELECT 1; END"]}
                      {:name :posts
                       :columns [{:name :id :type :integer}
                                 {:name :author :type :integer :not-null? true}
                                 {:name :slug :type :text}]
                       :primary-key [:id]
                       :uniques [[:slug :author]]
                       :checks [{:name :has_slug :check [:raw "slug <> ''"]}]
                       :foreign-keys [{:columns [:author]
                                       :ref-table :users
                                       :ref-columns [:id]
                                       :on-delete :cascade
                                       :on-update :set-null}]
                       :indexes [{:name :idx_posts_expr :columns [[:raw "(id + author)"]]}]}
                      {:name :audit
                       :columns [{:name :who :type :any}
                                 {:name :what :type :blob}]
                       :strict? true}]
             :views ["CREATE VIEW post_authors AS SELECT author FROM posts"]
             :raw ["CREATE TABLE misc (x REAL DEFAULT 1.5)"]})
          ["CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, email TEXT NOT NULL UNIQUE COLLATE NOCASE, age INTEGER CHECK (age >= 0), bio VARCHAR(200), joined TEXT DEFAULT (datetime('now')), nickname TEXT DEFAULT 'it''s me')"
           "CREATE UNIQUE INDEX idx_users_email ON users (email) WHERE age >= 18"
           "CREATE TRIGGER trg_users AFTER INSERT ON users BEGIN SELECT 1; END"
           "CREATE TABLE posts (id INTEGER, author INTEGER NOT NULL, slug TEXT, PRIMARY KEY (id), UNIQUE (slug, author), CONSTRAINT has_slug CHECK (slug <> ''), FOREIGN KEY (author) REFERENCES users (id) ON DELETE CASCADE ON UPDATE SET NULL)"
           "CREATE INDEX idx_posts_expr ON posts ((id + author))"
           "CREATE TABLE audit (who ANY, what BLOB) STRICT"
           "CREATE VIEW post_authors AS SELECT author FROM posts"
           "CREATE TABLE misc (x REAL DEFAULT 1.5)"])
      "compiled sugar must introspect Equivalent to its hand-written SQL counterpart"))
  (testing "the round trip is honest — a genuinely different hand-written schema drifts"
    (is (not (equivalent-declarations?
               (schema/->sql
                 {:tables [{:name :t :columns [{:name :a :type :integer}]}]})
               ["CREATE TABLE t (a INT)"])))))
