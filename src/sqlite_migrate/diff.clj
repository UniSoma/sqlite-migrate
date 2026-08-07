(ns sqlite-migrate.diff
  "The Equivalence relation and the full Diff model (ADR 0003, 0004).

  One fixed, knobless relation over Snapshots, normalizing at
  comparison time only: identifiers compare ASCII-case-folded with
  quoting ignored, declared type text and opaque expressions compare as
  token sequences (Token comparison — whitespace and comments vanish,
  keywords and identifiers fold, string/blob literals stay byte-exact),
  named siblings pair by folded name. Physical column order, type text,
  constraint names, PK/index column order, and table flags stay
  Semantic. The Diff is plain EDN all the way down — entries embed both
  sides' verbatim sub-values with stored CREATE sql lifted out of
  Clojure meta into the value — and `pr-str`/`read-string` round-trips.

  Entries come out in a locked deterministic order: tables by folded
  name then views by folded name; inside a changed table the table-level
  entry first, then columns (declared order, then removed columns in
  live order), checks, uniques, foreign keys (named ones folded-name
  sorted, then unpaired unnamed ones live-then-declared in source
  order), indexes, and triggers (folded-name sorted). A view collapses
  to at most one whole-value entry — fine-grained entries exist only
  inside a changed table (ADR 0004)."
  (:require [clojure.set :as set]
    [sqlite-migrate.extract :as x]))

;; ---------------------------------------------------------------------------
;; Token comparison

(defn- fold [s]
  (some-> s x/fold-name))

(defn- token-key
  "The identity of one token under Token comparison: words and quoted
  identifiers collapse to their folded spelling (quoting is Noise);
  every other kind compares by verbatim text."
  [{:keys [t text] :as tok}]
  (case t
    (:word :qid) [:id (:fold tok)]
    [t text]))

(defn- opaque=
  "Token comparison over two opaque-expression texts; nil only equals
  nil."
  [a b]
  (cond
    (and (nil? a) (nil? b)) true
    (or (nil? a) (nil? b)) false
    :else (= (mapv token-key (x/tokenize a))
            (mapv token-key (x/tokenize b)))))

(defn- type=
  "Declared type text compares case-insensitively with whitespace
  normalized — as a token sequence, never by affinity (ADR 0003)."
  [a b]
  (opaque= (or a "") (or b "")))

;; ---------------------------------------------------------------------------
;; Entry construction

(defn- embed
  "A sub-value made self-contained for a Diff entry: stored CREATE sql
  lifted from Clojure meta into the value itself (`:sql`), recursively
  for nested indexes and triggers, so a serialized Diff travels alone."
  [v]
  (when v
    (let [lift #(if-let [sql (:sql (meta %))] (assoc % :sql sql) %)]
      (cond-> (lift v)
        (:indexes v) (update :indexes update-vals lift)
        (:triggers v) (update :triggers update-vals lift)))))

(defn- entry [kind path live declared]
  {:kind kind :path path :live (embed live) :declared (embed declared)})

(defn- changed-entry [path live declared facts]
  (assoc (entry :changed path live declared) :facts facts))

(defn- one-or-changed
  "The zero-or-one entries for an object present on `live`/`declared`
  sides: a whole-value :removed/:added entry when one-sided, a :changed
  entry with `(facts-fn live declared)` when both-sided and the fact
  set is non-empty."
  [path live declared facts-fn]
  (cond
    (nil? declared) [(entry :removed path live nil)]
    (nil? live) [(entry :added path nil declared)]
    :else (let [facts (facts-fn live declared)]
            (when (seq facts) [(changed-entry path live declared facts)]))))

(defn- fold-keyed
  "Re-key a `{name value}` sibling map by folded name."
  [m]
  (into {} (map (fn [[k v]] [(fold k) v])) m))

(defn- keyed-entries
  "Entries for two `{name value}` sibling maps paired by folded name
  (sibling order is Noise), folded-name sorted, path segment `kind`
  plus the object's own spelling (declared side's when present)."
  [parent-path kind live-m declared-m facts-fn]
  (let [lm (fold-keyed live-m)
        dm (fold-keyed declared-m)]
    (mapcat (fn [k]
              (let [l (lm k)
                    d (dm k)]
                (one-or-changed (conj parent-path kind (:name (or d l)))
                  l d facts-fn)))
      (sort (into #{} (concat (keys lm) (keys dm)))))))

;; ---------------------------------------------------------------------------
;; Compared facts, one keyword per fact (ADR 0004: no fact can differ
;; without a nameable keyword)

(defn- generated= [a b]
  (cond
    (and (nil? a) (nil? b)) true
    (or (nil? a) (nil? b)) false
    :else (and (= (:storage a) (:storage b))
            (opaque= (:expr a) (:expr b)))))

(defn- column-facts [l d]
  (cond-> #{}
    (not (type= (:type l) (:type d))) (conj :type)
    (not= (:not-null? l) (:not-null? d)) (conj :not-null?)
    (not (opaque= (:default l) (:default d))) (conj :default)
    (not= (fold (:collate l)) (fold (:collate d))) (conj :collate)
    (not (generated= (:generated l) (:generated d))) (conj :generated)))

(defn- primary-key= [a b]
  (cond
    (and (nil? a) (nil? b)) true
    (or (nil? a) (nil? b)) false
    :else (and (= (fold (:name a)) (fold (:name b)))
            (= (mapv fold (:columns a)) (mapv fold (:columns b))))))

(defn- shared-column-order
  "Folded column-name order restricted to the columns present on both
  sides — membership differences are the column entries' job; relative
  order of the shared columns is the :column-order fact's."
  [table shared]
  (into [] (comp (map (comp fold :name)) (filter shared)) (:columns table)))

(defn- table-facts
  "Table-scoped facts (ADR 0004): STRICT, WITHOUT ROWID, AUTOINCREMENT,
  physical column order, primary key."
  [l d]
  (let [shared (set/intersection
                 (into #{} (map (comp fold :name)) (:columns l))
                 (into #{} (map (comp fold :name)) (:columns d)))]
    (cond-> #{}
      (not= (:strict? l) (:strict? d)) (conj :strict?)
      (not= (:without-rowid? l) (:without-rowid? d)) (conj :without-rowid?)
      (not= (:autoincrement? l) (:autoincrement? d)) (conj :autoincrement?)
      (not= (shared-column-order l shared) (shared-column-order d shared)) (conj :column-order)
      (not (primary-key= (:primary-key l) (:primary-key d))) (conj :primary-key))))

(defn- check-facts [l d]
  (cond-> #{}
    (not (opaque= (:expr l) (:expr d))) (conj :expr)))

(defn- unique-facts [l d]
  (cond-> #{}
    (not= (mapv fold (:columns l)) (mapv fold (:columns d))) (conj :columns)))

(defn- foreign-key-facts [l d]
  (cond-> #{}
    (not= (mapv fold (:columns l)) (mapv fold (:columns d))) (conj :columns)
    (not= (fold (:ref-table l)) (fold (:ref-table d))) (conj :ref-table)
    (not= (mapv fold (:ref-columns l)) (mapv fold (:ref-columns d))) (conj :ref-columns)
    (not= (:on-update l) (:on-update d)) (conj :on-update)
    (not= (:on-delete l) (:on-delete d)) (conj :on-delete)
    (not= (:match l) (:match d)) (conj :match)
    (not (opaque= (:deferrable l) (:deferrable d))) (conj :deferrable)))

(defn- index-column= [a b]
  (and (= (fold (:name a)) (fold (:name b)))
    (opaque= (:expr a) (:expr b))
    (= (fold (:collate a)) (fold (:collate b)))
    (= (:desc? a) (:desc? b))))

(defn- index-facts [l d]
  (cond-> #{}
    (not= (:unique? l) (:unique? d)) (conj :unique?)
    (not= (:partial? l) (:partial? d)) (conj :partial?)
    (not (and (= (count (:columns l)) (count (:columns d)))
           (every? true? (map index-column= (:columns l) (:columns d))))) (conj :columns)
    (not (opaque= (:where l) (:where d))) (conj :where)))

(defn- sql-facts
  "Objects the Snapshot carries opaquely (triggers, views, virtual
  tables) compare by Token comparison over their stored CREATE sql."
  [l d]
  (cond-> #{}
    (not (opaque= (:sql (meta l)) (:sql (meta d)))) (conj :sql)))

;; ---------------------------------------------------------------------------
;; Fine-grained entries inside a changed table

(defn- column-entries
  "Column entries paired by folded name: added and changed columns in
  declared column order, then removed columns in live column order.
  Position differences are the table-level :column-order fact, never a
  column entry."
  [tpath l-cols d-cols]
  (let [lm (into {} (map (juxt (comp fold :name) identity)) l-cols)
        dm (into {} (map (juxt (comp fold :name) identity)) d-cols)]
    (concat
      (mapcat (fn [d]
                (one-or-changed (conj tpath :column (:name d))
                  (lm (fold (:name d))) d column-facts))
        d-cols)
      (for [l l-cols
            :when (not (contains? dm (fold (:name l))))]
        (entry :removed (conj tpath :column (:name l)) l nil)))))

(defn- constraint-entries
  "Entries for one constraint kind (checks, uniques, foreign keys).
  Named constraints pair by folded name and report their differing
  facts; unnamed constraints pair by token-equality (an empty fact set
  under `facts-fn`), with the unpaired remainder reported as
  added/removed (ADR 0004 — never ordinal pairing). Unnamed paths end
  in `[:live i]`/`[:declared i]` — the constraint's side plus its
  source-order position there — so paths stay unique across the whole
  Diff (a live and a declared unnamed constraint may share an index)."
  [tpath kind l-list d-list facts-fn]
  (let [named (fn [cs]
                (into {} (keep (fn [c] (when (:name c) [(fold (:name c)) c]))) cs))
        unnamed (fn [cs]
                  (vec (keep-indexed (fn [i c] (when-not (:name c) [i c])) cs)))
        ln (named l-list)
        dn (named d-list)
        named-entries
        (mapcat (fn [k]
                  (let [l (ln k)
                        d (dn k)]
                    (one-or-changed (conj tpath kind (:name (or d l)))
                      l d facts-fn)))
          (sort (into #{} (concat (keys ln) (keys dn)))))
        [l-rest d-rest]
        (reduce (fn [[l-rest d-rest] [_ lc :as lp]]
                  (if-let [[di _] (some (fn [[_ dc :as dp]]
                                          (when (empty? (facts-fn lc dc)) dp))
                                    d-rest)]
                    [l-rest (vec (remove #(= di (first %)) d-rest))]
                    [(conj l-rest lp) d-rest]))
          [[] (unnamed d-list)]
          (unnamed l-list))]
    (concat named-entries
      (for [[i c] l-rest] (entry :removed (conj tpath kind [:live i]) c nil))
      (for [[i c] d-rest] (entry :added (conj tpath kind [:declared i]) nil c)))))

(defn- table-entries
  "The entries for a table present on both sides: one table-level
  :changed entry when a table-scoped fact differs, plus fine-grained
  entries for columns, constraints, indexes, and triggers. A virtual
  table is opaque — it compares by stored CREATE sql alone (or flags
  :virtual? when only one side is virtual)."
  [l d]
  (let [tpath [:table (:name d)]]
    (if (or (:virtual? l) (:virtual? d))
      (if (not= (boolean (:virtual? l)) (boolean (:virtual? d)))
        [(changed-entry tpath l d #{:virtual?})]
        (when (seq (sql-facts l d))
          [(changed-entry tpath l d #{:sql})]))
      (concat
        (when-let [facts (seq (table-facts l d))]
          [(changed-entry tpath l d (set facts))])
        (column-entries tpath (:columns l) (:columns d))
        (constraint-entries tpath :check (:checks l) (:checks d) check-facts)
        (constraint-entries tpath :unique (:uniques l) (:uniques d) unique-facts)
        (constraint-entries tpath :foreign-key (:foreign-keys l) (:foreign-keys d) foreign-key-facts)
        (keyed-entries tpath :index (:indexes l) (:indexes d) index-facts)
        (keyed-entries tpath :trigger (:triggers l) (:triggers d) sql-facts)))))

(defn- view-triggers=
  "True when two `{name trigger}` maps pair exactly by folded name with
  token-equal stored CREATE sql on every pair."
  [lm dm]
  (let [l (fold-keyed lm)
        d (fold-keyed dm)]
    (and (= (set (keys l)) (set (keys d)))
      (every? (fn [[k lt]] (opaque= (:sql (meta lt)) (:sql (meta (d k))))) l))))

(defn- view-entries
  "The zero-or-one entries for a view present on both sides: one
  whole-value :changed entry when the stored CREATE sql or the trigger
  set differs — fine-grained entries exist only inside a changed table
  (ADR 0004), so a view collapses to `:facts` ⊆ #{:sql :triggers}."
  [l d]
  (let [facts (cond-> (sql-facts l d)
                (not (view-triggers= (:triggers l) (:triggers d))) (conj :triggers))]
    (when (seq facts)
      [(changed-entry [:view (:name d)] l d facts)])))

;; ---------------------------------------------------------------------------
;; The Diff

(defn diff
  "Compare two Snapshots `(live, declared)` into a Diff: a flat
  `:entries` vector plus both sides' Snapshot metadata (ADR 0004).

  Each entry is one self-contained Semantic difference: a
  target-relative `:kind` (`:added` = declared-only, `:removed` =
  live-only, `:changed` = both sides present, not Equivalent), a
  `:path` addressing the object, both sides' verbatim sub-values under
  `:live`/`:declared` (stored CREATE sql included), and for `:changed`
  the set of differing fact keywords under `:facts`. An object present
  on one side only is one whole-value entry; fine-grained entries exist
  only inside a changed table (a changed view is one whole-value
  :changed entry). Entries come out in the locked
  deterministic order (see the namespace docstring), so identical
  Snapshot pairs yield byte-identical serialized Diffs, and the whole
  value survives `pr-str`/`read-string`. Empty `:entries` iff the
  Snapshots are Equivalent."
  [live declared]
  (let [object-entries (fn [path-kind live-m declared-m both-fn]
                         (let [lm (fold-keyed live-m)
                               dm (fold-keyed declared-m)]
                           (mapcat (fn [k]
                                     (let [l (lm k)
                                           d (dm k)]
                                       (cond
                                         (nil? d) [(entry :removed [path-kind (:name l)] l nil)]
                                         (nil? l) [(entry :added [path-kind (:name d)] nil d)]
                                         :else (both-fn l d))))
                             (sort (into #{} (concat (keys lm) (keys dm)))))))]
    {:entries (vec (concat
                     (object-entries :table (:tables live) (:tables declared) table-entries)
                     (object-entries :view (:views live) (:views declared) view-entries)))
     :live-metadata (meta live)
     :declared-metadata (meta declared)}))
