(ns ^:no-doc sqlite-migrate.impl.extract
  "The narrow extractor (ADR 0001): lifts the pragma-invisible facts out
  of stored CREATE text as verbatim opaque expression text — CHECK
  bodies, generated/index/partial expressions, DEFAULT spellings,
  constraint names, per-column COLLATE, AUTOINCREMENT, FK deferrability.

  Lexical only: reads the token stream of `sqlite-migrate.impl.lexical`
  for keywords and spans. Never a SQL parser; expression text is
  carried verbatim, never understood."
  (:require [clojure.string :as str]
    [sqlite-migrate.impl.lexical :as lex]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Token-stream helpers

(defn- split-commas
  "Ranges `[start end)` of the depth-0 comma-separated segments between
  `open` (a `(` index, exclusive) and `close` (its `)` index)."
  [toks ^long open ^long close]
  (loop [i (inc open) start (inc open) depth 0 acc []]
    (cond
      (>= i close) (if (< start close) (conj acc [start close]) acc)
      (lex/punct-at? toks i "(") (recur (inc i) start (inc depth) acc)
      (lex/punct-at? toks i ")") (recur (inc i) start (dec depth) acc)
      (and (zero? depth) (lex/punct-at? toks i ","))
      (recur (inc i) (inc i) depth (conj acc [start i]))
      :else (recur (inc i) start depth acc))))

(defn- span-text
  "Verbatim source text from token `i` through token `j` inclusive."
  [^String src toks ^long i ^long j]
  (subs src (:s (get toks i)) (:e (get toks j))))

(defn- inner-text
  "Trimmed verbatim text between the `(` at `open` and its `)`."
  [^String src toks ^long open ^long close]
  (str/trim (subs src (:e (get toks open)) (:s (get toks close)))))

(defn- find-word
  "First index in `[from end)` holding the bare word `s` at depth 0
  relative to `from`, or nil."
  [toks ^long from ^long end s]
  (loop [i from depth 0]
    (cond
      (>= i end) nil
      (lex/punct-at? toks i "(") (recur (inc i) (inc depth))
      (lex/punct-at? toks i ")") (recur (inc i) (dec depth))
      (and (zero? depth) (lex/word-at? toks i s)) i
      :else (recur (inc i) depth))))

(defn- find-punct
  "First index in `[from end)` holding the punctuation `s`, or nil."
  [toks ^long from ^long end s]
  (loop [i from]
    (when (< i end)
      (if (lex/punct-at? toks i s) i (recur (inc i))))))

(defn- deferrability
  "Verbatim `[NOT] DEFERRABLE [INITIALLY DEFERRED|IMMEDIATE]` clause in
  token range `[from end)`, or nil."
  [^String src toks ^long from ^long end]
  (when-let [d (find-word toks from end "deferrable")]
    (let [start (if (and (> d from) (lex/word-at? toks (dec d) "not")) (dec d) d)
          stop (if (and (lex/word-at? toks (inc d) "initially")
                     (or (lex/word-at? toks (+ d 2) "deferred")
                       (lex/word-at? toks (+ d 2) "immediate")))
                 (+ d 2)
                 d)]
      (span-text src toks start stop))))

;; ---------------------------------------------------------------------------
;; CREATE TABLE extraction

(defn- default-spelling
  "Verbatim DEFAULT value starting at token `i` (the token after the
  DEFAULT keyword): a parenthesized expression, a signed number, or a
  single literal. Returns `[text next-index]`."
  [^String src toks ^long i]
  (cond
    (lex/punct-at? toks i "(")
    (let [close (lex/match-paren toks i)]
      [(span-text src toks i close) (inc close)])

    (or (lex/punct-at? toks i "+") (lex/punct-at? toks i "-"))
    [(span-text src toks i (inc i)) (+ i 2)]

    :else
    [(:text (get toks i)) (inc i)]))

(defn- references-end
  "Index just past the end of the column-level REFERENCES clause whose
  REFERENCES keyword sits at token `i`, bounded by `b` (the end of the
  column definition): the referenced table, an optional parenthesized
  column list, and any ON / MATCH / [NOT] DEFERRABLE tails — stopping
  before the next constraint keyword (CHECK, DEFAULT, NOT NULL, ...)."
  ^long [toks ^long i ^long b]
  (let [j (+ i 2) ; past REFERENCES and the table name
        j (long (if (and (< j b) (lex/punct-at? toks j "("))
                  (inc (lex/match-paren toks j))
                  j))]
    (loop [j j]
      (if (>= j b)
        b
        (cond
          ;; ON DELETE|UPDATE {SET NULL|SET DEFAULT|CASCADE|RESTRICT|NO ACTION}
          (lex/word-at? toks j "on")
          (let [k (+ j 2)]
            (recur (long (cond
                           (or (lex/word-at? toks k "set") (lex/word-at? toks k "no")) (+ k 2)
                           :else (inc k)))))

          (lex/word-at? toks j "match")
          (recur (+ j 2))

          (and (lex/word-at? toks j "not") (lex/word-at? toks (inc j) "deferrable"))
          (recur (+ j 2))

          (lex/word-at? toks j "deferrable")
          (recur (long (if (lex/word-at? toks (inc j) "initially")
                         (+ j 3)
                         (inc j))))

          :else j)))))

(defn- paren-body
  "When token `i` opens a parenthesized group, `[index-past-close
  inner-text]`; else nil."
  [^String src toks ^long i]
  (when (lex/punct-at? toks i "(")
    (let [close (lex/match-paren toks i)]
      [(inc close) (inner-text src toks i close)])))

(defn- column-def
  "Fold one column-definition token range into the accumulator."
  [^String src toks acc [^long a ^long b]]
  (let [col (:fold (get toks a))]
    (loop [i (inc a) pending nil acc acc]
      (if (>= i b)
        acc
        (let [tok (get toks i)]
          (if (not= :word (:t tok))
            (recur (inc i) pending acc)
            (case (:fold tok)
              "constraint" (recur (+ i 2) (:ident (get toks (inc i))) acc)
              "default" (let [[text j] (default-spelling src toks (inc i))]
                          (recur (long j) nil (assoc-in acc [:defaults col] text)))
              "collate" (recur (+ i 2) nil
                          (assoc-in acc [:collates col] (:ident (get toks (inc i)))))
              "as" (if-let [[j expr] (paren-body src toks (inc i))]
                     (recur (long j) nil (assoc-in acc [:generated col] expr))
                     (recur (inc i) pending acc))
              "check" (if-let [[j expr] (paren-body src toks (inc i))]
                        (recur (long j) nil
                          (update acc :checks conj {:name pending :expr expr}))
                        (recur (inc i) pending acc))
              "references" (let [e (references-end toks i b)]
                             (recur e nil
                               (update acc :fks conj
                                 {:name pending
                                  :columns [(:ident (get toks a))]
                                  :ref-table (:ident (get toks (inc i)))
                                  :deferrable (deferrability src toks i e)})))
              "unique" (recur (inc i) nil
                         (update acc :uniques conj
                           {:name pending
                            :columns [(:ident (get toks a))]}))
              "primary" (recur (inc i) nil
                          (cond-> acc pending (assoc :pk-name pending)))
              "autoincrement" (recur (inc i) pending (assoc acc :autoincrement? true))
              (recur (inc i) pending acc))))))))

(defn- table-constraint
  "Fold one table-constraint token range into the accumulator."
  [^String src toks acc [^long a ^long b]]
  (let [[cname c] (if (lex/word-at? toks a "constraint")
                    [(:ident (get toks (inc a))) (+ a 2)]
                    [nil a])
        kind (:fold (get toks c))
        open (find-punct toks c b "(")
        close (when open (lex/match-paren toks open))
        column-names (fn []
                       (mapv (fn [[s _]] (:ident (get toks s)))
                         (split-commas toks open close)))]
    (case kind
      "primary"
      (cond-> acc
        cname (assoc :pk-name cname)
        (and open (find-word toks (inc open) close "autoincrement"))
        (assoc :autoincrement? true))

      "unique"
      (update acc :uniques conj {:name cname :columns (column-names)})

      "check"
      (update acc :checks conj {:name cname :expr (inner-text src toks open close)})

      "foreign"
      (let [r (find-word toks c b "references")]
        (update acc :fks conj
          {:name cname
           :columns (column-names)
           :ref-table (when r (:ident (get toks (inc r))))
           :deferrable (when r (deferrability src toks r b))}))

      acc)))

(def ^:private constraint-openers #{"constraint" "primary" "unique" "check" "foreign"})

(defn table-facts
  "Extract the pragma-invisible facts from a stored CREATE TABLE `sql`:
  `{:defaults {folded-col text} :collates {folded-col name}
    :generated {folded-col expr} :checks [{:name :expr}]
    :uniques [{:name :columns}]
    :fks [{:name :columns :ref-table :deferrable}]
    :pk-name name-or-nil :autoincrement? bool}` — all text verbatim,
  constraint sequences in source order."
  [^String sql]
  (let [toks (lex/tokenize sql)
        open (find-punct toks 0 (count toks) "(")
        close (when open (lex/match-paren toks open))
        init {:defaults {} :collates {} :generated {}
              :checks [] :uniques [] :fks []
              :pk-name nil :autoincrement? false}]
    (if-not open
      init
      (reduce (fn [acc [a :as seg]]
                (let [tok (get toks a)]
                  (if (and (= :word (:t tok))
                        (contains? constraint-openers (:fold tok)))
                    (table-constraint sql toks acc seg)
                    (column-def sql toks acc seg))))
        init
        (split-commas toks open close)))))

;; ---------------------------------------------------------------------------
;; CREATE INDEX extraction

(defn index-facts
  "Extract the pragma-invisible facts from a stored CREATE INDEX `sql`:
  `{:columns [text-or-nil ...] :where text-or-nil}` — one entry per
  indexed position, verbatim expression text for expression positions
  (nil for plain named columns), and the partial-index WHERE clause."
  [^String sql]
  (let [toks (lex/tokenize sql)
        n (count toks)
        on (find-word toks 0 n "on")
        open (when on (find-punct toks (inc on) n "("))
        close (when open (lex/match-paren toks open))
        segment (fn [[^long a ^long b]]
                  ;; strip trailing ASC|DESC, then COLLATE <name>
                  (let [b (if (or (lex/word-at? toks (dec b) "asc")
                                (lex/word-at? toks (dec b) "desc"))
                            (dec b) b)
                        b (if (and (> (- b 2) a) (lex/word-at? toks (- b 2) "collate"))
                            (- b 2) b)]
                    (cond
                      ;; single bare/quoted identifier: a plain named column
                      (and (= 1 (- b a)) (#{:word :qid} (:t (get toks a))))
                      nil
                      ;; fully parenthesized: strip one level
                      (and (lex/punct-at? toks a "(") (= (lex/match-paren toks a) (dec b)))
                      (inner-text sql toks a (dec b))
                      :else
                      (str/trim (span-text sql toks a (dec b))))))
        where (when-let [w (and close (find-word toks (inc close) n "where"))]
                (when (< (inc w) n)
                  (str/trim (span-text sql toks (inc w) (dec n)))))]
    {:columns (if close (mapv segment (split-commas toks open close)) [])
     :where where}))
