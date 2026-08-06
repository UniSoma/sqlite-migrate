---
id: sqm-01kzbppnyq59
title: Lock the testing strategy and round-trip correctness property
status: open
type: feature
priority: 2
mode: hitl
created: '2026-08-06T14:12:44.119211345Z'
updated: '2026-08-06T20:37:28.450893026Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
deps:
- sqm-01kzbppn728d
---

## Description

## Question

State the library's correctness property precisely — e.g. for any live file and target: apply(plan(diff(introspect(f), target))) yields a file whose introspection is equivalent to target under the equivalence relation, or a complete refusal list. Decide the generative testing approach (schema generators, real SQLite in the loop) the spec commits to.

## Notes

**2026-08-06T20:37:28.450893026Z**

From gates ticket (ADR 0008): gate SQL (including the baked LIMIT) falls under the byte-identical plan determinism property; checker report sample order is explicitly NOT under it.
