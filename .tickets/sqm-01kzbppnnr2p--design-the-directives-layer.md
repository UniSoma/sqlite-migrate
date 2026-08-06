---
id: sqm-01kzbppnnr2p
title: Design the directives layer
status: open
type: feature
priority: 2
mode: hitl
created: '2026-08-06T14:12:43.832337314Z'
updated: '2026-08-06T20:02:08.229279309Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
deps:
- sqm-01kzbppnftsn
---

## Description

## Question

The intent channel: how does an author supply what the diff can't infer — renames (column/table), defaults for NOT NULL tightening, type-change coercions? What is a directive as data, how does it bind to a diff, and how do unused/stale directives behave?

## Notes

**2026-08-06T20:02:08.229279309Z**

From refusal taxonomy (ADR 0007): directives consume exactly the :needs-intent refusal class — an entry plans once its refusal vector is empty; :incapable is never liftable. Only launch :needs-intent code is :destructive-drop; a rename directive is a resolution of that refusal (drop-vs-rename), not a refusal kind. Open code set: directives ticket may add :needs-intent codes if it finds a second ambiguity class.
