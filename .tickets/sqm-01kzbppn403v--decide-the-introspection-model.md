---
id: sqm-01kzbppn403v
title: Decide the introspection model
status: open
type: feature
priority: 2
mode: hitl
created: '2026-08-06T14:12:43.264537180Z'
updated: '2026-08-06T14:12:52.996685027Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
deps:
- sqm-01kzbppmqqvd
---

## Description

## Question

Re-derive live-file-as-truth from first principles: what does the library read from a live SQLite file (sqlite_schema + which pragmas), what normalized data structure does introspection produce, and is the introspected shape the same shape as the declared target (one schema representation) or a distinct one with a projection between them?
