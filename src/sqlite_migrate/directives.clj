(ns sqlite-migrate.directives
  "The Directive set (ADR 0020): assemble explicit Directives against
  one Diff before planning, so authorising every drop a Diff implies is
  a thread rather than a hand-copied list of `:destructive-drop`
  refusals.

  A set is a plain map `{:diff <Diff> :directives [...]}`, opened by
  `against` and closed by `build`; every step takes the set first and
  returns it, so the whole thing threads through `->`:

      (-> (d/against diff)
          (d/rename-tables  {\"users\" \"people\"})
          (d/rename-columns {\"users\" {\"name\" \"full_name\"}})
          (d/drop-tables)
          (d/drop-columns [\"orders\"])
          (d/build))

  Literal steps (`rename-tables`, `rename-columns`) append the maps they
  are given verbatim. Derived steps (`drop-tables`, `drop-columns`) read
  the Diff and emit one explicit per-object Directive for each removed
  object not already claimed by a rename earlier in the set. Steps are
  eager and order-sensitive: a rename must precede the drops it should
  exclude, and a drop derived before a rename is the rename-and-drop
  conflict the planner throws on (ADR 0009). `build` returns exactly
  what `sqlite-migrate.core/plan` takes under `:directives`; the
  planner never sees the set, and the set validates nothing.

  Public surface: `against`, `rename-tables`, `rename-columns`,
  `drop-tables`, `drop-columns`, `build`. All pure. Depends on the
  Diff shape and the identifier fold only — never on the planner."
  (:require [sqlite-migrate.impl.extract :as x]
    [sqlite-migrate.impl.util :as u]))

(defn- diff?
  "True when `x` has the shape of a Diff: an `:entries` vector plus
  both sides' Snapshot provenance."
  [x]
  (and (map? x) (vector? (:entries x))
    (contains? x :live-provenance) (contains? x :declared-provenance)))

(defn against
  "Open a Directive set bound to `diff`, seeded with `initial`
  Directives (default none) taken verbatim and in order. Returns
  `{:diff diff :directives [...]}`. Throws `:malformed-input` when
  `diff` is not a Diff — a derived step needs its entries."
  ([diff] (against diff []))
  ([diff initial]
    (when-not (diff? diff)
      (u/malformed! "against requires a Diff — the value sqlite-migrate.core/diff returns"
        {:diff diff}))
    {:diff diff :directives (vec initial)}))

(defn- append [directive-set directives]
  (update directive-set :directives into directives))

(defn- by-folded-key
  "The `[k v]` pairs of `m` sorted by folded key, so a map's iteration
  order never leaks into the output."
  [m]
  (sort-by (comp x/fold-name key) m))

(defn rename-tables
  "Append one `:rename-table` Directive per entry of `renames`, a map
  of live table name to declared table name, both spellings verbatim.
  Entries are appended sorted by folded live name. Literal: nothing is
  checked against the Diff — a rename the Diff has no pair for is
  appended anyway and surfaces as unused in the Plan (ADR 0009)."
  [directive-set renames]
  (append directive-set
    (for [[from to] (by-folded-key renames)]
      {:directive :rename-table :from from :to to})))

(defn rename-columns
  "Append one `:rename-column` Directive per column entry of
  `renames`, a map of live table name to a map of live column name to
  declared column name, all spellings verbatim. Tables are appended
  sorted by folded live name, columns within a table likewise. Literal,
  exactly as `rename-tables`."
  [directive-set renames]
  (append directive-set
    (for [[table columns] (by-folded-key renames)
          [from to] (by-folded-key columns)]
      {:directive :rename-column :table table :from from :to to})))

(defn- claimed-tables
  "Folded live names of the tables a `:rename-table` already in the
  Directive set claims."
  [{:keys [directives]}]
  (into #{}
    (comp (filter #(= :rename-table (:directive %)))
      (map (comp x/fold-name :from)))
    directives))

(defn- claimed-columns
  "Folded `[table column]` live names a `:rename-column` already in the
  Directive set claims."
  [{:keys [directives]}]
  (into #{}
    (comp (filter #(= :rename-column (:directive %)))
      (map (juxt (comp x/fold-name :table) (comp x/fold-name :from))))
    directives))

(defn- selection
  "The folded live names a select list narrows a derived step to, or
  nil when no list was given and every removed object qualifies."
  [names]
  (when names
    (into #{} (map x/fold-name) names)))

(defn- selected? [wanted folded-name]
  (or (nil? wanted) (contains? wanted folded-name)))

(defn- removed-tables
  "The Diff entries of the Directive set that remove a whole table,
  in Diff order — path `[:table name]`. Views never need a drop
  Directive and never match."
  [{:keys [diff]}]
  (filter (fn [{:keys [kind path]}]
            (and (= :removed kind) (= :table (first path)) (= 2 (count path))))
    (:entries diff)))

(defn- removed-columns
  "The Diff entries of the Directive set that remove a column from a
  table present on both sides, in Diff order — path
  `[:table name :column name]`. Constraints, indexes, and triggers
  never need a drop Directive and never match."
  [{:keys [diff]}]
  (filter (fn [{:keys [kind path]}]
            (and (= :removed kind) (= :column (get path 2)) (= 4 (count path))))
    (:entries diff)))

(defn drop-tables
  "Append one `:drop-table` Directive per table the Diff removed, in
  Diff order, the live name verbatim — skipping any table a
  `:rename-table` already in the set claims by folded `:from`. With
  `names`, only the removed tables whose folded name is among the
  folded `names` are taken; a name the Diff never removed yields
  nothing. Derived: renames must come earlier in the thread to be
  honoured."
  ([directive-set] (drop-tables directive-set nil))
  ([directive-set names]
    (let [claimed (claimed-tables directive-set)
          wanted (selection names)]
      (append directive-set
        (for [{[_ table] :path} (removed-tables directive-set)
              :let [folded (x/fold-name table)]
              :when (and (not (claimed folded)) (selected? wanted folded))]
          {:directive :drop-table :table table})))))

(defn drop-columns
  "Append one `:drop-column` Directive per column the Diff removed
  from a table present on both sides, in Diff order, names verbatim as
  the Diff spells them — skipping any column a `:rename-column`
  already in the set claims by folded table and `:from`. With `tables`,
  only removed columns of the tables whose folded name is among the
  folded `tables` are taken. Derived, exactly as `drop-tables`."
  ([directive-set] (drop-columns directive-set nil))
  ([directive-set tables]
    (let [claimed (claimed-columns directive-set)
          wanted (selection tables)]
      (append directive-set
        (for [{[_ table _ column] :path} (removed-columns directive-set)
              :let [folded-table (x/fold-name table)]
              :when (and (not (claimed [folded-table (x/fold-name column)]))
                      (selected? wanted folded-table))]
          {:directive :drop-column :table table :column column})))))

(defn build
  "The set's Directives as a vector — exactly what
  `sqlite-migrate.core/plan` takes under `:directives`. The set is
  eager, so nothing resolves here; `build` only extracts."
  [directive-set]
  (:directives directive-set))
