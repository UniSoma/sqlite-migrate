---
id: sqm-01kzbppnrsqa
title: 'Decide execution policies: atomicity, destructiveness, stage-then-swap'
status: open
type: feature
priority: 2
mode: hitl
created: '2026-08-06T14:12:43.929066213Z'
updated: '2026-08-06T20:02:08.429733423Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
deps:
- sqm-01kzbppncxq2
---

## Description

## Question

Re-derive the old notes' policies from first principles for a general-purpose library: apply-or-refuse atomically? destructive drops without confirmation? stage-then-swap vs in-place transaction? What does the outcome value (applied/refused/audit record) look like?

## Notes

**2026-08-06T20:02:08.429733423Z**

From refusal taxonomy (ADR 0007): the default Apply contract now includes refusing any Plan whose unhandled collection is non-empty (closes the half-applied-rename trap). This ticket names the explicit opt-in flag for partial convergence; the refusing default is fixed.
