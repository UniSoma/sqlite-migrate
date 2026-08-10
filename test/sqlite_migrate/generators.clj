(ns sqlite-migrate.generators
  "The generators of the generative property suite (ADR 0010): a schema
  generator emitting shrinkable EDN Schema values that reach past the
  sugar's subset via the raw escape hatches, a mutation generator
  perturbing a schema into a nearby target (renames arriving with their
  matching Directive), and a row generator populating live files with
  conforming and violating rows. The fourth generator — the curated
  nasty corpus — rides in `sqlite-migrate.corpus` as deterministic
  seeds. Plain test.check generators, no deftest here."
  (:require [clojure.string :as str]
    [clojure.test.check.generators :as gen]))

;; ---------------------------------------------------------------------------
;; Identifier plumbing

(defn id-str
  "The string spelling of a Schema identifier (keyword or string)."
  [x]
  (if (keyword? x) (name x) x))

(defn fold
  "ASCII-case-folded identifier spelling — the equivalence relation's
  matching key (ADR 0003)."
  [x]
  (str/lower-case (id-str x)))

(defn qid
  "Quote an identifier for hand-built SQL, embedded quotes doubled."
  [x]
  (str "\"" (str/replace (id-str x) "\"" "\"\"") "\""))

;; Fresh names introduced by mutations use prefixes no generated name
;; can produce (generated first chars come from a-m; nasty names are a
;; fixed list), so collisions are impossible by construction.
(def ^:private fresh-column "znew")
(def ^:private fresh-table "znt")

;; ---------------------------------------------------------------------------
;; Schema generator

(def ^:private gen-plain-name
  (gen/fmap (fn [[c cs]] (apply str c cs))
    (gen/tuple (gen/elements (seq "abcdefghijkm"))
      (gen/vector (gen/elements (seq "abcdefghijkmopqrstuvwxy0123456789_")) 0 5))))

(def ^:private gen-identifier
  ;; keywords and strings both; nasty spellings (reserved words, case,
  ;; spaces, embedded quotes) stay strings
  (gen/frequency
    [[7 (gen/one-of [gen-plain-name (gen/fmap keyword gen-plain-name)])]
     [1 (gen/elements ["order" "group" "Select" "UPPER" "with space" "quo\"te" "mixedCase"])]]))

(def ^:private strict-type-keys [:int :integer :real :text :blob :any])

(def ^:private gen-type
  (gen/frequency
    [[8 (gen/elements strict-type-keys)]
     ;; the unchecked string type escape hatch (ADR 0002)
     [2 (gen/elements ["VARCHAR(16)" "NUMERIC" "DOUBLE PRECISION" "CLOB"])]]))

(def ^:private gen-check-expr
  ;; always-true opaque expressions: the tokenizer, diff, and rebuild
  ;; all carry them while the row generator never has to solve them
  (gen/elements [[:raw "1 = 1"] [:raw "2 > 1"] [:raw "'x' = 'x'"]
                 [:raw "1 IN (1, 2)"] [:raw "abs(1) >= 0"]]))

(def ^:private gen-default
  (gen/one-of
    [(gen/choose -9 99)
     (gen/elements [0.5 1.25 -2.75])
     (gen/elements ["d" "it's" "0123"])
     ;; opaque-expression defaults via the raw escape hatch
     (gen/elements [[:raw "(abs(-3))"] [:raw "(1 + 2)"] [:raw "(datetime('now'))"]])]))

(def ^:private gen-flag
  (gen/frequency [[4 (gen/return false)] [1 (gen/return true)]]))

(def ^:private gen-column
  (gen/let [nm gen-identifier
            t (gen/frequency [[9 gen-type] [1 (gen/return nil)]])
            not-null? gen-flag
            unique? gen-flag
            dflt (gen/frequency [[3 (gen/return nil)] [1 gen-default]])
            check (gen/frequency [[5 (gen/return nil)] [1 gen-check-expr]])
            collate (gen/frequency [[6 (gen/return nil)] [1 (gen/elements ["NOCASE" "RTRIM"])]])]
    (gen/return
      (cond-> {:name nm}
        (some? t) (assoc :type t)
        not-null? (assoc :not-null? true)
        unique? (assoc :unique? true)
        (some? dflt) (assoc :default dflt)
        (some? check) (assoc :check check)
        (some? collate) (assoc :collate collate)))))

(defn- numeric-affinity? [t]
  (let [s (some-> t id-str str/lower-case)]
    (and (some? s)
      (or (str/includes? s "int") (str/includes? s "real")
        (str/includes? s "doub") (str/includes? s "num")))))

(defn- build-indexes
  "Indexes for `table-name` over `cols`, one per shape tuple
  `[expr? unique? where?]`. Expression indexes wrap abs() and only
  target numeric columns (abs of text collapses every row to 0.0,
  which a unique index cannot survive)."
  [table-name cols shapes]
  (vec
    (for [[i [expr? unique? where?]] (map-indexed vector shapes)
          :let [col (nth cols (mod i (count cols)))
                cname (:name col)
                expr? (and expr? (numeric-affinity? (:type col)))]]
      (cond-> {:name (str "ix_" (fold table-name) "_" i)
               :columns [(if expr? [:raw (str "(abs(" (qid cname) "))")] cname)]}
        unique? (assoc :unique? true)
        where? (assoc :where [:raw (str (qid cname) " IS NOT NULL")])))))

(def ^:private gen-table
  (gen/let [nm gen-identifier
            raw-cols (gen/vector-distinct-by (comp fold :name) gen-column
                       {:min-elements 1 :max-elements 4})
            pk-kind (gen/frequency [[3 (gen/return :none)]
                                    [3 (gen/return :ipk)]
                                    [2 (gen/return :table-pk)]])
            auto? gen-flag
            strict? gen-flag
            without-rowid? gen-flag
            unique-col? gen-flag
            check (gen/frequency [[3 (gen/return nil)] [1 gen-check-expr]])
            named-check? gen-flag
            n-indexes (gen/choose 0 2)
            idx-shapes (gen/vector (gen/tuple gen-flag gen-flag gen-flag) 2)]
    (let [cols (vec (remove #(contains? #{"idpk" "ref0"} (fold (:name %))) raw-cols))
          cols (if (seq cols) cols [{:name "c0" :type :integer}])
          cols (if (= :ipk pk-kind)
                 (into [(cond-> {:name "idpk" :type :integer :primary-key? true}
                          auto? (assoc :autoincrement? true))]
                   cols)
                 cols)
          auto? (and auto? (= :ipk pk-kind))
          without-rowid? (and without-rowid? (not= :none pk-kind) (not auto?))
          strict? (and strict? (every? #(keyword? (:type %)) cols))
          table-pk (when (= :table-pk pk-kind)
                     (mapv :name (take (min 2 (count cols)) cols)))
          uniques (when (and unique-col? (> (count cols) 1))
                    [[(:name (peek cols))]])]
      (gen/return
        (cond-> {:name nm :columns cols}
          table-pk (assoc :primary-key table-pk)
          (seq uniques) (assoc :uniques uniques)
          (some? check) (assoc :checks [(if named-check?
                                          {:name (str "ck_" (fold nm)) :check check}
                                          check)])
          strict? (assoc :strict? true)
          without-rowid? (assoc :without-rowid? true)
          (pos? n-indexes) (assoc :indexes
                             (build-indexes nm cols (take n-indexes idx-shapes))))))))

(def gen-schema
  "Shrinkable EDN Schema values: 1-3 tables with mixed constraints and
  flags, plus escape-hatch territory — raw check/index/default
  expressions, string types, a raw view, a raw trigger, and a raw
  statement carrying a generated column the sugar cannot spell."
  (gen/let [tables (gen/vector-distinct-by (comp fold :name) gen-table
                     {:min-elements 1 :max-elements 3})
            fk? gen-flag
            view? gen-flag
            trigger? gen-flag
            raw-table? gen-flag]
    (let [t1 (first tables)
          ipk? (some :primary-key? (:columns t1))
          tables (if (and fk? (> (count tables) 1) ipk?)
                   (update tables 1
                     (fn [t2]
                       (-> t2
                         (update :columns conj {:name "ref0" :type :integer})
                         (assoc :foreign-keys [{:columns ["ref0"]
                                                :ref-table (:name t1)
                                                :ref-columns ["idpk"]}])
                         ;; a strict child stays strict-legal: ref0 is :integer
                         (dissoc :without-rowid?))))
                   tables)
          tables (if trigger?
                   (update tables 0 assoc :triggers
                     [(str "CREATE TRIGGER \"tg_main\" AFTER INSERT ON "
                        (qid (:name t1)) " BEGIN SELECT 1; END")])
                   tables)
          view (when view?
                 (str "CREATE VIEW \"v_main\" AS SELECT "
                   (qid (:name (first (:columns t1))))
                   " FROM " (qid (:name t1))))]
      (gen/return
        (cond-> {:tables tables}
          view (assoc :views [view])
          ;; generated columns live past the sugar — raw statement hatch
          raw-table? (assoc :raw
                       ["CREATE TABLE \"zz_raw\" (\"a\" INT, \"b\" INT GENERATED ALWAYS AS (\"a\" + 1) VIRTUAL)"]))))))

;; ---------------------------------------------------------------------------
;; Mutation generator

(defn- raw-texts
  "Every opaque SQL text in `schema`, lowercased — the conservative
  is-this-name-referenced-in-text corpus."
  [schema]
  (str/lower-case
    (str/join "\n"
      (concat
        (for [t (:tables schema)
              c (:columns t)
              :when (:check c)]
          (second (:check c)))
        (for [t (:tables schema)
              ck (:checks t)]
          (second (if (map? ck) (:check ck) ck)))
        (for [t (:tables schema)
              ix (:indexes t)
              c (:columns ix)
              :when (vector? c)]
          (second c))
        (for [t (:tables schema)
              ix (:indexes t)
              :when (:where ix)]
          (second (:where ix)))
        (mapcat :triggers (:tables schema))
        (map #(if (vector? %) (second %) %) (:views schema))
        (map #(if (vector? %) (second %) %) (:raw schema))))))

(defn- text-referenced? [texts nm]
  ;; quoted spellings double embedded quotes, so check both forms
  (or (str/includes? texts (fold nm))
    (str/includes? texts (str/replace (fold nm) "\"" "\"\""))))

(defn- pk-col-names
  "Folded names of every primary-key column of `table` (column-level
  and table-level)."
  [table]
  (into (set (for [c (:columns table) :when (:primary-key? c)] (fold (:name c))))
    (let [pk (:primary-key table)
          cols (if (map? pk) (:columns pk) pk)]
      (map fold cols))))

(defn- fk-col-names [table]
  (set (map fold (mapcat :columns (:foreign-keys table)))))

(defn- unique-col-names
  "Folded names of columns already under a live uniqueness promise:
  column-level UNIQUE, table-level uniques, unique indexes, the PK."
  [table]
  (-> (set (for [c (:columns table) :when (:unique? c)] (fold (:name c))))
    (into (pk-col-names table))
    (into (for [u (:uniques table)
                c (if (map? u) (:columns u) u)]
            (fold c)))
    (into (for [ix (:indexes table)
                :when (:unique? ix)
                c (:columns ix)
                nm (if (vector? c)
                     ;; an expression entry covers every column its raw
                     ;; text mentions — abs() collapses distinct values,
                     ;; so duplicates there are a live-index matter too
                     (let [txt (str/lower-case (second c))]
                       (for [col (:columns table)
                             :when (str/includes? txt (fold (:name col)))]
                         (:name col)))
                     [c])]
            (fold nm)))))

(defn- update-table [schema tname f]
  (update schema :tables
    (fn [ts] (mapv #(if (= (fold (:name %)) (fold tname)) (f %) %) ts))))

(defn- rename-in-table
  "Structural column rename inside a table: the column itself plus every
  structured reference (table PK, uniques, plain index columns, FK
  child columns). Raw-text references are the caller's exclusion."
  [table from to]
  (let [r #(if (= (fold %) (fold from)) to %)
        rename-key-list (fn [u] (if (map? u) (update u :columns #(mapv r %)) (mapv r u)))]
    (cond-> (update table :columns (fn [cs] (mapv #(update % :name r) cs)))
      (:primary-key table) (update :primary-key rename-key-list)
      (:uniques table) (update :uniques #(mapv rename-key-list %))
      (:indexes table) (update :indexes
                         (fn [ixs]
                           (mapv (fn [ix]
                                   (update ix :columns
                                     (fn [cs] (mapv #(if (vector? %) % (r %)) cs))))
                             ixs)))
      (:foreign-keys table) (update :foreign-keys
                              (fn [fks] (mapv #(update % :columns (fn [cs] (mapv r cs))) fks))))))

(defn- default-for
  "A constant DEFAULT matching `t` even under STRICT — blob columns get
  a blob literal via the raw hatch (still a constant, ADR 0015)."
  [t]
  (case (some-> t id-str str/lower-case)
    ("blob" nil) [:raw "X'07'"]
    ("text" "varchar(16)" "clob") "d"
    ("real" "double precision") 7.5
    7))

(defn- gen-added-column
  "A column for the add-column mutation. Shapes cover the gate corners:
  NOT NULL without a default (:empty-table gate), NOT NULL with one,
  and a UNIQUE key over the new column with a constant default (ADR
  0015). Opaque-expression defaults on new key columns are the
  documented bidirectionality exclusion, so they are never generated."
  [table]
  (gen/let [shape (gen/frequency [[3 (gen/return :plain)]
                                  [1 (gen/return :not-null-no-default)]
                                  [1 (gen/return :not-null-default)]
                                  [1 (gen/return :unique-const-default)]])
            t (if (:strict? table) (gen/elements strict-type-keys) gen-type)]
    (gen/return
      [shape
       (case shape
         :plain {:name fresh-column :type t}
         :not-null-no-default {:name fresh-column :type t :not-null? true}
         :not-null-default {:name fresh-column :type t :not-null? true :default (default-for t)}
         :unique-const-default {:name fresh-column :type t :unique? true :default (default-for t)})])))

(defn- scenario-map
  [live target mutation directives]
  {:live live
   :target target
   :mutation mutation
   :directives (vec directives)})

(defn- mutation-gens
  "One generator per applicable concrete mutation of `live`; the caller
  one-ofs over them. Add-column and add-table are always applicable, so
  the vector is never empty."
  [live]
  (let [texts (raw-texts live)
        tables (:tables live)
        fk-ref-tables (set (for [t tables, fk (:foreign-keys t)] (fold (:ref-table fk))))
        gen-for-table (fn [f] (gen/let [t (gen/elements tables)] (f t)))]
    ;; threaded through `concat`: the two always-applicable generators
    ;; first, then one collection of candidates per mutation family
    (-> [;; add-column
         (gen-for-table
           (fn [t]
             (gen/let [[shape col] (gen-added-column t)]
               (gen/return
                 (scenario-map live
                   (update-table live (:name t) #(update % :columns conj col))
                   {:kind :add-column :table (:name t) :column fresh-column :shape shape}
                   [])))))
         ;; add-table
         (gen/return
           (scenario-map live
             (update live :tables conj {:name fresh-table :columns [{:name "za" :type :integer}]})
             {:kind :add-table :table fresh-table}
             []))]
      (concat
        (for [t tables
              :let [protected (-> (pk-col-names t) (into (fk-col-names t)))
                    droppable (vec (for [c (:columns t)
                                         :let [f (fold (:name c))]
                                         :when (and (not (protected f))
                                                 (not (text-referenced? texts (:name c)))
                                                 (not (contains? (unique-col-names t) f))
                                                 (not (some (fn [ix]
                                                              (some #(and (not (vector? %))
                                                                       (= f (fold %)))
                                                                (:columns ix)))
                                                        (:indexes t)))
                                                 (> (count (:columns t)) 1))]
                                     (:name c)))]
              :when (seq droppable)]
          ;; drop-column, authorized half the time — both the executed
          ;; drop and the standing :needs-intent refusal get exercised
          (gen/let [cname (gen/elements droppable)
                    authorized? gen/boolean]
            (gen/return
              (scenario-map live
                (update-table live (:name t)
                  #(update % :columns (fn [cs] (vec (remove (fn [c] (= (fold (:name c)) (fold cname))) cs)))))
                {:kind :drop-column :table (:name t) :column cname :authorized? authorized?}
                (when authorized?
                  [{:directive :drop-column :table (id-str (:name t)) :column (id-str cname)}])))))
        (for [t tables
              :let [protected (-> (pk-col-names t) (into (fk-col-names t)))
                    renameable (vec (for [c (:columns t)
                                          :let [f (fold (:name c))]
                                          :when (and (not (protected f))
                                                  (not (text-referenced? texts (:name c))))]
                                      (:name c)))]
              :when (seq renameable)]
          ;; rename-column, always with its matching Directive
          (gen/let [cname (gen/elements renameable)]
            (gen/return
              (scenario-map live
                (update-table live (:name t) #(rename-in-table % cname "znc"))
                {:kind :rename-column :table (:name t) :column cname :to "znc"}
                [{:directive :rename-column :table (id-str (:name t))
                  :from (id-str cname) :to "znc"}]))))
        (for [t tables
              :let [protected (-> (pk-col-names t) (into (fk-col-names t)))
                    ;; nullable, non-strict-table, text-unreferenced columns
                    ;; only: the row generator keeps their values fixpoints
                    ;; under any affinity change
                    retypeable (vec (for [c (:columns t)
                                          :let [f (fold (:name c))]
                                          :when (and (some? (:type c))
                                                  (not (:not-null? c))
                                                  (not (:strict? t))
                                                  (not (protected f))
                                                  (not (text-referenced? texts (:name c))))]
                                      c))]
              :when (seq retypeable)]
          (gen/let [c (gen/elements retypeable)
                    nt (gen/such-that #(not= % (:type c)) gen-type 50)]
            (gen/return
              (scenario-map live
                (update-table live (:name t)
                  #(update % :columns
                     (fn [cs] (mapv (fn [col] (if (= (fold (:name col)) (fold (:name c)))
                                                (assoc col :type nt)
                                                col))
                                cs))))
                {:kind :retype :table (:name t) :column (:name c) :to-type nt}
                []))))
        (for [t tables
              :when (> (count (:columns t)) 1)]
          ;; reorder: physical column order is semantic (ADR 0003) — a
          ;; pure reorder forces the rebuild path
          (gen/return
            (scenario-map live
              (update-table live (:name t)
                #(update % :columns (fn [cs] (conj (vec (rest cs)) (first cs)))))
              {:kind :reorder :table (:name t)}
              [])))
        (for [t tables
              :let [protected (pk-col-names t)
                    candidates (vec (remove #(protected (fold (:name %))) (:columns t)))]
              :when (seq candidates)]
          (gen/let [c (gen/elements candidates)]
            (let [adding? (not (:not-null? c))]
              (gen/return
                (scenario-map live
                  (update-table live (:name t)
                    #(update % :columns
                       (fn [cs] (mapv (fn [col] (if (= (fold (:name col)) (fold (:name c)))
                                                  (if adding?
                                                    (assoc col :not-null? true)
                                                    (dissoc col :not-null?))
                                                  col))
                                  cs))))
                  {:kind :toggle-not-null :table (:name t) :column (:name c) :adding? adding?}
                  [])))))
        (for [t tables
              :let [taken (unique-col-names t)
                    candidates (vec (for [c (:columns t)
                                          :when (not (taken (fold (:name c))))]
                                      (:name c)))]
              :when (seq candidates)]
          (gen/let [cname (gen/elements candidates)]
            (gen/return
              (scenario-map live
                (update-table live (:name t)
                  #(update % :uniques (fnil conj []) [cname]))
                {:kind :toggle-unique :table (:name t) :column cname :adding? true}
                []))))
        (for [t tables
              :when (seq (:uniques t))]
          (gen/return
            (scenario-map live
              (update-table live (:name t) #(update % :uniques (comp vec butlast)))
              {:kind :toggle-unique :table (:name t) :adding? false}
              [])))
        (for [t tables
              :when (if (:strict? t)
                      true
                      (every? #(keyword? (:type %)) (:columns t)))]
          (gen/return
            (scenario-map live
              (update-table live (:name t)
                #(if (:strict? %) (dissoc % :strict?) (assoc % :strict? true)))
              {:kind :toggle-strict :table (:name t) :adding? (not (:strict? t))}
              [])))
        (for [t tables
              :when (or (:without-rowid? t)
                      (and (seq (pk-col-names t))
                        (not (some :autoincrement? (:columns t)))))]
          (gen/return
            (scenario-map live
              (update-table live (:name t)
                #(if (:without-rowid? %) (dissoc % :without-rowid?) (assoc % :without-rowid? true)))
              {:kind :toggle-without-rowid :table (:name t) :adding? (not (:without-rowid? t))}
              [])))
        (for [t tables
              :let [nm (:name t)]
              :when (and (not (fk-ref-tables (fold nm)))
                      (not (text-referenced? texts nm)))]
          ;; drop-table, authorized half the time
          (gen/let [authorized? gen/boolean]
            (gen/return
              (scenario-map live
                (update live :tables
                  (fn [ts] (vec (remove #(= (fold (:name %)) (fold nm)) ts))))
                {:kind :drop-table :table nm :authorized? authorized?}
                (when authorized?
                  [{:directive :drop-table :table (id-str nm)}])))))
        (for [t tables
              :let [nm (:name t)]
              :when (and (not (fk-ref-tables (fold nm)))
                      (not (text-referenced? texts nm)))]
          ;; rename-table, always with its matching Directive
          (gen/return
            (-> (scenario-map live
                  (update-table live nm #(assoc % :name "znt2"))
                  {:kind :rename-table :table nm :to "znt2"}
                  [{:directive :rename-table :from (id-str nm) :to "znt2"}])
              (assoc :table-rename [nm "znt2"]))))))))

(defn gen-mutation
  "Generator of a scenario for `live`: `{:live :target :mutation
  :directives}` (plus `:table-rename` when the mutation renames a
  table), the target one perturbation away and renames arriving with
  their matching Directive."
  [live]
  (gen/one-of (vec (mutation-gens live))))

;; ---------------------------------------------------------------------------
;; Row generator

(defn- literal
  "A distinct-per-row SQL literal for row `i` matching the column
  type's affinity (STRICT included)."
  [t i]
  (let [s (some-> t id-str str/lower-case)]
    (cond
      (nil? s) (format "X'%04X'" (inc i))
      (str/includes? s "int") (str (inc i))
      (or (str/includes? s "char") (str/includes? s "clob") (str/includes? s "text"))
      (str "'v" i "'")
      (str/includes? s "blob") (format "X'%04X'" (inc i))
      (or (str/includes? s "real") (str/includes? s "doub") (str/includes? s "floa"))
      (str (+ i 1.5))
      :else (str (inc i)))))

(defn- row-literals
  "Per-column literals for row `i` of `table`: FK child columns stay
  NULL (always FK-clean), a retyped column gets non-numeric text — a
  fixpoint under any affinity change — and `overrides` (folded name →
  literal) win last."
  [table fk-cols retype-col i overrides]
  (vec
    (for [c (:columns table)
          :let [f (fold (:name c))]]
      (cond
        (contains? overrides f) (overrides f)
        (fk-cols f) "NULL"
        (= retype-col f) (str "'zz" i "'")
        :else (literal (:type c) i)))))

(defn- column-literal
  "The literal `row-literals` writes for `table`'s column named `cname`
  in row `i` — how an existing row actually spells that column, so a
  violating extra can duplicate it exactly."
  [table fk-cols retype-col cname i]
  (some (fn [[c lit]] (when (= (fold cname) (fold (:name c))) lit))
    (map vector (:columns table) (row-literals table fk-cols retype-col i {}))))

(defn- insert-sql [table literals]
  (str "INSERT INTO " (qid (:name table))
    " (" (str/join ", " (map (comp qid :name) (:columns table))) ")"
    " VALUES (" (str/join ", " literals) ")"))

(defn probe-insert-sql
  "An INSERT for `table` that omits its AUTOINCREMENT pk and fills every
  other column with row-`i` literals (FK children stay NULL) — the
  AUTOINCREMENT-continuity property's post-Apply probe. A table whose
  only column is that pk leaves nothing to name, so the INSERT takes
  SQLite's `DEFAULT VALUES` form instead of an empty column list."
  [table i]
  (let [cols (vec (remove :autoincrement? (:columns table)))
        fk (fk-col-names table)]
    (str "INSERT INTO " (qid (:name table))
      (if (empty? cols)
        " DEFAULT VALUES"
        (str " (" (str/join ", " (map (comp qid :name) cols)) ")"
          " VALUES ("
          (str/join ", " (for [c cols]
                           (if (fk (fold (:name c))) "NULL" (literal (:type c) i))))
          ")")))))

(defn- table-inserts
  "INSERT statements for one live table: `n` conforming rows, the
  mutation's violating extras when `violate?`, and — for AUTOINCREMENT
  tables — an insert-then-delete that advances the sequence past every
  existing id (the continuity property's precondition)."
  [table {:keys [kind shape column adding?]} mine? n violate?]
  (let [fk-cols (fk-col-names table)
        retype-col (when (and mine? (= :retype kind)) (fold column))
        n (if (and mine? (= :add-column kind))
            (case shape
              ;; the gate corners: rows present iff the trial violates
              :unique-const-default (if violate? (max n 2) (min n 1))
              :not-null-no-default (if violate? (max n 1) 0)
              n)
            n)
        n (if (and mine? violate? (= :toggle-unique kind) adding?) (max n 1) n)
        base (mapv #(row-literals table fk-cols retype-col % {}) (range n))
        extras (when (and mine? violate?)
                 (case kind
                   :toggle-not-null
                   (when adding?
                     [(row-literals table fk-cols retype-col n {(fold column) "NULL"})])
                   :toggle-unique
                   ;; the duplicate must repeat what row 0 really holds:
                   ;; an FK child column holds NULL there, and NULLs
                   ;; never collide — nor could a non-NULL one, with no
                   ;; parent row to point at — so that column yields no
                   ;; violating row at all
                   (when adding?
                     (let [dup (column-literal table fk-cols retype-col column 0)]
                       (when-not (= "NULL" dup)
                         [(row-literals table fk-cols retype-col n {(fold column) dup})])))
                   :toggle-strict
                   ;; rowid-alias INTEGER PKs reject non-integers even
                   ;; without STRICT — the live insert itself would fail
                   (when adding?
                     (when-let [c (first (filter #(and (numeric-affinity? (:type %))
                                                    (not (fk-cols (fold (:name %))))
                                                    (not (:primary-key? %))
                                                    (not ((pk-col-names table) (fold (:name %)))))
                                           (:columns table)))]
                       [(row-literals table fk-cols retype-col n {(fold (:name c)) "'zz'"})]))
                   nil))
        rows (into base extras)
        auto? (some :autoincrement? (:columns table))
        high (when auto? (inc (count rows)))]
    {:inserts (cond-> (mapv #(insert-sql table %) rows)
                ;; advance sqlite_sequence past every id ever issued
                auto? (into [(insert-sql table (row-literals table fk-cols retype-col (count rows) {}))
                             (str "DELETE FROM " (qid (:name table))
                               " WHERE \"idpk\" = " high)]))
     :count (count rows)
     :auto-high high}))

(defn gen-rows
  "Generator of the live file's row load for a scenario: conforming
  rows for every table plus, on violating trials, rows that break the
  mutation's target constraint — driving both gate directions. Yields
  `{:inserts [sql ...] :violate? bool :counts {folded-table n}
  :auto-highs {folded-table id}}`."
  [{:keys [live mutation]}]
  (gen/let [n (gen/choose 0 3)
            violate? (gen/frequency [[2 (gen/return false)] [1 (gen/return true)]])]
    (let [per-table (into {}
                      (for [t (:tables live)]
                        [(fold (:name t))
                         (table-inserts t mutation
                           (= (fold (:name t)) (fold (:table mutation))) n violate?)]))]
      (gen/return
        {:inserts (vec (mapcat :inserts (vals per-table)))
         :violate? violate?
         :counts (update-vals per-table :count)
         :auto-highs (into {} (for [[k v] per-table :when (:auto-high v)] [k (:auto-high v)]))}))))

(def gen-rowless-scenario
  "A scenario without its row load: a live Schema and a mutation into a
  nearby target, Directives riding along. Properties that never realize
  rows take this one."
  (gen/bind gen-schema gen-mutation))

(defn mutated-table-names
  "The folded table names `scenario`'s mutation can put in a Diff
  entry's path: the mutated table, plus its post-rename spelling when
  the mutation renames the table."
  [{:keys [mutation table-rename]}]
  (cond-> #{(fold (:table mutation))}
    table-rename (conj (fold (second table-rename)))))

(def gen-scenario
  "A full generative trial: a `gen-rowless-scenario` plus the live row
  load its live Schema and mutation call for."
  (gen/let [scenario gen-rowless-scenario
            rows (gen-rows scenario)]
    (gen/return (assoc scenario :rows rows))))
