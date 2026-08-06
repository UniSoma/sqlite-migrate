---
id: sqm-01kzbppn13g7
title: Decide the target-schema declaration format
status: closed
type: feature
priority: 2
mode: hitl
created: '2026-08-06T14:12:43.171799551Z'
updated: '2026-08-06T16:11:13.387344073Z'
closed: '2026-08-06T16:11:13.387344073Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
deps:
- sqm-01kzbppmxwry
assignee: jonasrodrigues
---

## Description

## Question

What data structure do users write to declare the target schema? EDN-native DSL, honeysql-style vectors, parsed CREATE statements, or something else? What is canonical vs sugar? This is the library's front door and shapes the diff and equivalence work.

## Notes

**2026-08-06T16:11:13.294334237Z**

Resolution: SQL text is the canonical Declaration (string or seq of statements); EDN 'Schema value' is a sugar layer compiling to SQL — core only ever sees SQL. Declarations are pure state (zero migration intent; all intent in the directives layer). Anything execution does that introspection can't capture (DML, ATTACH, PRAGMAs, temp objects) errors loudly. SQLite is the validator; multi-statement splitting via SQLite's prepare loop, never string manipulation. Schema value: one map mirroring Snapshot nesting, tables as ordered vector, keyword-or-string identifiers quoted verbatim (no munging), STRICT-set type keywords checked / strings passed through, raw escape hatches at statement and expression level; subset coverage (views/triggers raw-only initially). Recorded as ADR 0002; glossary terms Declaration and Schema value added to CONTEXT.md.

**2026-08-06T16:11:13.387344073Z**

SQL text canonical, EDN Schema value as sugar compiling to SQL; pure state, no intent; refuse loudly outside Snapshot scope; SQLite validates; ADR 0002 + glossary
