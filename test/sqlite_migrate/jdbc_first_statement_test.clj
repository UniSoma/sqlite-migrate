(ns sqlite-migrate.jdbc-first-statement-test
  "The `first-statement` contract on the JDBC adapter: SQLite's prepare
  loop, not string manipulation, finds where a statement ends."
  (:require [clojure.test :refer [are deftest is testing]]
    [sqlite-migrate.jdbc :as sql-jdbc]
    [sqlite-migrate.protocols :as p]
    [sqlite-migrate.test-util :refer [thrown-info]]))

(deftest first-statement-ends-where-sqlite-ends-it
  (with-open [conn (sql-jdbc/in-memory)]
    (p/execute-batch! conn ["CREATE TABLE t (a)" "CREATE TABLE u (b)"])
    (testing "a semicolon in a literal, a quoted identifier, a comment or a trigger body stays inside the statement"
      (are [sql statement] (= statement (p/first-statement conn sql))
      "CREATE TABLE x (a); CREATE TABLE y (b)" "CREATE TABLE x (a);"
      "CREATE TABLE x (a TEXT DEFAULT ';'); SELECT 1" "CREATE TABLE x (a TEXT DEFAULT ';');"
      "CREATE TABLE \"x;y\" (a); SELECT 1" "CREATE TABLE \"x;y\" (a);"
      "CREATE TABLE [x;y] (a); SELECT 1" "CREATE TABLE [x;y] (a);"
      "CREATE TABLE x (a) -- c;omment\n; SELECT 1" "CREATE TABLE x (a) -- c;omment\n;"
      "CREATE TABLE x (a) /* c;omment */; SELECT 1" "CREATE TABLE x (a) /* c;omment */;"
      "CREATE TRIGGER tr AFTER INSERT ON t BEGIN INSERT INTO u VALUES (new.a); END; SELECT 1"
      "CREATE TRIGGER tr AFTER INSERT ON t BEGIN INSERT INTO u VALUES (new.a); END;"
      "; ;CREATE TABLE x (a); SELECT 1" "; ;CREATE TABLE x (a);"
      "CREATE TABLE x (a) -- no semicolon" "CREATE TABLE x (a) -- no semicolon"))
    (is (= #{"t" "u"} (set (map :name (p/execute-query conn "SELECT name FROM sqlite_schema" []))))
      "first-statement prepares without executing")))

(deftest first-statement-is-nil-when-the-text-holds-no-statement
  (with-open [conn (sql-jdbc/in-memory)]
    (testing "whitespace, comments and empty statements hold no statement"
      (are [sql] (nil? (p/first-statement conn sql))
      ""
      "   \n"
      "-- only a comment"
      " ; ;; /* and a comment */ "
      "/* unterminated comment"))))

(deftest first-statement-cuts-a-rejected-statement-where-executing-it-fails
  (with-open [conn (sql-jdbc/in-memory)]
    (testing "a statement SQLite rejects comes back non-empty, and executing it raises SQLite's error"
      (are [sql] (let [statement (p/first-statement conn sql)]
                   (and (seq statement)
                     (= :sqlite-error (:sqlite-migrate/error
                                        (ex-data (thrown-info (p/execute-batch! conn [statement])))))))
        "CREATE INDEX i ON missing (a); CREATE TABLE x (a)"
        "CREATE TRIGGER tr AFTER INSERT ON missing BEGIN SELECT 1; END; CREATE TABLE x (a)"
        "CREATE TABLE x (a"
        "CREATE TABLE x (a TEXT DEFAULT 'open"
        "CREATE TABLE \"x (a)"
        "CREATE TABLE [x (a)"
        "CREATE TRIGGER tr AFTER INSERT ON missing BEGIN SELECT 1"))))

(deftest first-statement-throws-when-the-driver-fails
  (let [conn (sql-jdbc/in-memory)
        _ (.close conn)
        ex (thrown-info (p/first-statement conn "CREATE TABLE x (a);"))]
    (is (= :sqlite-error (:sqlite-migrate/error (ex-data ex)))
      "a closed connection is a failure, not a statement boundary")
    (is (some? (ex-cause ex)) "the driver exception rides as the cause")))
