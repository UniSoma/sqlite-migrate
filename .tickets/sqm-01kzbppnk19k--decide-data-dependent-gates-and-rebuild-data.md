---
id: sqm-01kzbppnk19k
title: Decide data-dependent gates and rebuild data movement
status: open
type: feature
priority: 2
mode: hitl
created: '2026-08-06T14:12:43.745580541Z'
updated: '2026-08-06T20:02:08.329406485Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
deps:
- sqm-01kzbppncxq2
---

## Description

## Question

When does migration legality depend on rows, not schema (NOT NULL tighten, UNIQUE create, PK duplicates, FK orphans)? Are gates verbatim SELECTs carried by the plan? And what data movement do rebuilds imply — is any row transformation beyond column mapping in scope?

## Notes

**2026-08-06T20:02:08.329406485Z**

From refusal taxonomy (ADR 0007): data-dependent legality is op metadata, never a refusal — ops carry a :gates slot naming their data preconditions (e.g. no NULLs under new NOT NULL, values coercible under STRICT). This ticket owns the mechanism: how gates are expressed, checked, and reported. If a 'refuse rather than gamble' policy knob is wanted, it fires as :incapable-under-policy like :rebuild-disabled does.
