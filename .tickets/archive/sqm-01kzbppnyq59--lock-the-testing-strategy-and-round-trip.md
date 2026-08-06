---
id: sqm-01kzbppnyq59
title: Lock the testing strategy and round-trip correctness property
status: closed
type: feature
priority: 2
mode: hitl
created: '2026-08-06T14:12:44.119211345Z'
updated: '2026-08-06T21:33:44.150083240Z'
closed: '2026-08-06T21:33:44.150083240Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
deps:
- sqm-01kzbppn728d
assignee: jonas
---

## Description

## Question

State the library's correctness property precisely — e.g. for any live file and target: apply(plan(diff(introspect(f), target))) yields a file whose introspection is equivalent to target under the equivalence relation, or a complete refusal list. Decide the generative testing approach (schema generators, real SQLite in the loop) the spec commits to.

## Notes

**2026-08-06T20:37:28.450893026Z**

From gates ticket (ADR 0008): gate SQL (including the baked LIMIT) falls under the byte-identical plan determinism property; checker report sample order is explicitly NOT under it.

**2026-08-06T21:33:39.978597330Z**

Resolution: six locked properties — (1) no-op and (2) round-trip inherited from ADR 0003; (3) residual convergence: post-Apply diff equals exactly the Plan's unhandled entries (full equivalence and the re-plan fixpoint fall out as corollaries); (4) data preservation: surviving columns' rows equal pre-Apply as multisets, loss only under explicit destructive directives, plus rowid stability (Rebuild copies rowid when both sides are rowid tables) and AUTOINCREMENT sequence-counter restoration; (5) gate bidirectionality scoped to the inventoried codes (Check pass ⇒ no data-dependent Apply failure; Gate fail ⇒ Apply would abort); (6) plan determinism (byte-identical pr-str incl. op and gate SQL; Check sample order excluded) and version honesty (a Plan for version V runs on V). Generative approach: four generators — EDN Schema values (shrinkable, reaching beyond the sugar subset via raw escape hatches), a mutation generator producing (live, target) pairs with matching directives, a row generator (conforming + violating), and a curated nasty-schema corpus as deterministic seeds. Real in-memory SQLite in every property run, no mocked engine; properties stated tool-agnostically with test.check named as reference tooling; two-point CI version matrix (floor + latest sqlite-jdbc), floor number left to the build effort. ADR 0010.

**2026-08-06T21:33:44.150083240Z**

Six locked properties (no-op, round-trip, residual convergence, data preservation incl. rowid + AUTOINCREMENT continuity, gate bidirectionality, plan determinism + version honesty); generative testing via four generators with real in-memory SQLite always, test.check as reference tooling, two-point CI version matrix. ADR 0010.
