---
id: sqm-01kzbppn9ter
title: Design the diff data model
status: open
type: feature
priority: 2
mode: hitl
created: '2026-08-06T14:12:43.449941678Z'
updated: '2026-08-06T14:12:53.267224342Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
deps:
- sqm-01kzbppn728d
---

## Description

## Question

The first-class public value: what does a schema diff look like as data? Added/removed/changed per object kind, how column-level changes nest, what derived indices (inbound FKs, dependents) it carries, and how it stays queryable and renderable without a database.
