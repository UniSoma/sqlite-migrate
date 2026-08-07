(ns sqlite-migrate.plan
  "The pure planner (ADR 0006, 0007): Diff entries in, an ordered,
  self-contained Plan out — every entry either served by ops or honestly
  unhandled with its full Refusal vector. Never throws for refusals;
  throwing is reserved for malformed input.

  In-place op vocabulary: `:create-table`, `:add-column`, `:drop-column`
  (restricted), `:set-not-null`/`:drop-not-null` and
  `:add-check`/`:drop-check` (target version 3.53+), and
  create/drop for indexes, triggers, and views. Everything else routes
  to a table rebuild — not implemented yet, so rebuild-routed entries go
  unhandled with `:rebuild-not-implemented` (or `:rebuild-disabled` when
  the `:rebuild?` capability is off). Selection is per table: one
  rebuild-routed entry collapses the table's entire change set (ADR
  0006 — never mix in-place and rebuild for one table).

  The locked phase order, baked into list position: (1) drop removed
  and changed secondary objects — triggers, then indexes, then views;
  (2) drop removed tables (never planned without intent — empty at this
  slice); (3) per-table change ops, tables folded-name-sorted, inside a
  table: drop checks, drop columns (reference-order so a generated
  column drops before the columns its expression reads), NOT NULL
  alters, add columns (declared order), add checks; (4) create added
  tables, folded-name-sorted; (5) create added and changed secondary
  objects — indexes, then views, then triggers. The planner exploits
  this order to legalize in-place forms (a covering index or CHECK
  drops before its column does) and verifies drop-column legality
  against the accumulated intermediate state.

  Capabilities are a flat map — the target `:sqlite-version` (defaulting
  to the live Snapshot's) plus `:rebuild?` (default true). A nil target
  version means \"latest\": every version gate passes."
  (:require [clojure.string :as str]
    [sqlite-migrate.extract :as x]))

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
(def ^:private v-generated "3.31.0")
(def ^:private v-strict "3.37.0")
(def ^:private v-alter-constraint "3.53.0")

;; ---------------------------------------------------------------------------
;; SQL emission

(defn- q-ident ^String [^String s]
  (str "\"" (str/replace s "\"" "\"\"") "\""))

(defn- column-def-sql
  "The column-definition text an :add-column op appends, re-emitted from
  the declared column value's verbatim facts."
  [{:keys [name type not-null? default collate generated]}]
  (str (q-ident name)
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

(defn- rebuild-refusal [{:keys [rebuild?]} what]
  (if rebuild?
    (refusal :incapable :rebuild-not-implemented
      (str what " can only converge through a table rebuild,"
        " which this planner does not implement yet"))
    (refusal :incapable :rebuild-disabled
      (str what " can only converge through a table rebuild"
        " and the :rebuild? capability is off"))))

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

(defn- op
  "An op with its plan-position sort key. Order vectors are padded to a
  fixed length: `compare` ranks vectors by length before content, and
  the phases emit keys of different arities."
  [order kind path serves sql]
  {:order (vec (take 6 (concat order (repeat 0))))
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
        gate (fn [ok? minimum what]
               (when-not ok?
                 (if (supports? capabilities minimum) nil [minimum what])))
        blocked (or (when (pos? (:pk col)) :pk)
                  (when-not appendable? :position)
                  (first
                    (keep identity
                      [(gate (or (not (:not-null? col)) (some? (:default col)))
                         v-alter-constraint "adding a NOT NULL column without a default")
                       (gate (not (current-word? (:default col)))
                         v-alter-constraint "adding a column with a CURRENT_* default")
                       (gate (not= :stored (:storage (:generated col)))
                         v-alter-constraint "adding a STORED generated column")
                       (gate (nil? (:generated col))
                         v-generated "adding a generated column")])))]
    (if blocked
      {:rebuild []}
      {:ops [(op [3 (x/fold-name tname) 3 "" -1] ; declared position patched by caller
               :add-column (:path entry) #{(:path entry)}
               [(str "ALTER TABLE " (q-ident tname) " ADD COLUMN " (column-def-sql col))])]})))

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
  [tname live-table col-fold
   {:keys [retained-indexes retained-checks dropped-col-folds surviving-sqls]}]
  (let [tfold (x/fold-name tname)]
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
      {:ops [(op [3 (x/fold-name tname) 2 (x/fold-name (entry-name entry))]
               (if (:not-null? (:declared entry)) :set-not-null :drop-not-null)
               (:path entry) #{(:path entry)}
               [(str "ALTER TABLE " (q-ident tname) " ALTER COLUMN "
                  (q-ident (entry-name entry))
                  (if (:not-null? (:declared entry)) " SET NOT NULL" " DROP NOT NULL"))])]}
      {:rebuild []})
    {:rebuild []}))

(defn- check-sort-key [c path]
  (if (:name c)
    [0 (x/fold-name (:name c)) -1]
    [1 "" (peek path)]))

(defn- add-check-op [tname entry]
  (let [c (:declared entry)]
    (op (into [3 (x/fold-name tname) 4] (check-sort-key c (:path entry)))
      :add-check (:path entry) #{(:path entry)}
      [(str "ALTER TABLE " (q-ident tname)
         " ADD " (when (:name c) (str "CONSTRAINT " (q-ident (:name c)) " "))
         "CHECK (" (:expr c) ")")])))

(defn- drop-check-op [tname entry]
  (let [c (:live entry)]
    (op (into [3 (x/fold-name tname) 0] (check-sort-key c (:path entry)))
      :drop-check (:path entry) #{(:path entry)}
      [(str "ALTER TABLE " (q-ident tname) " DROP CONSTRAINT " (q-ident (:name c)))])))

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
               :drop-index (str "DROP INDEX " (q-ident nm))
               :drop-trigger (str "DROP TRIGGER " (q-ident nm))
               :drop-view (str "DROP VIEW " (q-ident nm)))]
    (op [1 sub parent-fold (x/fold-name nm)] kind (:path entry) #{(:path entry)} [stmt])))

(defn- create-secondary-op [sub kind path serves parent-fold nm sql]
  (op [5 sub parent-fold (x/fold-name nm)] kind path serves [sql]))

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

(defn- route-table-entry
  "Route one entry of a changed regular table. `ctx` carries the live
  and declared table values plus the sets the intermediate-state checks
  need."
  [capabilities tname ctx entry]
  (let [seg (when (> (count (:path entry)) 2) (nth (:path entry) 2))]
    (cond
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
              destructive? (not= :virtual (get-in col [:generated :storage]))]
          (cond
            (not in-place?) {:rebuild (when destructive?
                                        [(destructive-refusal (entry-what entry))])}
            destructive? {:needs-intent [(destructive-refusal (entry-what entry))]}
            :else {:ops [(op [3 (x/fold-name tname) 1 (get (:drop-order ctx) col-fold)]
                           :drop-column (:path entry) #{(:path entry)}
                           [(str "ALTER TABLE " (q-ident tname)
                              " DROP COLUMN " (q-ident (:name col)))])]}))
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

(defn- surviving-referencer-sqls
  "The stored CREATE sql of every live view and trigger that survives
  the plan's phase-1 drops — the objects a drop-column must stay legal
  against. A :changed view's triggers recreate only in phase 5, so a
  dropped view excludes its triggers too."
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
        trigger-sqls (fn [obj]
                       (for [[nm trg] (:triggers obj)
                             :when (not (contains? dropped-triggers (x/fold-name nm)))]
                         (:sql (meta trg))))
        surviving-views (for [[nm v] (:views live-snapshot)
                              :when (not (contains? dropped-views (x/fold-name nm)))]
                          v)]
    (into []
      (remove nil?)
      (concat
        (mapcat trigger-sqls (vals (:tables live-snapshot)))
        (map (comp :sql meta) surviving-views)
        (mapcat trigger-sqls surviving-views)))))

(defn- plan-changed-table
  "Plan the entries of one changed regular table: route each entry, then
  apply ADR 0006's selection rule — one rebuild-routed entry collapses
  the whole table's change set into unhandled (never mix in-place and
  rebuild for one table). Returns `{:ops [...] :unhandled {entry
  refusals}}`."
  [capabilities opts tname entries]
  (let [live-table (table-context (:live-snapshot opts) :live tname)
        declared-table (table-context (:declared-snapshot opts) :declared tname)
        ctx (assoc (changed-table-ctx capabilities entries live-table declared-table)
              :surviving-sqls (:surviving-sqls opts))
        routed (mapv (fn [e] [e (route-table-entry capabilities tname ctx e)]) entries)
        collapse? (boolean (some (fn [[_ r]] (contains? r :rebuild)) routed))]
    (if collapse?
      {:ops []
       :unhandled (into {}
                    (map (fn [[e r]]
                           ;; a no-route (:refuse) entry keeps only its own
                           ;; refusals — a rebuild would not help it either
                           [e (if (:refuse r)
                                (vec (:refuse r))
                                (vec (concat (:rebuild r)
                                       (:needs-intent r)
                                       [(rebuild-refusal capabilities (entry-what e))])))]))
                    routed)}
      {:ops (declared-position declared-table (into [] (mapcat (comp :ops second)) routed))
       :unhandled (into {}
                    (keep (fn [[e r]]
                            (when-not (:ops r)
                              [e (vec (concat (:refuse r) (:needs-intent r)))])))
                    routed)})))

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
    (into [(op [4 tfold] :create-table (:path entry) serves [(:sql t)])]
      (concat
        (for [[nm idx] (sort-by key (:indexes t))]
          (create-secondary-op 0 :create-index (conj (:path entry) :index nm)
            serves tfold nm (:sql idx)))
        (for [[nm trg] (sort-by key (:triggers t))]
          (create-secondary-op 2 :create-trigger (conj (:path entry) :trigger nm)
            serves tfold nm (:sql trg)))))))

(defn- plan-table-group
  "Plan one table's entries: a whole-table entry stands alone; anything
  else is a changed table planned fine-grained."
  [capabilities opts tname entries]
  (let [whole (some #(when (= 2 (count (:path %))) %) entries)]
    (cond
      (and whole (= :added (:kind whole)))
      (let [unsupported (unsupported-object-refusals capabilities (:declared whole))]
        (if (seq unsupported)
          {:ops [] :unhandled {whole (vec unsupported)}}
          {:ops (create-table-ops whole) :unhandled {}}))

      (and whole (= :removed (:kind whole)))
      {:ops [] :unhandled {whole [(destructive-refusal (str "table " tname))]}}

      (and whole (or (:virtual? (:live whole)) (:virtual? (:declared whole))))
      {:ops []
       :unhandled {whole [(refusal :incapable :virtual-table-changed
                            (str "virtual table " tname " changed; its content lives in"
                              " module-owned shadow tables — no general alter or"
                              " rebuild exists"))]}}

      :else (plan-changed-table capabilities opts tname entries))))

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
  :capabilities ...}` — plain EDN, list position is execution order,
  byte-identical for identical inputs (ADR 0010).

  Opts: `:capabilities` (merged over the defaults — the live Snapshot's
  SQLite version plus `:rebuild? true`), and `:live-snapshot` /
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
        entries (:entries diff)
        opts (assoc opts :surviving-sqls
               (surviving-referencer-sqls (:live-snapshot opts) entries))
        groups (partition-by (fn [e] [(first (:path e)) (x/fold-name (second (:path e)))])
                 entries)
        results (mapv (fn [es]
                        (let [e (first es)
                              nm (second (:path e))]
                          (if (= :view (first (:path e)))
                            (plan-view-group capabilities nm es)
                            (plan-table-group capabilities opts nm es))))
                  groups)
        ops (mapv :op (sort-by :order (into [] (mapcat :ops) results)))
        by-entry (apply merge (map :unhandled results))
        unhandled (into [] (keep (fn [e]
                                   (when-let [refusals (get by-entry e)]
                                     {:entry e :refusals refusals})))
                    entries)]
    (check-completeness! entries ops unhandled)
    {:ops ops
     :unhandled unhandled
     :live-metadata (:live-metadata diff)
     :declared-metadata (:declared-metadata diff)
     :capabilities capabilities}))
