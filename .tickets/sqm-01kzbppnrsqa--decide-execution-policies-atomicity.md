---
id: sqm-01kzbppnrsqa
title: 'Decide execution policies: atomicity, destructiveness, stage-then-swap'
status: open
type: feature
priority: 2
mode: hitl
created: '2026-08-06T14:12:43.929066213Z'
updated: '2026-08-06T14:12:53.804129385Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
deps:
- sqm-01kzbppncxq2
---

## Description

## Question

Re-derive the old notes' policies from first principles for a general-purpose library: apply-or-refuse atomically? destructive drops without confirmation? stage-then-swap vs in-place transaction? What does the outcome value (applied/refused/audit record) look like?
