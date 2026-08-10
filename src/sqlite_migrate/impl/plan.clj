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
  (str (u/quote-identifier name)
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

(defn- ordered-op
  "An Op paired with its plan-position sort key, under `:op` and
  `:order`. Order vectors are padded to a fixed length with
  `order-pad`: `compare` ranks vectors by length
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
      {:ops [(ordered-op [phase-change-tables (x/fold-name tname) sub-add-column "" unpatched-position]
               :add-column (:path entry) #{(:path entry)}
               [(str "ALTER TABLE " (u/quote-identifier tname) " ADD COLUMN " (column-def-sql col))])]})))

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
      {:ops [(ordered-op [phase-change-tables (x/fold-name tname) sub-alter-column
                          (x/fold-name (entry-name entry))]
               (if (:not-null? (:declared entry)) :set-not-null :drop-not-null)
               (:path entry) #{(:path entry)}
               [(str "ALTER TABLE " (u/quote-identifier tname) " ALTER COLUMN "
                  (u/quote-identifier (entry-name entry))
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
    (ordered-op (into [phase-change-tables (x/fold-name tname) sub-add-check]
                  (check-sort-key c (:path entry)))
      :add-check (:path entry) #{(:path entry)}
      [(str "ALTER TABLE " (u/quote-identifier tname)
         " ADD " (when (:name c) (str "CONSTRAINT " (u/quote-identifier (:name c)) " "))
         "CHECK (" (:expr c) ")")])))

(defn- drop-check-op [tname entry]
  (let [c (:live entry)]
    (ordered-op (into [phase-change-tables (x/fold-name tname) sub-drop-check]
                  (check-sort-key c (:path entry)))
      :drop-check (:path entry) #{(:path entry)}
      [(str "ALTER TABLE " (u/quote-identifier tname) " DROP CONSTRAINT " (u/quote-identifier (:name c)))])))

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
               :drop-index (str "DROP INDEX " (u/quote-identifier nm))
               :drop-trigger (str "DROP TRIGGER " (u/quote-identifier nm))
               :drop-view (str "DROP VIEW " (u/quote-identifier nm)))]
    (ordered-op [phase-drop-secondary sub parent-fold (x/fold-name nm)]
      kind (:path entry) #{(:path entry)} [stmt])))

(defn- create-secondary-op [sub kind path serves parent-fold nm sql]
  (ordered-op [phase-create-secondary sub parent-fold (x/fold-name nm)] kind path serves [sql]))

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
;;
;; From here down a `pairing` is one changed table's two sides travelling
;; together: `{:live-table :declared-table :rename-map}` — the live table,
;; its declared counterpart, and the resolved :rename-column claims linking
;; their columns (declared fold → live column name, ADR 0009). Fusion
;; resolves the :rename-map; every rebuild and gate reads the whole thing.

(defn- quote-string-literal
  "Quote `s` as a SQL string literal — single quotes, embedded single
  quotes doubled per SQLite quoting rules. Not an identifier quoter;
  that is `sqlite-migrate.impl.util/quote-identifier`."
  ^String [^String s]
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
  skipped. Only a bare word can be one of those keywords — a quoted
  identifier spelling `if` is a table named `if`."
  [^String sql temp-name]
  (let [toks (x/tokenize sql)
        after-table (inc (long (first (keep-indexed
                                        (fn [i t]
                                          (when (and (= :word (:t t)) (= "table" (:fold t))) i))
                                        toks))))
        name-tok (some #(when (or (= :qid (:t %))
                                (and (= :word (:t %))
                                  (not (contains? #{"if" "not" "exists"} (:fold %)))))
                          %)
                   (drop after-table toks))]
    (str (subs sql 0 (:s name-tok)) (u/quote-identifier temp-name) (subs sql (:e name-tok)))))

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
  dropped columns are not copied. The pairing's `:rename-map` binds a
  declared column's folded name to the live column it renames (ADR
  0009) — the copy follows the rename. `rowid` copies explicitly when
  both sides are rowid tables and no copied INTEGER PRIMARY KEY column
  already aliases it (ADR 0010). Nil when nothing is copyable."
  [temp-name {:keys [live-table declared-table] renames :rename-map}]
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
        insert-cols (concat (when rowid? ["rowid"]) (map (comp u/quote-identifier :name) shared))
        select-cols (concat (when rowid? ["rowid"]) (map (comp u/quote-identifier live-of) shared))]
    (when (seq insert-cols)
      (str "INSERT INTO " (u/quote-identifier temp-name) " (" (str/join ", " insert-cols)
        ") SELECT " (str/join ", " select-cols)
        " FROM " (u/quote-identifier (:name live-table))))))

(defn- sequence-restore-sqls
  "The statements restoring the AUTOINCREMENT counter (ADR 0010): lift
  the live table's `sqlite_sequence` row onto the temp table's before
  the old table drops, so the renamed table's next id stays greater
  than any id ever issued. The copy already advanced the temp counter
  to the copied maximum; these statements only raise it to the live
  counter when that is higher (or plant it when the copy was empty)."
  [temp-name live-name]
  (let [live (quote-string-literal live-name)
        temp (quote-string-literal temp-name)]
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
  [temp {:keys [live-table declared-table] :as pairing}]
  (let [autoincrement? (and (:autoincrement? live-table) (:autoincrement? declared-table))]
    (-> [(create-sql-under-temp-name (:sql (meta declared-table)) temp)]
      (into (keep identity [(rebuild-copy-sql temp pairing)]))
      (into (when autoincrement?
              (sequence-restore-sqls temp (:name live-table)))))))

(defn- rebuild-swap-sqls
  "The rebuild's swap statements: drop the dependents that would break
  the rename, drop the old table, rename the staged table into place —
  never rename-first."
  [temp {:keys [live-table declared-table]} deps]
  (-> []
    (into (map #(str "DROP VIEW " (u/quote-identifier (:name %)))) (:views deps))
    (into (map #(str "DROP TRIGGER " (u/quote-identifier (:name %)))) (:triggers deps))
    (conj (str "DROP TABLE " (u/quote-identifier (:name live-table))))
    (conj (str "ALTER TABLE " (u/quote-identifier temp)
            " RENAME TO " (u/quote-identifier (:name declared-table))))))

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
  [planning-context tname serves {:keys [declared-table] :as pairing}]
  (let [tfold (x/fold-name tname)
        temp (temp-rebuild-name planning-context (:name declared-table))
        deps (rebuild-dependents (:surviving-dependents planning-context) tfold)
        sql (-> (rebuild-stage-sqls temp pairing)
              (into (rebuild-swap-sqls temp pairing deps))
              (into (rebuild-recreate-sqls declared-table deps)))]
    (ordered-op [phase-change-tables tfold] :rebuild-table [:table tname] serves sql)))

;; ---------------------------------------------------------------------------
;; Gates (ADR 0008): data preconditions as plan-compiled sampling
;; SELECTs. A gate exists iff SQLite would reject or destroy existing
;; rows when the op runs and conformance cannot be decided from the
;; Snapshot. Gate SQL falls under the plan determinism contract; it
;; always queries the LIVE table and column spellings — gates run
;; before any op does.

(def gate-sample-limit
  ;; ADR 0008: the LIMIT controls result-set size, not scan cost —
  ;; zero rows pass, k < limit rows fail with the exact count, limit
  ;; rows fail as "limit or more". ADR 0019: the value is not part of
  ;; the stability promise — any release may change it, which is why
  ;; every Gate carries it rather than readers assuming it.
  10)

(defn- gate
  "A Gate: `:code`, `:path`, `:explanation`, the sampling `:sql`, and the
  `:limit` that SQL was baked with — five keys (ADR 0019). Carrying the
  limit is what lets a Check result computed from a serialized Plan tell
  \"exactly limit violations\" from \"limit or more\" without assuming the
  reader's constant matches the compiler's."
  [code path explanation sql]
  {:code code :path path :explanation explanation :sql sql
   :limit gate-sample-limit})

(defn- sampling-sql
  "The sampling SELECT over the live table: violating rows under
  `condition` (nil = every row violates), with the baked LIMIT."
  [live-tname condition]
  (str "SELECT * FROM " (u/quote-identifier live-tname)
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
  [pairing declared-name]
  (if-let [lc (live-col-name pairing declared-name)]
    [:live lc]
    (let [d (:default (declared-column pairing declared-name))]
      (case (default-kind d)
        :constant [:const (str "(" (str/trim d) ")")]
        :null :null
        :opaque nil))))

(defn- key-part-sql
  "The bare SQL fragment of a `key-part` pair: the quoted live column,
  or the constant fragment verbatim."
  [[k v]]
  (if (= :live k) (u/quote-identifier v) v))

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
    " FROM " (u/quote-identifier live-tname)
    " WHERE " (when where (str "(" where ") AND "))
    (str/join " AND " (map #(str (:bare %) " IS NOT NULL") parts))
    " GROUP BY " (group-by-list parts)
    " HAVING COUNT(*) > 1 LIMIT " gate-sample-limit))

(defn- column-gates [pairing entry]
  (let [t (:name (:live-table pairing))]
    (case (:kind entry)
      :changed
      (when (and (contains? (:facts entry) :not-null?)
              (:not-null? (:declared entry))
              (nil? (:generated (:declared entry))))
        (let [c (:name (:live entry))]
          [(gate :not-null (:path entry)
             (str "column " c " of table " t " becomes NOT NULL;"
               " a stored NULL there would be rejected")
             (sampling-sql t (str (u/quote-identifier c) " IS NULL")))]))
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

(defn- check-constraint-gates [pairing route entry]
  (when (contains? #{:added :changed} (:kind entry))
    (let [t (:name (:live-table pairing))
          expr (:expr (:declared entry))]
      ;; the routes verify differently (measured on 3.53): in-place
      ;; ALTER TABLE ADD CHECK validation rejects rows where the
      ;; expression is NULL, while the rebuild's INSERT copy applies
      ;; insert-time CHECK semantics — NULL passes
      (if (= :in-place route)
        [(gate :check (:path entry)
           (str "table " t " adds CHECK (" expr ");"
             " rows where the expression is false or NULL would be rejected")
           (sampling-sql t (str "NOT (" expr ") OR (" expr ") IS NULL")))]
        [(gate :check (:path entry)
           (str "table " t " adds CHECK (" expr ");"
             " rows where the expression is false would be rejected")
           (sampling-sql t (str "NOT (" expr ")")))]))))

(defn- index-gates [pairing entry]
  (when (and (contains? #{:added :changed} (:kind entry))
          (:unique? (:declared entry)))
    (let [t (:name (:live-table pairing))
          idx (:declared entry)
          key-parts (mapv (fn [{:keys [name expr]}]
                            (if name (key-part pairing name) [:expr (str "(" expr ")")]))
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

(defn- foreign-key-gates [{:keys [live-snapshot declared-snapshot]} pairing entry]
  ;; ADR 0008: carried even though the Frame's foreign_key_check would
  ;; catch orphans at COMMIT — the gate reports before any work runs.
  ;; Action/match/deferrability changes carry no row precondition.
  (when (or (= :added (:kind entry))
          (and (= :changed (:kind entry))
            (some (:facts entry) [:columns :ref-table :ref-columns])))
    (let [t (:name (:live-table pairing))
          fk (:declared entry)
          key-parts (mapv #(key-part pairing %) (:columns fk))
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
                                              (str (u/quote-identifier v) " IS NOT NULL")))
                            key-parts))
              lookup (when parent-live
                       (str "NOT EXISTS (SELECT 1 FROM " (u/quote-identifier (:name parent-live))
                         " AS \"sqm_parent\" WHERE "
                         (str/join " AND "
                           (map (fn [rc [k :as kp]]
                                  (str "\"sqm_parent\"." (u/quote-identifier rc) " = "
                                    (if (= :live k)
                                      (str (u/quote-identifier t) "." (key-part-sql kp))
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
  [pairing {:keys [kind facts] :as entry}]
  (when (= :changed kind)
    (let [{:keys [live-table declared-table]} pairing
          t (:name live-table)
          pk-cols (get-in declared-table [:primary-key :columns])
          alias? (some? (rowid-alias-fold declared-table))]
      (vec
        (concat
          (when (and (contains? facts :primary-key) (seq pk-cols))
            (let [key-parts (mapv #(key-part pairing %) pk-cols)]
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
                                         (map (comp u/quote-identifier second)))
                                key-parts)
                      null-enforced? (and (not alias?) (seq live-qs)
                                       (or (:without-rowid? declared-table)
                                         (:strict? declared-table)))
                      group-list (group-by-list (map key-part-fragments key-parts))
                      dup (str row " IN (SELECT " key-list " FROM " (u/quote-identifier t)
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
                                  (when-let [lc (live-col-name pairing (:name dc))]
                                    (strict-violation-condition (u/quote-identifier lc)
                                      (some-> (:type dc) x/fold-name)))))
                          (:columns declared-table))]
              (when (seq conds)
                [(gate :strict (:path entry)
                   (str "table " t " converts to STRICT;"
                     " every stored value must match its column's declared type")
                   (sampling-sql t (str/join " OR " conds)))])))
          (when (and (contains? facts :without-rowid?) (:without-rowid? declared-table))
            (let [parts (reduce (fn [acc n]
                                  (if-let [lc (live-col-name pairing n)]
                                    (conj acc (str (u/quote-identifier lc) " IS NULL"))
                                    ;; a new PK column defaulting to NULL leaves
                                    ;; every copied row NULL there; a constant
                                    ;; or opaque default fills it (ADR 0015)
                                    (if (= :null (default-kind
                                                   (:default (declared-column pairing n))))
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

(defn- unique-gates [pairing entry]
  (when (contains? #{:added :changed} (:kind entry))
    (let [t (:name (:live-table pairing))
          cols (:columns (:declared entry))
          key-parts (mapv #(key-part pairing %) cols)]
      (when (gateable-key-parts? key-parts)
        [(gate :unique (:path entry)
           (str "unique key (" (str/join ", " cols) ") of table " t ";"
             " duplicate key groups would be rejected"
             (const-part-note cols key-parts))
           (key-group-sql t (mapv key-part-fragments key-parts) nil))]))))

(defn- entry-gates
  "The Gates one routed unit's entry contributes, compiled from the
  `pairing` (live and declared table values plus the resolved rename
  map) and, for a foreign key's parent lookup, the planning context's
  two Snapshots. `route` is `:in-place` or `:rebuild` — the route the
  unit took. A CHECK gate on the `:in-place` route must replicate ALTER
  TABLE ADD CHECK's NULL handling rather than the rebuild copy's."
  [planning-context pairing route {:keys [path] :as entry}]
  (let [seg (when (> (count path) 2) (nth path 2))]
    (cond
      (= 2 (count path)) (table-gates pairing entry)
      (= :column seg) (column-gates pairing entry)
      (= :check seg) (check-constraint-gates pairing route entry)
      (= :unique seg) (unique-gates pairing entry)
      (= :foreign-key seg) (foreign-key-gates planning-context pairing entry)
      (= :index seg) (index-gates pairing entry)
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
  "The live or declared table value for a changed table, from the
  Snapshot `plan` was given. Past `plan`'s entry guard both Snapshots
  are present and Snapshot-shaped (ADR 0017), so a table the Diff
  speaks of and the Snapshot lacks is a Diff/Snapshot mismatch — a bug
  in whoever paired them, not malformed input the caller can fix. The
  entry guard's provenance check turns most such pairings away first,
  but a `schema_version` is a mutation counter rather than a proof of
  identity (ADR 0017), so this stays a live guard."
  [snapshot side tname]
  (or (some (fn [[k v]] (when (= (x/fold-name k) (x/fold-name tname)) v))
        (:tables snapshot))
    (throw (ex-info (str "the " (name side) " Snapshot has no table " tname
                      ", which this Diff says changed")
             {:sqlite-migrate/error :internal
              :side side
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
  COLUMN; any other differing fact, a colliding rename set (the
  routing state's `:rename-collision?` — sequential in-place steps
  would collide), or a pre-3.25 target collapses it onto the rebuild
  path."
  [capabilities tname routing-state entry]
  (let [{:keys [from to]} (:rename entry)]
    (if (or (seq (:facts entry))
          (:rename-collision? routing-state)
          (not (supports? capabilities v-rename-column)))
      {:rebuild []}
      {:ops [(ordered-op [phase-change-tables (x/fold-name tname) sub-alter-column (x/fold-name from)]
               :rename-column (:path entry) #{(:path entry)}
               [(str "ALTER TABLE " (u/quote-identifier tname) " RENAME COLUMN "
                  (u/quote-identifier from) " TO " (u/quote-identifier to))])]})))

(defn- route-table-entry
  "Route one entry of a changed regular table. `pairing` carries the
  live and declared table values; `routing-state` carries the sets the
  intermediate-state checks need."
  [capabilities tname pairing routing-state entry]
  (let [seg (when (> (count (:path entry)) 2) (nth (:path entry) 2))]
    (cond
      (:rename entry) (route-renamed-column capabilities tname routing-state entry)

      (= 2 (count (:path entry))) ; table-level facts: STRICT, WITHOUT ROWID, order, PK, AUTOINCREMENT
      {:rebuild (unsupported-object-refusals capabilities (:declared entry))}

      (= :column seg)
      (case (:kind entry)
        :added (let [unsupported (unsupported-object-refusals capabilities (:declared entry))]
                 (if (seq unsupported)
                   {:refuse unsupported}
                   (route-added-column capabilities tname entry (:appendable? routing-state))))
        :removed
        (let [col (:live entry)
              col-fold (x/fold-name (:name col))
              in-place? (and (supports? capabilities v-drop-column)
                          (droppable-in-place? tname (:live-table pairing) col-fold routing-state))
              ;; an authorized drop is intent supplied (ADR 0009):
              ;; the :destructive-drop refusal is lifted, the route
              ;; stands on its own merits
              destructive? (and (not= :virtual (get-in col [:generated :storage]))
                             (not (contains? (:authorized-col-drops routing-state) col-fold)))]
          (cond
            (not in-place?) {:rebuild (when destructive?
                                        [(destructive-refusal (entry-what entry))])}
            destructive? {:needs-intent [(destructive-refusal (entry-what entry))]}
            :else {:ops [(ordered-op [phase-change-tables (x/fold-name tname) sub-drop-column
                                      (get (:drop-order routing-state) col-fold)]
                           :drop-column (:path entry) #{(:path entry)}
                           [(str "ALTER TABLE " (u/quote-identifier tname)
                              " DROP COLUMN " (u/quote-identifier (:name col)))])]}))
        :changed (route-changed-column capabilities tname entry))

      (= :check seg) (route-check capabilities tname entry)
      (= :index seg) (route-index entry tname)
      (= :trigger seg) (route-trigger entry tname)
      ;; :unique and :foreign-key constraints have no in-place ALTER form
      :else {:rebuild []})))

(defn- table-routing-state
  "The intermediate state routing a changed table's entries reads:
  which indexes and checks survive the plan's earlier drops, which
  columns are being dropped (and in what order), and whether the added
  columns form a declared-order suffix."
  [capabilities entries {:keys [live-table declared-table]}]
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
    {:appendable? (appended-suffix? declared-table added-folds)
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
  (ordered-op [phase-change-tables (x/fold-name declared-name) sub-rename-table]
    :rename-table [:table live-name] serves
    [(str "ALTER TABLE " (u/quote-identifier live-name) " RENAME TO " (u/quote-identifier declared-name))]))

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

(defn- column-entry?
  "True when `entry` names a column of a table — a path of the shape
  `[:table t :column c]`."
  [entry]
  (and (= 4 (count (:path entry))) (= :column (nth (:path entry) 2))))

(defn- entry-column-fold
  "The folded column name a column entry's path ends in, folded exactly
  as the Equivalence relation folds identifiers (ADR 0009)."
  [entry]
  (x/fold-name (peek (:path entry))))

(defn- columns-by-fold
  "`table`'s columns indexed by folded name."
  [table]
  (into {} (map (fn [c] [(x/fold-name (:name c)) c])) (:columns table)))

(defn- column-folds
  "The folded names of `table`'s columns."
  [table]
  (into #{} (map (comp x/fold-name :name)) (:columns table)))

(defn- rename-candidates
  "One table's :rename-column claims normalized for resolution: each
  directive paired with its folded live `from` and declared `to`."
  [rename-claims]
  (mapv (fn [dv] {:directive dv
                  :from-fold (x/fold-name (:from dv))
                  :to-fold (x/fold-name (:to dv))})
    rename-claims))

(defn- entry-subject-folds
  "The folded column names the column entries of `entries` whose kind is
  in `kinds` name."
  [entries kinds]
  (into #{} (comp (filter column-entry?)
              (filter (comp kinds :kind))
              (map entry-column-fold))
    entries))

(defn- anchored-renames
  "The active renames the change set anchors. An identity-paired table
  keeps a rename only while one of its two columns is a subject of
  `entries`, so a directive never plans against a drift the Diff does
  not show (ADR 0009)."
  [entries active]
  (let [live-subjects (entry-subject-folds entries #{:removed :changed})
        declared-subjects (entry-subject-folds entries #{:added :changed})]
    (filterv #(or (contains? live-subjects (:from-fold %))
                (contains? declared-subjects (:to-fold %)))
      active)))

(defn- rename-claims-entry?
  "True when the resolved `rename` claims `entry`: a column entry naming
  the rename's live `from` side when :removed, its declared `to` side
  when :added, or either when :changed."
  [{:keys [from-fold to-fold]} entry]
  (and (column-entry? entry)
    (let [f (entry-column-fold entry)
          kind (:kind entry)]
      (or (and (#{:removed :changed} kind) (= f from-fold))
        (and (#{:added :changed} kind) (= f to-fold))))))

(defn- rename-consumed?
  "True when one of the resolved `renames` claims `entry` — the entry is
  fused away into that rename's synthetic unit."
  [renames entry]
  (boolean (some #(rename-claims-entry? % entry) renames)))

(defn- rename-unit
  "One resolved rename's synthetic unit: a single :changed entry whose
  live and declared sides differ in name (`:rename {:from :to}`, facts
  compared name-blind), paired under `:orig` with the entries of
  `entries` it consumes."
  [tname entries lcols dcols {:keys [from-fold to-fold] :as rename}]
  (let [lc (lcols from-fold)
        dc (dcols to-fold)]
    {:entry {:kind :changed
             :path [:table tname :column (:name lc)]
             :live lc
             :declared dc
             :facts (not-empty (d/fused-column-facts lc dc))
             :rename {:from (:name lc) :to (:name dc)}}
     :orig (filterv #(rename-claims-entry? rename %) entries)}))

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
  :rename-directives [...] :pairing p :collision? bool}` — `:pairing`
  is the given pair completed with the `:rename-map` fusion resolved
  (declared fold → live column name), and `:collision?` says a rename
  target is still a live column name, forcing the rebuild path (swaps,
  chains)."
  [tname entries {:keys [live-table declared-table] :as pairing} rename-claims require-anchor?]
  (let [live-folds (column-folds live-table)
        active (active-column-renames (rename-candidates rename-claims)
                 live-folds (column-folds declared-table))
        effective (if require-anchor? (anchored-renames entries active) active)
        lcols (columns-by-fold live-table)
        dcols (columns-by-fold declared-table)]
    {:units (into (into [] (comp (remove #(rename-consumed? effective %))
                             (map (fn [e] {:entry e :orig [e]})))
                    entries)
              (map #(rename-unit tname entries lcols dcols %))
              effective)
     :rename-directives (mapv :directive effective)
     :pairing (assoc pairing :rename-map
                (into {} (map (fn [{:keys [from-fold to-fold]}]
                                [to-fold (:name (lcols from-fold))]))
                  effective))
     :collision? (boolean (some #(contains? live-folds (:to-fold %)) effective))}))

(defn- fused-serves
  "The two whole-table entry paths every op of a fused pair serves (ADR
  0009), or nil for an identity-paired table."
  [fused]
  (when fused #{(:path (:removed fused)) (:path (:added fused))}))

(defn- unit-serves
  "The entry paths one unit's ops serve: a fused pair's two whole-table
  paths, otherwise the paths of the entries the unit fused. Not a
  delegation to `fused-serves` — a fused pair answers for its two
  whole-table entries and nothing else (ADR 0009), so the unit's own
  entries are read only where there is no pair to answer for them.
  Callers serving a whole change set rather than one unit go to
  `fused-serves` direct, since no single unit's `:orig` covers them."
  [fused unit]
  (or (fused-serves fused) (into #{} (map :path) (:orig unit))))

(defn- unit-refusals
  "`refusals` keyed by the entries they answer for — a fused pair's
  refusals ride on both whole-table entries (ADR 0009), otherwise on
  every entry the unit fused."
  [fused unit refusals]
  (let [rs (vec refusals)]
    (if fused
      {(:removed fused) rs (:added fused) rs}
      (zipmap (:orig unit) (repeat rs)))))

(defn- merge-refusals
  "Merge per-unit `{entry [refusals]}` maps into one, an entry's
  refusals concatenated in unit order with duplicates dropped."
  [maps]
  (apply merge-with (fn [a b] (vec (distinct (concat a b)))) {} maps))

(defn- removed-column-folds
  "The folded names of the columns `units` drop outright."
  [units]
  (into #{} (comp (map :entry)
              (filter #(and (column-entry? %) (= :removed (:kind %))))
              (map entry-column-fold))
    units))

(defn- used-directives
  "The directives planning one table consumed (ADR 0009): the matched
  :rename-table, every :rename-column that fused, and each authorized
  :drop-column whose column the change set actually drops."
  [fused rename-directives authorized removed-folds]
  (-> (vec (when fused [(:directive fused)]))
    (into rename-directives)
    (into (keep (fn [[f dv]] (when (contains? removed-folds f) dv)))
      authorized)))

;; ---------------------------------------------------------------------------
;; Change set: one table's whole pending change, carried as a single
;; value from fusion through routing and ADR 0006's selection rule.
;;   :tname    the declared table name the change plans toward
;;   :pairing  the live table paired with its declared counterpart, its
;;             :rename-map resolved by fusion
;;   :fused    the matched :rename-table pair — its directive and the
;;             removed and added whole-table entries (ADR 0009) — or nil
;;             for an identity-paired table
;;   :entries  the table's Diff entries, as the Diff reported them
;;   :units    those entries fused under the resolved :rename-column
;;             claims, each `{:entry e :orig [entries]}`
;;   :collision?  whether a rename target collides with a live column name
;;   :authorized  the :drop-column claims authorizing this table's drops,
;;             by folded column name
;;   :routed   :units with every unit's `:route` attached, present only
;;             after `attach-routes`
;; `capabilities` and `planning-context` stay separate parameters: they
;; are ambient planner context, not part of any one table's change set.

(defn- attach-routes
  "`change-set` with `:routed` attached — every unit carrying its
  `:route`. Routing reads the table's own routing state widened with
  what phase-1 leaves standing, whether a rename target collides with a
  live column name, and the columns a :drop-column claim authorizes
  dropping."
  [capabilities planning-context
   {:keys [tname pairing units collision? authorized] :as change-set}]
  (let [routing-state (assoc (table-routing-state capabilities (mapv :entry units) pairing)
                        :surviving-sqls (:surviving-sqls planning-context)
                        :rename-collision? collision?
                        :authorized-col-drops (set (keys authorized)))]
    (assoc change-set :routed
      (mapv (fn [u]
              (assoc u :route (route-table-entry capabilities tname pairing
                                routing-state (:entry u))))
        units))))

(defn- in-place-result
  "The in-place branch of ADR 0006's selection rule: every unit's ops in
  declared column order, each gated by its entry's gates, led by the
  :rename-table op when the table is fused. A unit that routed nowhere
  leaves its entries unhandled with the refusals routing gave."
  [planning-context {:keys [tname pairing fused routed]}]
  {:ops (declared-position (:declared-table pairing)
          (into (vec (when fused
                       [(rename-table-op (:name (:live-table pairing)) tname
                          (fused-serves fused))]))
            (mapcat (fn [u]
                      (attach-unit-gates
                        (entry-gates planning-context pairing :in-place (:entry u))
                        (map #(assoc-in % [:op :serves] (unit-serves fused u))
                          (:ops (:route u))))))
            routed))
   :unhandled (merge-refusals
                (keep (fn [{:keys [route] :as u}]
                        (when-not (:ops route)
                          (unit-refusals fused u
                            (concat (:refuse route) (:needs-intent route)))))
                  routed))})

(defn- rebuild-disabled-result
  "The result when the change set needs a rebuild and the `:rebuild?`
  capability is off: no ops, every entry unhandled."
  [{:keys [fused routed]}]
  {:ops []
   :unhandled (merge-refusals
                (map (fn [{:keys [route] :as u}]
                       ;; a no-route (:refuse) entry keeps only its own
                       ;; refusals — a rebuild would not help it either
                       (unit-refusals fused u
                         (if (:refuse route)
                           (:refuse route)
                           (concat (:rebuild route) (:needs-intent route)
                             [(rebuild-disabled-refusal (entry-what (:entry u)))]))))
                  routed))})

(defn- rebuild-blockers
  "What refuses even with rebuilds allowed. ADR 0007: an older target
  just rebuilds more — unless a destructive drop still awaits intent,
  or the declared shape is one the target version cannot hold, since
  the rebuild would have to create it."
  [capabilities {{:keys [declared-table]} :pairing :keys [routed]}]
  (into (vec (unsupported-object-refusals capabilities declared-table))
    (mapcat (fn [{:keys [route]}]
              (concat (:refuse route) (:rebuild route) (:needs-intent route))))
    routed))

(defn- blocked-result
  "The result when a blocker rides the change set: no ops, every entry
  unhandled with its own refusals plus every blocker."
  [{:keys [fused routed]} blockers]
  {:ops []
   :unhandled (merge-refusals
                (map (fn [{:keys [route] :as u}]
                       (unit-refusals fused u
                         (distinct (concat (:refuse route) (:rebuild route)
                                     (:needs-intent route) blockers))))
                  routed))})

(defn- rebuild-result
  "The collapse branch of ADR 0006's selection rule: one :rebuild-table
  op serving every entry of the change set, carrying every gate the
  units want proven before the copy."
  [planning-context {:keys [tname pairing fused entries units]}]
  (let [gates (into [] (mapcat #(entry-gates planning-context pairing :rebuild (:entry %))) units)]
    {:ops [(cond-> (rebuild-table-op planning-context
                     (if fused (:name (:live-table pairing)) tname)
                     (or (fused-serves fused) (into #{} (map :path) entries))
                     pairing)
             (seq gates) (update :op assoc :gates gates))]
     :unhandled {}}))

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
  `pairing` is the live table paired with its declared counterpart;
  fusion completes it with the `:rename-map` linking their columns.
  Returns `{:ops [...] :unhandled {entry refusals} :used
  [directives]}`."
  [capabilities planning-context claims tname entries pairing fused]
  (let [lt-fold (x/fold-name (:name (:live-table pairing)))
        ;; fusion both consumes the pairing and hands it back with its
        ;; :rename-map resolved — nothing below wants the unresolved one
        {:keys [units rename-directives pairing collision?]}
        (fuse-column-entries tname entries pairing
          (get (:column-renames claims) lt-fold) (nil? fused))
        authorized (get (:drop-columns claims) lt-fold)
        ;; from here down the table's whole pending change travels as
        ;; one change set — see the vocabulary above `attach-routes`
        change-set (attach-routes capabilities planning-context
                     {:tname tname :pairing pairing :fused fused
                      :entries entries :units units
                      :collision? collision? :authorized authorized})
        collapse? (boolean (some #(contains? (:route %) :rebuild) (:routed change-set)))]
    (assoc
      (cond
        (not collapse?)
        (in-place-result planning-context change-set)

        (not (:rebuild? capabilities))
        (rebuild-disabled-result change-set)

        :else
        (let [blockers (rebuild-blockers capabilities change-set)]
          (if (seq blockers)
            (blocked-result change-set blockers)
            (rebuild-result planning-context change-set))))
      :used (used-directives fused rename-directives authorized
              (removed-column-folds units)))))

(defn- plan-changed-table
  "Plan the entries of one changed regular table under the resolved
  directive claims — see `plan-table-changes` for the selection rule
  and return shape."
  [capabilities planning-context claims tname entries]
  (plan-table-changes capabilities planning-context claims tname entries
    {:live-table (table-context (:live-snapshot planning-context) :live tname)
     :declared-table (table-context (:declared-snapshot planning-context) :declared tname)}
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
  [capabilities planning-context claims removed added directive]
  (let [pairing {:live-table (table-context (:live-snapshot planning-context)
                               :live (second (:path removed)))
                 :declared-table (table-context (:declared-snapshot planning-context)
                                   :declared (second (:path added)))}]
    (plan-table-changes capabilities planning-context claims
      (:name (:declared-table pairing))
      (d/fused-entries (:live-table pairing) (:declared-table pairing))
      pairing
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
    (into [(ordered-op [phase-create-tables tfold] :create-table (:path entry) serves [(:sql t)])]
      (concat
        (for [[nm idx] (sort-by key (:indexes t))]
          (create-secondary-op 0 :create-index (conj (:path entry) :index nm)
            serves tfold nm (:sql idx)))
        (for [[nm trg] (sort-by key (:triggers t))]
          (create-secondary-op 2 :create-trigger (conj (:path entry) :trigger nm)
            serves tfold nm (:sql trg)))))))

(defn- plan-table-group
  "Plan one table's entries: a whole-table entry stands alone; anything
  else is a changed table planned fine-grained. `claims` carries the
  resolved directive claims (ADR 0009): a whole-table removal plans as
  a phase-2 :drop-table op when a :drop-table directive authorizes it."
  [capabilities planning-context claims tname entries]
  (let [whole (some #(when (= 2 (count (:path %))) %) entries)]
    (cond
      (and whole (= :added (:kind whole)))
      (let [unsupported (unsupported-object-refusals capabilities (:declared whole))]
        (if (seq unsupported)
          {:ops [] :unhandled {whole (vec unsupported)}}
          {:ops (create-table-ops whole) :unhandled {}}))

      (and whole (= :removed (:kind whole)))
      (if-let [directive (get (:drop-tables claims) (x/fold-name tname))]
        {:ops [(ordered-op [phase-drop-tables (x/fold-name tname)]
                 :drop-table (:path whole) #{(:path whole)}
                 [(str "DROP TABLE " (u/quote-identifier tname))])]
         :unhandled {}
         :used [directive]}
        {:ops [] :unhandled {whole [(destructive-refusal (str "table " tname))]}})

      (and whole (or (:virtual? (:live whole)) (:virtual? (:declared whole))))
      {:ops []
       :unhandled {whole [(refusal :incapable :virtual-table-changed
                            (str "virtual table " tname " changed; its content lives in"
                              " module-owned shadow tables — no general alter or"
                              " rebuild exists"))]}}

      :else (plan-changed-table capabilities planning-context claims tname entries))))

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

(defn- snapshot-shaped?
  "True when `x` could be a Snapshot: a map of `:tables` and `:views`,
  each keyed by object name (ADR 0001). Shape only — provenance rides
  in metadata, and nothing here reads it."
  [x]
  (and (map? x) (map? (:tables x)) (map? (:views x))))

(defn- validate-context!
  "ADR 0017's entry guard: planning reads whole table values out of the
  two Snapshots the Diff was computed from, so both are required
  arguments. One `:malformed-input` here, before any planning."
  [live declared]
  (doseq [[side snapshot] [[:live live] [:declared declared]]]
    (when-not (snapshot-shaped? snapshot)
      (u/malformed! (str "plan requires the " (name side) " Snapshot the Diff"
                      " was computed from as its " (name side) " argument")
        {:side side :snapshot snapshot}))))

(defn- validate-provenance!
  "ADR 0017's provenance check — the pure-side sibling of `apply!`'s
  fingerprint probe: the Snapshots handed to `plan` must be the ones
  `diff` compared. `facet` is what each side compares: whole provenance
  on the live side, `:sqlite-version` alone on the declared one, since
  a declared Snapshot's `:schema-version` counts the statements a
  throwaway pristine database happened to take and so differs with no
  semantic difference behind it. Swapping the two arguments fails on
  the live side, unless both carry byte-identical provenance."
  [live declared diff]
  (doseq [[side snapshot from-diff facet]
          [[:live live (:live-provenance diff) identity]
           [:declared declared (:declared-provenance diff) :sqlite-version]]]
    (when (not= (facet (meta snapshot)) (facet from-diff))
      (u/malformed! (str "the " (name side) " Snapshot is not the one this Diff"
                      " was computed from")
        {:side side
         :snapshot-provenance (meta snapshot)
         :diff-provenance from-diff}))))

(defn- resolve-claims
  "The directive claims planning reads, indexed by folded live name (ADR
  0009): `{:drop-tables {tfold d} :column-renames {tfold [d ...]}
  :drop-columns {tfold {cfold d}}}`. A table's column claims stay in
  directive order, so planning consumes them as the caller wrote them."
  [directives]
  {:drop-tables (into {} (comp (filter #(= :drop-table (:directive %)))
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
                   {} directives)})

(defn- entry-group-key
  "The group `entry` belongs to: its object kind paired with its folded
  object name. Entries of one object plan together (ADR 0006)."
  [entry]
  [(first (:path entry)) (x/fold-name (second (:path entry)))])

(defn- whole-table-entry
  "The lone whole-object entry of the group keyed `k` in `groups-by-key`
  — the group holds exactly one entry, of kind `kind`, naming the
  object itself. Nil otherwise; only such a group can fuse (ADR 0009)."
  [groups-by-key k kind]
  (let [g (groups-by-key k)
        e (first g)]
    (when (and (= 1 (count g)) (= 2 (count (:path e))) (= kind (:kind e)))
      e)))

(defn- fused-table-pairs
  "The :rename-table directives matching a removed/added whole-table
  pair among `groups` (ADR 0009), in directive order, each as
  `{:directive d :removed e :added e}`."
  [directives groups]
  (let [by-key (into {} (map (juxt (comp entry-group-key first) identity)) groups)]
    (vec (for [dv directives
               :when (= :rename-table (:directive dv))
               :let [removed (whole-table-entry by-key [:table (x/fold-name (:from dv))] :removed)
                     added (whole-table-entry by-key [:table (x/fold-name (:to dv))] :added)]
               :when (and removed added
                       ;; a virtual pair never fuses: no general
                       ;; alter or rebuild exists (ADR 0007)
                       (not (:virtual? (:live removed)))
                       (not (:virtual? (:declared added))))]
           {:directive dv :removed removed :added added}))))

(defn- fused-group-keys
  "The group keys the fused pairs consume — a fused pair's two groups
  plan together, so neither may plan again on its own."
  [fused]
  (into #{} (mapcat (fn [{:keys [removed added]}]
                      [(entry-group-key removed) (entry-group-key added)]))
    fused))

(defn- planning-context-for
  "The planning context every table planner threads: both Snapshots and
  what the phase-1 drops leave standing (ADR 0006)."
  [live declared entries]
  (let [dependents (surviving-dependents live entries)]
    {:live-snapshot live
     :declared-snapshot declared
     :surviving-dependents dependents
     :surviving-sqls (surviving-referencer-sqls dependents)}))

(defn- plan-entry-group
  "Plan one group of Diff entries — the view planner for a view group,
  the table planner otherwise."
  [capabilities planning-context claims group]
  (let [[kind nm] (:path (first group))]
    (if (= :view kind)
      (plan-view-group capabilities nm group)
      (plan-table-group capabilities planning-context claims nm group))))

(defn- collect-results
  "Fold the per-group results into the Plan's entry-facing pieces:
  `:ops` in execution order (ADR 0006), `:unhandled` in Diff-entry
  order as `{:entry e :refusals [...]}`, and `:used` — the set of
  directives some group consumed."
  [entries results]
  (let [by-entry (apply merge {} (map :unhandled results))]
    {:ops (mapv :op (sort-by :order (into [] (mapcat :ops) results)))
     :unhandled (into [] (keep (fn [e]
                                 (when-let [refusals (get by-entry e)]
                                   {:entry e :refusals refusals})))
                  entries)
     :used (into #{} (mapcat :used) results)}))

(defn plan
  "Plan `diff` — computed from Snapshots `live` and `declared` — into an
  ordered, self-contained Plan value (ADR 0006): `{:ops [...]
  :unhandled [...] :live-provenance ... :declared-provenance ...
  :capabilities ... :directives [...] :unused-directives [...]}` —
  plain EDN, list position is execution order, byte-identical for
  identical inputs (ADR 0010).

  Both Snapshots are required planning context, not options (ADR 0017):
  the Diff carries only their provenance, while planning a changed
  table — or a `:rename-table` directive fusing a removed/added pair —
  reads whole table values. A missing or non-Snapshot argument, and a
  Snapshot whose provenance is not the Diff's, each throw
  `:malformed-input` before any planning.

  Opts are exactly `:capabilities` (merged over the defaults — the live
  Snapshot's SQLite version plus `:rebuild? true`) and `:directives`
  (ADR 0009 — the intent channel; structurally validated before
  planning, echoed verbatim under `:directives`, the unmatched
  remainder reported in input order under `:unused-directives`).

  Every Diff entry is either served by ≥1 op or listed in `:unhandled`
  as `{:entry e :refusals [...]}` carrying every applicable Refusal
  (`{:class :code :explanation}`) — never throws for refusals."
  ([live declared diff] (plan live declared diff {}))
  ([live declared diff opts]
    (validate-context! live declared)
    (validate-provenance! live declared diff)
    (let [capabilities (merge {:sqlite-version (:sqlite-version (meta live))
                               :rebuild? true}
                         (:capabilities opts))
          directives (vec (:directives opts))
          _ (validate-directives! directives)
          entries (:entries diff)
          planning-context (planning-context-for live declared entries)
          claims (resolve-claims directives)
          groups (partition-by entry-group-key entries)
          fused (fused-table-pairs directives groups)
          consumed (fused-group-keys fused)
          results (into (into [] (keep (fn [g]
                                         (when-not (contains? consumed (entry-group-key (first g)))
                                           (plan-entry-group capabilities planning-context claims g))))
                          groups)
                    (map (fn [{:keys [directive removed added]}]
                           (plan-fused-table capabilities planning-context claims
                             removed added directive)))
                    fused)
          {:keys [ops unhandled used]} (collect-results entries results)]
      (check-completeness! entries ops unhandled)
      {:ops ops
       :unhandled unhandled
       :live-provenance (:live-provenance diff)
       :declared-provenance (:declared-provenance diff)
       :capabilities capabilities
       :directives directives
       :unused-directives (filterv (complement used) directives)})))
