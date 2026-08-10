(ns sqlite-migrate.jdbc-frame-test
  "The executor-owned Frame contract of `execute-batch!` on the JDBC
  adapter, observed through the public surfaces only."
  (:require [clojure.test :refer [deftest is]]
    [sqlite-migrate.core :as m]
    [sqlite-migrate.jdbc :as sql-jdbc]
    [sqlite-migrate.protocols :as p]
    [sqlite-migrate.test-util :refer [thrown-info]]))

(deftest batch-is-all-or-nothing
  (with-open [conn (sql-jdbc/in-memory)]
    (let [ex (thrown-info
               (p/execute-batch! conn ["CREATE TABLE a (id INTEGER PRIMARY KEY)"
                                       "THIS IS NOT SQL"]))]
      (is (some? ex) "a failing statement must throw")
      (is (= 1 (:statement-index (ex-data ex)))
        "the frame error must carry the failing statement's batch index")
      (is (some? (ex-cause ex)) "the driver exception rides as the cause"))
    (is (empty? (:tables (m/snapshot conn)))
      "a failing statement must leave no trace of earlier statements")))

(deftest fk-violations-detected-before-commit-roll-everything-back
  (with-open [conn (sql-jdbc/in-memory)]
    (let [ex (thrown-info
               (p/execute-batch!
                 conn
                 ["CREATE TABLE parents (id INTEGER PRIMARY KEY)"
                  "CREATE TABLE children (id INTEGER PRIMARY KEY, parent_id INTEGER REFERENCES parents(id))"
                  "INSERT INTO children (id, parent_id) VALUES (1, 999)"]))]
      (is (some? ex) "foreign_key_check rows must roll back and throw")
      (is (= :sqlite-error (:sqlite-migrate/error (ex-data ex))))
      (is (empty? (:tables (m/snapshot conn)))
        "the whole batch must roll back, not just the violating insert"))))

(deftest successful-batch-returns-nil
  (with-open [conn (sql-jdbc/in-memory)]
    (is (nil? (p/execute-batch! conn ["CREATE TABLE t (id INTEGER PRIMARY KEY)"])))
    (is (= ["t"] (keys (:tables (m/snapshot conn)))))))

(deftest violating-gate-rows-roll-back-and-carry-index-aligned-results
  (with-open [conn (sql-jdbc/in-memory)]
    (p/execute-batch! conn ["CREATE TABLE t (a INTEGER, b TEXT)"
                            "INSERT INTO t (a, b) VALUES (1, NULL), (2, 'set')"])
    (let [ex (thrown-info
               (p/execute-batch!
                 conn
                 ["CREATE TABLE trace (x INTEGER)"]
                 ["SELECT * FROM t WHERE 0 = 1"
                  "SELECT * FROM t WHERE b IS NULL LIMIT 10"
                  "SELECT * FROM t WHERE 0 = 1"]))
          data (ex-data ex)]
      (is (= :gates-violated (:sqlite-migrate/error data))
        "violating gate rows throw the executor-level :gates-violated")
      (is (= [[] [{:a 1 :b nil}] []] (:gate-results data))
        "the results are index-aligned with gate-sqls, empty = passed")
      (is (not (contains? data :statement-index))
        "a gate failure is not a statement failure")
      (is (not (contains? (:tables (m/snapshot conn)) "trace"))
        "gates run before the statements — nothing was created"))))

(deftest every-gate-runs-before-the-frame-decides
  (with-open [conn (sql-jdbc/in-memory)]
    (p/execute-batch! conn ["CREATE TABLE t (a INTEGER)"
                            "INSERT INTO t (a) VALUES (1)"])
    (let [results (:gate-results
                    (ex-data (thrown-info
                               (p/execute-batch! conn []
                                 ["SELECT * FROM t"
                                  "SELECT * FROM t WHERE a = 1"]))))]
      (is (= [[{:a 1}] [{:a 1}]] results)
        "the step runs every gate, never fail-fast on the first"))))

(deftest passing-gates-let-the-statements-run
  (with-open [conn (sql-jdbc/in-memory)]
    (p/execute-batch! conn ["CREATE TABLE t (a INTEGER)"])
    (is (nil? (p/execute-batch! conn ["CREATE TABLE made (x INTEGER)"]
                ["SELECT * FROM t WHERE a IS NULL"])))
    (is (contains? (:tables (m/snapshot conn)) "made")
      "all gates empty ⇒ the batch proceeds")))

(deftest empty-gate-sqls-is-the-two-argument-arity
  (with-open [conn (sql-jdbc/in-memory)]
    (is (nil? (p/execute-batch! conn ["CREATE TABLE t (a INTEGER)"] [])))
    (is (= ["t"] (keys (:tables (m/snapshot conn))))
      "an empty gate list behaves exactly like the two-argument arity")))
