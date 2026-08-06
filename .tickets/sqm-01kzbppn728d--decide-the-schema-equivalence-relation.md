---
id: sqm-01kzbppn728d
title: Decide the schema equivalence relation
status: open
type: feature
priority: 2
mode: hitl
created: '2026-08-06T14:12:43.362511999Z'
updated: '2026-08-06T14:12:53.177305817Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
deps:
- sqm-01kzbppn13g7
- sqm-01kzbppn403v
---

## Description

## Question

What counts as 'the same schema'? Physical column order, quoting/case of identifiers, type-name aliases and affinity, default-expression formatting, constraint naming, whitespace in CHECK expressions. Which differences are semantic and which are noise? This relation defines when a migration is a no-op and what the round-trip correctness property means.
