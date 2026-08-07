---
id: sqm-01kzcv5h4yna
title: Generative property suite and CI matrix
status: open
type: task
priority: 2
mode: afk
created: '2026-08-07T00:49:59.448023031Z'
updated: '2026-08-07T01:07:09.321447618Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: Four generators implemented; schema generation reaches past the sugar via escape hatches and shrinks
  done: false
- title: All locked properties run generatively on real in-memory SQLite
  done: false
- title: Two-point CI version matrix with a chosen floor runs version honesty on each point
  done: false
- title: GraalVM native-image smoke job green
  done: false
deps:
- sqm-01kzcv5gr94g
- sqm-01kzcv5gvdny
- sqm-01kzcv5gy8gq
tags:
- phase-4
---

## Description

Turn the locked properties into a generative harness and make CI prove them across versions and native-image. As a maintainer, every property runs against generated schemas that reach past the sugar's easy subset, and the matrix catches version drift before users do.

Scope: the four generators — (1) schema generator emitting shrinkable EDN Schema values, using the raw escape hatches to reach opaque-expression territory beyond the sugar subset; (2) mutation generator perturbing a schema into a nearby target (add/drop/rename column, retype, reorder, toggle constraints/STRICT/WITHOUT ROWID) with renames arriving alongside their matching Directive; (3) row generator populating live files with conforming and violating rows; (4) the curated nasty-schema corpus as deterministic regression seeds. All locked properties (no-op, round-trip, residual convergence, data preservation, gate bidirectionality, plan determinism, version honesty) wired generatively with org.clojure/test.check and real in-memory SQLite — no mocked engine. CI: two-point sqlite-jdbc version matrix (floor = oldest conveniently pinnable, chosen here; plus latest) running the suite including version honesty on each, and a GraalVM native-image smoke job. ADRs 0010, 0014.
