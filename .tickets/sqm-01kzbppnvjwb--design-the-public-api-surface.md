---
id: sqm-01kzbppnvjwb
title: Design the public API surface
status: open
type: feature
priority: 2
mode: hitl
created: '2026-08-06T14:12:44.018738427Z'
updated: '2026-08-06T18:32:45.747254951Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
deps:
- sqm-01kzbppnnr2p
- sqm-01kzbppnrsqa
---

## Description

## Question

The namespace-level API: which functions exist (introspect, diff, plan, check, apply...), what values flow between them, how directives and policies are passed, and what the minimal effectful protocol is that keeps the core runtime-agnostic.

## Notes

**2026-08-06T18:32:45.747254951Z**

From 'Decide the diff-as-product surfaces' (ADR 0005): namespace placement of drift?, drift-report, and by-object is delegated here; the CI recipe's composition story (no bundled check fn) is also this ticket's to settle.
