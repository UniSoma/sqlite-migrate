(ns ^:no-doc sqlite-migrate.impl.lexical
  "Everything the library knows about Opaque expressions, by their
  tokens alone (CONTEXT.md: Opaque expression, Token comparison, Noise):
  the tokenizer, Token comparison, DEFAULT Noise and classification,
  and the identifier-mention check.

  Lexical only: SQLite's token classes (bare and quoted identifiers,
  strings, blobs, numbers, comments, punctuation) plus parenthesis
  depth. Never a SQL parser (ADR 0002, 0003, 0021); expression text is
  carried verbatim, never understood. Knows nothing about SQLite
  versions. Depends on `sqlite-migrate.impl.util` only."
  (:require [clojure.string :as str]
    [sqlite-migrate.impl.util :as u]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Tokenizer

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

(defn- scan-number
  "Position just past the numeric literal starting at `i`: digits,
  dots, hex digits after a `0x` prefix, and a signed exponent
  (`1e+5`) — the sign is consumed only when the literal is not hex,
  follows an `e`/`E`, and is itself followed by a digit."
  ^long [^String src ^long i]
  (let [n (.length src)
        hex? (and (= \0 (.charAt src i)) (< (inc i) n)
               (let [x (.charAt src (inc i))] (or (= x \x) (= x \X))))]
    (loop [j (inc i)]
      (if (>= j n)
        j
        (let [d (.charAt src j)]
          (cond
            (or (Character/isLetterOrDigit d) (= d \.)) (recur (inc j))
            (and (or (= d \+) (= d \-))
              (not hex?)
              (let [p (.charAt src (dec j))] (or (= p \e) (= p \E)))
              (< (inc j) n)
              (Character/isDigit (.charAt src (inc j)))) (recur (inc j))
            :else j))))))

(defn- word-start? [c]
  (or (Character/isLetter (char c)) (= c \_)))

(defn- word-char? [c]
  (or (Character/isLetterOrDigit (char c)) (= c \_) (= c \$)))

(defn tokenize
  "Tokenize `src` by SQLite's lexical rules into a vector of tokens
  `{:t kind :s start :e end :text verbatim}` — kinds `:word`, `:qid`,
  `:str`, `:blob`, `:num`, `:punct`. Words and quoted identifiers also
  carry `:ident` (dequoted, original case) and `:fold` (dequoted, under
  `sqlite-migrate.impl.util/fold-name`). Whitespace and comments vanish.

  The token map is this namespace's published data shape: callers may
  read it to find spans and keywords, but ask this namespace every
  question about an expression: same expression, same DEFAULT, which
  kind of DEFAULT, which constant, which identifiers it mentions."
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
                  qq (str c c)
                  ident (if (neg? (.indexOf raw qq)) raw (str/replace raw qq (str c)))]
              (recur j (conj acc {:t :qid :s i :e j :text (subs src i j)
                                  :ident ident :fold (u/fold-name ident)})))

            (= c \[)
            (let [k (.indexOf src "]" (int i))
                  j (long (if (neg? k) n (inc k)))
                  ident (subs src (inc i) (if (neg? k) n k))]
              (recur j (conj acc {:t :qid :s i :e j :text (subs src i j)
                                  :ident ident :fold (u/fold-name ident)})))

            (or (Character/isDigit c)
              (and (= c \.) (< (inc i) n)
                (Character/isDigit (.charAt src (inc i)))))
            (let [j (scan-number src i)]
              (recur j (conj acc {:t :num :s i :e j :text (subs src i j)})))

            (word-start? c)
            (let [j (long (loop [j (inc i)]
                            (if (and (< j n) (word-char? (.charAt src j)))
                              (recur (inc j))
                              j)))
                  text (subs src i j)]
              (if (and (= 1 (count text)) (or (= c \x) (= c \X))
                    (< j n) (= (.charAt src j) \'))
                (let [k (scan-quoted src (inc j) \')]
                  (recur k (conj acc {:t :blob :s i :e k :text (subs src i k)})))
                (recur j (conj acc {:t :word :s i :e j :text text
                                    :ident text :fold (u/fold-name text)}))))

            :else
            (recur (inc i) (conj acc {:t :punct :s i :e (inc i) :text (str c)}))))))))

;; ---------------------------------------------------------------------------
;; Token predicates and paren pairing

(defn word-at?
  "True when token `i` of `toks` is the bare word whose folded spelling
  is `s`. A quoted identifier spelling the same word is not a keyword."
  [toks i s]
  (let [tok (get toks i)]
    (and (= :word (:t tok)) (= s (:fold tok)))))

(defn punct-at?
  "True when token `i` of `toks` is the punctuation `s`."
  [toks i s]
  (let [tok (get toks i)]
    (and (= :punct (:t tok)) (= s (:text tok)))))

(defn match-paren
  "Index of the `)` matching the `(` at `open`, pairing by token depth
  alone (ADR 0021); `(count toks)` when it never closes."
  ^long [toks ^long open]
  (loop [i (inc open) depth 0]
    (cond
      (>= i (count toks)) i
      (punct-at? toks i "(") (recur (inc i) (inc depth))
      (punct-at? toks i ")") (if (zero? depth) i (recur (inc i) (dec depth)))
      :else (recur (inc i) depth))))

;; ---------------------------------------------------------------------------
;; Token comparison

(defn- token-key
  "The identity of one token under Token comparison: words and quoted
  identifiers collapse to their folded spelling (quoting is Noise);
  every other kind compares by verbatim text."
  [{:keys [t text] :as tok}]
  (case t
    (:word :qid) [:id (:fold tok)]
    [t text]))

(defn opaque=
  "True when Opaque expression texts `a` and `b` are equal under Token
  comparison: whitespace and comments vanish, keywords and identifiers
  fold, quoting drops, string and blob literals stay byte-exact. nil
  only equals nil."
  [a b]
  (cond
    (and (nil? a) (nil? b)) true
    (or (nil? a) (nil? b)) false
    :else (= (mapv token-key (tokenize a))
            (mapv token-key (tokenize b)))))

;; ---------------------------------------------------------------------------
;; DEFAULT Noise and classification

(defn- unparenthesize
  "`text` with every pair of parentheses that wraps the whole of it
  removed and the remainder trimmed — `(0.01)` and `((0.01))` become
  `0.01`; `(a) + (b)` stays, since its first `(` closes before the end."
  [^String text]
  (let [toks (tokenize text)
        last-i (dec (count toks))]
    (if (and (pos? last-i) (punct-at? toks 0 "(") (= last-i (match-paren toks 0)))
      (recur (str/trim (subs text (:e (get toks 0)) (:s (get toks last-i)))))
      (str/trim text))))

(defn default=
  "True when DEFAULT spellings `a` and `b` are equal under Token
  comparison once the parentheses wrapping the whole of each drop out
  — `(0.01)` equals `0.01` (ADR 0021). nil (no DEFAULT) only equals
  nil."
  [a b]
  (opaque= (some-> a unparenthesize) (some-> b unparenthesize)))

(defn- noise-free-kind
  "The `default-kind` of a spelling whose wrapping parentheses already
  dropped out."
  [s]
  (cond
    (or (nil? s) (re-matches #"(?i)NULL" s)) :null
    (or (re-matches #"[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?" s)
      (re-matches #"(?i)[+-]?0x[0-9A-F]+" s)
      (re-matches #"'(?:[^']|'')*'" s)
      (re-matches #"(?i)X'(?:[0-9A-F]{2})*'" s)
      (re-matches #"(?i)TRUE|FALSE" s)) :constant
    :else :opaque))

(defn default-kind
  "The classification of a column DEFAULT's verbatim `spelling` that
  compiles Gates (ADR 0015) and routes added columns (ADR 0022):
  `:null` when the column defaults to NULL (no default, or the NULL
  keyword); `:constant` for a literal whose value every copied row will
  share (number, string, blob, TRUE/FALSE); `:opaque` for any other
  expression — never understood (ADR 0015). Parentheses wrapping the
  whole spelling are Noise (ADR 0021)."
  [spelling]
  (noise-free-kind (some-> spelling unparenthesize)))

(defn default-constant
  "The literal text of a `:constant` DEFAULT `spelling`, verbatim and
  trimmed, with the parentheses wrapping the whole spelling dropped —
  `('x')` gives `'x'`. nil for a `:null` or `:opaque` DEFAULT."
  [spelling]
  (let [s (some-> spelling unparenthesize)]
    (when (= :constant (noise-free-kind s))
      s)))

;; ---------------------------------------------------------------------------
;; Identifier mentions

(defn mentions?
  "True when Opaque expression `text` lexically mentions the folded
  identifier `ident-fold`: a word or quoted-identifier token folding to
  it. The same spelling inside a string literal is no mention. nil text
  mentions nothing. Conservative — a mention is not a proven
  reference."
  [text ident-fold]
  (boolean (and text
             (some #(and (#{:word :qid} (:t %)) (= ident-fold (:fold %)))
               (tokenize text)))))
