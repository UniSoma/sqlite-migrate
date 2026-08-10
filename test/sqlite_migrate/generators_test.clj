(ns sqlite-migrate.generators-test
  "Unit tests for the generative suite's own generators
  (`sqlite-migrate.generators`) — the SQL they hand-build must be SQL
  SQLite accepts, or a generator bug masquerades as a property failure."
  (:require [clojure.test :refer [deftest is testing]]
    [sqlite-migrate.generators :as g]
    [sqlite-migrate.jdbc :as sql-jdbc]
    [sqlite-migrate.protocols :as p]))

(deftest probe-insert-sql-emits-default-values-for-an-autoincrement-only-table
  (let [table {:name "a"
               :columns [{:name "idpk" :type :integer
                          :primary-key? true :autoincrement? true}]}]
    (testing "no column survives the AUTOINCREMENT pk's removal"
      (is (= "INSERT INTO \"a\" DEFAULT VALUES" (g/probe-insert-sql table 1000))
        "an empty column list must become DEFAULT VALUES, not \"() VALUES ()\""))
    (testing "SQLite accepts the emitted probe"
      (with-open [conn (sql-jdbc/in-memory)]
        (p/execute-batch! conn ["CREATE TABLE \"a\" (\"idpk\" INTEGER PRIMARY KEY AUTOINCREMENT)"])
        (p/execute-batch! conn [(g/probe-insert-sql table 1000)])
        (is (= [{:idpk 1}] (p/execute-query conn "SELECT idpk FROM a" []))
          "the probe must insert exactly one row, its id issued by SQLite")))))

(deftest probe-insert-sql-names-every-surviving-column
  (let [table {:name "t"
               :columns [{:name "idpk" :type :integer
                          :primary-key? true :autoincrement? true}
                         {:name "label" :type :text}
                         {:name "n" :type :integer}]}]
    (testing "the AUTOINCREMENT pk drops out and the rest keep their literals"
      (is (= "INSERT INTO \"t\" (\"label\", \"n\") VALUES ('v3', 4)"
            (g/probe-insert-sql table 3))))
    (testing "SQLite accepts the emitted probe"
      (with-open [conn (sql-jdbc/in-memory)]
        (p/execute-batch! conn [(str "CREATE TABLE \"t\" (\"idpk\" INTEGER PRIMARY KEY AUTOINCREMENT,"
                                  " \"label\" TEXT, \"n\" INTEGER)")])
        (p/execute-batch! conn [(g/probe-insert-sql table 3)])
        (is (= [{:idpk 1 :label "v3" :n 4}]
              (p/execute-query conn "SELECT idpk, label, n FROM t" [])))))))
