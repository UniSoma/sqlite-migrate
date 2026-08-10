(ns sqlite-migrate.core
  "The sqlite-migrate pipeline: snapshot -> diff -> plan -> check -> apply!.

  Introspects live and declared schemas into verbatim Snapshots, diffs
  them under the full Diff model (fine-grained entries inside changed
  tables — ADR 0003, 0004), and plans in-place ops with honest Refusals
  for everything that needs a rebuild (ADR 0006, 0007). `check` runs
  the Plan's Gates — its data preconditions — read-only as a
  pre-flight; `apply!` checks them by default inside the Frame (ADR
  0008, 0011). Every effectful edge speaks to a
  `sqlite-migrate.protocols/SQLiteExecutor`."
  (:require [clojure.string :as str]
    [sqlite-migrate.impl.diff :as d]
    [sqlite-migrate.impl.extract :as x]
    [sqlite-migrate.impl.plan :as pl]
    [sqlite-migrate.impl.report :as r]
    [sqlite-migrate.impl.util :as u]
    [sqlite-migrate.protocols :as p]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Introspection

(defn- q [conn sql & params]
  (p/execute-query conn sql (vec params)))

(defn- current-fingerprint [conn]
  (-> (q conn "PRAGMA main.schema_version") first :schema_version))

(defn- table-columns
  "Column maps in storage (cid) order, generated columns included:
  pragma facts (name, declared type text, notnull, pk position, hidden
  code) merged with the extractor's verbatim per-column facts (DEFAULT
  spelling, COLLATE, generated expression)."
  [conn table-name facts]
  (->> (q conn "SELECT name, type, \"notnull\", pk, hidden FROM pragma_table_xinfo(?) WHERE hidden <> 1 ORDER BY cid"
         table-name)
    (mapv (fn [{:keys [name type notnull pk hidden]}]
            (let [k (x/fold-name name)]
              (cond-> {:name name :type type :not-null? (= 1 notnull) :pk pk}
                (contains? (:defaults facts) k)
                (assoc :default (get (:defaults facts) k))
                (contains? (:collates facts) k)
                (assoc :collate (get (:collates facts) k))
                (contains? #{2 3} hidden)
                (assoc :generated {:expr (get (:generated facts) k)
                                   :storage (if (= 2 hidden) :virtual :stored)})))))))

(defn- foreign-keys
  "Foreign keys merged from two sources: `pragma_foreign_key_list`
  groups (columns, referenced table and columns, actions) and the
  extractor's per-clause name and deferrability. A pragma group is
  paired with its clause by referenced table plus from-columns; when
  that key is incomplete or ambiguous (duplicate identical clauses)
  pairing falls back to positional order (pragma ids descending)."
  [conn table-name facts]
  (let [groups (->> (q conn "SELECT id, seq, \"table\", \"from\", \"to\", on_update, on_delete, \"match\" FROM pragma_foreign_key_list(?)"
                      table-name)
                 (group-by :id)
                 (sort-by key >)
                 (mapv (fn [[_ rows]]
                         (let [rows (sort-by :seq rows)]
                           {:columns (mapv :from rows)
                            :ref-table (:table (first rows))
                            :ref-columns (mapv :to rows)
                            :on-update (:on_update (first rows))
                            :on-delete (:on_delete (first rows))
                            :match (:match (first rows))}))))
        clauses (vec (:fks facts))
        fk-key (fn [{:keys [ref-table columns]}]
                 (when (and ref-table (seq columns))
                   [(x/fold-name ref-table) (mapv x/fold-name columns)]))
        ckeys (mapv fk-key clauses)
        gkeys (mapv fk-key groups)
        matchable? (and (seq clauses)
                     (= (count groups) (count clauses))
                     (every? some? ckeys)
                     (apply distinct? ckeys)
                     (= (set ckeys) (set gkeys)))]
    (if matchable?
      (let [by-key (zipmap gkeys groups)]
        (mapv (fn [clause ck]
                (assoc (by-key ck)
                  :name (:name clause)
                  :deferrable (:deferrable clause)))
          clauses
          ckeys))
      (mapv (fn [group clause]
              (assoc group
                :name (:name clause)
                :deferrable (:deferrable clause)))
        groups
        (concat clauses (repeat nil))))))

(defn- index-value
  "One named index: pragma facts (uniqueness, partiality, per-column
  name/collation/direction) merged with the extractor's verbatim
  expression and partial-WHERE text from the stored CREATE INDEX sql."
  [conn index-name unique partial sql]
  (let [facts (when sql (x/index-facts sql))
        cols (->> (q conn "SELECT seqno, cid, name, \"desc\", coll FROM pragma_index_xinfo(?) WHERE key = 1 ORDER BY seqno"
                    index-name)
               (mapv (fn [{:keys [seqno cid name desc coll]}]
                       (let [base {:collate coll :desc? (= 1 desc)}]
                         (if (= -2 cid)
                           (assoc base :expr (get-in facts [:columns seqno]))
                           (assoc base :name name))))))]
    (with-meta
      (cond-> {:name index-name
               :unique? (= 1 unique)
               :partial? (= 1 partial)
               :columns cols}
        (:where facts) (assoc :where (:where facts)))
      {:sql sql})))

(defn- table-indexes
  "Named (`CREATE INDEX`) indexes of `table-name`, keyed by index name.
  Automatic `sqlite_autoindex_*` indexes (origin `u`/`pk`) are
  engine-internal and excluded from the Snapshot."
  [conn stored-sql table-name]
  (into {}
    (for [{:keys [name unique partial]}
          (q conn "SELECT name, \"unique\", origin, partial FROM pragma_index_list(?) WHERE origin = 'c'"
            table-name)]
      [name (index-value conn name unique partial (stored-sql "index" name))])))

(defn- regular-table [conn stored-sql {:keys [name wr strict]}]
  (let [sql (stored-sql "table" name)
        facts (when sql (x/table-facts sql))
        columns (table-columns conn name facts)
        pk-cols (->> columns (filter #(pos? (:pk %))) (sort-by :pk) (mapv :name))]
    (with-meta
      (cond-> {:name name
               :strict? (= 1 strict)
               :without-rowid? (= 1 wr)
               :columns columns
               :checks (vec (:checks facts))
               :uniques (vec (:uniques facts))
               :foreign-keys (foreign-keys conn name facts)
               :autoincrement? (boolean (:autoincrement? facts))
               :indexes (table-indexes conn stored-sql name)}
        (seq pk-cols) (assoc :primary-key {:name (:pk-name facts) :columns pk-cols}))
      {:sql sql})))

(defn snapshot
  "Introspect the live `main` schema of `conn` into a Snapshot: tables
  (with columns, indexes, and triggers nested), views (with their
  triggers), and opaque virtual tables — identifiers as strings, columns
  in storage (cid) order. Engine-internal objects (`sqlite_*`, shadow
  tables, automatic indexes) are excluded. Provenance never affects
  Snapshot equality: each object map carries its stored CREATE sql
  verbatim as Clojure metadata (`{:sql ...}` via `clojure.core/meta`),
  and the Snapshot map itself carries `{:sqlite-version ...
  :schema-version ...}` the same way."
  [conn]
  (let [tlist (q conn (str "SELECT name, type, wr, strict FROM pragma_table_list"
                        " WHERE schema = 'main'"
                        " AND type IN ('table', 'view', 'virtual')"
                        " AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\'"
                        " ORDER BY name"))
        master (q conn "SELECT type, name, tbl_name, sql FROM main.sqlite_master")
        sql-by-key (into {} (map (juxt (juxt :type :name) :sql)) master)
        stored-sql (fn [type name] (sql-by-key [type name]))
        triggers-by-table (group-by :tbl_name (filter #(= "trigger" (:type %)) master))
        triggers-on (fn [tbl-name]
                      (into {}
                        (for [{:keys [name sql]} (triggers-by-table tbl-name)]
                          [name (with-meta {:name name} {:sql sql})])))]
    (with-meta
      {:tables (into {}
                 (for [{:keys [name type] :as row} tlist
                       :when (not= "view" type)]
                   [name
                    (if (= "virtual" type)
                      (with-meta {:name name :virtual? true}
                        {:sql (stored-sql "table" name)})
                      (assoc (regular-table conn stored-sql row)
                        :triggers (triggers-on name)))]))
       :views (into {}
                (for [{:keys [name type]} tlist
                      :when (= "view" type)]
                  [name (with-meta
                          {:name name
                           :columns (mapv :name (q conn "SELECT name FROM pragma_table_xinfo(?) ORDER BY cid" name))
                           :triggers (triggers-on name)}
                          {:sql (stored-sql "view" name)})]))}
      {:sqlite-version (-> (q conn "SELECT sqlite_version() AS v") first :v)
       :schema-version (current-fingerprint conn)})))

(defn- guard-invisible-effects!
  "Throw `:malformed-input` with which-statement context when executing
  `statement` did anything a Snapshot cannot capture. Three loud
  checks: every Declaration statement must change the main schema (DML,
  ATTACH, PRAGMA side effects, and temp objects bump nothing); no
  engine-internal table other than `sqlite_sequence` may exist
  (ANALYZE creates `sqlite_stat*`, which the Snapshot excludes); and no
  table — engine-internal ones included — may hold rows afterwards
  (`CREATE TABLE ... AS SELECT` smuggles data past the first check)."
  [conn statement index before-fingerprint]
  (let [bail! (fn [msg extra]
                (throw (ex-info msg (merge {:sqlite-migrate/error :malformed-input
                                            :statement statement
                                            :statement-index index}
                                      extra))))]
    (when (= before-fingerprint (current-fingerprint conn))
      (bail! (str "Declaration statement " index " has no effect on the main"
               " schema — a Snapshot cannot capture what it does")
        {}))
    (let [tables (q conn (str "SELECT name FROM pragma_table_list"
                           " WHERE schema = 'main' AND type = 'table'"
                           " AND name <> 'sqlite_schema'"))]
      (doseq [{:keys [name]} tables]
        (when (and (str/starts-with? name "sqlite_")
                (not= "sqlite_sequence" name))
          (bail! (str "Declaration statement " index " created engine-internal"
                   " table " name " — a Snapshot cannot capture what it does")
            {:table name}))
        (when (seq (q conn (str "SELECT 1 FROM " (u/q-ident name) " LIMIT 1")))
          (bail! (str "Declaration statement " index " left rows in table "
                   name " — a Snapshot carries schema, never data")
            {:table name}))))))

(defn declared-snapshot
  "Realize `declaration` (a SQL statement string or seq of statement
  strings) into the pristine database behind `conn` and introspect it.
  Guards the pristine premise (throws `:malformed-input` if the
  database already contains objects) and refuses loudly — with
  which-statement context — any statement whose effect introspection
  cannot capture: DML, ATTACH, PRAGMA side effects, temp objects."
  [conn declaration]
  (let [before (snapshot conn)
        existing (concat (keys (:tables before)) (keys (:views before)))]
    (when (seq existing)
      (throw (ex-info (str "declared-snapshot requires an empty database — found "
                        (count existing) " object(s)")
               {:sqlite-migrate/error :malformed-input
                :existing-objects (vec (sort existing))}))))
  (let [statements (if (string? declaration) [declaration] (vec declaration))]
    (doseq [[index statement] (map-indexed vector statements)]
      (let [fingerprint (current-fingerprint conn)]
        (p/execute-batch! conn [statement])
        (guard-invisible-effects! conn statement index fingerprint))))
  (snapshot conn))

;; ---------------------------------------------------------------------------
;; Diff

(defn diff
  "Compare two Snapshots (live, declared) into a Diff: a flat `:entries`
  vector plus both sides' Snapshot metadata. Each entry is one
  self-contained Semantic difference — target-relative `:kind`
  (:added/:removed/:changed), `:path`, both sides' verbatim sub-values
  with stored CREATE sql embedded, and for :changed the `:facts` set —
  in a locked deterministic order, plain EDN all the way down. Empty
  `:entries` iff the Snapshots are Equivalent. See
  `sqlite-migrate.impl.diff/diff` for the full contract (ADR 0003, 0004)."
  [live declared]
  (d/diff live declared))

(defn drift?
  "True when `diff` has entries — the live schema is not Equivalent to
  the declared one. A Diff is empty iff its two Snapshots are Equivalent
  (ADR 0003's no-op property), so this is the single predicate over the
  Equivalence relation."
  [diff]
  (boolean (seq (:entries diff))))

(defn drift-report
  "Render `diff` into a deterministic plain-text drift report: one
  block per entry in the locked entry order — per-fact both-sides
  lines for a :changed entry, the object's whole verbatim CREATE sql
  for an :added/:removed one. Pure and single-arity; renders from a
  deserialized Diff alone; an empty Diff yields the empty string.
  Presentation-only — the text is not a parse contract, the EDN Diff
  is the machine surface. See `sqlite-migrate.impl.report/drift-report`
  for the full contract (ADR 0005)."
  [diff]
  (r/drift-report diff))

(defn by-object
  "Regroup `diff`'s flat entries under the object each belongs to: a
  vector of `{:path [<kind> <name>] :entries [...]}` groups in the
  locked entry order, a changed table's table-level entry reunited
  with its fine-grained children, entries verbatim and in order. The
  one nesting view — an empty Diff yields `[]`. See
  `sqlite-migrate.impl.report/by-object` for the full contract
  (ADR 0005)."
  [diff]
  (r/by-object diff))

;; ---------------------------------------------------------------------------
;; Plan

(defn plan
  "Plan a Diff into an ordered, self-contained Plan: `{:ops [...]
  :unhandled [...] :live-metadata ... :declared-metadata ...
  :capabilities ... :directives [...] :unused-directives [...]}` —
  list position is execution order, every Diff entry either served by
  ≥1 op or honestly unhandled with its full Refusal vector,
  byte-identical for identical inputs. Opts: `:capabilities`
  (defaults: the live Snapshot's SQLite version plus `:rebuild?
  true`), `:directives` (the intent channel, ADR 0009 — per-object
  Directive maps that lift `:needs-intent` refusals: `:rename-table`,
  `:rename-column`, `:drop-table`, `:drop-column`; a conflicting set
  throws `:malformed-input`, an unmatched directive is inert and
  reported under `:unused-directives` in input order, and apply! never
  consults them), and `:live-snapshot`/`:declared-snapshot` (required
  planning context whenever the Diff contains a changed table). See
  `sqlite-migrate.impl.plan/plan` for the full contract (ADR 0006,
  0007, 0009)."
  ([diff] (plan diff {}))
  ([diff opts] (pl/plan diff opts)))

(defn plan-report
  "Render `plan` into a deterministic plain-text plan report — the
  pre-apply review artifact: a header with both sides' identity, the
  Ops in execution order with kind, object path, Gates (code and
  explanation), and full SQL always; then the unhandled entries with
  each Refusal's class, code, and explanation; then the unused
  Directives verbatim. Pure and single-arity; renders from a
  deserialized Plan alone. Presentation-only — the text is not a
  parse contract, the EDN Plan is the machine surface. See
  `sqlite-migrate.impl.report/plan-report` for the full contract
  (ADR 0012)."
  [plan]
  (r/plan-report plan))

;; ---------------------------------------------------------------------------
;; Check (ADR 0008)

(defn- verify-fingerprint!
  "Throw `:drift-refused` when the live `schema_version` fingerprint no
  longer matches `plan`'s source Snapshot metadata — the plan's SQL
  (gate SQL included) was compiled against a schema that no longer
  exists. No override; the remedy is re-diff, re-plan."
  [conn plan]
  (let [plan-fingerprint (get-in plan [:live-metadata :schema-version])
        live-fingerprint (current-fingerprint conn)]
    (when (not= plan-fingerprint live-fingerprint)
      (throw (ex-info (str "live schema_version " live-fingerprint
                        " does not match the plan's source fingerprint "
                        plan-fingerprint)
               {:sqlite-migrate/error :drift-refused
                :plan-fingerprint plan-fingerprint
                :live-fingerprint live-fingerprint
                :live-metadata (:live-metadata plan)
                :declared-metadata (:declared-metadata plan)})))))

(defn- run-gates
  "Run every Gate of `plan` verbatim over `conn`'s query path and
  return the Check result: `{:pass? bool :gates [...]}`, one result map
  per Gate in op order — the Gate itself under `:gate`, its op's plan
  index, `:pass?`, the sampled `:violations` count, `:more?` when the
  count hit the Gate's baked limit (\"limit or more\"), and the
  violating `:sample-rows` (row order is SQLite's — outside the
  determinism contract, ADR 0008)."
  [conn plan]
  (let [results (vec (for [[op-index op] (map-indexed vector (:ops plan))
                           gate (:gates op)]
                       (let [rows (p/execute-query conn (:sql gate) [])
                             n (count rows)]
                         {:gate gate
                          :op-index op-index
                          :pass? (zero? n)
                          :violations n
                          :more? (= n (:limit gate))
                          :sample-rows rows})))]
    {:pass? (every? :pass? results) :gates results}))

(defn check
  "Run every Gate of `plan` read-only against `conn` and return the
  Check result — the pre-flight over the plan's data preconditions
  (ADR 0008): `{:pass? bool :gates [...]}`, one result map per Gate in
  op order — the Gate itself under `:gate`, its op's plan index
  (`:op-index`), `:pass?`, the sampled `:violations` count, `:more?`
  when the count hit the Gate's baked limit (\"limit or more\"), and
  the violating `:sample-rows` (row order is SQLite's — outside the
  determinism contract). Refuses (`:drift-refused`, no override) when the
  live `schema_version` fingerprint no longer matches the Plan's
  source Snapshot metadata. Never mutates the database; SQLite's own
  enforcement remains the backstop."
  [conn plan]
  (verify-fingerprint! conn plan)
  (run-gates conn plan))

(defn check-report
  "Render a Check result into a deterministic plain-text check report:
  a one-line verdict, then one block per failing Gate — code, path, op
  index, explanation, violation count (\"limit or more\" when the
  sample hit the Gate's baked limit), and the sample rows verbatim.
  Pure and single-arity; renders from a deserialized Check result
  alone. Presentation-only — the text is not a parse contract, the
  EDN Check result is the machine surface. See
  `sqlite-migrate.impl.report/check-report` for the full contract
  (ADR 0012)."
  [check-result]
  (r/check-report check-result))

;; ---------------------------------------------------------------------------
;; Apply

(defn- op-at-batch-index
  "Attribute the flattened batch statement index `i` back to its Op:
  `[op-index op statement]`, or nil when `i` is out of range."
  [ops ^long i]
  (loop [op-index 0 offset i]
    (when (< op-index (count ops))
      (let [op (nth ops op-index)
            n (count (:sql op))]
        (if (< offset n)
          [op-index op (nth (:sql op) offset)]
          (recur (inc op-index) (- offset n)))))))

(defn apply!
  "Execute `plan` on `conn` inside the executor-owned Frame — a dumb fold
  over the ops in plan order, all-or-nothing. Refuses (`:drift-refused`,
  no override) when the live `schema_version` fingerprint no longer
  matches the Plan's source Snapshot metadata; refuses
  (`:unhandled-refused`) when the Plan has unhandled entries and
  `:allow-unhandled?` is not set. By default every Gate is checked
  up-front once the Frame's transaction is open (TOCTOU-free — ADR
  0008); a failing Gate rolls back and throws `:gate-failed` carrying
  the Check result verbatim under `:check`. `:check-gates? false` opts
  out (ADR 0011) — for the operator who just ran `check` and wants to
  skip a second full scan. A mid-apply SQLite failure throws
  `:sqlite-error` carrying the failing Op verbatim, its plan index
  (`:op-index`), and the specific SQL statement that failed. Returns a
  minimal Apply report — the Check result rides it under `:check`,
  absent when gate-checking was skipped; throws on every non-success."
  ([conn plan] (apply! conn plan {}))
  ([conn plan opts]
    (when (and (seq (:unhandled plan)) (not (:allow-unhandled? opts)))
      (let [n (count (:unhandled plan))]
        (throw (ex-info (str "plan has " n " unhandled "
                          (if (= 1 n) "entry" "entries")
                          " and :allow-unhandled? is not set")
                 {:sqlite-migrate/error :unhandled-refused
                  :unhandled (:unhandled plan)}))))
    (verify-fingerprint! conn plan)
    (let [check-gates? (not (false? (:check-gates? opts)))
          checked (volatile! nil)
          pre-check! (when check-gates?
                       (fn []
                         (let [result (run-gates conn plan)]
                           (vreset! checked result)
                           (when-not (:pass? result)
                             (let [n (count (remove :pass? (:gates result)))]
                               (throw (ex-info (str n " of " (count (:gates result))
                                                 " gates failed; nothing was applied")
                                        {:sqlite-migrate/error :gate-failed
                                         :check result})))))))
          statements (into [] (mapcat :sql) (:ops plan))]
      (try
        (if pre-check!
          (p/execute-batch! conn statements pre-check!)
          (p/execute-batch! conn statements))
        (catch Exception e
          (let [data (ex-data e)
                located (when-let [i (:statement-index data)]
                          (op-at-batch-index (:ops plan) i))]
            (cond
              located
              (let [[op-index op statement] located]
                (throw (ex-info (str "SQLite error during apply — op " op-index
                                  " (" (name (:kind op)) ") failed")
                         {:sqlite-migrate/error :sqlite-error
                          :op op
                          :op-index op-index
                          :statement statement}
                         (or (ex-cause e) e))))

              (:sqlite-migrate/error data)
              (throw e)

              :else
              (throw (ex-info "SQLite error during apply"
                       {:sqlite-migrate/error :sqlite-error}
                       e))))))
      (cond-> (assoc (select-keys plan [:live-metadata :declared-metadata :ops])
                :schema-version (current-fingerprint conn))
        check-gates? (assoc :check @checked)))))
