---
id: sqm-01kzbppnk19k
title: Decide data-dependent gates and rebuild data movement
status: open
type: feature
priority: 2
mode: hitl
created: '2026-08-06T14:12:43.745580541Z'
updated: '2026-08-06T14:12:53.621525396Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
deps:
- sqm-01kzbppncxq2
---

## Description

## Question

When does migration legality depend on rows, not schema (NOT NULL tighten, UNIQUE create, PK duplicates, FK orphans)? Are gates verbatim SELECTs carried by the plan? And what data movement do rebuilds imply — is any row transformation beyond column mapping in scope?
