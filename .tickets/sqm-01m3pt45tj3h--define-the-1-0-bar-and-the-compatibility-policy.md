---
id: sqm-01m3pt45tj3h
title: Define the 1.0 bar and the compatibility policy
status: open
type: feature
priority: 2
mode: hitl
created: '2026-09-29T14:46:58.130743815Z'
updated: '2026-09-29T18:00:50.804472273Z'
parent: sqm-01m3pt2fgxp9
tags:
- wayfinder:grilling
---

## Question

What exactly must be true to tag 1.0.0, and what does 1.0 promise afterwards? Amends ADR 0014 and the "Version policy" section of docs/releasing.md.

Settled while charting (map Notes), to be made precise and written down:

- The bar is both a frozen design against explicit criteria and a real-consumer gate (early assessment + late re-run). Which criteria, stated how, and checked by whom?
- Breaking changes are allowed in majors, replacing "a breaking change would be a new artifact name" — with open sets add-only in every version, a deprecating minor before any break, and Plan changes classified (different Plan = minor; data-losing or failing Plan = bug fix).

Open: what counts as "breaking" for this library (a Snapshot/Diff/Plan key rename? a report wording change? a refused-where-it-planned change?), how deprecation is signalled in a data-first API, and whether 0.x releases before 1.0 get any promise.

## Notes

**2026-09-29T18:00:50.804472273Z**

From "Decide how a Plan leaves consumer-owned live objects alone": ADR 0024 asserts that after 1.0, the same input yielding different Diff entries is a breaking change. That's why view trigger entries land before 1.0. The settled policy covers Plans and open sets, not Diff entry shape. Confirm here that a Diff-shape change is a major, or soften ADR 0024.
