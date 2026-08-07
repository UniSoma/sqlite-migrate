---
id: sqm-01kzcv5gbyfb
title: Equivalence relation and the full Diff model
status: closed
type: task
priority: 2
mode: afk
created: '2026-08-07T00:49:58.652163319Z'
updated: '2026-08-07T04:08:37.019845945Z'
closed: '2026-08-07T04:08:37.019845945Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: Tokenizer handles all SQLite token classes and nothing more
  done: true
- title: Equivalence erases the locked noise classes and keeps the locked semantic ones
  done: true
- title: 'Diff entries complete: fine-grained changed-table entries, differing-fact keywords, token-equality pairing for unnamed constraints'
  done: true
- title: Deterministic entry order + pr-str round-trip verified
  done: true
- title: No-op and round-trip properties pass on the corpus
  done: true
deps:
- sqm-01kzcv5g8zb3
tags:
- phase-2
---

## Description

The heart of the diff bet: one fixed, knobless equivalence relation and the complete Diff value. As a developer I get a Diff whose entries name every semantic difference and erase every cosmetic one, deterministically, as plain EDN.

Scope: the lexical tokenizer (SQLite token classes only — bare/quoted identifiers dequoted and case-folded, keywords folded, string/blob literals byte-exact, whitespace/comments vanish; never grammar); the equivalence relation normalizing at comparison time only (type text case/whitespace-insensitive, never affinity; column order, constraint names, PK/index column order, STRICT/WITHOUT ROWID semantic; sibling order noise); full Diff entries — target-relative kinds, fine-grained entries only inside changed tables plus one table-level entry for table-scoped facts, both sides' verbatim sub-values including stored CREATE sql, differing-fact keywords 1:1 with every compared fact, unnamed constraints paired by token-equality; locked deterministic entry order; pr-str/read-string round-trip contract.

The no-op property (empty diff iff equivalent) and the round-trip property (introspect, emit stored SQL into a pristine db, introspect, equivalent) land here against the corpus. ADRs 0003, 0004.

## Notes

**2026-08-07T04:08:37.019845945Z**

Shipped in 733c1dd. sqlite-migrate.diff holds the knobless comparison-time equivalence (token comparison over opaque expressions and type text; noise erased: identifier case/quoting, expression whitespace/comments/keyword case, sibling order; semantic kept: column order, type text, constraint names, PK/index column order, STRICT/WITHOUT ROWID/AUTOINCREMENT) and the full Diff: whole-value entries for one-sided objects and changed views/triggers/virtual tables, fine-grained entries only inside changed tables plus one table-level entry, differing-fact keywords 1:1, unnamed constraints paired by token-equality with side-tagged [:live i]/[:declared i] paths keeping paths unique Diff-wide, locked deterministic order, pr-str round-trip. Tokenizer extended for leading-dot and signed-exponent numerics. No-op (incl. perturbed pairs) and round-trip properties pass on the nasty corpus.
