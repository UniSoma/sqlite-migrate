---
id: sqm-01m3pt47a3zs
title: 'Decide packaging: one artifact or an adapter split'
status: open
type: feature
priority: 2
mode: hitl
created: '2026-09-29T14:46:59.651066308Z'
updated: '2026-09-29T14:47:03.320647560Z'
parent: sqm-01m3pt2fgxp9
tags:
- wayfinder:grilling
deps:
- sqm-01m3pt46xqvr
---

## Question

With a babashka adapter shipping at 1.0, does the single artifact `io.github.unisoma/sqlite-migrate` (with next.jdbc and sqlite-jdbc as real POM deps, ADR 0014) still fit, or do adapters split out (e.g. a core artifact plus per-adapter artifacts)? Weigh what a babashka user pulls through bb.edn deps, what a JVM user adds to get going, how the babashka driver dependency is declared, and that a split after 1.0 is a coordinate change consumers must follow. Amends ADR 0014.