---
id: sqm-01kzpbqq04y3
title: Rename the Diff/Plan provenance keys to the glossary word
status: open
type: chore
priority: 1
mode: afk
created: '2026-08-10T17:32:42.372107698Z'
updated: '2026-08-10T17:32:42.372107698Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: The Diff, Plan, and Apply report expose :live-provenance and :declared-provenance; the :live-metadata and :declared-metadata spellings appear nowhere in src, test, docs, or README
  done: false
- title: ADR prose that still says "Snapshot metadata" is corrected to "Snapshot provenance"
  done: false
- title: drift-report, plan-report, and check-report output is byte-identical to before the rename
  done: false
- title: Full suite green and clj-kondo clean
  done: false
---

## Description

`CONTEXT.md`'s **Snapshot provenance** entry lists *Snapshot metadata* under `_Avoid_`, and CLAUDE.md makes glossary vocabulary normative for code, docstrings, and tests. The avoided word is currently baked into the **public** surface: the Diff wrapper, the Plan wrapper, and the Apply report all carry `:live-metadata` / `:declared-metadata`, and the word propagates into the report renderers and test names.

Rename to `:live-provenance` / `:declared-provenance` throughout, including the ADR prose that still uses the old word.

**Timing matters.** These keys already shipped in the `0.1.0-SNAPSHOT` on Clojars. The rename is cheap now and a breaking change after `0.1.0`.

Found by a two-axis code review of the epic (Standards axis, hard violation).
