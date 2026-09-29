---
id: sqm-01m3q0kapxa2
title: Decide how a Plan leaves consumer-owned live objects alone
status: closed
type: feature
priority: 1
mode: hitl
created: '2026-09-29T16:40:06.109245733Z'
updated: '2026-09-29T17:59:02.447998728Z'
closed: '2026-09-29T17:59:02.447998728Z'
parent: sqm-01m3pt2fgxp9
tags:
- wayfinder:grilling
links:
- sqm-01m3pt45mm8c
- sqm-01m3q0kb3z87
- sqm-01m3q53h6bq6
assignee: jonasrodrigues
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

## Notes

**2026-09-29T17:59:02.351902698Z**

Resolved with the user (grilling). Recorded in ADR 0023 "Keep Directives leave live-only objects in place" and ADR 0024 "A view present on both sides carries fine-grained trigger entries". CONTEXT.md gains Kept entry and updates Plan, Directive, Directive set and Claim.

1. Channel: new Directive kinds, not a plan opt and not a Declaration recipe or helper. `{:directive :keep-table :table}`, `:keep-view {:view}`, `:keep-index {:table :index}`, `:keep-trigger {:table|:view :trigger}`. Flat keys name live objects. They're echoed, reported unused, and a keep plus a drop or rename of one live object is :malformed-input.
2. Matching: a keep matches one whole :removed entry. :keep-table covers virtual tables and keeps the table's nested indexes and triggers. On :changed or :added objects a keep is unused, never an error. Columns and constraints can't be kept.
3. Plan: a new :kept slot. Completeness is served ∪ kept ∪ unhandled. "Nothing to do" means empty :ops and empty :unhandled. The Plan report gets a kept section. ADR 0010 residual convergence: after Apply, the re-diff is exactly unhandled + kept.
4. Rebuild: kept indexes and triggers on the rebuilt table are re-created from live stored sql. Kept views that mention it join the surviving dependents. An invalid re-create fails Apply atomically; nothing is predicted.
5. Dropped tables: kept objects on a dropped table go with it (the keep is unused). A kept view or trigger body that reads a dropped table is dropped in phase 1 by the lexical mention test, and the keep counts as used. The same rule fixes the new bug "Rebuild counts a dropped table's triggers as surviving dependents".
6. Views get [:view v :trigger x] entries (amends ADR 0004). A changed view's re-create brings back its surviving triggers, and the generator gains an INSTEAD OF trigger. The cost was judged at about one build ticket, and it's only cheap before 1.0.
7. ADR 0007 stands: dropping a live-only index, trigger or view needs no intent.
8. Builder: keep-tables/keep-views (names) and keep-indexes/keep-triggers (parent names) mirror the drop steps. Keeps claim their objects; there are no predicate arguments.
9. CI: drift? stays honest. The documented check is to plan with keeps and assert empty :ops and :unhandled. No new function.
10. Ships in 1.0.

Answer to the ticket's sub-question: dropping a live-only trigger is not :needs-intent. A keep protects the ones a consumer owns.

**2026-09-29T17:59:02.447998728Z**

Keep Directives (:keep-table/:keep-view/:keep-index/:keep-trigger) leave live-only objects in place: a Plan :kept slot, kept through Rebuilds, gone with their table; views gain fine-grained trigger entries; trigger drops still need no intent. ADRs 0023, 0024.
