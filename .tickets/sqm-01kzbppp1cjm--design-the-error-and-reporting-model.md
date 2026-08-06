---
id: sqm-01kzbppp1cjm
title: Design the error and reporting model
status: open
type: feature
priority: 2
mode: hitl
created: '2026-08-06T14:12:44.204846042Z'
updated: '2026-08-06T20:02:08.524102616Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
deps:
- sqm-01kzbppnftsn
- sqm-01kzbppnrsqa
---

## Description

## Question

How do refusals, gate violations, and outcomes render for humans — and what is the data-to-rendering contract so other surfaces (CI, editors) can render them too? Message catalogs, severity, stable codes.

## Notes

**2026-08-06T20:02:08.524102616Z**

From refusal taxonomy (ADR 0007): Refusals are structured data (class + code + explanation, vector per unhandled entry, open code set with add-only compatibility promise); plan never throws for refusals — throwing is reserved for malformed input. The error/reporting model should align with this split.
