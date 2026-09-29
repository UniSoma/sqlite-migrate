---
id: sqm-01m3q0kapxa2
title: Decide how a Plan leaves consumer-owned live objects alone
status: open
type: feature
priority: 1
mode: hitl
created: '2026-09-29T16:40:06.109245733Z'
updated: '2026-09-29T16:40:20.260116218Z'
parent: sqm-01m3pt2fgxp9
tags:
- wayfinder:grilling
links:
- sqm-01m3pt45mm8c
- sqm-01m3q0kb3z87
---

## Question

A consumer's live file holds objects that its components create at runtime and no Declaration names. Does the library give such an object a supported way through a Plan, and if it does, what shape does that way take? Also: is dropping a live-only trigger `:needs-intent`?

From the first-consumer assessment (Assess the first consumer against today's SNAPSHOT, findings 3 and 4, repros R3, R4, R8 and R9; FINDINGS.md "Consumer-owned objects: this project's position"). The consumer's file holds 75 runtime cache tables, plus "freeze" guard triggers that make a table read-only. Their only routes today:

- **A drop Directive per object.** This destroys the data they mean to keep.
- **`:allow-unhandled? true`.** This waives every other unhandled entry in the same Plan, real refusals included.
- **Appending each live object's stored CREATE text to a per-file Declaration.** This works: no unhandled entries, zero drift afterwards, the triggers survive the Rebuild and still fire. It costs about 25 lines of consumer code, and the Declaration stops meaning "the intent". It also fails when an appended trigger's table is being removed (R9): `declared-snapshot` throws.

A live-only trigger is dropped today with no Directive, standalone or inside a Rebuild (R3), which silently unfreezes a frozen table. Triggers hold no data, which is why no Directive guards them now. Their effect is behaviour, though.

The consumer proposes a plan opt of plain data, for example `:unmanaged #{[:table "x_cache"] [:table "t" :trigger "freeze_t"]}`, with four properties:

1. It is data, not a predicate. An entry that matches nothing is reported, as unused Directives are.
2. It changes scope, not Equivalence. The Diff still reports the objects, and the Plan serves those entries by leaving the objects alone.
3. Kept objects survive a Rebuild: they are recreated from their stored text.
4. They leave with their table when the Declaration drops it.

Review against ADR 0003 (Equivalence has no knobs), ADR 0007 (refusal taxonomy), ADR 0009 (the Directives layer), ADR 0017 (the Diff is the drift surface) and the README limits "Selective equivalence" and "Bulk destructive intent". Also consider the answer this implies for index-only or view-only consumer objects.

Call grilling, domain-modeling and codebase-design.