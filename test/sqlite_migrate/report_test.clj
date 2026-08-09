(ns sqlite-migrate.report-test
  "Plan report and Check report (ADR 0012): the two human renderers —
  `plan-report`, the pre-apply review artifact with full SQL always,
  and `check-report`, failing Gates with counts and sample rows. Both
  deterministic, single-arity, presentation-only."
  (:require [clojure.edn :as edn]
    [clojure.string :as str]
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

(defn- review-plan
  "A Plan exercising every plan-report section: a gated Rebuild op, an
  unhandled removed table, and an unused directive."
  []
  (let [live (snap ["CREATE TABLE t (a INTEGER, b TEXT)"
                    "CREATE TABLE gone (x INTEGER)"])
        declared (snap ["CREATE TABLE t (a INTEGER, b BLOB NOT NULL)"])]
    (m/plan (m/diff live declared)
      {:live-snapshot live :declared-snapshot declared
       :directives [{:directive :rename-table :from "nope" :to "nada"}]})))

(deftest plan-report-is-the-pre-apply-review-artifact
  (let [plan (review-plan)
        report (m/plan-report plan)]
    (testing "the header names both sides' identity"
      (is (str/includes? report
            (str (get-in plan [:live-metadata :schema-version]))))
      (is (str/includes? report
            (str (get-in plan [:declared-metadata :schema-version]))))
      (is (str/includes? report
            (get-in plan [:live-metadata :sqlite-version]))))
    (testing "every op renders kind, path, and its gates' code and explanation"
      (is (str/includes? report "rebuild-table"))
      (is (str/includes? report (pr-str [:table "t"])))
      (is (str/includes? report "not-null"))
      (is (str/includes? report
            "column b of table t becomes NOT NULL; a stored NULL there would be rejected")))
    (testing "full SQL always — a Rebuild's multi-statement SQL appears whole (ADR 0012)"
      (let [statements (mapcat :sql (:ops plan))]
        (is (= 4 (count statements)) "the Rebuild op must carry its whole statement sequence")
        (doseq [s statements]
          (is (str/includes? report s)
            (str "statement must appear whole: " s)))))
    (testing "unhandled entries render with each refusal's class, code, and explanation"
      (is (str/includes? report (pr-str [:table "gone"])))
      (is (str/includes? report "needs-intent"))
      (is (str/includes? report "destructive-drop"))
      (is (str/includes? report
            (-> plan :unhandled first :refusals first :explanation))))
    (testing "unused directives render verbatim"
      (is (str/includes? report
            (pr-str {:directive :rename-table :from "nope" :to "nada"}))))
    (testing "deterministic — rendering twice, and from a deserialized Plan, yields the same string"
      (is (= report (m/plan-report plan)))
      (is (= report (m/plan-report (edn/read-string (pr-str plan))))))))

(deftest check-report-shows-failing-gates-with-counts-and-sample-rows
  (with-open [live (sql-jdbc/in-memory)]
    (p/execute-batch! live
      ["CREATE TABLE t (a INTEGER, b TEXT)"
       "INSERT INTO t (a, b) VALUES (1, 'x'), (2, NULL), (3, NULL)"])
    (let [live-snap (m/snapshot live)
          declared (snap ["CREATE TABLE t (a INTEGER, b TEXT NOT NULL)"])
          plan (m/plan (m/diff live-snap declared)
                 {:live-snapshot live-snap :declared-snapshot declared})
          result (m/check live plan)
          report (m/check-report result)]
      (testing "the failing gate renders its code, path, explanation, count, and sample rows"
        (is (str/includes? report "not-null"))
        (is (str/includes? report (pr-str [:table "t" :column "b"])))
        (is (str/includes? report
              "column b of table t becomes NOT NULL; a stored NULL there would be rejected"))
        (is (str/includes? report "2 violation"))
        (is (str/includes? report (pr-str {:a 2 :b nil})))
        (is (str/includes? report (pr-str {:a 3 :b nil}))))
      (testing "deterministic — rendering twice, and from a deserialized Check result, yields the same string"
        (is (= report (m/check-report result)))
        (is (= report (m/check-report (edn/read-string (pr-str result))))))
      (testing "a passing Check result renders a one-line all-clear, no gate blocks"
        (p/execute-batch! live ["UPDATE t SET b = 'filled' WHERE b IS NULL"])
        (let [live-snap (m/snapshot live)
              plan (m/plan (m/diff live-snap declared)
                     {:live-snapshot live-snap :declared-snapshot declared})
              passing (m/check live plan)
              pass-report (m/check-report passing)]
          (is (true? (:pass? passing)))
          (is (not (str/includes? pass-report "violation")))
          (is (= pass-report (m/check-report passing))))))))

(deftest check-report-marks-a-count-at-the-baked-limit-as-limit-or-more
  (with-open [live (sql-jdbc/in-memory)]
    (p/execute-batch! live
      (into ["CREATE TABLE t (a INTEGER, b TEXT)"]
        (map #(str "INSERT INTO t (a, b) VALUES (" % ", NULL)") (range 12))))
    (let [live-snap (m/snapshot live)
          declared (snap ["CREATE TABLE t (a INTEGER, b TEXT NOT NULL)"])
          plan (m/plan (m/diff live-snap declared)
                 {:live-snapshot live-snap :declared-snapshot declared})
          report (m/check-report (m/check live plan))]
      (is (str/includes? report "10 or more")
        "a count at the Gate's baked limit reads as \"limit or more\""))))
