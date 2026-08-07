(ns sqlite-migrate.extract
  "The narrow extractor (ADR 0001): lifts the pragma-invisible facts out
  of stored CREATE text as verbatim opaque expression text — CHECK
  bodies, generated/index/partial expressions, DEFAULT spellings,
  constraint names, per-column COLLATE, AUTOINCREMENT, FK deferrability.

  Lexical only: SQLite's token classes (bare and quoted identifiers,
  strings, blobs, numbers, comments, punctuation) plus parenthesis
  depth. Never a SQL parser; expression text is carried verbatim, never
  understood."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Tokenizer

(defn- lower ^String [^String s]
  (.toLowerCase s java.util.Locale/ROOT))

(defn fold-name
  "Case-fold an identifier for pairing extracted facts with pragma
  rows. Folding is for matching only; Snapshots keep original
  spellings."
  [s]
  (lower s))

(defn- scan-quoted
  "Position just past the closing `q` of a quoted region starting at
  `i` (first char after the opening quote), honouring doubled-quote
  escapes."
  ^long [^String src ^long i q]
  (let [n (.length src)]
    (loop [j i]
      (cond
        (>= j n) n
        (= (.charAt src j) ^char q)
        (if (and (< (inc j) n) (= (.charAt src (inc j)) ^char q))
          (recur (+ j 2))
          (inc j))
        :else (recur (inc j))))))

(defn- word-start? [^Character c]
  (or (Character/isLetter (char c)) (= c \_)))

(defn- word-char? [^Character c]
  (or (Character/isLetterOrDigit (char c)) (= c \_) (= c \$)))

(defn tokenize
  "Tokenize `src` by SQLite's lexical rules into a vector of tokens
  `{:t kind :s start :e end :text verbatim}` — kinds `:word`, `:qid`,
  `:str`, `:blob`, `:num`, `:punct`. Words and quoted identifiers also
  carry `:ident` (dequoted, original case) and `:fold` (dequoted,
  ASCII-folded). Whitespace and comments vanish."
  [^String src]
  (let [n (.length src)]
    (loop [i 0 acc []]
      (if (>= i n)
        acc
        (let [c (.charAt src i)]
          (cond
            (Character/isWhitespace c)
            (recur (inc i) acc)

            (and (= c \-) (< (inc i) n) (= (.charAt src (inc i)) \-))
            (let [j (.indexOf src "\n" (int i))]
              (recur (long (if (neg? j) n (inc j))) acc))

            (and (= c \/) (< (inc i) n) (= (.charAt src (inc i)) \*))
            (let [j (.indexOf src "*/" (int (+ i 2)))]
              (recur (long (if (neg? j) n (+ j 2))) acc))

            (= c \')
            (let [j (scan-quoted src (inc i) \')]
              (recur j (conj acc {:t :str :s i :e j :text (subs src i j)})))

            (or (= c \") (= c \`))
            (let [j (scan-quoted src (inc i) c)
                  raw (subs src (inc i) (max (inc i) (dec j)))
                  ident (str/replace raw (str c c) (str c))]
              (recur j (conj acc {:t :qid :s i :e j :text (subs src i j)
                                  :ident ident :fold (lower ident)})))

            (= c \[)
            (let [k (.indexOf src "]" (int i))
                  j (long (if (neg? k) n (inc k)))
                  ident (subs src (inc i) (if (neg? k) n k))]
              (recur j (conj acc {:t :qid :s i :e j :text (subs src i j)
                                  :ident ident :fold (lower ident)})))

            (Character/isDigit c)
            (let [j (loop [j (inc i)]
                      (if (and (< j n)
                            (let [d (.charAt src j)]
                              (or (Character/isLetterOrDigit d) (= d \.))))
                        (recur (inc j))
                        j))]
              (recur j (conj acc {:t :num :s i :e j :text (subs src i j)})))

            (word-start? c)
            (let [j (loop [j (inc i)]
                      (if (and (< j n) (word-char? (.charAt src j)))
                        (recur (inc j))
                        j))
                  text (subs src i j)]
              (if (and (= 1 (count text)) (or (= c \x) (= c \X))
                    (< j n) (= (.charAt src j) \'))
                (let [k (scan-quoted src (inc j) \')]
                  (recur k (conj acc {:t :blob :s i :e k :text (subs src i k)})))
                (recur j (conj acc {:t :word :s i :e j :text text
                                    :ident text :fold (lower text)}))))

            :else
            (recur (inc i) (conj acc {:t :punct :s i :e (inc i) :text (str c)}))))))))

;; ---------------------------------------------------------------------------
;; Token-stream helpers

(defn- word-at? [toks i s]
  (let [tok (get toks i)]
    (and tok (= :word (:t tok)) (= s (:fold tok)))))

(defn- punct-at? [toks i s]
  (let [tok (get toks i)]
    (and tok (= :punct (:t tok)) (= s (:text tok)))))

(defn- match-paren
  "Index of the `)` matching the `(` at `open`."
  [toks ^long open]
  (loop [i (inc open) depth 0]
    (cond
      (>= i (count toks)) i
      (punct-at? toks i "(") (recur (inc i) (inc depth))
      (punct-at? toks i ")") (if (zero? depth) i (recur (inc i) (dec depth)))
      :else (recur (inc i) depth))))

(defn- split-commas
  "Ranges `[start end)` of the depth-0 comma-separated segments between
  `open` (a `(` index, exclusive) and `close` (its `)` index)."
  [toks ^long open ^long close]
  (loop [i (inc open) start (inc open) depth 0 acc []]
    (cond
      (>= i close) (if (< start close) (conj acc [start close]) acc)
      (punct-at? toks i "(") (recur (inc i) start (inc depth) acc)
      (punct-at? toks i ")") (recur (inc i) start (dec depth) acc)
      (and (zero? depth) (punct-at? toks i ","))
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
      (punct-at? toks i "(") (recur (inc i) (inc depth))
      (punct-at? toks i ")") (recur (inc i) (dec depth))
      (and (zero? depth) (word-at? toks i s)) i
      :else (recur (inc i) depth))))

(defn- find-punct
  "First index in `[from end)` holding the punctuation `s`, or nil."
  [toks ^long from ^long end s]
  (loop [i from]
    (cond
      (>= i end) nil
      (punct-at? toks i s) i
      :else (recur (inc i)))))

(defn- deferrability
  "Verbatim `[NOT] DEFERRABLE [INITIALLY DEFERRED|IMMEDIATE]` clause in
  token range `[from end)`, or nil."
  [^String src toks ^long from ^long end]
  (when-let [d (loop [i from]
                 (cond
                   (>= i end) nil
                   (word-at? toks i "deferrable") i
                   :else (recur (inc i))))]
    (let [start (if (and (> d from) (word-at? toks (dec d) "not")) (dec d) d)
          stop (if (and (word-at? toks (inc d) "initially")
                     (or (word-at? toks (+ d 2) "deferred")
                       (word-at? toks (+ d 2) "immediate")))
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
    (punct-at? toks i "(")
    (let [close (match-paren toks i)]
      [(span-text src toks i close) (inc close)])

    (or (punct-at? toks i "+") (punct-at? toks i "-"))
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
        j (long (if (and (< j b) (punct-at? toks j "("))
                  (inc (match-paren toks j))
                  j))]
    (loop [j j]
      (if (>= j b)
        b
        (cond
          ;; ON DELETE|UPDATE {SET NULL|SET DEFAULT|CASCADE|RESTRICT|NO ACTION}
          (word-at? toks j "on")
          (let [k (+ j 2)]
            (recur (long (cond
                           (or (word-at? toks k "set") (word-at? toks k "no")) (+ k 2)
                           :else (inc k)))))

          (word-at? toks j "match")
          (recur (+ j 2))

          (and (word-at? toks j "not") (word-at? toks (inc j) "deferrable"))
          (recur (+ j 2))

          (word-at? toks j "deferrable")
          (recur (long (if (word-at? toks (inc j) "initially")
                         (+ j 3)
                         (inc j))))

          :else j)))))

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
              "as" (if (punct-at? toks (inc i) "(")
                     (let [close (match-paren toks (inc i))]
                       (recur (inc close) nil
                         (assoc-in acc [:generated col]
                           (inner-text src toks (inc i) close))))
                     (recur (inc i) pending acc))
              "check" (if (punct-at? toks (inc i) "(")
                        (let [close (match-paren toks (inc i))]
                          (recur (inc close) nil
                            (update acc :checks conj
                              {:name pending
                               :expr (inner-text src toks (inc i) close)})))
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
  (let [[cname c] (if (word-at? toks a "constraint")
                    [(:ident (get toks (inc a))) (+ a 2)]
                    [nil a])
        kind (:fold (get toks c))
        open (find-punct toks c b "(")
        close (when open (match-paren toks open))]
    (case kind
      "primary"
      (cond-> acc
        cname (assoc :pk-name cname)
        (and open (find-word toks (inc open) close "autoincrement"))
        (assoc :autoincrement? true))

      "unique"
      (update acc :uniques conj
        {:name cname
         :columns (mapv (fn [[s _]] (:ident (get toks s)))
                    (split-commas toks open close))})

      "check"
      (update acc :checks conj {:name cname :expr (inner-text src toks open close)})

      "foreign"
      (let [r (find-word toks c b "references")]
        (update acc :fks conj
          {:name cname
           :columns (mapv (fn [[s _]] (:ident (get toks s)))
                      (split-commas toks open close))
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
  (let [toks (tokenize sql)
        open (find-punct toks 0 (count toks) "(")
        close (when open (match-paren toks open))
        init {:defaults {} :collates {} :generated {}
              :checks [] :uniques [] :fks []
              :pk-name nil :autoincrement? false}]
    (if-not open
      init
      (reduce (fn [acc [a _ :as range]]
                (let [tok (get toks a)]
                  (if (and (= :word (:t tok))
                        (contains? constraint-openers (:fold tok)))
                    (table-constraint sql toks acc range)
                    (column-def sql toks acc range))))
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
  (let [toks (tokenize sql)
        n (count toks)
        on (find-word toks 0 n "on")
        open (when on (find-punct toks (inc on) n "("))
        close (when open (match-paren toks open))
        segment (fn [[^long a ^long b]]
                  ;; strip trailing ASC|DESC, then COLLATE <name>
                  (let [b (if (or (word-at? toks (dec b) "asc")
                                (word-at? toks (dec b) "desc"))
                            (dec b) b)
                        b (if (and (> (- b 2) a) (word-at? toks (- b 2) "collate"))
                            (- b 2) b)]
                    (cond
                      ;; single bare/quoted identifier: a plain named column
                      (and (= 1 (- b a)) (#{:word :qid} (:t (get toks a))))
                      nil
                      ;; fully parenthesized: strip one level
                      (and (punct-at? toks a "(") (= (match-paren toks a) (dec b)))
                      (inner-text sql toks a (dec b))
                      :else
                      (str/trim (span-text sql toks a (dec b))))))
        where (when close
                (when-let [w (find-word toks (inc close) n "where")]
                  (when (< (inc w) n)
                    (str/trim (span-text sql toks (inc w) (dec n))))))]
    {:columns (if close (mapv segment (split-commas toks open close)) [])
     :where where}))
