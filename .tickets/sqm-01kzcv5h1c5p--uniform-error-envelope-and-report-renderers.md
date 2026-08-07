---
id: sqm-01kzcv5h1c5p
title: Uniform error envelope and report renderers
status: open
type: task
priority: 2
mode: afk
created: '2026-08-07T00:49:59.335667882Z'
updated: '2026-08-07T01:07:09.227979254Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: Every library throw carries the envelope with its class and locked verbatim-value payload
  done: false
- title: ex-messages are one line; sqlite-error carries cause, failing Op, plan index, statement
  done: false
- title: plan-report (full SQL always) and check-report render deterministically with no knobs
  done: false
- title: 'Test: one dispatch handles every non-success class'
  done: false
deps:
- sqm-01kzcv5gr94g
- sqm-01kzcv5gvdny
tags:
- phase-4
---

## Description

One dispatch for every failure, and the two remaining human renderers. As a consumer I write a single ex-data lookup on :sqlite-migrate/error no matter which function threw; as an operator I review a plan-report before applying and a check-report when gates fail.

Scope: audit and unify every throw site under the envelope — :malformed-input (offending input under a descriptive key), :drift-refused (both fingerprints + both metadata blocks), :unhandled-refused (unhandled entries verbatim with their refusal vectors), :gate-failed (the full Check result), :sqlite-error (driver exception as ex-cause; mid-apply carries the failing Op verbatim, its plan index, and the failing statement). Payloads reuse existing values verbatim — never bespoke summaries; ex-message is a one-line class-specific summary. Renderers: plan-report (header with both identities, Ops in order with kind, path, gates, full SQL always, then unhandled entries with refusals, then unused directives) and check-report (failing gates with counts and sample rows) — deterministic, single-arity, no knobs. No message catalog, no severity field. ADR 0012.
