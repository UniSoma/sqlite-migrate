(ns sqlite-migrate.properties-test
  "The locked correctness properties (ADR 0010), run generatively over
  real in-memory SQLite: no-op, round-trip, residual convergence, data
  preservation (rowid stability and AUTOINCREMENT continuity riding
  along), gate bidirectionality (ADR 0015 corners included), plan
  determinism, and version honesty. Deterministic regression seeds from
  `sqlite-migrate.corpus` run as plain deftests alongside."
  (:require [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [clojure.test.check.clojure-test :refer [defspec]]
    [clojure.test.check.properties :as prop]
    [sqlite-migrate.core :as m]
    [sqlite-migrate.corpus :as corpus]
    [sqlite-migrate.generators :as g]
    [sqlite-migrate.jdbc :as sql-jdbc]
    [sqlite-migrate.protocols :as p]
    [sqlite-migrate.schema :as schema]))

(def ^:private trials
  "Per-property trial count — modest by default so the suite stays
  fast; CI raises it through the SQM_TRIALS environment variable."
  (or (some-> (System/getenv "SQM_TRIALS") Long/parseLong) 40))

;; ---------------------------------------------------------------------------
;; Harness

(defn- declaration-of
  "The SQL statement vector of `x` — an EDN Schema value or already a
  vector of statements."
  [x]
  (if (map? x) (schema/->sql x) x))

(defn- snap-of
  "Snapshot of `x` (Schema value or statement vector) realized into a
  fresh pristine in-memory database."
  [x]
  (with-open [conn (sql-jdbc/in-memory)]
    (m/declared-snapshot conn (declaration-of x))))

(defn- emit-declaration
  "Re-emit `snapshot` as the Declaration its stored CREATE sql spells:
  tables (virtual ones included), then indexes, then views, then
  triggers — a dependency-safe statement order."
  [snapshot]
  (let [tables (vals (:tables snapshot))
        views (vals (:views snapshot))]
    (vec
      (concat
        (map #(:sql (meta %)) tables)
        (for [t tables, ix (vals (:indexes t))] (:sql (meta ix)))
        (map #(:sql (meta %)) views)
        (for [t tables, tr (vals (:triggers t))] (:sql (meta tr)))
        (for [v views, tr (vals (:triggers v))] (:sql (meta tr)))))))

(defn- run-trial
  "Realize `scenario`'s live file (schema plus rows), plan the drift to
  its target with the scenario's Directives, run Check, and call `f`
  with `{:conn :plan :check :declared}` while the live conn is open."
  [scenario f]
  (with-open [conn (sql-jdbc/in-memory)]
    (p/execute-batch! conn (schema/->sql (:live scenario)))
    (when-let [inserts (seq (get-in scenario [:rows :inserts]))]
      (p/execute-batch! conn (vec inserts)))
    (let [declared (snap-of (:target scenario))
          live-snap (m/snapshot conn)
          plan (m/plan (m/diff live-snap declared)
                 {:live-snapshot live-snap :declared-snapshot declared
                  :directives (:directives scenario)})
          check (m/check conn plan)]
      (f {:conn conn :plan plan :check check :declared declared}))))

(defn- survivors
  "Per-table survivor mapping of `scenario` (ADR 0010's data
  preservation pairing): every live table not removed by a drop-table
  Directive, its post-Apply name, its column pairs `[live post]` (drop
  Directive columns excluded, rename Directives followed), and whether
  rowid identity must survive (both sides rowid tables)."
  [{:keys [live target mutation table-rename]}]
  (let [{mkind :kind mtable :table mcol :column mto :to authorized? :authorized?} mutation]
    (vec
      (for [t (:tables live)
            :let [tf (g/fold (:name t))
                  mine? (and mtable (= tf (g/fold mtable)))]
            :when (not (and mine? (= :drop-table mkind) authorized?))]
        (let [post-name (if (and table-rename (= tf (g/fold (first table-rename))))
                          (second table-rename)
                          (:name t))
              target-t (first (filter #(= (g/fold (:name %)) (g/fold post-name))
                                (:tables target)))
              col-pair (fn [c]
                         (let [cf (g/fold (:name c))
                               target-col? (and mine? mcol (= cf (g/fold mcol)))]
                           (cond
                             (and target-col? (= :drop-column mkind) authorized?) nil
                             (and target-col? (= :rename-column mkind)) [(:name c) mto]
                             :else [(:name c) (:name c)])))]
          {:live-table (:name t)
           :post-table post-name
           :cols (vec (keep col-pair (:columns t)))
           :rowid? (and (not (:without-rowid? t))
                     (if target-t (not (:without-rowid? target-t)) true))})))))

(defn- rows-at
  "The multiset (frequencies) of `table`'s rows at `cols`, aliased
  positionally so live and post names compare directly; rowid rides as
  an extra key when `rowid?`. Blob values normalize to vectors so
  multiset equality is value equality."
  [conn table cols rowid?]
  (let [selection (str/join ", "
                    (concat (when rowid? ["rowid AS zrid"])
                      (map-indexed (fn [i c] (str (g/qid c) " AS c" i)) cols)))]
    (frequencies
      (mapv (fn [row] (update-vals row #(if (bytes? %) (vec %) %)))
        (p/execute-query conn (str "SELECT " selection " FROM " (g/qid table)) [])))))

(defn- autoincrement-continuity-ok?
  "True when every table AUTOINCREMENT on both sides hands out a fresh
  id greater than any id ever issued pre-Apply. Only checked on fully
  converged plans (the post shape must be the target's)."
  [{:keys [live target] :as scenario} conn plan]
  (or (seq (:unhandled plan))
    (every?
      (fn [t]
        (let [live-name (or (first (get scenario :table-rename)) (:name t))
              live-t (first (filter #(= (g/fold (:name %)) (g/fold live-name))
                              (:tables live)))
              high (get-in scenario [:rows :auto-highs (g/fold live-name)])]
          (or (nil? live-t)
            (not (some :autoincrement? (:columns live-t)))
            (nil? high)
            (do (p/execute-batch! conn [(g/probe-insert-sql t 1000)])
              (< high (-> (p/execute-query conn
                            (str "SELECT max(\"idpk\") AS mx FROM " (g/qid (:name t))) [])
                        first :mx))))))
      (filter #(some :autoincrement? (:columns %)) (:tables target)))))

;; ---------------------------------------------------------------------------
;; Properties 1 and 2 (ADR 0003 via ADR 0010): no-op and round-trip

(defspec no-op-property trials
  (prop/for-all [s g/gen-schema]
    (let [a (snap-of s)
          b (snap-of s)
          d (m/diff a b)
          plan (m/plan d {:live-snapshot a :declared-snapshot b})]
      (and (not (m/drift? d))
        (empty? (:ops plan))
        (empty? (:unhandled plan))))))

(defspec round-trip-property trials
  (prop/for-all [s g/gen-schema]
    (let [a (snap-of s)
          b (snap-of (emit-declaration a))]
      (not (m/drift? (m/diff a b))))))

;; ---------------------------------------------------------------------------
;; Property: residual convergence — the post-Apply diff equals exactly
;; the Plan's unhandled entries

(defspec residual-convergence-property trials
  (prop/for-all [scenario g/gen-scenario]
    (run-trial scenario
      (fn [{:keys [conn plan check declared]}]
        (if-not (:pass? check)
          true
          (do (m/apply! conn plan {:allow-unhandled? true})
            (= (:entries (m/diff (m/snapshot conn) declared))
              (mapv :entry (:unhandled plan)))))))))

;; ---------------------------------------------------------------------------
;; Property: data preservation — multiset row equality over surviving
;; columns, rowid stability and AUTOINCREMENT continuity riding along

(defspec data-preservation-property trials
  (prop/for-all [scenario g/gen-scenario]
    (run-trial scenario
      (fn [{:keys [conn plan check]}]
        (if-not (:pass? check)
          true
          (let [survs (survivors scenario)
                pre (into {}
                      (for [{:keys [live-table cols rowid?]} survs]
                        [(g/fold live-table)
                         (rows-at conn live-table (map first cols) rowid?)]))]
            (m/apply! conn plan {:allow-unhandled? true})
            (and (every? (fn [{:keys [live-table post-table cols rowid?]}]
                           (= (get pre (g/fold live-table))
                             (rows-at conn post-table (map second cols) rowid?)))
                   survs)
              (autoincrement-continuity-ok? scenario conn plan))))))))

;; ---------------------------------------------------------------------------
;; Property: gate bidirectionality — Check is a predicate, not advice

(defspec gate-bidirectionality-property trials
  (prop/for-all [scenario g/gen-scenario]
    (run-trial scenario
      (fn [{:keys [conn plan check]}]
        (if (:pass? check)
          ;; passing Check ⇒ Apply cannot fail for data-dependent reasons
          (map? (m/apply! conn plan {:allow-unhandled? true}))
          ;; failing Check ⇒ forcing Apply past the gates aborts
          (try (m/apply! conn plan {:allow-unhandled? true :check-gates? false})
            false
            (catch clojure.lang.ExceptionInfo e
              (= :sqlite-error (:sqlite-migrate/error (ex-data e))))))))))

;; ---------------------------------------------------------------------------
;; Property: plan determinism — same (Diff, Capabilities, Directives)
;; from independently recomputed Snapshots gives a byte-identical Plan

(defn- plan-of-scenario
  "Plan `scenario` from a fresh realization of both sides (rows are
  irrelevant to planning and skipped)."
  [scenario]
  (let [live (snap-of (:live scenario))
        declared (snap-of (:target scenario))]
    (m/plan (m/diff live declared)
      {:live-snapshot live :declared-snapshot declared
       :directives (:directives scenario)})))

(defspec plan-determinism-property trials
  (prop/for-all [scenario g/gen-scenario]
    (= (pr-str (plan-of-scenario scenario))
      (pr-str (plan-of-scenario scenario)))))

;; ---------------------------------------------------------------------------
;; Property: version honesty — the Plan compiled for the current SQLite
;; version executes successfully on it (the cross-version matrix is CI's)

(defspec version-honesty-property trials
  (prop/for-all [scenario g/gen-scenario]
    (run-trial scenario
      (fn [{:keys [conn plan check]}]
        (or (not (:pass? check))
          (some? (:schema-version (m/apply! conn plan {:allow-unhandled? true}))))))))

;; ---------------------------------------------------------------------------
;; Deterministic corpus seeds (the fourth generator, plain deftests)

(deftest corpus-nasty-declaration-satisfies-no-op
  (let [a (snap-of corpus/nasty-declaration)
        b (snap-of corpus/nasty-declaration)
        d (m/diff a b)]
    (is (not (m/drift? d)) "the nasty corpus must not drift from itself")
    (let [plan (m/plan d {:live-snapshot a :declared-snapshot b})]
      (is (empty? (:ops plan)))
      (is (empty? (:unhandled plan))))))

(deftest corpus-nasty-declaration-round-trips
  (let [a (snap-of corpus/nasty-declaration)
        b (snap-of (emit-declaration a))]
    (is (not (m/drift? (m/diff a b)))
      "re-emitting the nasty corpus from stored CREATE sql must introspect Equivalent")))

(deftest corpus-nasty-target-plans-deterministically-and-converges-residually
  (let [plan-once (fn []
                    (let [live (snap-of corpus/nasty-declaration)
                          declared (snap-of corpus/nasty-target-declaration)]
                      (m/plan (m/diff live declared)
                        {:live-snapshot live :declared-snapshot declared})))]
    (is (= (pr-str (plan-once)) (pr-str (plan-once)))
      "independently recomputed Plans must be byte-identical")
    (with-open [conn (sql-jdbc/in-memory)]
      (p/execute-batch! conn corpus/nasty-declaration)
      (let [declared (snap-of corpus/nasty-target-declaration)
            live (m/snapshot conn)
            plan (m/plan (m/diff live declared)
                   {:live-snapshot live :declared-snapshot declared})]
        (is (:pass? (m/check conn plan)) "no rows, so every Gate passes")
        (m/apply! conn plan {:allow-unhandled? true})
        (is (= (mapv :entry (:unhandled plan))
              (:entries (m/diff (m/snapshot conn) declared)))
          "the post-Apply diff must equal exactly the Plan's unhandled entries")))))

(defn- strict-conversion-check
  "Calls `f` with the connection, Plan, and Check result for one live
  text value under the strict-conversion corpus seed."
  [text f]
  (with-open [conn (sql-jdbc/in-memory)]
    (p/execute-batch! conn (:live corpus/strict-conversion-seed))
    (p/execute-batch! conn [(str "INSERT INTO ids (id) VALUES ('"
                              (str/replace text "'" "''") "')")])
    (let [declared (snap-of (:target corpus/strict-conversion-seed))
          live (m/snapshot conn)
          plan (m/plan (m/diff live declared)
                 {:live-snapshot live :declared-snapshot declared})]
      (f conn plan (m/check conn plan)))))

(deftest corpus-strict-conversion-gate-matches-sqlite-in-both-directions
  (doseq [[text stored] (:accepted corpus/strict-conversion-seed)]
    (testing (str "accepted spelling " (pr-str text))
      (strict-conversion-check text
        (fn [conn plan check]
          (is (:pass? check)
            (str "the Gate must accept " (pr-str text) " — SQLite does"))
          (m/apply! conn plan)
          (is (= [{:id stored}]
                (p/execute-query conn "SELECT id FROM ids" []))
            "the copy stores the losslessly converted integer")))))
  (doseq [text (:rejected corpus/strict-conversion-seed)]
    (testing (str "rejected spelling " (pr-str text))
      (strict-conversion-check text
        (fn [conn plan check]
          (is (not (:pass? check))
            (str "the Gate must reject " (pr-str text) " — SQLite would abort"))
          (let [ex (try (m/apply! conn plan {:check-gates? false}) nil
                     (catch clojure.lang.ExceptionInfo e e))]
            (is (= :sqlite-error (:sqlite-migrate/error (ex-data ex)))
              "forcing Apply past the failed Gate must abort")))))))

(deftest corpus-new-unique-key-over-constant-default-gates-on-row-count
  (let [{:keys [live constant-default-target null-default-target]} corpus/new-key-seed
        run (fn [target n-rows f]
              (with-open [conn (sql-jdbc/in-memory)]
                (p/execute-batch! conn live)
                (dotimes [i n-rows]
                  (p/execute-batch! conn [(str "INSERT INTO t (a) VALUES (" i ")")]))
                (let [declared (snap-of target)
                      live-snap (m/snapshot conn)
                      plan (m/plan (m/diff live-snap declared)
                             {:live-snapshot live-snap :declared-snapshot declared})]
                  (f conn plan (m/check conn plan)))))]
    (testing "two rows share the constant default, so the key must gate"
      (run constant-default-target 2
        (fn [conn plan check]
          (is (not (:pass? check)))
          (let [ex (try (m/apply! conn plan {:check-gates? false}) nil
                     (catch clojure.lang.ExceptionInfo e e))]
            (is (= :sqlite-error (:sqlite-migrate/error (ex-data ex))))))))
    (testing "one row cannot collide with itself"
      (run constant-default-target 1
        (fn [conn plan check]
          (is (:pass? check))
          (m/apply! conn plan)
          (is (= [{:k 7}] (p/execute-query conn "SELECT k FROM t" []))))))
    (testing "a NULL-default key never gates — NULL keys are always distinct"
      (run null-default-target 2
        (fn [conn plan check]
          (is (:pass? check))
          (m/apply! conn plan)
          (is (= [{:k nil} {:k nil}]
                (p/execute-query conn "SELECT k FROM t" []))))))))

(deftest corpus-fk-addition-gate-matches-sqlite-in-both-directions
  (let [{:keys [live target]} corpus/fk-orphan-seed
        run (fn [rows f]
              (with-open [conn (sql-jdbc/in-memory)]
                (p/execute-batch! conn live)
                (p/execute-batch! conn rows)
                (let [declared (snap-of target)
                      live-snap (m/snapshot conn)
                      plan (m/plan (m/diff live-snap declared)
                             {:live-snapshot live-snap :declared-snapshot declared})]
                  (f conn plan (m/check conn plan)))))]
    (testing "an orphan child row fails the :foreign-key Gate; forcing Apply aborts"
      (run ["INSERT INTO p (id) VALUES (1)"
            "INSERT INTO c (pid) VALUES (2)"]
        (fn [conn plan check]
          (is (not (:pass? check)))
          (is (= [:foreign-key]
                (into [] (comp (remove :pass?) (map (comp :code :gate)))
                  (:gates check)))
            "the :foreign-key Gate is the one that fails")
          (let [ex (try (m/apply! conn plan {:check-gates? false}) nil
                     (catch clojure.lang.ExceptionInfo e e))]
            (is (= :sqlite-error (:sqlite-migrate/error (ex-data ex)))
              "forcing Apply past the failed Gate must abort")))))
    (testing "resolving and NULL child rows pass the Gate and Apply succeeds"
      (run ["INSERT INTO p (id) VALUES (1)"
            "INSERT INTO c (pid) VALUES (1)"
            "INSERT INTO c (pid) VALUES (NULL)"]
        (fn [conn plan check]
          (is (:pass? check))
          (m/apply! conn plan)
          (is (= [{:pid 1} {:pid nil}]
                (p/execute-query conn "SELECT pid FROM c" []))
            "rows survive the rebuild under the new FK"))))))
