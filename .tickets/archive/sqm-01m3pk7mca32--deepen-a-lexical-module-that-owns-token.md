---
id: sqm-01m3pk7mca32
title: Deepen a lexical module that owns Token comparison and DEFAULT classification
status: closed
type: task
priority: 2
mode: afk
created: '2026-09-29T12:46:31.302576725Z'
updated: '2026-09-29T13:43:58.022054515Z'
closed: '2026-09-29T13:43:58.022054515Z'
acceptance:
- title: impl.lexical owns tokenize, Token comparison identity, DEFAULT Noise and classification, and the identifier-mention check; unparenthesize is private
  done: true
- title: No namespace outside impl.lexical strips DEFAULT Noise or reads token :t/:fold to compare expressions
  done: true
- title: bb test passes; clj-kondo --lint src test ci is clean
  done: true
- title: impl.extract's only public vars are table-facts and index-facts; fold-name lives in impl.util and no x/fold-name call remains
  done: true
- title: lexical_test.clj covers the tokenizer, same-DEFAULT, a DEFAULT classification table and the identifier-mention check without a database; equivalence_test keeps only m/diff-level Equivalence tests
  done: true
- title: No test namespace other than equivalence_test is edited, so added-column-routes-by-the-populated-table-rule and gates_test's constant-DEFAULT gate SQL pass unchanged
  done: true
deps:
- sqm-01m3pk739j87
tags:
- settled
---

## Description

Knowledge of Opaque expressions is spread over three modules: `impl.extract` tokenizes and strips DEFAULT Noise, `impl.diff` compares tokens, and `impl.plan` classifies DEFAULTs and finds identifier mentions. ADR 0021's single Noise rule had to land in each, and one caller missed it (sqm-01m3pk739j87). `key-part` still strips Noise before calling `default-kind`, which strips it again.

One lexical module owns tokenizing, Token comparison, DEFAULT Noise, DEFAULT classification and the identifier-mention check. Callers ask "same expression?", "same DEFAULT?", "what kind of DEFAULT?", "what constant does it hold?" and "does this text mention that identifier?", and never strip Noise or compare raw tokens themselves. This is a pure refactor: Diffs, Plans, gate SQL and reports stay byte-identical, and no test outside `equivalence_test` changes.

It came out of the 2026-09-29 architecture review (candidate 1) and the grilling that followed.

### Decisions

- New namespace `sqlite-migrate.impl.lexical` (`^:no-doc`). Its docstring points to Token comparison, Noise and Opaque expression in CONTEXT.md and states the "lexical only, never a parser" discipline (ADRs 0002, 0003, 0021). No new CONTEXT.md term, no ADR, no CHANGELOG entry (`docs/releasing.md`: `impl.*` refactors are invisible).
- Dependencies run util ← lexical ← extract. diff and plan require lexical; core keeps extract for `table-facts` and `index-facts`; directives and report require only util.
- `fold-name` moves to `impl.util` next to `quote-identifier`, keeping its strict behaviour (it throws on nil). The tokenizer computes `:fold` through it, and all 98 `x/fold-name` call sites (plan 85, directives 8, core 3, diff 1, report 1) switch to `u/fold-name`. diff's nil-safe `fold` wrapper stays.
- `tokenize` stays public. The token map `{:t :s :e :text :ident :fold}` is lexical's published data shape: extract and plan's `create-sql-under-temp-name` read it to find spans and keywords, and `create-sql-under-temp-name` stays in plan. Reading tokens to compare expressions happens only inside lexical.
- Lexical owns and exposes paren pairing by token depth and the word/punct token predicates the extractor needs, since lexical cannot require extract and ADR 0021 defines that pairing as lexical. Extractor-only helpers (`split-commas`, `span-text`, `default-spelling` and the like) stay private in extract.
- `token-key` and `opaque=` move in from diff. `default=` leaves diff; `column-facts` asks lexical "same DEFAULT?". `type=` stays in diff: treating a nil type as empty text is a rule about Snapshot type text, and it then calls lexical's expression comparison.
- `unparenthesize` becomes private. `default-kind` (`:null` / `:constant` / `:opaque`) moves in from plan, and a second function returns the Noise-free text of a `:constant` DEFAULT, or nil. `key-part` uses both and strips nothing, so gate SQL for `DEFAULT ('x')` stays `('x')` (pinned by `gates_test`). The other `default-kind` callers need only the keyword.
- The module knows nothing about SQLite versions: the ADR 0022 populated-table rule for ADD COLUMN stays in the planner.
- `references?` moves in from plan as the identifier-mention check. `index-references?` stays in plan, since it reads the index Snapshot shape, and calls the mention check.
- Tests replace, never layer. A new database-free `test/sqlite_migrate/lexical_test.clj` takes over the tokenizer tests and the `unparenthesize` `are` cases from `equivalence_test`; those cases become same-DEFAULT and classification cases. `equivalence-erases-parentheses-wrapping-a-default` stays in `equivalence_test` minus its `are` block. No `#'` reach-ins.
- `lexical_test` gains a table-driven DEFAULT classification test: bare and parenthesized CURRENT_*, `(random())`, `(0)`, `-1`, `'x'`, `X'00'`, `NULL`, `(NULL)`, `TRUE`. Identifier-mention cases cover a bare word, a quoted identifier, case folding, and the same spelling inside a string literal, which is not a mention.

## Notes

**2026-09-29T13:43:58.022054515Z**

New impl.lexical owns the tokenizer, Token comparison (opaque=, private token-key), DEFAULT Noise (private unparenthesize) behind default=, DEFAULT classification (default-kind, plus default-constant for the Noise-free text of a :constant DEFAULT), and the identifier-mention check, renamed from references? to mentions?. It also exposes word-at?, punct-at? and match-paren for the extractor. impl.extract's only public vars are now table-facts and index-facts. fold-name moved to impl.util, and all 98 x/fold-name calls are now u/fold-name. diff asks lexical for opaque= and default=. plan's key-part strips nothing: it wraps default-constant in parentheses, so gate SQL stays byte-identical. The tokenizer tests moved to the new database-free lexical_test, which adds same-DEFAULT, classification-table and mention cases. equivalence_test keeps only m/diff-level tests. bb test is 172/911 green and clj-kondo is clean.
