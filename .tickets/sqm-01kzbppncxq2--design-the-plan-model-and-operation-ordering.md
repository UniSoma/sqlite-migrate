---
id: sqm-01kzbppncxq2
title: Design the plan model and operation ordering
status: open
type: feature
priority: 2
mode: hitl
created: '2026-08-06T14:12:43.549592505Z'
updated: '2026-08-06T14:12:53.446550425Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
deps:
- sqm-01kzbppn9ter
- sqm-01kzbppmqqvd
---

## Description

## Question

What is a migration plan as data? Op granularity, whether each op carries its own DDL, how dependency ordering is expressed (baked into list position vs explicit edges), where the 12-step rebuild lives, and what the executor's contract is.
