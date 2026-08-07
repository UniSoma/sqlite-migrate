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
