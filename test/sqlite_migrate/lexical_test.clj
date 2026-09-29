(ns sqlite-migrate.lexical-test
  "The lexical module over Opaque expressions: the tokenizer covers
  SQLite's token classes and nothing more, DEFAULT Noise drops out of
  same-DEFAULT and classification, and the identifier-mention check
  reads tokens, never text. No database — every claim here is lexical."
  (:require [clojure.test :refer [are deftest is testing]]
    [sqlite-migrate.impl.lexical :as lex]))

;; ---------------------------------------------------------------------------
;; Tokenizer: SQLite token classes, nothing more

(defn- kinds+texts [src]
  (mapv (juxt :t :text) (lex/tokenize src)))

(deftest tokenizer-covers-sqlite-token-classes
  (testing "whitespace and both comment styles vanish"
    (is (= [] (kinds+texts "  \t\n")))
    (is (= [[:word "a"] [:word "b"]] (kinds+texts "a -- line comment\nb")))
    (is (= [[:word "a"] [:word "b"]] (kinds+texts "a /* block\ncomment */ b")))
    (is (= [[:word "a"]] (kinds+texts "a -- unterminated line comment")))
    (is (= [[:word "a"]] (kinds+texts "a /* unterminated block"))))
  (testing "bare words carry dequoted folded identifiers"
    (is (= [{:t :word :s 0 :e 6 :text "SELECT" :ident "SELECT" :fold "select"}]
          (lex/tokenize "SELECT"))))
  (testing "quoted identifiers in all three quoting styles dequote and fold"
    (are [src ident] (= [ident] (mapv :fold (lex/tokenize src)))
      "\"Group\"" "group"
      "`Group`" "group"
      "[Group]" "group"
      "\"a\"\"b\"" "a\"b"))
  (testing "string literals keep their verbatim text, doubled quotes included"
    (is (= [[:str "'it''s'"]] (kinds+texts "'it''s'"))))
  (testing "blob literals are one token"
    (is (= [[:blob "x'CAFE'"]] (kinds+texts "x'CAFE'")))
    (is (= [[:blob "X'CAFE'"]] (kinds+texts "X'CAFE'"))))
  (testing "numeric literals: integers, decimals, hex, leading dot, signed exponents"
    (are [src] (= [[:num src]] (kinds+texts src))
      "42"
      "1.5"
      "0x1A"
      ".5"
      "1e5"
      "1e+5"
      "1.5E-3"))
  (testing "a dot not followed by a digit stays punctuation"
    (is (= [[:word "a"] [:punct "."] [:word "b"]] (kinds+texts "a.b"))))
  (testing "a sign after a hex literal is an operator, not an exponent"
    (is (= [[:num "0x1E"] [:punct "+"] [:num "5"]] (kinds+texts "0x1E+5"))))
  (testing "operators and punctuation come out as punct tokens"
    (is (= [[:word "a"] [:punct "<"] [:punct ">"] [:word "b"]]
          (kinds+texts "a <> b")))))

;; ---------------------------------------------------------------------------
;; Same DEFAULT: Token comparison once DEFAULT Noise drops out

(deftest same-default-drops-parentheses-spanning-the-whole-spelling
  (testing "a pair spanning the whole spelling is Noise, however deep (ADR 0021)"
    (are [a b] (lex/default= a b)
      "(0.01)" "0.01"
      "(( 0.01 ))" "0.01"
      "0.01" "0.01"
      "('a')" "'a'"))
  (testing "a pair that closes before the end, or an unbalanced one, stays"
    (are [a b] (not (lex/default= a b))
      "(a) + (b)" "a) + (b"
      "(1" "1"
      "1)" "1"))
  (testing "the spelling inside the parentheses stays Semantic"
    (is (not (lex/default= "(1.0)" "1.00"))))
  (testing "no DEFAULT only equals no DEFAULT"
    (is (lex/default= nil nil))
    (is (not (lex/default= nil "NULL")))))

;; ---------------------------------------------------------------------------
;; DEFAULT classification (ADR 0015, 0022)

(deftest default-classification-reads-the-noise-free-spelling
  (are [spelling kind constant] (= [kind constant]
                                  [(lex/default-kind spelling)
                                   (lex/default-constant spelling)])
    nil :null nil
    "NULL" :null nil
    "(NULL)" :null nil
    "null" :null nil
    "0" :constant "0"
    "(0)" :constant "0"
    "(( 0.01 ))" :constant "0.01"
    "-1" :constant "-1"
    "0x1F" :constant "0x1F"
    "'x'" :constant "'x'"
    "('x')" :constant "'x'"
    "X'00'" :constant "X'00'"
    "TRUE" :constant "TRUE"
    "false" :constant "false"
    "CURRENT_TIMESTAMP" :opaque nil
    "(CURRENT_TIMESTAMP)" :opaque nil
    "CURRENT_DATE" :opaque nil
    "(CURRENT_TIME)" :opaque nil
    "(random())" :opaque nil
    "(1 + 2)" :opaque nil
    "(a) + (b)" :opaque nil))

;; ---------------------------------------------------------------------------
;; Identifier mentions

(deftest identifier-mention-reads-word-and-quoted-identifier-tokens
  (testing "a bare word or a quoted identifier folding to the name is a mention"
    (are [text] (lex/mentions? text "qty")
      "qty > 0"
      "\"qty\" > 0"
      "[qty] + 1"
      "QTY * 2"
      "\"Qty\" IS NOT NULL"))
  (testing "the same spelling inside a string literal, or a longer word, is not"
    (are [text] (not (lex/mentions? text "qty"))
      "'qty' = name"
      "qty_total > 0"
      nil)))
