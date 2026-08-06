---
id: sqm-01kzbppp1cjm
title: Design the error and reporting model
status: closed
type: feature
priority: 2
mode: hitl
created: '2026-08-06T14:12:44.204846042Z'
updated: '2026-08-06T22:11:20.494216925Z'
closed: '2026-08-06T22:11:20.494216925Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
deps:
- sqm-01kzbppnftsn
- sqm-01kzbppnrsqa
assignee: jonas
---

## Description

## Question

How do refusals, gate violations, and outcomes render for humans — and what is the data-to-rendering contract so other surfaces (CI, editors) can render them too? Message catalogs, severity, stable codes.

## Notes

**2026-08-06T20:02:08.524102616Z**

From refusal taxonomy (ADR 0007): Refusals are structured data (class + code + explanation, vector per unhandled entry, open code set with add-only compatibility promise); plan never throws for refusals — throwing is reserved for malformed input. The error/reporting model should align with this split.

**2026-08-06T22:11:20.404209132Z**

Resolution (ADR 0012): One uniform ex-info envelope discriminated by the namespaced key :sqlite-migrate/error over an open, add-only class set — launch classes :malformed-input, :drift-refused, :unhandled-refused, :gate-failed, :sqlite-error (single class, driver exception as ex-cause; mid-Apply failures carry the failing Op verbatim, its plan index, and the specific failing SQL statement). Payloads reuse existing values verbatim (Check result, unhandled entries, fingerprints + Snapshot metadata) — never bespoke summary shapes. ex-message is a one-line class-specific summary; detail lives in ex-data. Renderers: plan-report (Plan → string, full SQL always, ops + gates + unhandled refusals + unused directives) and check-report (Check result → string) join drift-report under the convention 'X-report = human rendering of X'; the data value Check returns is renamed Check result. No Apply-report renderer at launch. No message catalog — explanation strings are baked where values are produced; codes/classes are the stable machine surface, all strings presentation-only; i18n out of scope. No severity field — the channel (thrown vs in-report) and refusal classes already express it.

**2026-08-06T22:11:20.494216925Z**

One namespaced error envelope (:sqlite-migrate/error, open five-class launch set) with verbatim-value payloads; plan-report + check-report renderers join drift-report; Check's value renamed Check result; no catalog, no severity, one-line ex-messages. ADR 0012.
