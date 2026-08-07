(ns sqlite-migrate.schema
  "The EDN sugar layer (ADR 0002): a Schema value — one data value
  describing target state, mirroring the Snapshot's nesting — compiled
  by `->sql` to a vector of SQL statement strings the core consumes as
  a normal Declaration. Pure; depends on nothing in the core."
  (:require [clojure.string :as str]))

(defn- malformed!
  "Throw the `:malformed-input` taxonomy error with `msg` and `data`."
  [msg data]
  (throw (ex-info msg (assoc data :sqlite-migrate/error :malformed-input))))

(defn- identifier
  "Compile `x` (keyword or string) to a quoted identifier spelled
  verbatim — no munging, no case folding. Embedded double quotes are
  doubled per SQLite quoting rules."
  [x]
  (let [s (cond
            (keyword? x) (name x)
            (string? x) x
            :else (malformed! (str "identifier must be a keyword or string: " (pr-str x))
                    {:value x}))]
    (str "\"" (str/replace s "\"" "\"\"") "\"")))

(def ^:private strict-types
  "The STRICT-legal column type keywords and their canonical uppercase
  spellings."
  {:int "INT" :integer "INTEGER" :real "REAL" :text "TEXT" :blob "BLOB" :any "ANY"})

(defn- column-type
  "Compile a column `:type`: a STRICT-legal keyword to its canonical
  uppercase spelling, any string verbatim (the unchecked escape hatch).
  Unknown keywords are rejected."
  [t]
  (cond
    (string? t) t
    (contains? strict-types t) (strict-types t)
    :else (malformed! (str "unknown column type keyword: " (pr-str t)
                        " — use one of " (pr-str (sort (keys strict-types)))
                        " or a string passed through verbatim")
            {:type t})))

(defn- raw?
  "True when `x` is an expression-position raw escape hatch:
  `[:raw \"...\"]`."
  [x]
  (and (vector? x) (= :raw (first x)) (string? (second x)) (= 2 (count x))))

(defn- expression
  "Compile `x` in an expression position: only `[:raw \"...\"]` is
  accepted, and its text rides verbatim."
  [x position]
  (if (raw? x)
    (second x)
    (malformed! (str position " takes [:raw \"...\"] — got " (pr-str x))
      {:value x})))

(defn- default-value
  "Compile a column `:default`: a number verbatim, a string as a SQL
  string literal (embedded quotes doubled), `[:raw \"...\"]` verbatim."
  [x]
  (cond
    (number? x) (str x)
    (raw? x) (second x)
    (string? x) (str "'" (str/replace x "'" "''") "'")
    :else (malformed! (str ":default takes a number, a string literal, or [:raw \"...\"] — got "
                        (pr-str x))
            {:value x})))

(defn- column-def
  [{col-name :name
    :keys [type primary-key? autoincrement? not-null? unique? default collate check]}]
  (cond-> (identifier col-name)
    (some? type) (str " " (column-type type))
    primary-key? (str " PRIMARY KEY")
    autoincrement? (str " AUTOINCREMENT")
    not-null? (str " NOT NULL")
    unique? (str " UNIQUE")
    (some? default) (str " DEFAULT " (default-value default))
    (some? collate) (str " COLLATE " (if (keyword? collate) (name collate) collate))
    (some? check) (str " CHECK (" (expression check ":check") ")")))

(defn- identifier-list [ids]
  (str "(" (str/join ", " (map identifier ids)) ")"))

(defn- constraint-prefix
  "`CONSTRAINT \"name\" ` when `name` is present, empty otherwise."
  [name]
  (if (some? name) (str "CONSTRAINT " (identifier name) " ") ""))

(defn- primary-key-clause
  "Table-level `:primary-key`: a vector of column identifiers, or a map
  `{:name ... :columns [...]}` for a named constraint."
  [pk]
  (let [{:keys [name columns]} (if (map? pk) pk {:columns pk})]
    (str (constraint-prefix name) "PRIMARY KEY " (identifier-list columns))))

(defn- unique-clause
  "One table-level `:uniques` entry: a vector of column identifiers, or
  a map `{:name ... :columns [...]}` for a named constraint."
  [u]
  (let [{:keys [name columns]} (if (map? u) u {:columns u})]
    (str (constraint-prefix name) "UNIQUE " (identifier-list columns))))

(defn- check-clause
  "One table-level `:checks` entry: a bare `[:raw \"...\"]`, or a map
  `{:name ... :check [:raw \"...\"]}` for a named constraint."
  [c]
  (let [{:keys [name check]} (if (map? c) c {:check c})]
    (str (constraint-prefix name) "CHECK (" (expression check ":checks entry") ")")))

(def ^:private fk-actions
  "Foreign-key action keywords and their canonical SQL spellings."
  {:cascade "CASCADE"
   :restrict "RESTRICT"
   :set-null "SET NULL"
   :set-default "SET DEFAULT"
   :no-action "NO ACTION"})

(defn- fk-action
  "Compile a foreign-key action: a known keyword to its canonical
  spelling, any string verbatim. Unknown keywords are rejected."
  [a]
  (cond
    (string? a) a
    (contains? fk-actions a) (fk-actions a)
    :else (malformed! (str "unknown foreign-key action keyword: " (pr-str a)
                        " — use one of " (pr-str (sort (keys fk-actions)))
                        " or a string passed through verbatim")
            {:action a})))

(defn- foreign-key-clause
  "One `:foreign-keys` entry: `{:columns [...] :ref-table x
  :ref-columns [...]}` plus optional `:name`, `:on-delete`,
  `:on-update` (action keyword or verbatim string)."
  [{:keys [name columns ref-table ref-columns on-delete on-update]}]
  (cond-> (str (constraint-prefix name)
            "FOREIGN KEY " (identifier-list columns)
            " REFERENCES " (identifier ref-table) " " (identifier-list ref-columns))
    (some? on-delete) (str " ON DELETE " (fk-action on-delete))
    (some? on-update) (str " ON UPDATE " (fk-action on-update))))

(defn- create-table
  [{:keys [name columns primary-key uniques checks foreign-keys strict? without-rowid?]}]
  (let [defs (concat (map column-def columns)
               (when (some? primary-key) [(primary-key-clause primary-key)])
               (map unique-clause uniques)
               (map check-clause checks)
               (map foreign-key-clause foreign-keys))
        flags (cond-> []
                strict? (conj "STRICT")
                without-rowid? (conj "WITHOUT ROWID"))]
    (str "CREATE TABLE " (identifier name)
      " (" (str/join ", " defs) ")"
      (when (seq flags) (str " " (str/join ", " flags))))))

(defn- index-column
  "One index `:columns` entry: an identifier, or `[:raw \"...\"]` for an
  expression."
  [c]
  (if (raw? c) (second c) (identifier c)))

(defn- create-index
  "One `:indexes` entry nested under `table-name`: `{:name ...
  :columns [...]}` plus optional `:unique?` and `:where [:raw \"...\"]`."
  [table-name {:keys [name columns unique? where]}]
  (cond-> (str "CREATE " (if unique? "UNIQUE " "") "INDEX " (identifier name)
            " ON " (identifier table-name)
            " (" (str/join ", " (map index-column columns)) ")")
    (some? where) (str " WHERE " (expression where ":where"))))

(defn- raw-statement
  "One raw-only statement position (`:views`, `:triggers`, top-level
  `:raw`): a whole SQL statement string, or `[:raw \"...\"]`."
  [x position]
  (cond
    (string? x) x
    (raw? x) (second x)
    :else (malformed! (str position " is raw-only at launch — a whole SQL statement string or "
                        "[:raw \"...\"], got " (pr-str x))
            {:value x})))

(defn- table-statements
  "The table's CREATE TABLE followed by its nested indexes and (raw)
  triggers, in declaration order."
  [{:keys [name indexes triggers] :as table}]
  (concat [(create-table table)]
    (map #(create-index name %) indexes)
    (map #(raw-statement % ":triggers entry") triggers)))

(defn ->sql
  "Compile `schema` — an EDN Schema value — to a vector of SQL statement
  strings, a Declaration the core consumes like hand-written SQL.

  The Schema value mirrors the Snapshot's nesting, with vectors where
  order matters:

    {:tables [{:name id
               :columns [{:name id, :type kw-or-string,
                          :primary-key? :autoincrement? :not-null?
                          :unique? bools, :default value, :collate x,
                          :check [:raw \"...\"]} ...]
               :primary-key [ids] | {:name id :columns [ids]}
               :uniques [[ids] | {:name id :columns [ids]} ...]
               :checks [[:raw s] | {:name id :check [:raw s]} ...]
               :foreign-keys [{:columns [ids] :ref-table id
                               :ref-columns [ids] :name id
                               :on-delete a :on-update a} ...]
               :indexes [{:name id :columns [id-or-raw ...]
                          :unique? bool :where [:raw s]} ...]
               :triggers [sql-string-or-raw ...]
               :strict? :without-rowid? bools} ...]
     :views [sql-string-or-raw ...]
     :raw [sql-string-or-raw ...]}

  Identifiers (keywords or strings) compile to quoted verbatim
  spelling — no munging, no case folding. Column types are STRICT-legal
  keywords (:int :integer :real :text :blob :any) compiled to canonical
  uppercase, or any string passed through verbatim; unknown keywords
  are rejected. Expression positions take `[:raw \"...\"]`; views and
  triggers are raw-only at launch (whole CREATE statements). Tables
  compile in declaration order, each followed by its nested indexes and
  triggers; then views; then top-level `:raw` statements. Malformed
  input throws `:malformed-input`."
  [schema]
  (vec (concat (mapcat table-statements (:tables schema))
         (map #(raw-statement % ":views entry") (:views schema))
         (map #(raw-statement % ":raw entry") (:raw schema)))))
