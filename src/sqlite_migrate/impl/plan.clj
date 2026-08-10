(ns ^:no-doc sqlite-migrate.impl.plan
  "The pure planner (ADR 0006, 0007): Diff entries in, an ordered,
  self-contained Plan out — every entry either served by ops or honestly
  unhandled with its full Refusal vector. Never throws for refusals;
  throwing is reserved for malformed input.

  In-place op vocabulary: `:create-table`, `:add-column`, `:drop-column`
  (restricted), `:set-not-null`/`:drop-not-null` and
  `:add-check`/`:drop-check` (target version 3.53+), and
  create/drop for indexes, triggers, and views. Everything else routes
  to the `:rebuild-table` composite op — the 12-step generalized ALTER
  TABLE compiled wholly at plan time. Selection is per table: one
  rebuild-routed entry collapses the table's entire change set into
  one rebuild (ADR 0006 — never mix in-place and rebuild for one
  table). A collapse stays unhandled with `:rebuild-disabled` when the
  `:rebuild?` capability is off, and with the blocking Refusals when a
  destructive drop still awaits intent or the declared shape cannot
  exist on the target version.

  The locked phase order, baked into list position: (1) drop removed
  and changed secondary objects — triggers, then indexes, then views;
  (2) drop removed tables (only ever planned under a :drop-table
  Directive — ADR 0009); (3) per-table change ops, tables
  folded-name-sorted, inside a table: a fused pair's table rename
  first, then drop checks, drop columns (reference-order so a
  generated column drops before the columns its expression reads),
  NOT NULL alters and column renames, add columns (declared order),
  add checks; (4) create added
  tables, folded-name-sorted; (5) create added and changed secondary
  objects — indexes, then views, then triggers. The planner exploits
  this order to legalize in-place forms (a covering index or CHECK
  drops before its column does) and verifies drop-column legality
  against the accumulated intermediate state.

  Ops carry their data preconditions as Gates (ADR 0008): plain-EDN
  maps with a code, path, explanation, and one plan-compiled sampling
  SELECT with a baked LIMIT, in the op's `:gates` vector — on the
  in-place op that introduces the constraint, or all together on the
  table's :rebuild-table. Gate SQL queries live spellings and falls
  under the same determinism contract as op `:sql`.

  Capabilities are a flat map — the target `:sqlite-version` (defaulting
  to the live Snapshot's) plus `:rebuild?` (default true). A nil target
  version means \"latest\": every version gate passes."
  (:require [clojure.string :as str]
    [sqlite-migrate.impl.diff :as d]
    [sqlite-migrate.impl.extract :as x]
    [sqlite-migrate.impl.util :as u]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Capabilities and version gates

(defn- version-vec [s]
  (vec (take 3 (concat (map parse-long (re-seq #"\d+" (str s))) (repeat 0)))))

(defn- supports?
  "True when the capabilities' target version is at least `minimum`.
  A nil target version means latest — every gate passes."
  [{:keys [sqlite-version]} minimum]
  (or (nil? sqlite-version)
    (>= (compare (version-vec sqlite-version) (version-vec minimum)) 0)))

;; The version gates the planner consults. DROP COLUMN arrived in 3.35;
;; generated columns in 3.31; STRICT tables in 3.37; the ALTER COLUMN
;; SET/DROP NOT NULL, ADD CHECK / DROP CONSTRAINT family (and the
;; relaxed ADD COLUMN forms) in 3.53.
(def ^:private v-drop-column "3.35.0")
(def ^:private v-rename-column "3.25.0")
(def ^:private v-generated "3.31.0")
(def ^:private v-strict "3.37.0")
(def ^:private v-alter-constraint "3.53.0")

;; ---------------------------------------------------------------------------
;; SQL emission

(defn- column-def-sql
  "The column-definition text an :add-column op appends, re-emitted from
  the declared column value's verbatim facts."
  [{:keys [name type not-null? default collate generated]}]
  (str (u/q-ident name)
    (when (seq type) (str " " type))
    (when collate (str " COLLATE " collate))
    (when not-null? " NOT NULL")
    (when default (str " DEFAULT " default))
    (when generated
      (str " GENERATED ALWAYS AS (" (:expr generated) ") "
        (if (= :stored (:storage generated)) "STORED" "VIRTUAL")))))

;; ---------------------------------------------------------------------------
;; Refusals

(defn- refusal [class code explanation]
  {:class class :code code :explanation explanation})

(defn- rebuild-disabled-refusal [what]
  (refusal :incapable :rebuild-disabled
    (str what " can only converge through a table rebuild"
      " and the :rebuild? capability is off")))

(defn- destructive-refusal [what]
  (refusal :needs-intent :destructive-drop
    (str "dropping " what " would discard stored values;"
      " it plans only with explicit intent (a directive)")))

(defn- unsupported-refusal [what minimum {:keys [sqlite-version]}]
  (refusal :incapable :unsupported-by-target-version
    (str what " requires SQLite " minimum
      " and the target version is " sqlite-version)))

(defn- unsupported-object-refusals
  "The `:unsupported-by-target-version` Refusals for a declared object
  that cannot exist on the target version at all — detected from
  Snapshot flags (ADR 0007), never by parsing SQL."
  [capabilities declared]
  (when declared
    (cond-> []
      (and (:strict? declared) (not (supports? capabilities v-strict)))
      (conj (unsupported-refusal (str "STRICT table " (:name declared)) v-strict capabilities))

      (and (not (supports? capabilities v-generated))
        (or (:generated declared) (some :generated (:columns declared))))
      (conj (unsupported-refusal
              (str "a generated column on " (:name declared)) v-generated capabilities)))))

;; ---------------------------------------------------------------------------
;; Lexical reference checks (Token comparison machinery — never a parser)

(defn- references?
  "True when opaque-expression `text` lexically references the folded
  column name `col-fold` (a word or quoted-identifier token folding to
  it). Conservative and lexical only."
  [text col-fold]
  (boolean (and text
             (some #(and (#{:word :qid} (:t %)) (= col-fold (:fold %)))
               (x/tokenize text)))))

(defn- index-references?
  "True when index value `idx` touches the folded column name: as a
  named key column, inside a key expression, or inside the partial
  WHERE clause."
  [idx col-fold]
  (boolean (or (some #(or (= col-fold (some-> (:name %) x/fold-name))
                        (references? (:expr %) col-fold))
                 (:columns idx))
             (references? (:where idx) col-fold))))

;; ---------------------------------------------------------------------------
;; Per-entry routing inside a changed table
;;
;; A route is one of:
;;   {:ops [...]}                — in-place, ordered op maps (with :order keys)
;;   {:needs-intent [refusals]} — an in-place route exists but requires intent
;;   {:rebuild extra-refusals}  — the only route is a rebuild
;;   {:refuse [refusals]}       — no route at all (unsupported/virtual)

;; The locked phase order (see the ns docstring), named so the :order
;; sort keys read as phases rather than bare numbers.
(def ^:private phase-drop-secondary 1)
(def ^:private phase-drop-tables 2)
(def ^:private phase-change-tables 3)
(def ^:private phase-create-tables 4)
(def ^:private phase-create-secondary 5)

;; Sub-phases inside one changed table (phase 3). NOT NULL alters and
;; column renames share sub-alter-column, per the ns docstring.
(def ^:private sub-rename-table -1)
(def ^:private sub-drop-check 0)
(def ^:private sub-drop-column 1)
(def ^:private sub-alter-column 2)
(def ^:private sub-add-column 3)
(def ^:private sub-add-check 4)

;; An :add-column key carries this placeholder until `declared-position`
;; patches in the column's declared-order index.
(def ^:private unpatched-position -1)

(def ^:private order-pad 0)

(defn- op
  "An op with its plan-position sort key. Order vectors are padded to a
  fixed length with `order-pad`: `compare` ranks vectors by length
  before content, and the phases emit keys of different arities. The
  -1 sentinels (`sub-rename-table`, `unpatched-position`) intentionally
  sort before the pad."
  [order kind path serves sql]
  {:order (vec (take 6 (concat order (repeat order-pad))))
   :op {:kind kind :path path :serves serves :sql sql}})

(defn- entry-name [entry] (peek (:path entry)))

(defn- appended-suffix?
  "True when every added column sits in a trailing block of the declared
  column order — the only position ALTER TABLE ADD COLUMN can produce."
  [declared-table added-folds]
  (let [decl-order (mapv (comp x/fold-name :name) (:columns declared-table))]
    (every? added-folds (take-last (count added-folds) decl-order))))

(defn- current-word? [default]
  (contains? #{"current_time" "current_date" "current_timestamp"}
    (some-> default x/fold-name)))

(defn- route-added-column
  [capabilities tname entry appendable?]
  (let [col (:declared entry)
        version-floor (fn [ok? minimum what]
                        (when-not ok?
                          (if (supports? capabilities minimum) nil [minimum what])))
        blocked (or (when (pos? (:pk col)) :pk)
                  (when-not appendable? :position)
                  (first
                    (keep identity
                      [(version-floor (or (not (:not-null? col)) (some? (:default col)))
                         v-alter-constraint "adding a NOT NULL column without a default")
                       (version-floor (not (current-word? (:default col)))
                         v-alter-constraint "adding a column with a CURRENT_* default")
                       (version-floor (not= :stored (:storage (:generated col)))
                         v-alter-constraint "adding a STORED generated column")
                       (version-floor (nil? (:generated col))
                         v-generated "adding a generated column")])))]
    (if blocked
      {:rebuild []}
      {:ops [(op [phase-change-tables (x/fold-name tname) sub-add-column "" unpatched-position]
               :add-column (:path entry) #{(:path entry)}
               [(str "ALTER TABLE " (u/q-ident tname) " ADD COLUMN " (column-def-sql col))])]})))

(defn- droppable-in-place?
  "Drop-column legality against the accumulated intermediate state: the
  column may not sit in the primary key, a UNIQUE constraint, or a
  FOREIGN KEY clause of its own table, nor be referenced by an index,
  CHECK, or generated column that survives the plan's earlier drops
  (`:retained-indexes`/`:retained-checks`, other dropped columns
  excepted), nor by a view or trigger that survives them
  (`:surviving-sqls` — conservative lexical check: a surviving object
  whose stored sql mentions both the column and the table blocks the
  drop)."
  [_tname live-table col-fold
   {:keys [retained-indexes retained-checks dropped-col-folds surviving-sqls]}]
  ;; the lexical checks key on the LIVE table name — under a fused
  ;; table rename (ADR 0009) the ops target the declared name while
  ;; every surviving stored sql still spells the live one
  (let [tfold (x/fold-name (:name live-table))]
    (and
      (not (some #(= col-fold (x/fold-name %)) (get-in live-table [:primary-key :columns])))
      (zero? (:pk (some #(when (= col-fold (x/fold-name (:name %))) %) (:columns live-table))))
      (not (some (fn [u] (some #(= col-fold (x/fold-name %)) (:columns u)))
             (:uniques live-table)))
      ;; SQLite rejects DROP COLUMN when the column sits in a FOREIGN KEY
      ;; clause; the Snapshot cannot tell a column-level REFERENCES (whose
      ;; drop SQLite allows) from a table-level clause, so block both
      (not (some (fn [fk] (some #(= col-fold (x/fold-name %)) (:columns fk)))
             (:foreign-keys live-table)))
      (not (some #(index-references? % col-fold) retained-indexes))
      (not (some #(references? (:expr %) col-fold) retained-checks))
      (not (some (fn [c]
                   (and (:generated c)
                     (not (contains? dropped-col-folds (x/fold-name (:name c))))
                     (not= col-fold (x/fold-name (:name c)))
                     (references? (get-in c [:generated :expr]) col-fold)))
             (:columns live-table)))
      (not (some #(and (references? % col-fold) (references? % tfold))
             surviving-sqls)))))

(defn- order-dropped-columns
  "Dropped columns in a legal drop order: a generated column whose
  expression reads another dropped column drops before it. Folded-name
  order breaks ties; a cycle (impossible in a valid schema) falls back
  to folded-name order."
  [cols]
  (loop [remaining (vec (sort-by (comp x/fold-name :name) cols)) out []]
    (if (empty? remaining)
      out
      (let [referenced? (fn [c]
                          (some #(and (not= % c)
                                   (references? (get-in % [:generated :expr])
                                     (x/fold-name (:name c))))
                            remaining))
            pick (or (first (remove referenced? remaining)) (first remaining))]
        (recur (vec (remove #(identical? % pick) remaining)) (conj out pick))))))

(defn- route-changed-column [capabilities tname entry]
  (if (= #{:not-null?} (:facts entry))
    (if (supports? capabilities v-alter-constraint)
      {:ops [(op [phase-change-tables (x/fold-name tname) sub-alter-column
                  (x/fold-name (entry-name entry))]
               (if (:not-null? (:declared entry)) :set-not-null :drop-not-null)
               (:path entry) #{(:path entry)}
               [(str "ALTER TABLE " (u/q-ident tname) " ALTER COLUMN "
                  (u/q-ident (entry-name entry))
                  (if (:not-null? (:declared entry)) " SET NOT NULL" " DROP NOT NULL"))])]}
      {:rebuild []})
    {:rebuild []}))

(defn- check-sort-key
  "The tail of a check op's sort key: named checks (0) sort before
  anonymous ones (1), by folded name and diff position respectively."
  [c path]
  (if (:name c)
    [0 (x/fold-name (:name c)) -1]
    [1 "" (peek path)]))

(defn- add-check-op [tname entry]
  (let [c (:declared entry)]
    (op (into [phase-change-tables (x/fold-name tname) sub-add-check]
          (check-sort-key c (:path entry)))
      :add-check (:path entry) #{(:path entry)}
      [(str "ALTER TABLE " (u/q-ident tname)
         " ADD " (when (:name c) (str "CONSTRAINT " (u/q-ident (:name c)) " "))
         "CHECK (" (:expr c) ")")])))

(defn- drop-check-op [tname entry]
  (let [c (:live entry)]
    (op (into [phase-change-tables (x/fold-name tname) sub-drop-check]
          (check-sort-key c (:path entry)))
      :drop-check (:path entry) #{(:path entry)}
      [(str "ALTER TABLE " (u/q-ident tname) " DROP CONSTRAINT " (u/q-ident (:name c)))])))

(defn- route-check [capabilities tname entry]
  (let [gated (supports? capabilities v-alter-constraint)]
    (case (:kind entry)
      :added (if gated {:ops [(add-check-op tname entry)]} {:rebuild []})
      :removed (if (and gated (:name (:live entry)))
                 {:ops [(drop-check-op tname entry)]}
                 {:rebuild []})
      :changed (if (and gated (:name (:live entry)))
                 {:ops [(drop-check-op tname entry) (add-check-op tname entry)]}
                 {:rebuild []}))))

;; ---------------------------------------------------------------------------
;; Secondary objects: indexes, triggers, views (create/drop always plan)

(defn- drop-secondary-op [sub kind entry parent-fold]
  (let [nm (entry-name entry)
        stmt (case kind
               :drop-index (str "DROP INDEX " (u/q-ident nm))
               :drop-trigger (str "DROP TRIGGER " (u/q-ident nm))
               :drop-view (str "DROP VIEW " (u/q-ident nm)))]
    (op [phase-drop-secondary sub parent-fold (x/fold-name nm)]
      kind (:path entry) #{(:path entry)} [stmt])))

(defn- create-secondary-op [sub kind path serves parent-fold nm sql]
  (op [phase-create-secondary sub parent-fold (x/fold-name nm)] kind path serves [sql]))

(defn- route-index [entry tname]
  (let [tfold (x/fold-name tname)]
    (case (:kind entry)
      :added {:ops [(create-secondary-op 0 :create-index (:path entry) #{(:path entry)}
                      tfold (entry-name entry) (:sql (:declared entry)))]}
      :removed {:ops [(drop-secondary-op 1 :drop-index entry tfold)]}
      :changed {:ops [(drop-secondary-op 1 :drop-index entry tfold)
                      (create-secondary-op 0 :create-index (:path entry) #{(:path entry)}
                        tfold (entry-name entry) (:sql (:declared entry)))]})))

(defn- route-trigger [entry parent-name]
  (let [pfold (x/fold-name parent-name)]
    (case (:kind entry)
      :added {:ops [(create-secondary-op 2 :create-trigger (:path entry) #{(:path entry)}
                      pfold (entry-name entry) (:sql (:declared entry)))]}
      :removed {:ops [(drop-secondary-op 0 :drop-trigger entry pfold)]}
      :changed {:ops [(drop-secondary-op 0 :drop-trigger entry pfold)
                      (create-secondary-op 2 :create-trigger (:path entry) #{(:path entry)}
                        pfold (entry-name entry) (:sql (:declared entry)))]})))

;; ---------------------------------------------------------------------------
;; The :rebuild-table composite op (ADR 0006, 0008, 0010)

(defn- q-str ^String [^String s]
  (str "'" (str/replace s "'" "''") "'"))

(defn- temp-rebuild-name
  "The deterministic temporary name the rebuild creates the new table
  under — the declared name plus a fixed suffix, lengthened until it
  collides with nothing in either Snapshot."
  [{:keys [live-snapshot declared-snapshot]} nm]
  (let [taken (into #{} (map x/fold-name)
                (concat (keys (:tables live-snapshot)) (keys (:views live-snapshot))
                  (keys (:tables declared-snapshot)) (keys (:views declared-snapshot))))]
    (loop [candidate (str nm "__sqm_rebuild")]
      (if (contains? taken (x/fold-name candidate))
        (recur (str candidate "_"))
        candidate))))

(defn- create-sql-under-temp-name
  "The declared table's verbatim CREATE sql with its table-name token
  replaced by the quoted `temp-name` — token substitution (never a
  parser): the first identifier after the TABLE keyword, IF NOT EXISTS
  skipped."
  [^String sql temp-name]
  (let [toks (x/tokenize sql)
        after-table (inc (long (first (keep-indexed
                                        (fn [i t]
                                          (when (and (= :word (:t t)) (= "table" (:fold t))) i))
                                        toks))))
        name-tok (some #(when (and (#{:word :qid} (:t %))
                                (not (contains? #{"if" "not" "exists"} (:fold %))))
                          %)
                   (drop after-table toks))]
    (str (subs sql 0 (:s name-tok)) (u/q-ident temp-name) (subs sql (:e name-tok)))))

(defn- rowid-alias-fold
  "The folded name of `table`'s INTEGER PRIMARY KEY column — the rowid
  alias — when one exists."
  [table]
  (when-not (:without-rowid? table)
    (let [pks (filterv #(pos? (:pk %)) (:columns table))]
      (when (and (= 1 (count pks))
              (= "integer" (some-> (:type (first pks)) x/fold-name)))
        (x/fold-name (:name (first pks)))))))

(defn- rebuild-copy-sql
  "The rebuild's INSERT...SELECT — column mapping strictly by name (ADR
  0008): declared non-generated columns that also exist live, in
  declared order; new columns take their declared defaults by omission;
  dropped columns are not copied. `renames` maps a declared column's
  folded name to the live column a rename directive binds it to (ADR
  0009) — the copy follows the rename. `rowid` copies explicitly when
  both sides are rowid tables and no copied INTEGER PRIMARY KEY column
  already aliases it (ADR 0010). Nil when nothing is copyable."
  [temp-name live-table declared-table renames]
  (let [live-name-by-fold (into {} (map (fn [c] [(x/fold-name (:name c)) (:name c)]))
                            (:columns live-table))
        live-of (fn [c] (let [f (x/fold-name (:name c))]
                          (or (get renames f) (get live-name-by-fold f))))
        shared (filterv #(and (nil? (:generated %)) (live-of %))
                 (:columns declared-table))
        alias-fold (rowid-alias-fold declared-table)
        rowid? (and (not (:without-rowid? live-table))
                 (not (:without-rowid? declared-table))
                 (not (and alias-fold
                        (some #(= alias-fold (x/fold-name (:name %))) shared))))
        insert-cols (concat (when rowid? ["rowid"]) (map (comp u/q-ident :name) shared))
        select-cols (concat (when rowid? ["rowid"]) (map (comp u/q-ident live-of) shared))]
    (when (seq insert-cols)
      (str "INSERT INTO " (u/q-ident temp-name) " (" (str/join ", " insert-cols)
        ") SELECT " (str/join ", " select-cols)
        " FROM " (u/q-ident (:name live-table))))))

(defn- sequence-restore-sqls
  "The statements restoring the AUTOINCREMENT counter (ADR 0010): lift
  the live table's `sqlite_sequence` row onto the temp table's before
  the old table drops, so the renamed table's next id stays greater
  than any id ever issued. The copy already advanced the temp counter
  to the copied maximum; these statements only raise it to the live
  counter when that is higher (or plant it when the copy was empty)."
  [temp-name live-name]
  (let [live (q-str live-name)
        temp (q-str temp-name)]
    [(str "UPDATE sqlite_sequence SET seq = (SELECT s.seq FROM sqlite_sequence AS s"
       " WHERE s.name = " live ") WHERE name = " temp
       " AND seq < (SELECT s.seq FROM sqlite_sequence AS s WHERE s.name = " live ")")
     (str "INSERT INTO sqlite_sequence (name, seq) SELECT " temp ", s.seq"
       " FROM sqlite_sequence AS s WHERE s.name = " live
       " AND NOT EXISTS (SELECT 1 FROM sqlite_sequence AS s2 WHERE s2.name = " temp ")")]))

(defn- rebuild-dependents
  "The surviving views and triggers the rebuild must drop and recreate
  around its rename: SQLite reparses every view and trigger during
  ALTER TABLE RENAME, so any survivor that lexically references the
  rebuilt table would fail the rename while the old table is gone.
  Returns `{:views [...] :triggers [...]}` — referencing views with
  their surviving triggers, plus standalone referencing triggers of
  other parents."
  [{:keys [views table-triggers]} tfold]
  (let [dep-views (filterv #(references? (:sql %) tfold) views)
        dep-view-folds (into #{} (map (comp x/fold-name :name)) dep-views)
        loose-view-triggers (for [v views
                                  :when (not (contains? dep-view-folds (x/fold-name (:name v))))
                                  trg (:triggers v)
                                  :when (references? (:sql trg) tfold)]
                              trg)
        loose-table-triggers (for [trg table-triggers
                                   :when (and (not= tfold (x/fold-name (:table trg)))
                                           (references? (:sql trg) tfold))]
                               trg)]
    {:views dep-views
     :triggers (vec (concat loose-view-triggers loose-table-triggers))}))

(defn- rebuild-stage-sqls
  "The rebuild's staging statements: create the declared shape under
  `temp`, INSERT...SELECT copy, and — when both sides autoincrement —
  restore the AUTOINCREMENT counter."
  [temp live-table declared-table renames]
  (let [autoincrement? (and (:autoincrement? live-table) (:autoincrement? declared-table))]
    (-> [(create-sql-under-temp-name (:sql (meta declared-table)) temp)]
      (into (keep identity [(rebuild-copy-sql temp live-table declared-table renames)]))
      (into (when autoincrement?
              (sequence-restore-sqls temp (:name live-table)))))))

(defn- rebuild-swap-sqls
  "The rebuild's swap statements: drop the dependents that would break
  the rename, drop the old table, rename the staged table into place —
  never rename-first."
  [temp live-table declared-table deps]
  (-> []
    (into (map #(str "DROP VIEW " (u/q-ident (:name %)))) (:views deps))
    (into (map #(str "DROP TRIGGER " (u/q-ident (:name %)))) (:triggers deps))
    (conj (str "DROP TABLE " (u/q-ident (:name live-table))))
    (conj (str "ALTER TABLE " (u/q-ident temp)
            " RENAME TO " (u/q-ident (:name declared-table))))))

(defn- rebuild-recreate-sqls
  "The rebuild's recreate statements: the declared table's indexes and
  triggers, then the dropped dependent views (each with its triggers)
  and standalone dependent triggers."
  [declared-table deps]
  (-> []
    (into (for [[_ idx] (sort-by key (:indexes declared-table))]
            (:sql (meta idx))))
    (into (for [[_ trg] (sort-by key (:triggers declared-table))]
            (:sql (meta trg))))
    (into (mapcat (fn [v] (cons (:sql v) (map :sql (:triggers v)))))
      (:views deps))
    (into (map :sql) (:triggers deps))))

(defn- rebuild-table-op
  "The composite :rebuild-table op for one changed table — SQLite's
  generalized 12-step ALTER TABLE compiled wholly at plan time, one op
  per rebuilt table (ADR 0006). The locked internal order: create the
  declared shape under a temp name, INSERT...SELECT copy, restore the
  AUTOINCREMENT counter, drop the dependents that would break the
  rename, drop the old table, rename the new one into place — never
  rename-first — then recreate the declared indexes and triggers and
  the dropped dependents."
  [opts tname serves live-table declared-table renames]
  (let [tfold (x/fold-name tname)
        temp (temp-rebuild-name opts (:name declared-table))
        deps (rebuild-dependents (:surviving-dependents opts) tfold)
        sql (-> (rebuild-stage-sqls temp live-table declared-table renames)
              (into (rebuild-swap-sqls temp live-table declared-table deps))
              (into (rebuild-recreate-sqls declared-table deps)))]
    (op [phase-change-tables tfold] :rebuild-table [:table tname] serves sql)))

;; ---------------------------------------------------------------------------
;; Gates (ADR 0008): data preconditions as plan-compiled sampling
;; SELECTs. A gate exists iff SQLite would reject or destroy existing
;; rows when the op runs and conformance cannot be decided from the
;; Snapshot. Gate SQL falls under the plan determinism contract; it
;; always queries the LIVE table and column spellings — gates run
;; before any op does.

(def ^:private gate-sample-limit
  ;; ADR 0008: the LIMIT controls result-set size, not scan cost —
  ;; zero rows pass, k < limit rows fail with the exact count, limit
  ;; rows fail as "limit or more"
  10)

(defn- gate [code path explanation sql]
  {:code code :path path :explanation explanation :sql sql
   :limit gate-sample-limit})

(defn- sampling-sql
  "The sampling SELECT over the live table: violating rows under
  `condition` (nil = every row violates), with the baked LIMIT."
  [live-tname condition]
  (str "SELECT * FROM " (u/q-ident live-tname)
    (when condition (str " WHERE " condition))
    " LIMIT " gate-sample-limit))

(defn- live-col-name
  "The live spelling of the column a declared column name pairs with:
  the rename directive's live source when one binds it, else the
  folded-name match. Nil when the column is new."
  [{:keys [live-table rename-map]} declared-name]
  (let [f (x/fold-name declared-name)]
    (or (get rename-map f)
      (some #(when (= f (x/fold-name (:name %))) (:name %))
        (:columns live-table)))))

(defn- declared-column
  "The declared table's column named `n`, by folded-name identity."
  [{:keys [declared-table]} n]
  (let [f (x/fold-name n)]
    (some #(when (= f (x/fold-name (:name %))) %)
      (:columns declared-table))))

(defn- default-kind
  "The gate-compilation classification of a column DEFAULT's verbatim
  spelling: :null when the column defaults to NULL (no default, or the
  NULL keyword); :constant for a literal whose value every copied row
  will share (number, string, blob, TRUE/FALSE); :opaque for any other
  expression — the planner never understands those (ADR 0015)."
  [spelling]
  (let [s (some-> spelling str/trim)]
    (cond
      (or (nil? s) (re-matches #"(?i)NULL" s)) :null
      (or (re-matches #"[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?" s)
        (re-matches #"(?i)[+-]?0x[0-9A-F]+" s)
        (re-matches #"'(?:[^']|'')*'" s)
        (re-matches #"(?i)X'(?:[0-9A-F]{2})*'" s)
        (re-matches #"(?i)TRUE|FALSE" s)) :constant
      :else :opaque)))

(defn- key-part
  "How a declared key column contributes to a gate over the LIVE
  table: `[:live name]` for its live column spelling; `[:const sql]` —
  its parenthesized constant DEFAULT — when the column is new, since
  the rebuild copy gives every existing row that constant (ADR 0015);
  `:null` when a new column defaults to NULL, so the key can neither
  collide nor dangle; nil when a new column's default is an opaque
  expression — undecidable at plan time, the documented gate exclusion
  (ADR 0015, the Frame remains the backstop)."
  [gctx declared-name]
  (if-let [lc (live-col-name gctx declared-name)]
    [:live lc]
    (let [d (:default (declared-column gctx declared-name))]
      (case (default-kind d)
        :constant [:const (str "(" (str/trim d) ")")]
        :null :null
        :opaque nil))))

(defn- key-part-sql
  "The bare SQL fragment of a `key-part` pair: the quoted live column,
  or the constant fragment verbatim."
  [[k v]]
  (if (= :live k) (u/q-ident v) v))

(defn- positional-group-const?
  "True when a :const key part's fragment is an integer literal —
  SQLite constant-folds those into positional GROUP BY references even
  parenthesized or signed (measured on 3.53), so they must stay out of
  GROUP BY. Grouping semantics are unchanged: a constant is one group
  either way (ADR 0015)."
  [[k v]]
  (and (= :const k)
    (some? (re-matches #"(?i)\(\s*[+-]?(?:\d+|0x[0-9A-F]+)\s*\)" v))))

(defn- key-part-fragments
  "The `{:select :bare :group}` SQL fragments of one key part —
  `:group` nil for integer-literal constants, which must stay out of
  GROUP BY (`positional-group-const?`)."
  [kp]
  (let [q (key-part-sql kp)]
    {:select q :bare q :group (when-not (positional-group-const? kp) q)}))

(defn- group-by-list
  "The GROUP BY list over the non-nil `:group` fragments of `parts`;
  \"NULL\" when every part must stay out — one group of every row,
  matching an all-constant key's semantics (ADR 0015)."
  [parts]
  (let [gs (keep :group parts)]
    (if (seq gs) (str/join ", " gs) "NULL")))

(defn- gateable-key-parts?
  "Whether key parts support a gate at all: none opaque (nil), none
  defaulting to NULL — those keys never collide nor dangle (ADR 0015)."
  [key-parts]
  (and (seq key-parts) (every? some? key-parts)
    (not-any? #{:null} key-parts)))

(defn- const-part-note
  "The explanation suffix naming the new key columns whose constant
  defaults a gate substitutes; nil when every part is live."
  [names parts]
  (let [news (into [] (comp (filter (comp #{:const} first second)) (map first))
               (map vector names parts))]
    (when (seq news)
      (str "; new column" (when (next news) "s") " " (str/join ", " news)
        (if (next news) " take" " takes") " a constant default on every copied row"))))

(defn- key-group-sql
  "The duplicate-key-group sampling SELECT: one row per violating key
  group with its COUNT, keys containing NULL excluded — SQLite
  uniqueness treats them as always distinct. `parts` are `{:select
  :bare :group}` SQL fragments per key part — `:group` nil for parts
  that must stay out of GROUP BY (integer-literal constants read as
  positional there); an all-nil key groups by NULL, one group of every
  row. `where` is a partial index's verbatim predicate."
  [live-tname parts where]
  (str "SELECT " (str/join ", " (map :select parts)) ", COUNT(*) AS \"sqm_count\""
    " FROM " (u/q-ident live-tname)
    " WHERE " (when where (str "(" where ") AND "))
    (str/join " AND " (map #(str (:bare %) " IS NOT NULL") parts))
    " GROUP BY " (group-by-list parts)
    " HAVING COUNT(*) > 1 LIMIT " gate-sample-limit))

(defn- column-gates [gctx entry]
  (let [t (:name (:live-table gctx))]
    (case (:kind entry)
      :changed
      (when (and (contains? (:facts entry) :not-null?)
              (:not-null? (:declared entry))
              (nil? (:generated (:declared entry))))
        (let [c (:name (:live entry))]
          [(gate :not-null (:path entry)
             (str "column " c " of table " t " becomes NOT NULL;"
               " a stored NULL there would be rejected")
             (sampling-sql t (str (u/q-ident c) " IS NULL")))]))
      :added
      ;; an explicit DEFAULT NULL is no default here — SQLite rejects
      ;; the addition exactly like the bare NOT NULL once rows exist;
      ;; an opaque default is assumed to fill the column (ADR 0015)
      (let [c (:declared entry)]
        (when (and (:not-null? c) (= :null (default-kind (:default c))) (nil? (:generated c)))
          [(gate :empty-table (:path entry)
             (str "column " (:name c) " is added NOT NULL with no default;"
               " table " t " must be empty")
             (sampling-sql t nil))]))
      nil)))

(defn- check-constraint-gates [gctx entry]
  (when (contains? #{:added :changed} (:kind entry))
    (let [t (:name (:live-table gctx))
          expr (:expr (:declared entry))]
      ;; the routes verify differently (measured on 3.53): in-place
      ;; ALTER TABLE ADD CHECK validation rejects rows where the
      ;; expression is NULL, while the rebuild's INSERT copy applies
      ;; insert-time CHECK semantics — NULL passes
      (if (:alter-validation? gctx)
        [(gate :check (:path entry)
           (str "table " t " adds CHECK (" expr ");"
             " rows where the expression is false or NULL would be rejected")
           (sampling-sql t (str "NOT (" expr ") OR (" expr ") IS NULL")))]
        [(gate :check (:path entry)
           (str "table " t " adds CHECK (" expr ");"
             " rows where the expression is false would be rejected")
           (sampling-sql t (str "NOT (" expr ")")))]))))

(defn- index-gates [gctx entry]
  (when (and (contains? #{:added :changed} (:kind entry))
          (:unique? (:declared entry)))
    (let [t (:name (:live-table gctx))
          idx (:declared entry)
          key-parts (mapv (fn [{:keys [name expr]}]
                            (if name (key-part gctx name) [:expr (str "(" expr ")")]))
                      (:columns idx))]
      (when (gateable-key-parts? key-parts)
        (let [parts (mapv (fn [{:keys [collate]} kp]
                            (let [f (key-part-fragments kp)]
                              (cond-> f
                                (and collate (:group f))
                                (update :group str " COLLATE " collate))))
                      (:columns idx) key-parts)]
          [(gate :unique (:path entry)
             (str "unique index " (peek (:path entry)) " on table " t ";"
               " duplicate key groups would be rejected"
               (const-part-note (mapv :name (:columns idx)) key-parts))
             (key-group-sql t parts (:where idx)))])))))

(defn- foreign-key-gates [gctx entry]
  ;; ADR 0008: carried even though the Frame's foreign_key_check would
  ;; catch orphans at COMMIT — the gate reports before any work runs.
  ;; Action/match/deferrability changes carry no row precondition.
  (when (or (= :added (:kind entry))
          (and (= :changed (:kind entry))
            (some (:facts entry) [:columns :ref-table :ref-columns])))
    (let [{:keys [live-table live-snapshot declared-snapshot]} gctx
          t (:name live-table)
          fk (:declared entry)
          key-parts (mapv #(key-part gctx %) (:columns fk))
          parent-fold (x/fold-name (:ref-table fk))
          find-parent (fn [snap]
                        (some (fn [[k v]] (when (= parent-fold (x/fold-name k)) v))
                          (:tables snap)))
          parent-live (find-parent live-snapshot)
          ref-cols (if (and (seq (:ref-columns fk)) (every? some? (:ref-columns fk)))
                     (:ref-columns fk)
                     ;; an implicit REFERENCES targets the parent's PK
                     (or (get-in (find-parent declared-snapshot) [:primary-key :columns])
                       (get-in parent-live [:primary-key :columns])))]
      ;; a NULL-defaulted new child column keeps the key un-enforced
      (when (and (gateable-key-parts? key-parts)
              (= (count ref-cols) (count key-parts)))
        (let [not-nulls (str/join " AND "
                          (keep (fn [[k v]] (when (= :live k)
                                              (str (u/q-ident v) " IS NOT NULL")))
                            key-parts))
              lookup (when parent-live
                       (str "NOT EXISTS (SELECT 1 FROM " (u/q-ident (:name parent-live))
                         " AS \"sqm_parent\" WHERE "
                         (str/join " AND "
                           (map (fn [rc [k :as kp]]
                                  (str "\"sqm_parent\"." (u/q-ident rc) " = "
                                    (if (= :live k)
                                      (str (u/q-ident t) "." (key-part-sql kp))
                                      (key-part-sql kp))))
                             ref-cols key-parts))
                         ")"))
              ;; a parent absent live means every full child key is an orphan
              condition (cond
                          (and lookup (seq not-nulls)) (str not-nulls " AND " lookup)
                          lookup lookup
                          (seq not-nulls) not-nulls
                          :else nil)]
          [(gate :foreign-key (:path entry)
             (str "rows of table " t " referencing " (:ref-table fk)
               " must have a matching parent row"
               (const-part-note (:columns fk) key-parts))
             (sampling-sql t condition))])))))

(defn- sql-sign-stripped
  "SQL for text expression `x` with one leading + or - removed."
  [x]
  (str "CASE WHEN substr(" x ", 1, 1) IN ('+', '-') THEN substr(" x ", 2)"
    " ELSE " x " END"))

(defn- sql-numeric-text-condition
  "Predicate SQL: text expression `x` is a numeric literal SQLite's
  STRICT tables accept (measured on 3.53.2) — optional sign, digits
  with at most one decimal point and at least one digit, optional
  signed all-digit exponent. Callers trim the surrounding ASCII
  whitespace STRICT tolerates before this sees the value."
  [x]
  (let [mantissa (fn [m]
                   (let [sm (sql-sign-stripped m)
                         d (str "replace(" sm ", '.', '')")]
                     (str d " <> '' AND " d " NOT GLOB '*[^0-9]*'"
                       " AND length(" sm ") - length(" d ") <= 1")))
        epos (str "instr(upper(" x "), 'E')")
        exponent (let [u (sql-sign-stripped (str "substr(" x ", " epos " + 1)"))]
                   (str u " <> '' AND " u " NOT GLOB '*[^0-9]*'"))]
    (str "CASE WHEN " epos " = 0 THEN " (mantissa x)
      " ELSE (" (mantissa (str "substr(" x ", 1, " epos " - 1)"))
      ") AND (" exponent ") END")))

(defn- sql-lossless-int64-condition
  "Predicate SQL: numeric text `x` converts to int64 losslessly, the
  way SQLite's STRICT INTEGER columns demand (measured on 3.53.2): a
  pure-digit spelling by textual boundary comparison against ±2^63 —
  '9223372036854775807' converts, '9223372036854775808' does not — and
  a decimal-point or exponent spelling through the double it denotes,
  which loses '9223372036854775806.0' to rounding."
  [x]
  (let [z (str "ltrim(" (sql-sign-stripped x) ", '0')")]
    (str "CASE WHEN instr(" x ", '.') = 0 AND instr(upper(" x "), 'E') = 0"
      " THEN length(" z ") < 19 OR (length(" z ") = 19 AND " z
      " <= CASE WHEN substr(" x ", 1, 1) = '-'"
      " THEN '9223372036854775808' ELSE '9223372036854775807' END)"
      " ELSE CAST(" x " AS REAL) = CAST(CAST(" x " AS REAL) AS INTEGER) END")))

(defn- sql-ws-trimmed
  "SQL for `q` with the surrounding ASCII whitespace SQLite's text-to-
  number conversion skips (space, \\t \\n \\v \\f \\r — not NBSP)
  trimmed off."
  [q]
  (str "trim(" q ", ' ' || char(9,10,11,12,13))"))

(defn- strict-violation-condition
  "The per-column violation predicate for a STRICT conversion, over the
  live column's stored values: the storage classes SQLite would reject
  after applying the usual affinity coercions — numeric text and
  integral reals convert losslessly and pass. Nil for ANY (nothing to
  reject). The text branches replicate SQLite's acceptance rule
  exactly (ADR 0015): surrounding ASCII whitespace trims off, the rest
  must be a well-formed numeric literal, and an INTEGER column
  additionally demands a lossless int64."
  [q ftype]
  (let [t (sql-ws-trimmed q)]
    (case ftype
      ("int" "integer")
      (str "CASE typeof(" q ")"
        " WHEN 'null' THEN 0 WHEN 'integer' THEN 0"
        " WHEN 'real' THEN " q " <> CAST(" q " AS INTEGER)"
        " WHEN 'text' THEN NOT ((" (sql-numeric-text-condition t) ")"
        " AND (" (sql-lossless-int64-condition t) "))"
        " ELSE 1 END")
      "real"
      (str "CASE typeof(" q ")"
        " WHEN 'null' THEN 0 WHEN 'integer' THEN 0 WHEN 'real' THEN 0"
        " WHEN 'text' THEN NOT (" (sql-numeric-text-condition t) ")"
        " ELSE 1 END")
      "text" (str "typeof(" q ") = 'blob'")
      "blob" (str "typeof(" q ") NOT IN ('blob', 'null')")
      nil)))

(defn- table-gates
  "The gates a changed table-level entry contributes: PK added/changed,
  STRICT conversion, WITHOUT ROWID conversion. NULL PK values are
  gated only where SQLite rejects them — WITHOUT ROWID or STRICT
  shapes without an INTEGER PRIMARY KEY alias; a plain rowid table
  tolerates NULL PK values (legacy quirk) and an alias auto-assigns."
  [gctx {:keys [kind facts] :as entry}]
  (when (= :changed kind)
    (let [{:keys [live-table declared-table]} gctx
          t (:name live-table)
          pk-cols (get-in declared-table [:primary-key :columns])
          alias? (some? (rowid-alias-fold declared-table))]
      (vec
        (concat
          (when (and (contains? facts :primary-key) (seq pk-cols))
            (let [key-parts (mapv #(key-part gctx %) pk-cols)]
              ;; a new NULL-defaulted PK column never collides: a plain
              ;; rowid table stores the NULLs as distinct keys (legacy
              ;; quirk) and STRICT / WITHOUT ROWID shapes mark the
              ;; declared column NOT NULL, so the column-level gates
              ;; guard those; a new alias column auto-assigns fresh
              ;; rowids even over a constant default (ADR 0015)
              (when (and (gateable-key-parts? key-parts)
                      (not (and alias? (some #{:const} (map first key-parts)))))
                (let [qs (mapv key-part-sql key-parts)
                      key-list (str/join ", " qs)
                      row (if (= 1 (count qs)) (first qs) (str "(" key-list ")"))
                      live-qs (into [] (comp (filter (comp #{:live} first))
                                         (map (comp u/q-ident second)))
                                key-parts)
                      null-enforced? (and (not alias?) (seq live-qs)
                                       (or (:without-rowid? declared-table)
                                         (:strict? declared-table)))
                      group-list (group-by-list (map key-part-fragments key-parts))
                      dup (str row " IN (SELECT " key-list " FROM " (u/q-ident t)
                            " WHERE " (str/join " AND " (map #(str % " IS NOT NULL") qs))
                            " GROUP BY " group-list " HAVING COUNT(*) > 1)")]
                  [(gate :primary-key (:path entry)
                     (str "primary key (" (str/join ", " pk-cols) ") of table " t
                       "; duplicate keys would be rejected"
                       (const-part-note pk-cols key-parts))
                     (sampling-sql t
                       (if null-enforced?
                         (str (str/join " OR " (map #(str % " IS NULL") live-qs)) " OR " dup)
                         dup)))]))))
          (when (and (contains? facts :strict?) (:strict? declared-table))
            (let [conds (keep (fn [dc]
                                (when (nil? (:generated dc))
                                  (when-let [lc (live-col-name gctx (:name dc))]
                                    (strict-violation-condition (u/q-ident lc)
                                      (some-> (:type dc) x/fold-name)))))
                          (:columns declared-table))]
              (when (seq conds)
                [(gate :strict (:path entry)
                   (str "table " t " converts to STRICT;"
                     " every stored value must match its column's declared type")
                   (sampling-sql t (str/join " OR " conds)))])))
          (when (and (contains? facts :without-rowid?) (:without-rowid? declared-table))
            (let [parts (reduce (fn [acc n]
                                  (if-let [lc (live-col-name gctx n)]
                                    (conj acc (str (u/q-ident lc) " IS NULL"))
                                    ;; a new PK column defaulting to NULL leaves
                                    ;; every copied row NULL there; a constant
                                    ;; or opaque default fills it (ADR 0015)
                                    (if (= :null (default-kind
                                                   (:default (declared-column gctx n))))
                                      (reduced :all)
                                      acc)))
                          [] pk-cols)
                  explanation (str "table " t " converts to WITHOUT ROWID;"
                                " primary-key columns must be non-NULL")]
              (cond
                (= :all parts) [(gate :without-rowid (:path entry) explanation
                                  (sampling-sql t nil))]
                (seq parts) [(gate :without-rowid (:path entry) explanation
                               (sampling-sql t (str/join " OR " parts)))]))))))))

(defn- unique-gates [gctx entry]
  (when (contains? #{:added :changed} (:kind entry))
    (let [t (:name (:live-table gctx))
          cols (:columns (:declared entry))
          key-parts (mapv #(key-part gctx %) cols)]
      (when (gateable-key-parts? key-parts)
        [(gate :unique (:path entry)
           (str "unique key (" (str/join ", " cols) ") of table " t ";"
             " duplicate key groups would be rejected"
             (const-part-note cols key-parts))
           (key-group-sql t (mapv key-part-fragments key-parts) nil))]))))

(defn- entry-gates
  "The Gates one routed unit's entry contributes, compiled from the
  gate context (live and declared table values, the resolved rename
  map, and both Snapshots)."
  [gctx {:keys [path] :as entry}]
  (let [seg (when (> (count path) 2) (nth path 2))]
    (cond
      (= 2 (count path)) (table-gates gctx entry)
      (= :column seg) (column-gates gctx entry)
      (= :check seg) (check-constraint-gates gctx entry)
      (= :unique seg) (unique-gates gctx entry)
      (= :foreign-key seg) (foreign-key-gates gctx entry)
      (= :index seg) (index-gates gctx entry)
      :else nil)))

(def ^:private gate-carrying-kinds
  ;; the in-place op that introduces the constraint its unit's gates
  ;; guard; every other gate rides a :rebuild-table
  #{:set-not-null :add-check :create-index :add-column})

(defn- attach-unit-gates
  "Attach `gates` to the first op of a routed unit whose kind can carry
  them (`gate-carrying-kinds`), leaving every other op untouched."
  [gates ops]
  (let [ops (vec ops)]
    (if-let [gs (seq gates)]
      (if-let [i (first (keep-indexed
                          (fn [i o] (when (gate-carrying-kinds (get-in o [:op :kind])) i))
                          ops))]
        (update-in ops [i :op] assoc :gates (vec gs))
        ops)
      ops)))

;; ---------------------------------------------------------------------------
;; Changed-table planning (fine-grained entries, ADR 0006 selection rule)

(defn- table-context
  "The live and declared table values for a changed table, from the
  Snapshots supplied in opts. Planning a changed table without them is
  malformed input."
  [snapshot side tname]
  (or (some (fn [[k v]] (when (= (x/fold-name k) (x/fold-name tname)) v))
        (:tables snapshot))
    (throw (ex-info (str "planning changed table " tname " requires the "
                      (name side) " Snapshot in opts")
             {:sqlite-migrate/error :malformed-input
              :missing (case side :live :live-snapshot :declared-snapshot)
              :table tname}))))

(defn- entry-what
  "A human phrase naming an entry, for Refusal explanations."
  [{:keys [kind path facts]}]
  (let [obj (if (= 2 (count path))
              (str "table " (second path))
              (str (name (nth path 2)) " " (peek path) " of table " (second path)))]
    (case kind
      :added (str "adding " obj)
      :removed (str "removing " obj)
      :changed (str "changing " obj (when facts (str " (" (str/join ", " (sort (map str facts))) ")"))))))

(defn- route-renamed-column
  "Route one fused column-rename entry (ADR 0009): a changed column
  whose sides differ in name. Name-only fuses to an in-place RENAME
  COLUMN; any other differing fact, a colliding rename set (ctx
  `:rename-collision?` — sequential in-place steps would collide), or
  a pre-3.25 target collapses it onto the rebuild path."
  [capabilities tname ctx entry]
  (let [{:keys [from to]} (:rename entry)]
    (if (or (seq (:facts entry))
          (:rename-collision? ctx)
          (not (supports? capabilities v-rename-column)))
      {:rebuild []}
      {:ops [(op [phase-change-tables (x/fold-name tname) sub-alter-column (x/fold-name from)]
               :rename-column (:path entry) #{(:path entry)}
               [(str "ALTER TABLE " (u/q-ident tname) " RENAME COLUMN "
                  (u/q-ident from) " TO " (u/q-ident to))])]})))

(defn- route-table-entry
  "Route one entry of a changed regular table. `ctx` carries the live
  and declared table values plus the sets the intermediate-state checks
  need."
  [capabilities tname ctx entry]
  (let [seg (when (> (count (:path entry)) 2) (nth (:path entry) 2))]
    (cond
      (:rename entry) (route-renamed-column capabilities tname ctx entry)

      (= 2 (count (:path entry))) ; table-level facts: STRICT, WITHOUT ROWID, order, PK, AUTOINCREMENT
      {:rebuild (unsupported-object-refusals capabilities (:declared entry))}

      (= :column seg)
      (case (:kind entry)
        :added (let [unsupported (unsupported-object-refusals capabilities (:declared entry))]
                 (if (seq unsupported)
                   {:refuse unsupported}
                   (route-added-column capabilities tname entry (:appendable? ctx))))
        :removed
        (let [col (:live entry)
              col-fold (x/fold-name (:name col))
              in-place? (and (supports? capabilities v-drop-column)
                          (droppable-in-place? tname (:live-table ctx) col-fold ctx))
              ;; an authorized drop is intent supplied (ADR 0009):
              ;; the :destructive-drop refusal is lifted, the route
              ;; stands on its own merits
              destructive? (and (not= :virtual (get-in col [:generated :storage]))
                             (not (contains? (:authorized-col-drops ctx) col-fold)))]
          (cond
            (not in-place?) {:rebuild (when destructive?
                                        [(destructive-refusal (entry-what entry))])}
            destructive? {:needs-intent [(destructive-refusal (entry-what entry))]}
            :else {:ops [(op [phase-change-tables (x/fold-name tname) sub-drop-column
                              (get (:drop-order ctx) col-fold)]
                           :drop-column (:path entry) #{(:path entry)}
                           [(str "ALTER TABLE " (u/q-ident tname)
                              " DROP COLUMN " (u/q-ident (:name col)))])]}))
        :changed (route-changed-column capabilities tname entry))

      (= :check seg) (route-check capabilities tname entry)
      (= :index seg) (route-index entry tname)
      (= :trigger seg) (route-trigger entry tname)
      ;; :unique and :foreign-key constraints have no in-place ALTER form
      :else {:rebuild []})))

(defn- changed-table-ctx
  "The intermediate-state context for routing a changed table's entries:
  which indexes and checks survive the plan's earlier drops, which
  columns are being dropped (and in what order), and whether the added
  columns form a declared-order suffix."
  [capabilities entries live-table declared-table]
  (let [seg-of (fn [e] (when (> (count (:path e)) 2) (nth (:path e) 2)))
        dropped-index-folds (into #{}
                              (comp (filter #(and (= :index (seg-of %))
                                               (#{:removed :changed} (:kind %))))
                                (map (comp x/fold-name entry-name)))
                              entries)
        droppable-check? (fn [e] (and (supports? capabilities v-alter-constraint)
                                   (:name (:live e))))
        dropped-check-live (into #{}
                             (comp (filter #(and (= :check (seg-of %))
                                              (#{:removed :changed} (:kind %))
                                              (droppable-check? %)))
                               (map :live))
                             entries)
        removed-cols (into [] (comp (filter #(and (= :column (seg-of %))
                                               (= :removed (:kind %))))
                                (map :live))
                       entries)
        added-folds (into #{} (comp (filter #(and (= :column (seg-of %))
                                               (= :added (:kind %))))
                                (map (comp x/fold-name entry-name)))
                      entries)
        drop-order (zipmap (map (comp x/fold-name :name)
                             (order-dropped-columns removed-cols))
                     (range))]
    {:live-table live-table
     :appendable? (appended-suffix? declared-table added-folds)
     :retained-indexes (vec (for [[nm idx] (sort-by key (:indexes live-table))
                                  :when (not (contains? dropped-index-folds (x/fold-name nm)))]
                              idx))
     :retained-checks (vec (remove (set dropped-check-live) (:checks live-table)))
     :dropped-col-folds (into #{} (map (comp x/fold-name :name)) removed-cols)
     :drop-order drop-order}))

(defn- declared-position
  "Patch each :add-column op's sort key with its column's position in
  the declared column order, so appended columns emit in declared
  order."
  [declared-table routed-ops]
  (let [pos (into {} (map-indexed (fn [i c] [(x/fold-name (:name c)) i]))
              (:columns declared-table))]
    (mapv (fn [{:keys [order] :as o}]
            (if (= :add-column (get-in o [:op :kind]))
              (assoc o :order (conj (pop order) (pos (x/fold-name (peek (get-in o [:op :path]))))))
              o))
      routed-ops)))

(defn- surviving-dependents
  "The live views and triggers that survive the plan's phase-1 drops —
  the objects a drop-column must stay legal against and a rebuild's
  rename must not orphan. `:views` carries each surviving view with
  its surviving triggers; `:table-triggers` each surviving trigger of
  a table parent — names and stored CREATE sql. A :changed object's
  recreate lands only in phase 5, so a dropped view excludes its
  triggers too."
  [live-snapshot entries]
  (let [dropped? (fn [e] (contains? #{:removed :changed} (:kind e)))
        dropped-views (into #{}
                        (comp (filter #(and (= :view (first (:path %)))
                                         (= 2 (count (:path %)))
                                         (dropped? %)))
                          (map (comp x/fold-name second :path)))
                        entries)
        dropped-triggers (into #{}
                           (comp (filter #(and (= :trigger (nth (:path %) 2 nil))
                                            (dropped? %)))
                             (map (comp x/fold-name peek :path)))
                           entries)
        surviving-triggers (fn [obj]
                             (vec (for [[nm trg] (sort-by key (:triggers obj))
                                        :when (not (contains? dropped-triggers (x/fold-name nm)))]
                                    {:name nm :sql (:sql (meta trg))})))]
    {:views (vec (for [[nm v] (sort-by key (:views live-snapshot))
                       :when (not (contains? dropped-views (x/fold-name nm)))]
                   {:name nm :sql (:sql (meta v)) :triggers (surviving-triggers v)}))
     :table-triggers (vec (for [[tn t] (sort-by key (:tables live-snapshot))
                                trg (surviving-triggers t)]
                            (assoc trg :table tn)))}))

(defn- surviving-referencer-sqls
  "The stored CREATE sql of every surviving dependent — the flat text
  view the drop-column legality check reads."
  [{:keys [views table-triggers]}]
  (into []
    (remove nil?)
    (concat
      (map :sql table-triggers)
      (map :sql views)
      (mapcat #(map :sql (:triggers %)) views))))

(defn- rename-table-op
  "The in-place table rename (ADR 0009) — ordered before every other
  phase-3 op of the fused table, so later in-place ops target the
  declared name."
  [live-name declared-name serves]
  (op [phase-change-tables (x/fold-name declared-name) sub-rename-table]
    :rename-table [:table live-name] serves
    [(str "ALTER TABLE " (u/q-ident live-name) " RENAME TO " (u/q-ident declared-name))]))

(defn- active-column-renames
  "The subset of one table's :rename-column claims that resolve
  simultaneously against the live and declared column sets (ADR 0009).
  A candidate binds a live `from` to a declared `to`; it stays active
  only while its `from` (when the declaration still has it) is claimed
  as another active rename's target and its `to` (when live still has
  it) is claimed as another active rename's source — the greatest such
  set, so swaps and chains resolve together while a half-match drops
  out inert."
  [claims live-folds declared-folds]
  (loop [active (filterv (fn [{:keys [from-fold to-fold]}]
                           (and (not= from-fold to-fold)
                             (contains? live-folds from-fold)
                             (contains? declared-folds to-fold)))
                  claims)]
    (let [sources (into #{} (map :from-fold) active)
          targets (into #{} (map :to-fold) active)
          keep? (fn [{:keys [from-fold to-fold]}]
                  (and (or (not (contains? declared-folds from-fold))
                         (contains? targets from-fold))
                    (or (not (contains? live-folds to-fold))
                      (contains? sources to-fold))))
          pruned (filterv keep? active)]
      (if (= pruned active) active (recur pruned)))))

(defn- fuse-column-entries
  "Fuse one table's column entries under its resolved :rename-column
  claims (ADR 0009). The entries a rename claims — its live `from`
  side, its declared `to` side — are consumed into one synthetic
  :changed entry per rename, live and declared sides differing in name
  (`:rename {:from :to}`, facts compared name-blind); every other
  entry passes through untouched. `require-anchor?` (identity-paired
  tables) keeps a rename inert unless some entry involves one of its
  columns, so a directive never plans against a drift the Diff does
  not show. Returns `{:units [{:entry e :orig [entries]}]
  :rename-directives [...] :rename-map {declared-fold live-name}
  :collision? bool}` — `:collision?` when a rename target is still a
  live column name, forcing the rebuild path (swaps, chains)."
  [tname entries live-table declared-table claims require-anchor?]
  (let [col-entry? (fn [e] (and (= 4 (count (:path e))) (= :column (nth (:path e) 2))))
        efold (fn [e] (x/fold-name (peek (:path e))))
        col-folds (fn [t] (into #{} (map (comp x/fold-name :name)) (:columns t)))
        live-folds (col-folds live-table)
        declared-folds (col-folds declared-table)
        normalized (mapv (fn [dv] {:directive dv
                                   :from-fold (x/fold-name (:from dv))
                                   :to-fold (x/fold-name (:to dv))})
                     claims)
        active (active-column-renames normalized live-folds declared-folds)
        effective (if require-anchor?
                    (let [live-subjects (into #{} (comp (filter col-entry?)
                                                    (filter #(#{:removed :changed} (:kind %)))
                                                    (map efold))
                                          entries)
                          declared-subjects (into #{} (comp (filter col-entry?)
                                                        (filter #(#{:added :changed} (:kind %)))
                                                        (map efold))
                                              entries)]
                      (filterv #(or (contains? live-subjects (:from-fold %))
                                  (contains? declared-subjects (:to-fold %)))
                        active))
                    active)
        sources (into #{} (map :from-fold) effective)
        targets (into #{} (map :to-fold) effective)
        consumed? (fn [e]
                    (and (col-entry? e)
                      (let [f (efold e)]
                        (case (:kind e)
                          :removed (contains? sources f)
                          :added (contains? targets f)
                          :changed (or (contains? sources f) (contains? targets f))))))
        col-of (fn [t] (into {} (map (fn [c] [(x/fold-name (:name c)) c])) (:columns t)))
        lcols (col-of live-table)
        dcols (col-of declared-table)
        synthetic (mapv (fn [{:keys [from-fold to-fold]}]
                          (let [lc (lcols from-fold)
                                dc (dcols to-fold)]
                            {:entry {:kind :changed
                                     :path [:table tname :column (:name lc)]
                                     :live lc
                                     :declared dc
                                     :facts (not-empty (d/fused-column-facts lc dc))
                                     :rename {:from (:name lc) :to (:name dc)}}
                             :orig (filterv (fn [e]
                                              (and (consumed? e)
                                                (let [f (efold e)]
                                                  (or (and (#{:removed :changed} (:kind e)) (= f from-fold))
                                                    (and (#{:added :changed} (:kind e)) (= f to-fold))))))
                                     entries)}))
                    effective)]
    {:units (into (into [] (comp (remove consumed?) (map (fn [e] {:entry e :orig [e]}))) entries)
              synthetic)
     :rename-directives (mapv :directive effective)
     :rename-map (into {} (map (fn [{:keys [from-fold to-fold]}]
                                 [to-fold (:name (lcols from-fold))]))
                   effective)
     :collision? (boolean (some #(contains? live-folds (:to-fold %)) effective))}))

(defn- plan-table-changes
  "The shared core planning one changed table's (or fused pair's)
  entries under the resolved directive claims: fuse the column
  renames, route every unit, then apply ADR 0006's selection rule —
  every change achievable in place plans in place; otherwise the whole
  change set collapses into one :rebuild-table (never mix in-place and
  rebuild for one table), staying unhandled when the `:rebuild?`
  capability is off or a blocker rides the change set. `fused` (nil
  for an identity-paired table) carries a matched :rename-table
  directive with the removed and added whole-table entries: ops then
  lead with the :rename-table op and target the declared name, every
  op serves both whole-table entries, and every refusal rides on both.
  Returns `{:ops [...] :unhandled {entry refusals} :used
  [directives]}`."
  [capabilities opts dctx tname entries live-table declared-table fused]
  (let [lt-fold (x/fold-name (:name live-table))
        serves-pair (when fused #{(:path (:removed fused)) (:path (:added fused))})
        {:keys [units rename-directives rename-map collision?]}
        (fuse-column-entries tname entries live-table declared-table
          (get (:column-renames dctx) lt-fold) (nil? fused))
        authorized (get (:drop-columns dctx) lt-fold)
        removed-folds (into #{} (comp (map :entry)
                                  (filter #(and (= 4 (count (:path %)))
                                             (= :column (nth (:path %) 2))
                                             (= :removed (:kind %))))
                                  (map #(x/fold-name (peek (:path %)))))
                        units)
        used (-> (vec (when fused [(:directive fused)]))
               (into rename-directives)
               (into (keep (fn [[f dv]] (when (contains? removed-folds f) dv)))
                 authorized))
        ctx (assoc (changed-table-ctx capabilities (mapv :entry units) live-table declared-table)
              :surviving-sqls (:surviving-sqls opts)
              :rename-collision? collision?
              :authorized-col-drops (set (keys authorized)))
        gctx {:live-table live-table
              :declared-table declared-table
              :rename-map rename-map
              :live-snapshot (:live-snapshot opts)
              :declared-snapshot (:declared-snapshot opts)}
        routed (mapv (fn [u] (assoc u :route (route-table-entry capabilities tname ctx (:entry u))))
                 units)
        collapse? (boolean (some #(contains? (:route %) :rebuild) routed))
        serves-of (fn [u] (or serves-pair (into #{} (map :path) (:orig u))))
        unhandled-for (fn [u refusals]
                        (let [rs (vec refusals)]
                          (if fused
                            {(:removed fused) rs (:added fused) rs}
                            (zipmap (:orig u) (repeat rs)))))
        merge-unh (fn [maps]
                    (apply merge-with (fn [a b] (vec (distinct (concat a b)))) {} maps))]
    (assoc
      (cond
        (not collapse?)
        {:ops (declared-position declared-table
                (into (vec (when fused
                             [(rename-table-op (:name live-table) tname serves-pair)]))
                  (mapcat (fn [u]
                            (attach-unit-gates
                              (entry-gates (assoc gctx :alter-validation? true) (:entry u))
                              (map #(assoc-in % [:op :serves] (serves-of u))
                                (:ops (:route u))))))
                  routed))
         :unhandled (merge-unh
                      (keep (fn [{:keys [route] :as u}]
                              (when-not (:ops route)
                                (unhandled-for u (concat (:refuse route) (:needs-intent route)))))
                        routed))}

        (not (:rebuild? capabilities))
        {:ops []
         :unhandled (merge-unh
                      (map (fn [{:keys [route] :as u}]
                             ;; a no-route (:refuse) entry keeps only its own
                             ;; refusals — a rebuild would not help it either
                             (unhandled-for u
                               (if (:refuse route)
                                 (:refuse route)
                                 (concat (:rebuild route) (:needs-intent route)
                                   [(rebuild-disabled-refusal (entry-what (:entry u)))]))))
                        routed))}

        :else
        ;; ADR 0007: with rebuilds allowed an older target just rebuilds
        ;; more — no refusal, unless a blocker rides the change set: a
        ;; destructive drop still awaiting intent, or a declared shape
        ;; the target version cannot hold (the rebuild would create it).
        (let [blockers (into (vec (unsupported-object-refusals capabilities declared-table))
                         (mapcat (fn [{:keys [route]}]
                                   (concat (:refuse route) (:rebuild route) (:needs-intent route))))
                         routed)]
          (if (seq blockers)
            {:ops []
             :unhandled (merge-unh
                          (map (fn [{:keys [route] :as u}]
                                 (unhandled-for u
                                   (distinct (concat (:refuse route) (:rebuild route)
                                               (:needs-intent route) blockers))))
                            routed))}
            {:ops [(let [gates (into [] (mapcat #(entry-gates gctx (:entry %))) units)]
                     (cond-> (rebuild-table-op opts
                               (if fused (:name live-table) tname)
                               (or serves-pair (into #{} (map :path) entries))
                               live-table declared-table rename-map)
                       (seq gates) (update :op assoc :gates gates)))]
             :unhandled {}})))
      :used used)))

(defn- plan-changed-table
  "Plan the entries of one changed regular table under the resolved
  directive claims — see `plan-table-changes` for the selection rule
  and return shape."
  [capabilities opts dctx tname entries]
  (plan-table-changes capabilities opts dctx tname entries
    (table-context (:live-snapshot opts) :live tname)
    (table-context (:declared-snapshot opts) :declared tname)
    nil))

;; ---------------------------------------------------------------------------
;; Fused table renames (ADR 0009): a matched :rename-table directive
;; turns a removed/added whole-table pair into a changed object whose
;; sides differ in name

(defn- plan-fused-table
  "Plan one fused table-rename pair (ADR 0009): the removed and added
  whole-table entries become a changed object whose sides differ in
  name, compared fine-grained and fed through `plan-table-changes` —
  in place the name change is a :rename-table op ordered first, and a
  collapse rides one :rebuild-table whose copy maps the live name (and
  any renamed columns) to the declared ones."
  [capabilities opts dctx removed added directive]
  (let [live-table (table-context (:live-snapshot opts) :live (second (:path removed)))
        declared-table (table-context (:declared-snapshot opts) :declared (second (:path added)))]
    (plan-table-changes capabilities opts dctx (:name declared-table)
      (d/fused-entries live-table declared-table)
      live-table declared-table
      {:directive directive :removed removed :added added})))

;; ---------------------------------------------------------------------------
;; Whole-object entries (tables and views present on one side, virtual
;; tables, changed views)

(defn- create-table-ops
  "The ops realizing one whole-table :added entry: the CREATE TABLE in
  phase 4 plus creates for its nested indexes and triggers in phase 5,
  all serving the table entry."
  [entry]
  (let [t (:declared entry)
        tfold (x/fold-name (:name t))
        serves #{(:path entry)}]
    (into [(op [phase-create-tables tfold] :create-table (:path entry) serves [(:sql t)])]
      (concat
        (for [[nm idx] (sort-by key (:indexes t))]
          (create-secondary-op 0 :create-index (conj (:path entry) :index nm)
            serves tfold nm (:sql idx)))
        (for [[nm trg] (sort-by key (:triggers t))]
          (create-secondary-op 2 :create-trigger (conj (:path entry) :trigger nm)
            serves tfold nm (:sql trg)))))))

(defn- plan-table-group
  "Plan one table's entries: a whole-table entry stands alone; anything
  else is a changed table planned fine-grained. `dctx` carries the
  resolved directive claims (ADR 0009): a whole-table removal plans as
  a phase-2 :drop-table op when a :drop-table directive authorizes it."
  [capabilities opts dctx tname entries]
  (let [whole (some #(when (= 2 (count (:path %))) %) entries)]
    (cond
      (and whole (= :added (:kind whole)))
      (let [unsupported (unsupported-object-refusals capabilities (:declared whole))]
        (if (seq unsupported)
          {:ops [] :unhandled {whole (vec unsupported)}}
          {:ops (create-table-ops whole) :unhandled {}}))

      (and whole (= :removed (:kind whole)))
      (if-let [directive (get (:drop-tables dctx) (x/fold-name tname))]
        {:ops [(op [phase-drop-tables (x/fold-name tname)]
                 :drop-table (:path whole) #{(:path whole)}
                 [(str "DROP TABLE " (u/q-ident tname))])]
         :unhandled {}
         :used [directive]}
        {:ops [] :unhandled {whole [(destructive-refusal (str "table " tname))]}})

      (and whole (or (:virtual? (:live whole)) (:virtual? (:declared whole))))
      {:ops []
       :unhandled {whole [(refusal :incapable :virtual-table-changed
                            (str "virtual table " tname " changed; its content lives in"
                              " module-owned shadow tables — no general alter or"
                              " rebuild exists"))]}}

      :else (plan-changed-table capabilities opts dctx tname entries))))

(defn- plan-view-group
  "Plan one view's whole-value entry (a view never carries fine-grained
  entries — ADR 0004): views and their triggers carry no data, so
  create/drop (and drop+create for a :changed view, its declared
  triggers recreated with it) always plan, all serving the view entry."
  [_capabilities vname entries]
  (let [whole (first entries)
        vfold (x/fold-name vname)
        serves #{(:path whole)}
        declared-trigger-ops (for [[nm trg] (sort-by key (:triggers (:declared whole)))]
                               (create-secondary-op 2 :create-trigger
                                 (conj (:path whole) :trigger nm)
                                 serves vfold nm (:sql trg)))]
    {:unhandled {}
     :ops (case (:kind whole)
            :added (into [(create-secondary-op 1 :create-view (:path whole)
                            serves vfold vname (:sql (:declared whole)))]
                     declared-trigger-ops)
            :removed [(drop-secondary-op 2 :drop-view whole vfold)]
            :changed (into [(drop-secondary-op 2 :drop-view whole vfold)
                            (create-secondary-op 1 :create-view (:path whole)
                              serves vfold vname (:sql (:declared whole)))]
                       declared-trigger-ops))}))

;; ---------------------------------------------------------------------------
;; Directives (ADR 0009): the intent channel — explicit, conditional,
;; per-object approvals that lift :needs-intent refusals

(def ^:private directive-keys
  "Required identifier keys per launch kind — exactly the resolutions
  of :destructive-drop, nothing else (ADR 0009)."
  {:rename-table [:from :to]
   :rename-column [:table :from :to]
   :drop-table [:table]
   :drop-column [:table :column]})

(defn- directive-live-path
  "The live path a directive claims — every :table, :column, and :from
  key names a live object, identifiers folded exactly as the
  Equivalence relation folds them (ADR 0009)."
  [{:keys [directive] :as d}]
  (case directive
    :rename-table [:table (x/fold-name (:from d))]
    :drop-table [:table (x/fold-name (:table d))]
    :rename-column [:table (x/fold-name (:table d)) :column (x/fold-name (:from d))]
    :drop-column [:table (x/fold-name (:table d)) :column (x/fold-name (:column d))]))

(defn- directive-declared-target
  "The declared target a rename claims — the :to side, folded; keyed by
  the live table for a column rename, since the directive carries the
  live table name. Nil for drops (they claim no declared object)."
  [{:keys [directive] :as d}]
  (case directive
    :rename-table [:table (x/fold-name (:to d))]
    :rename-column [:table (x/fold-name (:table d)) :column (x/fold-name (:to d))]
    nil))

(defn- check-directive-shape! [d]
  (let [ks (and (map? d) (keyword? (:directive d))
             (directive-keys (:directive d)))]
    (when-not ks
      (u/malformed! (str "not a directive: expected a map whose :directive is one of "
                      (str/join ", " (sort (keys directive-keys))))
        {:directive d}))
    (doseq [k ks]
      (when-not (string? (get d k))
        (u/malformed! (str "directive " (:directive d) " requires a string " k)
          {:directive d :missing k})))))

(defn- validate-directives!
  "Structural validation of the directive set alone, before planning
  proper (ADR 0009) — the one throw ADR 0007 reserves. A contradiction
  in the intent channel has no honest resolution: the same live path
  claimed twice, the same declared target claimed twice, or a rename
  and a drop over one object."
  [directives]
  (run! check-directive-shape! directives)
  (let [dup (fn [f] (->> directives (keep f) frequencies
                      (some (fn [[path n]] (when (> n 1) path)))))]
    (when-let [path (dup directive-live-path)]
      (u/malformed! (str "conflicting directives: the live path " path
                      " is claimed twice")
        {:path path :directives (vec directives)}))
    (when-let [path (dup directive-declared-target)]
      (u/malformed! (str "conflicting directives: the declared target " path
                      " is claimed twice")
        {:path path :directives (vec directives)})))
  (let [dropped-tables (into #{} (comp (filter #(= :drop-table (:directive %)))
                                   (map (comp x/fold-name :table)))
                         directives)]
    (doseq [d directives]
      (when (and (= :rename-column (:directive d))
              (contains? dropped-tables (x/fold-name (:table d))))
        (u/malformed! (str "conflicting directives: table " (:table d)
                        " is dropped, but a column rename inside it claims"
                        " its data survives")
          {:directive d :directives (vec directives)})))))

;; ---------------------------------------------------------------------------
;; Assembly

(defn- check-completeness!
  "ADR 0006's locked invariant, mechanically checked: served ∪ unhandled
  covers every entry. A violation is a planner bug, never user error."
  [entries ops unhandled]
  (let [served (into #{} (mapcat :serves) ops)
        unhandled-paths (into #{} (map (comp :path :entry)) unhandled)
        all (into #{} (map :path) entries)]
    (when (not= all (into served unhandled-paths))
      (throw (ex-info "planner completeness invariant violated"
               {:sqlite-migrate/error :internal
                :entries all
                :served served
                :unhandled unhandled-paths})))))

(defn plan
  "Plan `diff` into an ordered, self-contained Plan value (ADR 0006):
  `{:ops [...] :unhandled [...] :live-metadata ... :declared-metadata ...
  :capabilities ... :directives [...] :unused-directives [...]}` —
  plain EDN, list position is execution order, byte-identical for
  identical inputs (ADR 0010).

  Opts: `:capabilities` (merged over the defaults — the live Snapshot's
  SQLite version plus `:rebuild? true`), `:directives` (ADR 0009 — the
  intent channel; structurally validated before planning, echoed
  verbatim under `:directives`, the unmatched remainder reported in
  input order under `:unused-directives`), and `:live-snapshot` /
  `:declared-snapshot`, the two Snapshots the Diff was computed from —
  required context whenever the Diff contains a changed table
  (`:malformed-input` when missing there), unused otherwise.

  Every Diff entry is either served by ≥1 op or listed in `:unhandled`
  as `{:entry e :refusals [...]}` carrying every applicable Refusal
  (`{:class :code :explanation}`) — never throws for refusals."
  [diff opts]
  (let [capabilities (merge {:sqlite-version (get-in diff [:live-metadata :sqlite-version])
                             :rebuild? true}
                       (:capabilities opts))
        directives (vec (:directives opts))
        _ (validate-directives! directives)
        entries (:entries diff)
        dependents (surviving-dependents (:live-snapshot opts) entries)
        opts (assoc opts
               :surviving-dependents dependents
               :surviving-sqls (surviving-referencer-sqls dependents))
        groups (partition-by (fn [e] [(first (:path e)) (x/fold-name (second (:path e)))])
                 entries)
        group-key (fn [g] [(first (:path (first g))) (x/fold-name (second (:path (first g))))])
        by-key (into {} (map (juxt group-key identity)) groups)
        whole-entry (fn [k kind]
                      (let [g (by-key k)
                            e (first g)]
                        (when (and (= 1 (count g)) (= 2 (count (:path e)))
                                (= kind (:kind e)))
                          e)))
        fused (vec (for [dv directives
                         :when (= :rename-table (:directive dv))
                         :let [removed (whole-entry [:table (x/fold-name (:from dv))] :removed)
                               added (whole-entry [:table (x/fold-name (:to dv))] :added)]
                         :when (and removed added
                                 ;; a virtual pair never fuses: no general
                                 ;; alter or rebuild exists (ADR 0007)
                                 (not (:virtual? (:live removed)))
                                 (not (:virtual? (:declared added))))]
                     {:directive dv :removed removed :added added}))
        consumed (into #{} (mapcat (fn [{:keys [removed added]}]
                                     [[:table (x/fold-name (second (:path removed)))]
                                      [:table (x/fold-name (second (:path added)))]]))
                   fused)
        dctx {:drop-tables (into {} (comp (filter #(= :drop-table (:directive %)))
                                      (map (fn [dv] [(x/fold-name (:table dv)) dv])))
                             directives)
              :column-renames (reduce (fn [m dv]
                                        (if (= :rename-column (:directive dv))
                                          (update m (x/fold-name (:table dv)) (fnil conj []) dv)
                                          m))
                                {} directives)
              :drop-columns (reduce (fn [m dv]
                                      (if (= :drop-column (:directive dv))
                                        (assoc-in m [(x/fold-name (:table dv))
                                                     (x/fold-name (:column dv))]
                                          dv)
                                        m))
                              {} directives)}
        results (-> (into [] (keep (fn [g]
                                     (when-not (contains? consumed (group-key g))
                                       (let [e (first g)
                                             nm (second (:path e))]
                                         (if (= :view (first (:path e)))
                                           (plan-view-group capabilities nm g)
                                           (plan-table-group capabilities opts dctx nm g))))))
                      groups)
                  (into (map (fn [{:keys [directive removed added]}]
                               (plan-fused-table capabilities opts dctx removed added directive)))
                    fused))
        ops (mapv :op (sort-by :order (into [] (mapcat :ops) results)))
        by-entry (apply merge {} (map :unhandled results))
        unhandled (into [] (keep (fn [e]
                                   (when-let [refusals (get by-entry e)]
                                     {:entry e :refusals refusals})))
                    entries)
        used (into #{} (mapcat :used) results)]
    (check-completeness! entries ops unhandled)
    {:ops ops
     :unhandled unhandled
     :live-metadata (:live-metadata diff)
     :declared-metadata (:declared-metadata diff)
     :capabilities capabilities
     :directives directives
     :unused-directives (filterv (complement used) directives)}))
