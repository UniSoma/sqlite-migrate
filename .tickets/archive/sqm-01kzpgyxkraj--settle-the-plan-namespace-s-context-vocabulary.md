---
id: sqm-01kzpgyxkraj
title: Settle the plan namespace's context vocabulary and the second clump
status: closed
type: chore
priority: 3
mode: afk
created: '2026-08-10T19:04:01.400593041Z'
updated: '2026-08-10T19:33:31.665692929Z'
closed: '2026-08-10T19:33:31.665692929Z'
parent: sqm-01kzctnhwmjm
tags:
- phase-2
acceptance:
- title: CONTEXT.md either defines the word the plan namespace uses for a resolved directive, or the code uses a glossary word instead
  done: false
- title: The fused/routed pair travelling through the four result helpers is bundled or the clump is justified in writing
  done: false
- title: Full suite green and clj-kondo clean
  done: false
---

## Description

Leftovers from the two-axis review of the phase-2 plan cleanups (59faff1). The spec axis came back clean; these are the standards-axis judgement calls I declined to fix on top of a green pure refactor, gathered here so the vocabulary question gets settled deliberately rather than in passing.

1. **`claims` is not a glossary word.** CLAUDE.md says to name domain concepts as CONTEXT.md defines them. CONTEXT.md defines **Directive**; "claim" appears nowhere, though the docstrings have long spoken of "resolved directive claims". This commit promoted that prose to a top-level name (`claims`, `resolve-claims`, `rename-claims`, ~36 sites). Either add the word to CONTEXT.md — a Directive matched to a table becomes a claim on it — or rename to something built on `Directive`. Renaming 36 sites for taste alone was not worth the churn mid-refactor; deciding the vocabulary is.

2. **`pairing` is likewise undefined in CONTEXT.md.** It is a good name and now the plan namespace's central value, documented only in a source comment. If the glossary gains an entry for the claim above, it should probably gain one for this too.

3. **A second clump survives the first.** `in-place-result`, `rebuild-disabled-result`, `blocked-result`, and `rebuild-result` all take `fused` + `routed`; `route-units` takes seven positionals. That is the next value wanting to be born.

4. **The rename membership test is written twice** — `rename-consumed?` as a `case` over the `sources`/`targets` sets, and `rename-unit`'s `:orig` filter as a per-rename or-clause. The comment concedes they are the same relation. Extract the single-rename predicate and build the sets from it.

5. **`unit-serves` is a Middle Man**: `(or (fused-serves fused) ...)`, while `in-place-result` and `rebuild-result` both bypass it to call `fused-serves` directly.

6. **`plan-table-changes` shadows its `pairing` parameter** with the resolved one that fusion hands back. Deliberate and commented (nothing below wants the unresolved one), and shadowing-with-refinement is idiomatic, but it is worth a second opinion given this ticket family exists to stop one name covering two things.

Not a defect list — behaviour is verified unchanged. Close as wont-fix with reasons if the answer is "the current names are right".

## Notes

**2026-08-10T19:33:31.665692929Z**

Shipped in 29ab88d. Vocabulary settled by adding the words, not renaming ~36 sites: CONTEXT.md gains Claim (a Directive resolved against the live side it names), Pairing, and Change set. Items 3 and 4 done — one table's pending change travels as a single change-set value (:tname :pairing :fused :entries :units :collision? :authorized :routed); route-units, which took seven positionals and no longer returns units, is now attach-routes, and the four result helpers plus rebuild-blockers take the one value. The doubly-written rename membership test extracts to rename-claims-entry?, used by both rename-consumed? and rename-unit's :orig filter. Items 5 and 6 closed as wont-fix with reasons written into the source: unit-serves is not a delegation — a fused pair answers for its two whole-table entries and nothing else (ADR 0009), so a unit's own entries are read only where there is no pair to answer for them, and callers serving a whole change set go to fused-serves direct because no single unit's :orig covers them; the docstring now says so. plan-table-changes' pairing shadowing stays as deliberate shadowing-with-refinement, comment retained. Two-axis review run against 59faff1: spec axis clean, standards-axis findings fixed (collision?/authorized folded into the change set rather than travelling beside it, route-units renamed, rebuild-blockers' message chain destructured away, Claim entry trimmed to its neighbours' length). Full suite green (154 tests / 795 assertions), clj-kondo clean.
