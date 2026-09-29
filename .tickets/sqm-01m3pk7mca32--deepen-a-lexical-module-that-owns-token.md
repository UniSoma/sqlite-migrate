---
id: sqm-01m3pk7mca32
title: Deepen a lexical module that owns Token comparison and DEFAULT classification
status: open
type: task
priority: 2
mode: afk
created: '2026-09-29T12:46:31.302576725Z'
updated: '2026-09-29T12:46:31.302576725Z'
acceptance:
- title: impl.lexical owns tokenize, Token comparison identity, DEFAULT Noise and classification, and the identifier-mention check; unparenthesize is private
  done: false
- title: impl.extract holds only table-facts and index-facts; fold-name lives in impl.util and no x/fold-name call remains
  done: false
- title: No namespace outside impl.lexical strips DEFAULT Noise or reads token :t/:fold to compare expressions
  done: false
- title: lexical_test.clj covers the tokenizer and a DEFAULT classification table without a database; equivalence_test keeps only Equivalence-level tests
  done: false
- title: sqm-01m3pk739j87's regression deftest passes unchanged
  done: false
- title: bb test passes; clj-kondo --lint src test ci is clean
  done: false
deps:
- sqm-01m3pk739j87
---

## Description

Knowledge of Opaque expressions is spread over three modules:

| Namespace | Holds |
|---|---|
| `impl.extract` | the tokenizer, `unparenthesize`, `fold-name` |
| `impl.diff` | `token-key`, `opaque=`, `default=` |
| `impl.plan` | `default-kind`, `key-part`, `references?`, and until sqm-01m3pk739j87, `current-word?` |

ADR 0021's single Noise rule had to land in each of the three, and one caller missed it. That miss is part of sqm-01m3pk739j87. `key-part` also strips Noise before calling `default-kind`, which strips it again.

Deepen one lexical module that owns tokenizing, Token comparison identity, Noise stripping and DEFAULT classification. Callers then ask DEFAULT-level questions and never strip Noise themselves. This is a pure refactor: user-visible behaviour does not change.

It came out of the 2026-09-29 architecture review (candidate 1) and the grilling session that followed. The decisions below are settled.

## Design

**Placement**
- New namespace `sqlite-migrate.impl.lexical` (`^:no-doc`). Its docstring points to Token comparison, Noise and Opaque expression in CONTEXT.md and states the "lexical only, never a parser" discipline (ADRs 0002, 0003 and 0021). It adds no new CONTEXT.md term.
- `impl.extract` keeps only the table-fact extractor, `table-facts` and `index-facts`, and builds on `lexical/tokenize`.
- `fold-name` moves to `impl.util`, next to `quote-identifier`, so identifier spelling lives in one place. The tokenizer computes `:fold` through it. Update all ~99 `x/fold-name` call sites across core, plan, diff, report and directives. Directives and report then stop depending on the lexer.

**What moves behind the interface**
1. The tokenizer. `tokenize` stays public, because extract and the Rebuild's `create-sql-under-temp-name` need raw tokens from stored CREATE sql.
2. Token comparison identity: `token-key` and `opaque=` from diff. diff's `type=` and `default=` shrink to calls into the module or disappear.
3. DEFAULT Noise and classification: `unparenthesize` becomes **private**, and `default-kind` (`:null` / `:constant` / `:opaque`) moves in from plan. Callers ask "same DEFAULT?" and "what kind of DEFAULT?". `key-part` stops stripping Noise itself. The module stays SQLite-version-agnostic: the planner keeps the rule that ADD COLUMN on a populated table accepts only `:null` or `:constant` (ADR 0022).
4. The lexical identifier-mention check: `references?` from plan, which today reads `:t` and `:fold` off raw tokens itself.

Function names are chosen during implementation, test-first against the new test namespace. No design-it-twice round.

**Tests** (replace, don't layer)
- A new database-free `test/sqlite_migrate/lexical_test.clj`. It takes over the tokenizer and `unparenthesize` tests from `equivalence_test`, which become cases of the DEFAULT-level questions, since `unparenthesize` is private.
- It gains a table-driven DEFAULT classification test: bare and parenthesized CURRENT_*, `(random())`, `(0)`, `-1`, `'x'`, `X'00'`, `NULL`, `(NULL)`, `TRUE`.
- `equivalence_test` keeps only its `m/diff`-level Equivalence tests.
- No `#'` reach-ins.

**Success criterion:** the full suite passes with sqm-01m3pk739j87's regression table unchanged. No CHANGELOG entry and no ADR.
