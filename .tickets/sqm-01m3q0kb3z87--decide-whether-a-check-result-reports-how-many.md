---
id: sqm-01m3q0kb3z87
title: Decide whether a Check result reports how many rows violate a Gate
status: open
type: feature
priority: 3
mode: hitl
created: '2026-09-29T16:40:06.527441083Z'
updated: '2026-09-29T16:40:20.352125330Z'
parent: sqm-01m3pt2fgxp9
tags:
- wayfinder:grilling
links:
- sqm-01m3pt460acv
- sqm-01m3pt45mm8c
- sqm-01m3q0kapxa2
---

## Question

Should a Check result, or a Gate, give the number of violating rows, or a way to ask for it, and not only a sample capped at the Gate's baked limit? Answering yes reopens ADR 0019 (the Check result's anatomy and the Gate's baked sample limit).

From the first-consumer assessment (Assess the first consumer against today's SNAPSHOT, finding 6, repro R7): a real NOT NULL Gate reported "10 or more", and the true count was 11,563. The consumer's current migration component shows its Users the number of violating rows, so today they would need a `count(*)` of their own, which the Gate's SQL (it carries LIMIT) doesn't provide. Weigh the full-scan cost against the sample-only design, and whether the count could be opt-in. A new key is additive, so this does not have to block freezing the public surface.

Call grilling and domain-modeling.