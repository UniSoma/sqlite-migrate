(ns sqlite-migrate.error-envelope-test
  "The uniform error envelope (ADR 0012): every Non-success class the
  library throws rides under the one namespaced discriminator key
  `:sqlite-migrate/error`, with its locked verbatim-value payload and a
  one-line ex-message — so a consumer writes one dispatch regardless of
  which function threw."
  (:require [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [sqlite-migrate.core :as m]
    [sqlite-migrate.impl.plan :as pl]
    [sqlite-migrate.jdbc :as sql-jdbc]
    [sqlite-migrate.protocols :as p]
    [sqlite-migrate.test-util :refer [thrown-info]]))

(defn- snap
  "Snapshot of `declaration` realized into a fresh in-memory pristine
  database."
  [declaration]
  (with-open [conn (sql-jdbc/in-memory)]
    (m/declared-snapshot conn declaration)))

(defn- live-plan
  "Snapshot `live`'s current schema and plan it against `declared-decl`."
  [live declared-decl]
  (with-open [pristine (sql-jdbc/in-memory)]
    (let [live-snap (m/snapshot live)
          declared (m/declared-snapshot pristine declared-decl)]
      (m/plan live-snap declared (m/diff live-snap declared)))))

(defn- dispatch
  "The one consumer-side dispatch ADR 0012 promises: the class keyword
  read off `:sqlite-migrate/error` routes every library exception;
  anything without the envelope falls through to :handled/unknown."
  [e]
  (case (:sqlite-migrate/error (ex-data e))
    :malformed-input :handled/malformed-input
    :drift-refused :handled/drift-refused
    :unhandled-refused :handled/unhandled-refused
    :gate-failed :handled/gate-failed
    :sqlite-error :handled/sqlite-error
    :internal :handled/internal
    :handled/unknown))

(defn- one-line? [e]
  (and (string? (ex-message e))
    (seq (ex-message e))
    (not (str/includes? (ex-message e) "\n"))))

(deftest one-dispatch-handles-every-non-success-class
  (testing ":malformed-input carries the offending input under a descriptive key"
    (let [live (snap ["CREATE TABLE t (a INTEGER)"])
          bad {:directive :rename-table :from "x"}
          ex (thrown-info (m/plan live live (m/diff live live) {:directives [bad]}))]
      (is (some? ex) "a directive missing its required keys must throw")
      (is (= :handled/malformed-input (dispatch ex)))
      (is (one-line? ex))
      (is (= bad (:directive (ex-data ex))) "the offending directive rides verbatim")))
  (testing ":drift-refused carries both fingerprints and both Snapshot-provenance blocks"
    (with-open [live (sql-jdbc/in-memory)]
      (p/execute-batch! live ["CREATE TABLE t (a INTEGER)"])
      (let [plan (live-plan live ["CREATE TABLE t (a INTEGER, b TEXT)"])]
        (p/execute-batch! live ["CREATE TABLE drifted (x INTEGER)"])
        (let [ex (thrown-info (m/check live plan))
              data (ex-data ex)]
          (is (some? ex) "check must throw on fingerprint mismatch")
          (is (= :handled/drift-refused (dispatch ex)))
          (is (one-line? ex))
          (is (= (get-in plan [:live-provenance :schema-version])
                (:plan-fingerprint data)))
          (is (integer? (:live-fingerprint data)))
          (is (not= (:plan-fingerprint data) (:live-fingerprint data)))
          (is (= (:live-provenance plan) (:live-provenance data))
            "the Plan's live Snapshot provenance rides verbatim")
          (is (= (:declared-provenance plan) (:declared-provenance data))
            "the Plan's declared Snapshot provenance rides verbatim")))))
  (testing ":unhandled-refused carries the unhandled entries verbatim with their refusal vectors"
    (with-open [live (sql-jdbc/in-memory)]
      (p/execute-batch! live ["CREATE TABLE gone (x INTEGER)"])
      (let [plan (live-plan live [])
            ex (thrown-info (m/apply! live plan))
            data (ex-data ex)]
        (is (some? ex) "apply! must refuse a Plan with unhandled entries")
        (is (= :handled/unhandled-refused (dispatch ex)))
        (is (one-line? ex))
        (is (= (:unhandled plan) (:unhandled data))
          "the Plan's unhandled entries ride verbatim")
        (is (every? (comp seq :refusals) (:unhandled data))
          "each unhandled entry carries its refusal vector"))))
  (testing ":gate-failed carries the full Check result, exactly what a manual check returns"
    (with-open [live (sql-jdbc/in-memory)]
      (p/execute-batch! live ["CREATE TABLE t (a INTEGER, b TEXT)"
                              "INSERT INTO t (a, b) VALUES (1, NULL)"])
      (let [plan (live-plan live ["CREATE TABLE t (a INTEGER, b TEXT NOT NULL)"])
            manual (m/check live plan)
            ex (thrown-info (m/apply! live plan))]
        (is (some? ex) "apply! must throw when a Gate fails")
        (is (= :handled/gate-failed (dispatch ex)))
        (is (one-line? ex))
        (is (= manual (:check (ex-data ex)))
          "the Check result rides verbatim — identical to a manual check"))))
  (testing ":sqlite-error carries the driver cause, the failing Op verbatim, its plan index, and the failing statement"
    (with-open [live (sql-jdbc/in-memory)]
      (p/execute-batch! live ["CREATE TABLE t (a INTEGER, b TEXT)"
                              "INSERT INTO t (a, b) VALUES (1, NULL)"])
      (let [plan (live-plan live ["CREATE TABLE t (a INTEGER, b BLOB NOT NULL)"])
            ex (thrown-info (m/apply! live plan {:check-gates? false}))
            data (ex-data ex)]
        (is (some? ex) "the NULL row must abort the rebuild copy mid-apply")
        (is (= :handled/sqlite-error (dispatch ex)))
        (is (one-line? ex))
        (is (some? (ex-cause ex)) "the driver exception rides as ex-cause")
        (is (= (nth (:ops plan) (:op-index data)) (:op data))
          "the failing Op rides verbatim at its plan index")
        (is (some #(= (:statement data) %) (:sql (:op data)))
          "the failing statement is one of the Op's own statements"))))
  (testing ":internal (a planner bug, never user error) still carries the envelope"
    (let [ex (thrown-info (#'pl/check-completeness! [{:path [:table "t"]}] [] []))]
      (is (some? ex))
      (is (= :handled/internal (dispatch ex)))
      (is (one-line? ex))))
  (testing "an exception without the envelope falls through the same dispatch"
    (is (= :handled/unknown (dispatch (ex-info "elsewhere" {}))))))
