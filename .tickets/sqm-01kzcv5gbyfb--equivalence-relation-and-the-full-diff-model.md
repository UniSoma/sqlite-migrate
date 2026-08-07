---
id: sqm-01kzcv5gbyfb
title: Equivalence relation and the full Diff model
status: open
type: task
priority: 2
mode: afk
created: '2026-08-07T00:49:58.652163319Z'
updated: '2026-08-07T01:07:08.576565774Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: Tokenizer handles all SQLite token classes and nothing more
  done: false
- title: Equivalence erases the locked noise classes and keeps the locked semantic ones
  done: false
- title: 'Diff entries complete: fine-grained changed-table entries, differing-fact keywords, token-equality pairing for unnamed constraints'
  done: false
- title: Deterministic entry order + pr-str round-trip verified
  done: false
- title: No-op and round-trip properties pass on the corpus
  done: false
deps:
- sqm-01kzcv5g8zb3
tags:
- phase-2
---

## Description

The heart of the diff bet: one fixed, knobless equivalence relation and the complete Diff value. As a developer I get a Diff whose entries name every semantic difference and erase every cosmetic one, deterministically, as plain EDN.

Scope: the lexical tokenizer (SQLite token classes only — bare/quoted identifiers dequoted and case-folded, keywords folded, string/blob literals byte-exact, whitespace/comments vanish; never grammar); the equivalence relation normalizing at comparison time only (type text case/whitespace-insensitive, never affinity; column order, constraint names, PK/index column order, STRICT/WITHOUT ROWID semantic; sibling order noise); full Diff entries — target-relative kinds, fine-grained entries only inside changed tables plus one table-level entry for table-scoped facts, both sides' verbatim sub-values including stored CREATE sql, differing-fact keywords 1:1 with every compared fact, unnamed constraints paired by token-equality; locked deterministic entry order; pr-str/read-string round-trip contract.

The no-op property (empty diff iff equivalent) and the round-trip property (introspect, emit stored SQL into a pristine db, introspect, equivalent) land here against the corpus. ADRs 0003, 0004.
