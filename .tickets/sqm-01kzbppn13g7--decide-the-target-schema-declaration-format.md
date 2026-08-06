---
id: sqm-01kzbppn13g7
title: Decide the target-schema declaration format
status: open
type: feature
priority: 2
mode: hitl
created: '2026-08-06T14:12:43.171799551Z'
updated: '2026-08-06T14:12:52.899660369Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
deps:
- sqm-01kzbppmxwry
---

## Description

## Question

What data structure do users write to declare the target schema? EDN-native DSL, honeysql-style vectors, parsed CREATE statements, or something else? What is canonical vs sugar? This is the library's front door and shapes the diff and equivalence work.
