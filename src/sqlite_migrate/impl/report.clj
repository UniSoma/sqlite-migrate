(ns ^:no-doc sqlite-migrate.impl.report
  "Presentation-only surfaces over the pipeline's values: `drift-report`
  and `by-object` over the Diff (ADR 0005), `plan-report` over the Plan
  and `check-report` over the Check result (ADR 0012). All pure,
  deterministic, and single-arity, rendering from deserialized values
  alone — nothing here reads Clojure meta or touches a database. The
  rendered text is never a parse contract; the EDN values are the
  machine surface."
  (:require [clojure.string :as str]
    [sqlite-migrate.impl.extract :as x]))

(defn- fact-value
  "The rendered value of one differing fact on one side's sub-value.
  Facts read straight off the sub-value key of the same name, except
  the two that have no such key: `:column-order` renders the side's
  column-name order, `:triggers` (a changed view's fact) the side's
  sorted trigger names."
  [side fact]
  (case fact
    :column-order (mapv :name (:columns side))
    :triggers (vec (sort (keys (:triggers side))))
    (get side fact)))

(defn- indent-sql [sql]
  (->> (str/split-lines sql)
    (map #(str "  " %))
    (str/join "\n")))

(defn- one-sided-lines
  "The body lines for an `:added`/`:removed` entry: the object's whole
  verbatim CREATE sql, then any nested indexes' and triggers' CREATE
  sql in folded-name order (an added or removed table subsumes them —
  they get no entries of their own). A sub-value with no CREATE sql of
  its own (a one-sided column or unnamed constraint inside a changed
  table) renders as its verbatim EDN sub-value instead — regenerating
  SQL the Snapshot never held is against the verbatim-truth grain
  (ADR 0005)."
  [v]
  (let [nested (fn [m] (map val (sort-by (comp x/fold-name key) m)))]
    (concat
      [(if-let [sql (:sql v)]
         (indent-sql sql)
         (str "  " (pr-str v)))]
      (for [o (nested (:indexes v))] (indent-sql (:sql o)))
      (for [o (nested (:triggers v))] (indent-sql (:sql o))))))

(defn- fact-line [{:keys [live declared]} fact]
  (str "  " fact
    "  live " (pr-str (fact-value live fact))
    "  declared " (pr-str (fact-value declared fact))))

(defn- entry-lines [{:keys [kind path live declared facts] :as e}]
  (cons (str (name kind) " " (pr-str path))
    (case kind
      :changed (map #(fact-line e %) (sort facts))
      :added (one-sided-lines declared)
      :removed (one-sided-lines live))))

(defn drift-report
  "Render `diff` into a deterministic plain-text drift report
  (ADR 0005): one block per entry, in the Diff's locked entry order —
  for a `:changed` entry one line per differing fact with both sides'
  values, for an `:added`/`:removed` entry the object's whole verbatim
  CREATE sql. Pure and single-arity, renders from a deserialized Diff
  alone; the same Diff always yields the same string, and an empty
  Diff yields the empty string. Presentation-only: the wording and
  layout are not a parse contract — consumers wanting a machine
  surface have the EDN Diff."
  [diff]
  (let [lines (mapcat entry-lines (:entries diff))]
    (if (seq lines)
      (str (str/join "\n" lines) "\n")
      "")))

(defn- identity-line
  "One side's identity for a report header: its Snapshot-metadata block
  rendered field by field."
  [label {:keys [sqlite-version schema-version]}]
  (str label "  sqlite " sqlite-version "  schema_version " schema-version))

(defn- gate-lines [{:keys [code path explanation]}]
  [(str "  gate " (name code) "  " (pr-str path))
   (str "    " explanation)])

(defn- op-lines
  "One Op block: kind and object path, its Gates (code, path,
  explanation), then every statement verbatim — full SQL always, never
  elided or reflowed (ADR 0012: the \"exactly this will run\" contract)."
  [index {:keys [kind path gates sql]}]
  (concat
    [(str "op " index "  " (name kind) "  " (pr-str path))]
    (mapcat gate-lines gates)
    sql))

(defn- refusal-line [{:keys [class code explanation]}]
  (str "  " (name class) " " (name code) " — " explanation))

(defn- unhandled-lines [{:keys [entry refusals]}]
  (cons (str "unhandled  " (name (:kind entry)) "  " (pr-str (:path entry)))
    (map refusal-line refusals)))

(defn plan-report
  "Render `plan` into a deterministic plain-text plan report (ADR
  0012) — the pre-apply review artifact: a header with both sides'
  identity, the Ops in execution order with kind, object path, Gates
  (code and explanation), and full SQL always; then the unhandled
  entries with each Refusal's class, code, and explanation; then the
  unused Directives verbatim. Pure and single-arity, renders from a
  deserialized Plan alone; the same Plan always yields the same
  string. Presentation-only: the wording and layout are not a parse
  contract — the EDN Plan is the machine surface."
  [plan]
  (str
    (str/join "\n"
      (concat
        [(identity-line "live    " (:live-metadata plan))
         (identity-line "declared" (:declared-metadata plan))]
        (mapcat op-lines (range) (:ops plan))
        (mapcat unhandled-lines (:unhandled plan))
        (map #(str "unused directive  " (pr-str %)) (:unused-directives plan))))
    "\n"))

(defn- gate-result-lines
  "One failing Gate block of a Check report: the Gate's code, path, and
  op index, its explanation, the sampled violation count (\"limit or
  more\" when the sample hit the Gate's baked limit), and the violating
  sample rows verbatim."
  [{:keys [gate op-index violations more? sample-rows]}]
  (concat
    [(str "gate " (name (:code gate)) "  " (pr-str (:path gate)) "  op " op-index)
     (str "  " (:explanation gate))
     (str "  " violations (when more? " or more")
       (if (= 1 violations) " violation" " violations"))]
    (map #(str "  " (pr-str %)) sample-rows)))

(defn check-report
  "Render a Check result into a deterministic plain-text check report
  (ADR 0012): a one-line verdict, then one block per failing Gate —
  code, path, op index, explanation, violation count (\"limit or more\"
  when the sample hit the Gate's baked limit), and the sample rows
  verbatim (row order is SQLite's — outside the determinism contract,
  ADR 0008). A passing result renders the verdict line alone. Pure and
  single-arity, renders from a deserialized Check result alone; the
  same Check result always yields the same string. Presentation-only:
  the wording and layout are not a parse contract — the EDN Check
  result is the machine surface."
  [{:keys [pass? gates]}]
  (let [n (count gates)
        failing (remove :pass? gates)
        verdict (if pass?
                  (str "check passed — " n (if (= 1 n) " gate" " gates"))
                  (str "check failed — " (count failing) " of " n
                    (if (= 1 n) " gate" " gates") " violated"))]
    (str (str/join "\n" (cons verdict (mapcat gate-result-lines failing)))
      "\n")))

(defn by-object
  "Regroup `diff`'s flat entries under the object each belongs to
  (ADR 0005): a vector of `{:path [<kind> <name>] :entries [...]}`
  groups, one per object, in the Diff's locked entry order. A changed
  table's table-level entry is reunited with its fine-grained column,
  constraint, index, and trigger children; a one-sided object or a
  changed view is a one-entry group. Entries ride verbatim and keep
  their order; an empty Diff yields `[]`."
  [diff]
  (into []
    (map (fn [entries]
           {:path (subvec (:path (first entries)) 0 2)
            :entries (vec entries)}))
    (partition-by #(subvec (:path %) 0 2) (:entries diff))))
