---
id: sqm-01m3pt2fgxp9
title: sqlite-migrate 1.0.0 readiness
status: open
type: epic
priority: 2
mode: hitl
created: '2026-09-29T14:46:02.523849786Z'
updated: '2026-09-29T16:40:52.244007515Z'
tags:
- wayfinder:map
---

## Destination

Every decision between today's 0.2.0-SNAPSHOT and a 1.0.0 that can be frozen for good is locked — accrued in ADRs and CONTEXT.md, with ADR 0014's release criterion amended — including a babashka adapter and the findings of a real-consumer assessment, and packaged as one handoff build epic, "Take sqlite-migrate to 1.0.0". The release cut itself stays with docs/releasing.md.

## Notes

- **Plan, don't do.** Tickets produce decisions; the build happens from the handoff epic. Follows the closed map "sqlite-migrate design spec" (sqm-01kzbpngs10b), whose ADRs 0001–0014 plus amendments 0015–0022 are the baseline.
- **Bounded review, not re-derivation.** Locked ADRs are reopenable, but each area is reviewed against explicit criteria — the ADR 0010 correctness properties, the public-surface promises (ADR 0013/0020), the add-only open sets, the README's "Unsupported transformations and limits" — and yields a ticket only where it fails. Before 1.0 is the only cheap window for a breaking change.
- **The 1.0 bar is both**: a frozen design against explicit criteria *and* a real consumer. The consumer is the old project; the user runs its assessment (they can't share it here) — once early against today's SNAPSHOT to feed the review, once late against the frozen candidate as a gate.
- **Compatibility after 1.0** (settled while charting, formalized by "Define the 1.0 bar and the compatibility policy"): breaking changes are allowed in majors, but open sets (error classes, refusal codes, Gate codes, Directive kinds) stay add-only in every version; a break ships only after a deprecating minor; a Plan that differs for the same input is a minor, a Plan that would lose data or fail is a bug fix.
- **babashka is a 1.0 requirement**, via babashka.sqlite (experimental) or the sqlite3 CLI. No degraded adapter: it ships only with a full-Frame, atomic Apply. The driver *dependency* may be labelled experimental; the Executor contract never is. If neither route honours the Frame, the map comes back to the user before 1.0 drops bb.
- **Production-readiness scope**: concurrent-writer behaviour is decided; large-table Rebuild cost is measured and documented, no performance promise; observability is out.
- **Skills**: every grilling ticket calls grilling + domain-modeling; surface and adapter tickets also call codebase-design; research tickets run the research skill on a local research/<name> branch (local git only). Clojure work follows docs/agents/clojure-style.md. ADR amendments follow the existing "> Amended by ADR N" header convention.

## Decisions so far

- [Research: can babashka.sqlite or the sqlite3 CLI honour the Executor's Frame?](sqm-01m3pt45egw5): babashka.sqlite (FFI) fits the whole Executor contract and the unchanged suite passes in bb; the sqlite3 CLI's JSON corrupts values on current LTS CLIs; the core loads unchanged. Recommend babashka.sqlite; risk: experimental driver, host SQLite. Findings: docs/research/babashka-adapter.md on branch research/babashka-adapter.
- [Assess the first consumer against today's SNAPSHOT](sqm-01m3pt45mm8c): Equivalence and Rebuild data safety held on the real file (no false drift and no missed change across 395 entries; every value kept). The upgrade can't apply because of a view-over-view Rebuild bug. The findings became three bugs outside the map plus the tickets "Decide how a Plan leaves consumer-owned live objects alone" and "Decide whether a Check result reports how many rows violate a Gate". The per-finding dispositions are in the ticket's note.

## Not yet specified

- **Documentation completeness for a 1.0 user.** What a 1.0 reader needs (reference vs guide, the babashka path, measured Rebuild costs, concurrency guidance, upgrade/compat policy page, a guide from a `.sql` script to a Declaration, and how to archive Snapshots so they still plan) can't be sharpened until the surface, the adapter and the concurrency decision settle.
- **The late consumer gate.** How the frozen candidate is re-run against the consumer's file and what "no new findings" means — shaped by the 1.0 bar decision. The early run never needed a Directive, so the late run should use a pack with a real rename or column drop. It can't pass until the bug "Rebuild drops and recreates every view that reads the table transitively" is fixed.
- **Synthesize the handoff epic.** "Take sqlite-migrate to 1.0.0" from the closed map, once the frontier empties. It picks up whichever consumer-found bugs are still open ("Rebuild drops and recreates every view that reads the table transitively", "declared-snapshot realizes every statement of a multi-statement Declaration string", "declared-snapshot reports a failing statement's Declaration index and text").

## Out of scope

- Maintainability of impl/plan.clj (1931 lines) — no public contract, refactorable after 1.0 without breaking anyone; not a soundness question.
- Event callbacks / observability hooks — an optional opts key is purely additive after 1.0, and per-statement events would collide with ADR 0016's data-only Executor seam that the babashka adapter depends on; revisit once both runtimes exist.
- Carried over from the closed map "sqlite-migrate design spec": other databases, CLI/GUI tooling, versioned-migration compatibility, writable_schema, row transformation beyond by-name copy (the first consumer asked for a backfill/coerce channel for parity, and confirms no pack uses one today), stage-then-swap as an Apply mode, i18n.