---
id: sqm-01kzcv5gf22s
title: 'Diff-as-product surfaces: drift-report and by-object'
status: closed
type: task
priority: 2
mode: afk
created: '2026-08-07T00:49:58.752841440Z'
updated: '2026-08-09T20:30:46.423952121Z'
closed: '2026-08-09T20:30:46.423952121Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: drift-report renders changed objects per-fact and one-sided objects as verbatim CREATE sql, deterministically
  done: false
- title: by-object regroups flat entries under their objects
  done: false
- title: CI drift-check and filtering recipes documented
  done: false
deps:
- sqm-01kzcv5gbyfb
tags:
- phase-4
---

## Description

Ship the remaining two public Diff surfaces and the documented patterns, completing diff-as-product. As an ops engineer I can read a drift report at a glance; as a CI author I can follow the recipe to fail builds on drift.

Scope: `drift-report` — single-arity, deterministic, presentation-only Diff-to-string; per-fact both-sides lines for changed objects, whole verbatim CREATE sql for one-sided ones; renders from a deserialized Diff alone; output order is the locked entry order. `by-object` — the one nesting view, reuniting a changed table's table-level entry with its fine-grained children. Documentation: the CI drift-check recipe (introspect, diff, fail on drift?, archive the printed Diff) and the consumer-filtering pattern over plain entries. No knobs, no filter helpers, no other grouping views. ADR 0005.

## Notes

**2026-08-09T20:30:46.423952121Z**

Shipped in 8c2c254. drift-report (sqlite-migrate.impl.report, re-exported via core beside drift?) renders changed objects one line per differing fact with both sides' values and one-sided objects as their whole verbatim CREATE sql, in the locked entry order, from a deserialized Diff alone; sql-less one-sided sub-values render as pr-str EDN per ADR 0005's no-SQL-regeneration decision. by-object regroups flat entries under [kind name] object groups via the locked order's contiguity. README.md documents the CI drift-check recipe and the consumer-filtering pattern over plain entries.
