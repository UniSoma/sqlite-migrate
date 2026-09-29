(ns ^:no-doc sqlite-migrate.impl.util
  "Helpers shared across the core, the planner, and the sugar layer:
  the identifier fold, SQL identifier quoting, and the
  `:malformed-input` taxonomy throw. Depends on `clojure.string` only."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn fold-name
  "The identifier fold: `s` lower-cased under the root locale, the key
  identifiers pair and compare by. Folding is for matching only;
  Snapshots keep original spellings. Throws on nil."
  ^String [^String s]
  (.toLowerCase s java.util.Locale/ROOT))

(defn quote-identifier
  "Quote `s` as a SQL identifier spelled verbatim — no munging, no case
  folding. Embedded double quotes are doubled per SQLite quoting rules."
  ^String [^String s]
  (str "\"" (str/replace s "\"" "\"\"") "\""))

(defn malformed!
  "Throw the `:malformed-input` taxonomy error with `msg` and `data`."
  [msg data]
  (throw (ex-info msg (assoc data :sqlite-migrate/error :malformed-input))))
