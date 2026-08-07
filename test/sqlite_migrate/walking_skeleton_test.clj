(ns sqlite-migrate.walking-skeleton-test
  "Walking skeleton: the whole pipeline end-to-end through the public
  surfaces (ADR 0013) against real in-memory SQLite."
  (:require [clojure.test :refer [deftest is testing]]
            [sqlite-migrate.core :as m]
            [sqlite-migrate.jdbc :as sql-jdbc]
            [sqlite-migrate.protocols :as p]
            [sqlite-migrate.test-util :refer [thrown-info]]))

(def declaration
  ["CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)"
   "CREATE TABLE posts (id INTEGER PRIMARY KEY, user_id INTEGER REFERENCES users(id), title TEXT)"])

(deftest empty-database-converges-on-declaration
  (with-open [live (sql-jdbc/in-memory)
              pristine (sql-jdbc/in-memory)]
    (let [declared (m/declared-snapshot pristine declaration)
          d (m/diff (m/snapshot live) declared)]
      (testing "an empty live database drifts from a non-empty Declaration"
        (is (m/drift? d)))
      (let [pl (m/plan d)]
        (testing "the Plan carries :create-table ops with plan-time :sql and :serves"
          (is (= [:create-table :create-table] (mapv :kind (:ops pl))))
          (is (= [[:table "posts"] [:table "users"]] (mapv :path (:ops pl))))
          (is (every? #(seq (:sql %)) (:ops pl)))
          (is (every? #(contains? % :serves) (:ops pl)))
          (is (empty? (:unhandled pl))))
        (let [report (m/apply! live pl)]
          (testing "apply! returns a minimal Apply report"
            (is (map? report))
            (is (= (:ops pl) (:ops report)))
            (is (contains? report :schema-version)))
          (testing "after apply, drift? goes false"
            (is (not (m/drift? (m/diff (m/snapshot live) declared))))))))))

(deftest empty-file-converges-on-declaration
  (let [f (java.io.File/createTempFile "sqlite-migrate" ".db")]
    (try
      (with-open [live (sql-jdbc/connect (.getPath f))
                  pristine (sql-jdbc/in-memory)]
        (let [declared (m/declared-snapshot pristine declaration)]
          (m/apply! live (m/plan (m/diff (m/snapshot live) declared)))
          (is (not (m/drift? (m/diff (m/snapshot live) declared))))))
      (finally (.delete f)))))

(deftest apply-refuses-on-fingerprint-mismatch
  (with-open [live (sql-jdbc/in-memory)
              pristine (sql-jdbc/in-memory)]
    (let [declared (m/declared-snapshot pristine declaration)
          pl (m/plan (m/diff (m/snapshot live) declared))]
      ;; live schema changes after the Plan was computed
      (p/execute-batch! live ["CREATE TABLE intruder (id INTEGER PRIMARY KEY)"])
      (let [ex (thrown-info (m/apply! live pl))]
        (is (some? ex) "apply! must throw on fingerprint mismatch")
        (is (= :drift-refused (:sqlite-migrate/error (ex-data ex))))))))

(deftest mid-apply-failure-attributes-op-index-and-statement
  ;; ADR 0012: a mid-Apply failure carries the failing Op verbatim, its
  ;; plan index, and the specific SQL statement that failed.
  (with-open [live (sql-jdbc/in-memory)]
    (let [pl {:ops [{:kind :create-table
                     :path [:table "a"]
                     :serves #{[:table "a"]}
                     :sql ["CREATE TABLE a (id INTEGER PRIMARY KEY)"]}
                    {:kind :create-table
                     :path [:table "b"]
                     :serves #{[:table "b"]}
                     :sql ["CREATE TABLE b (id INTEGER PRIMARY KEY)"
                           "THIS IS NOT SQL"]}]
              :unhandled []
              :live-metadata (meta (m/snapshot live))
              :declared-metadata {}}
          ex (thrown-info (m/apply! live pl))
          data (ex-data ex)]
      (is (some? ex) "apply! must throw when a statement fails mid-apply")
      (is (= :sqlite-error (:sqlite-migrate/error data)))
      (is (= (get-in pl [:ops 1]) (:op data)) "the failing Op rides verbatim")
      (is (= 1 (:op-index data)) "the Op's plan index rides along")
      (is (= "THIS IS NOT SQL" (:statement data)) "the specific failing statement rides along")
      (is (some? (ex-cause ex)) "the driver exception rides as the cause")
      (is (empty? (:tables (m/snapshot live))) "the whole batch rolls back"))))

(deftest declared-snapshot-guards-non-empty-database
  (with-open [conn (sql-jdbc/in-memory)]
    (p/execute-batch! conn ["CREATE TABLE leftover (id INTEGER PRIMARY KEY)"])
    (let [ex (thrown-info (m/declared-snapshot conn declaration))]
      (is (some? ex) "declared-snapshot must refuse a non-empty database")
      (is (= :malformed-input (:sqlite-migrate/error (ex-data ex)))))))
