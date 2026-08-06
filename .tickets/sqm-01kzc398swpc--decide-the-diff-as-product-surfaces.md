---
id: sqm-01kzc398swpc
title: Decide the diff-as-product surfaces
status: open
type: feature
priority: 2
mode: hitl
created: '2026-08-06T17:52:36.155549441Z'
updated: '2026-08-06T17:52:36.155549441Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
deps:
- sqm-01kzbppn9ter
---

## Description

## Question

The diff data model (ADR 0004) is locked: flat self-contained EDN entries, serialization contract, equivalent? predicate. Which concrete diff-as-product surfaces earn spec space, and what does each promise? Candidates: CI drift checking (exit-code semantics, serialized diff artifacts), human rendering (text/format of a drift report, whole-CREATE display for one-sided objects), programmatic assertions (equivalent?, post-filtering patterns ADR 0003 points consumers at), and the grouped/nested view functions ADR 0004 says exist. Decide what is in the library vs left to consumers, and naming/shape of each surface — without growing a CLI (out of scope).
