(ns ^:no-doc sqlite-migrate.impl.dependents
  "What stands while a Plan runs, and who reads whom (ADR 0006, 0026):
  the live views and triggers that survive the phase-1 and phase-2
  drops, the ones a Rebuild must drop and recreate around its rename,
  and the readers of each changed view that phase 1 takes down with it.

  One lexical mention graph over the surviving views
  (`sqlite-migrate.impl.lexical/mentions?`, conservative by design)
  answers every question here: which views depend on a seed, in what
  order they recreate, and which changed-view entries a reader serves.
  Pure; never builds an Op. Depends on `impl.lexical` and `impl.util`
  only."
  (:require [sqlite-migrate.impl.lexical :as lex]
    [sqlite-migrate.impl.util :as u]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; The mention graph

(defn- name-folds
  "The folded `:name` of each object in `objects`, as a set."
  [objects]
  (into #{} (map (comp u/fold-name :name)) objects))

(defn- view-reads
  "The mention graph of `views`: folded view name to the set of folds
  among the other views and `extra-folds` its stored sql mentions."
  [views extra-folds]
  (let [candidates (into (name-folds views) extra-folds)]
    (into {}
      (map (fn [{:keys [name sql]}]
             (let [f (u/fold-name name)]
               [f (into #{} (filter #(and (not= f %) (lex/mentions? sql %))) candidates)])))
      views)))

(defn- reachable
  "The folds reachable from `f` along `edges`."
  [edges f]
  (loop [seen #{} todo (vec (edges f))]
    (if-let [g (peek todo)]
      (if (contains? seen g)
        (recur seen (pop todo))
        (recur (conj seen g) (into (pop todo) (edges g))))
      seen)))

(defn- inverted
  "`edges` with every edge reversed."
  [edges]
  (reduce (fn [m [f gs]] (reduce #(update %1 %2 (fnil conj #{}) f) m gs)) {} edges))

(defn- dependent-view-folds
  "The folds of the views in `reads` that mention a seed, or a view
  already in the set."
  [reads seeds]
  (let [direct (into #{} (keep (fn [[f gs]] (when (some seeds gs) f))) reads)]
    (into direct (mapcat #(reachable (inverted reads) %)) direct)))

(defn- dependency-order
  "`views` ordered so each follows the views among them it mentions in
  `reads`, ties by folded name. The members of a cycle — only a
  conservative false mention can close one — take name order among
  themselves."
  [views reads]
  (let [by-fold (into (sorted-map) (map (juxt (comp u/fold-name :name) identity)) views)
        folds (set (keys by-fold))
        reads (into {} (map (fn [f] [f (into #{} (filter folds) (reads f))])) folds)
        reach (into {} (map (juxt identity #(reachable reads %))) folds)
        cycle-of (fn [f] (into (sorted-set f) (filter #(contains? (reach %) f)) (reach f)))]
    (loop [placed [] pending (into (sorted-set) folds)]
      (if (empty? pending)
        placed
        (let [members (some (fn [f]
                              (let [group (cycle-of f)]
                                (when (not-any? #(and (pending %) (not (group %)))
                                        (mapcat reads group))
                                  group)))
                        pending)]
          (recur (into placed (map by-fold) members) (reduce disj pending members)))))))

;; ---------------------------------------------------------------------------
;; Survivors

(defn surviving
  "The live views and triggers that survive the plan's phase-1 and
  phase-2 drops — the objects a drop-column must stay legal against
  and a rebuild's rename must not orphan. `:views` carries each
  surviving view with its surviving triggers; `:table-triggers` each
  surviving trigger of a table parent — names and stored CREATE sql. A
  :changed object's recreate lands only in phase 5, so a dropped view
  excludes its triggers too, and so does a table removed under a
  :drop-table Directive in `claims`: its triggers nest in its
  whole-value entry and leave with it in phase 2 (ADR 0023). The
  readers of a changed view are still among them (ADR 0026)."
  [live-snapshot claims entries]
  (let [dropped? (fn [e] (contains? #{:removed :changed} (:kind e)))
        whole-folds (fn [object pred]
                      (into #{}
                        (comp (filter #(and (= object (first (:path %)))
                                         (= 2 (count (:path %)))
                                         (pred %)))
                          (map (comp u/fold-name second :path)))
                        entries))
        dropped-tables (into #{} (filter (:drop-tables claims))
                         (whole-folds :table #(= :removed (:kind %))))
        dropped-views (whole-folds :view dropped?)
        dropped-triggers (into #{}
                           (comp (filter #(and (= :trigger (nth (:path %) 2 nil))
                                            (dropped? %)))
                             (map (comp u/fold-name peek :path)))
                           entries)
        surviving-triggers (fn [obj]
                             (vec (for [[nm trg] (sort-by key (:triggers obj))
                                        :when (not (contains? dropped-triggers (u/fold-name nm)))]
                                    {:name nm :sql (:sql (meta trg))})))]
    {:views (vec (for [[nm v] (sort-by key (:views live-snapshot))
                       :when (not (contains? dropped-views (u/fold-name nm)))]
                   {:name nm :sql (:sql (meta v)) :triggers (surviving-triggers v)}))
     :table-triggers (vec (for [[tn t] (sort-by key (:tables live-snapshot))
                                :when (not (contains? dropped-tables (u/fold-name tn)))
                                trg (surviving-triggers t)]
                            (assoc trg :table tn)))}))

(defn referencer-sqls
  "The stored CREATE sql of every object in `survivors` — the flat text
  the drop-column legality check reads."
  [{:keys [views table-triggers]}]
  (into []
    (remove nil?)
    (concat
      (map :sql table-triggers)
      (map :sql views)
      (mapcat #(map :sql (:triggers %)) views))))

(defn- dependents-of
  "`rebuild-dependents` over the mention graph `reads` of
  `survivors`' views, built with `seeds` among its candidates."
  [{:keys [views table-triggers]} reads seeds]
  (let [dep-view-folds (dependent-view-folds reads seeds)
        referenced (into dep-view-folds seeds)
        references? (fn [sql] (some #(lex/mentions? sql %) referenced))
        loose-view-triggers (for [v views
                                  :when (not (contains? dep-view-folds (u/fold-name (:name v))))
                                  trg (:triggers v)
                                  :when (references? (:sql trg))]
                              (assoc trg :view (:name v)))
        loose-table-triggers (for [trg table-triggers
                                   :when (and (not (contains? seeds (u/fold-name (:table trg))))
                                           (references? (:sql trg)))]
                               trg)]
    {:views (dependency-order
              (filterv #(contains? dep-view-folds (u/fold-name (:name %))) views)
              reads)
     :triggers (vec (concat loose-view-triggers loose-table-triggers))}))

(defn rebuild-dependents
  "The views and triggers among `survivors` that lexically read one of
  the `seeds` — folded names of tables or views that go missing while
  the survivors stand. SQLite reparses every view and trigger during
  ALTER TABLE RENAME, so such a survivor would fail the rename. Returns
  `{:views [...] :triggers [...]}` — every view that reads a seed
  directly or through another such view, in dependency order and with
  its surviving triggers, plus the triggers of other parents that
  reference a seed or one of those views, each carrying its parent
  under `:table` or `:view`. A seed's own triggers are left to the
  caller."
  [survivors seeds]
  (dependents-of survivors (view-reads (:views survivors) seeds) seeds))

;; ---------------------------------------------------------------------------
;; Readers of a changed view (ADR 0026): phase 1 drops them with the
;; view and phase 5 creates them again, so no view or trigger reads a
;; missing view while phase 3 runs

(defn- changed-view-paths
  "The Diff path of each :changed view entry, keyed by folded view
  name."
  [entries]
  (into {}
    (keep (fn [{:keys [kind path]}]
            (when (and (= :view (first path)) (= 2 (count path)) (= :changed kind))
              [(u/fold-name (second path)) path])))
    entries))

(defn- readers-of
  "The Readers of the changed views among `entries`: the
  `rebuild-dependents` of those views among `survivors`, with the
  triggers of a table a `fused` pair renames left out — the Diff pairs
  each with the renamed table as a changed trigger, and the fused plan
  drops and creates it. Carries, for the questions below, the mention
  graph it was found with, the changed-view paths, and the folds of
  the views missing between phases 1 and 5."
  [survivors entries fused]
  (let [changed-paths (changed-view-paths entries)
        reads (view-reads (:views survivors) (keys changed-paths))
        renamed (into #{} (map (comp u/fold-name second :path :removed)) fused)
        readers (update (dependents-of survivors reads (set (keys changed-paths))) :triggers
                  (fn [trgs] (filterv #(not (some-> (:table %) u/fold-name renamed)) trgs)))]
    (assoc readers
      :reads reads
      :changed-paths changed-paths
      :missing-view-folds (into (set (keys changed-paths)) (name-folds (:views readers)))
      :trigger-folds (name-folds (:triggers readers)))))

(defn- without-readers
  "`survivors` less the reader views and reader triggers in `readers` —
  those leave in phase 1, so neither a Rebuild nor drop-column legality
  counts them."
  [survivors {:keys [trigger-folds] :as readers}]
  (let [view-folds (name-folds (:views readers))
        keep-triggers (fn [trgs] (filterv #(not (contains? trigger-folds (u/fold-name (:name %)))) trgs))]
    {:views (into []
              (comp (remove #(contains? view-folds (u/fold-name (:name %))))
                (map #(update % :triggers keep-triggers)))
              (:views survivors))
     :table-triggers (keep-triggers (:table-triggers survivors))}))

(defn reads-missing-view?
  "True when `sql` mentions a view missing between phases 1 and 5 — a
  changed view or one of its readers."
  [{:keys [missing-view-folds]} sql]
  (boolean (some #(lex/mentions? sql %) missing-view-folds)))

(defn reader-trigger?
  "True when `trigger-name` is one of the reader triggers."
  [{:keys [trigger-folds]} trigger-name]
  (contains? trigger-folds (u/fold-name trigger-name)))

(defn reader-serves
  "The paths of the changed-view entries an object with stored `sql`
  reaches: those it mentions, and those the reader views it mentions
  reach in turn."
  [{:keys [reads changed-paths views]} sql]
  (let [candidates (into (set (keys changed-paths)) (name-folds views))
        direct (into #{} (filter #(lex/mentions? sql %)) candidates)]
    (into #{} (keep changed-paths) (into direct (mapcat #(reachable reads %)) direct))))

(defn planning-dependents
  "The survivors and readers every table planner threads: `:surviving-dependents`
  — what the phase-1 and phase-2 drops leave standing once the readers
  are gone (ADR 0006) — `:surviving-sqls`, their flat sql, and
  `:readers`, the Readers of the changed views (ADR 0026) that
  `reads-missing-view?`, `reader-trigger?` and `reader-serves` answer
  over."
  [live-snapshot claims entries fused]
  (let [survivors (surviving live-snapshot claims entries)
        readers (readers-of survivors entries fused)
        dependents (without-readers survivors readers)]
    {:surviving-dependents dependents
     :surviving-sqls (referencer-sqls dependents)
     :readers readers}))
