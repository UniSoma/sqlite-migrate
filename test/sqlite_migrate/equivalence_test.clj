(ns sqlite-migrate.equivalence-test
  "The Equivalence relation (ADR 0003): the lexical tokenizer covers
  SQLite's token classes and nothing more, and `equivalent?` erases the
  locked Noise classes while keeping the locked Semantic differences —
  on Snapshots taken from real in-memory SQLite."
  (:require [clojure.test :refer [are deftest is testing]]
    [sqlite-migrate.core :as m]
    [sqlite-migrate.extract :as x]
    [sqlite-migrate.jdbc :as sql-jdbc]))

(defn- snap
  "Snapshot of `declaration` realized into a fresh in-memory pristine
  database."
  [declaration]
  (with-open [conn (sql-jdbc/in-memory)]
    (m/declared-snapshot conn declaration)))

(defn- equivalent-declarations? [a b]
  (m/equivalent? (snap a) (snap b)))

;; ---------------------------------------------------------------------------
;; Tokenizer: SQLite token classes, nothing more

(defn- kinds+texts [src]
  (mapv (juxt :t :text) (x/tokenize src)))

(deftest tokenizer-covers-sqlite-token-classes
  (testing "whitespace and both comment styles vanish"
    (is (= [] (kinds+texts "  \t\n")))
    (is (= [[:word "a"] [:word "b"]] (kinds+texts "a -- line comment\nb")))
    (is (= [[:word "a"] [:word "b"]] (kinds+texts "a /* block\ncomment */ b")))
    (is (= [[:word "a"]] (kinds+texts "a -- unterminated line comment")))
    (is (= [[:word "a"]] (kinds+texts "a /* unterminated block"))))
  (testing "bare words carry dequoted folded identifiers"
    (is (= [{:t :word :s 0 :e 6 :text "SELECT" :ident "SELECT" :fold "select"}]
          (x/tokenize "SELECT"))))
  (testing "quoted identifiers in all three quoting styles dequote and fold"
    (are [src ident] (= [ident] (mapv :fold (x/tokenize src)))
      "\"Group\"" "group"
      "`Group`" "group"
      "[Group]" "group"
      "\"a\"\"b\"" "a\"b"))
  (testing "string literals keep their verbatim text, doubled quotes included"
    (is (= [[:str "'it''s'"]] (kinds+texts "'it''s'"))))
  (testing "blob literals are one token"
    (is (= [[:blob "x'CAFE'"]] (kinds+texts "x'CAFE'")))
    (is (= [[:blob "X'CAFE'"]] (kinds+texts "X'CAFE'"))))
  (testing "numeric literals: integers, decimals, hex, leading dot, signed exponents"
    (are [src] (= [[:num src]] (kinds+texts src))
      "42"
      "1.5"
      "0x1A"
      ".5"
      "1e5"
      "1e+5"
      "1.5E-3"))
  (testing "a dot not followed by a digit stays punctuation"
    (is (= [[:word "a"] [:punct "."] [:word "b"]] (kinds+texts "a.b"))))
  (testing "a sign after a hex literal is an operator, not an exponent"
    (is (= [[:num "0x1E"] [:punct "+"] [:num "5"]] (kinds+texts "0x1E+5"))))
  (testing "operators and punctuation come out as punct tokens"
    (is (= [[:word "a"] [:punct "<"] [:punct ">"] [:word "b"]]
          (kinds+texts "a <> b")))))

;; ---------------------------------------------------------------------------
;; Noise: differences the Equivalence relation erases

(deftest equivalence-erases-identifier-case-and-quoting
  (testing "table and column identifier case and quoting style are Noise"
    (is (equivalent-declarations?
          ["CREATE TABLE \"Users\" (\"Id\" INTEGER PRIMARY KEY, [Name] TEXT NOT NULL)"]
          ["CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)"])))
  (testing "index and constraint name case is Noise"
    (is (equivalent-declarations?
          ["CREATE TABLE t (x INTEGER, CONSTRAINT Positive CHECK (x > 0))"
           "CREATE INDEX IDX_T_X ON t (x)"]
          ["CREATE TABLE t (x INTEGER, CONSTRAINT positive CHECK (x > 0))"
           "CREATE INDEX idx_t_x ON t (x)"]))))

(deftest equivalence-erases-opaque-expression-noise
  (testing "whitespace, comments, keyword case, and identifier quoting inside opaque expressions are Noise"
    (is (equivalent-declarations?
          ["CREATE TABLE t (x INTEGER CHECK (x > 0), y REAL DEFAULT (1 + 2), z REAL GENERATED ALWAYS AS (x * 2) VIRTUAL)"]
          ["CREATE TABLE t (x INTEGER CHECK (\"x\">0 /* positive */), y REAL DEFAULT (1+2), z REAL GENERATED ALWAYS AS ( x  *  2 ) VIRTUAL)"]))
    (is (equivalent-declarations?
          ["CREATE TABLE t (x INTEGER, y INTEGER)"
           "CREATE INDEX idx ON t ((x + y)) WHERE x IN (1, 2)"]
          ["CREATE TABLE t (x INTEGER, y INTEGER)"
           "CREATE INDEX idx ON t ((x+y)) WHERE x in (1,2)"]))))

(deftest equivalence-erases-type-text-case-and-whitespace
  (is (equivalent-declarations?
        ["CREATE TABLE t (a NUMERIC, b VARCHAR (10))"]
        ["CREATE TABLE t (a numeric, b VARCHAR(10))"])))

(deftest equivalence-erases-sibling-order
  (testing "order among named siblings (indexes, triggers, views) is Noise"
    (is (equivalent-declarations?
          ["CREATE TABLE t (x INTEGER, y INTEGER)"
           "CREATE INDEX idx_a ON t (x)"
           "CREATE INDEX idx_b ON t (y)"]
          ["CREATE TABLE t (x INTEGER, y INTEGER)"
           "CREATE INDEX idx_b ON t (y)"
           "CREATE INDEX idx_a ON t (x)"]))))

;; ---------------------------------------------------------------------------
;; Semantic: differences the Equivalence relation keeps

(deftest equivalence-keeps-physical-column-order
  (is (not (equivalent-declarations?
             ["CREATE TABLE t (a INTEGER, b TEXT)"]
             ["CREATE TABLE t (b TEXT, a INTEGER)"]))))

(deftest equivalence-keeps-declared-type-text
  (testing "type text compares as text, never by affinity"
    (is (not (equivalent-declarations?
               ["CREATE TABLE t (a INT)"]
               ["CREATE TABLE t (a INTEGER)"])))))

(deftest equivalence-keeps-constraint-names
  (testing "a named CHECK is not equivalent to the same unnamed CHECK"
    (is (not (equivalent-declarations?
               ["CREATE TABLE t (x INTEGER, CONSTRAINT positive CHECK (x > 0))"]
               ["CREATE TABLE t (x INTEGER, CHECK (x > 0))"]))))
  (testing "two different constraint names differ"
    (is (not (equivalent-declarations?
               ["CREATE TABLE t (x INTEGER, CONSTRAINT c1 CHECK (x > 0))"]
               ["CREATE TABLE t (x INTEGER, CONSTRAINT c2 CHECK (x > 0))"])))))

(deftest equivalence-keeps-pk-and-index-column-order
  (is (not (equivalent-declarations?
             ["CREATE TABLE t (a INTEGER, b INTEGER, PRIMARY KEY (a, b))"]
             ["CREATE TABLE t (a INTEGER, b INTEGER, PRIMARY KEY (b, a))"])))
  (is (not (equivalent-declarations?
             ["CREATE TABLE t (a INTEGER, b INTEGER)"
              "CREATE INDEX idx ON t (a, b)"]
             ["CREATE TABLE t (a INTEGER, b INTEGER)"
              "CREATE INDEX idx ON t (b, a)"]))))

(deftest equivalence-keeps-table-flags
  (is (not (equivalent-declarations?
             ["CREATE TABLE t (a INTEGER PRIMARY KEY)"]
             ["CREATE TABLE t (a INTEGER PRIMARY KEY) STRICT"])))
  (is (not (equivalent-declarations?
             ["CREATE TABLE t (a INTEGER PRIMARY KEY)"]
             ["CREATE TABLE t (a INTEGER PRIMARY KEY) WITHOUT ROWID"]))))

(deftest equivalence-keeps-honest-drift
  (testing "beyond token identity, expression differences are drift"
    (is (not (equivalent-declarations?
               ["CREATE TABLE t (x INTEGER CHECK (x > 0))"]
               ["CREATE TABLE t (x INTEGER CHECK (0 < x))"]))))
  (testing "explicit COLLATE BINARY vs absent is drift"
    (is (not (equivalent-declarations?
               ["CREATE TABLE t (x TEXT COLLATE BINARY)"]
               ["CREATE TABLE t (x TEXT)"]))))
  (testing "numeric literal spelling is drift"
    (is (not (equivalent-declarations?
               ["CREATE TABLE t (x REAL DEFAULT 1.0)"]
               ["CREATE TABLE t (x REAL DEFAULT 1.00)"])))))
