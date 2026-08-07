---
id: sqm-01kzcv5gvdny
title: Gates and Check
status: open
type: task
priority: 2
mode: afk
created: '2026-08-07T00:49:59.147087165Z'
updated: '2026-08-07T01:07:08.947519820Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: Full launch gate inventory compiles with baked-LIMIT sampling SELECTs under the determinism contract
  done: false
- title: check runs gates read-only and returns the Check result
  done: false
- title: apply! gate-checks by default inside the frame with opt-out; failure rolls back with the Check result
  done: false
- title: Gate bidirectionality property passes
  done: false
deps:
- sqm-01kzcv5gne5m
tags:
- phase-3
---

## Description

Data preconditions as first-class, checkable plan artifacts. As an operator I know before a maintenance window whether the rows will survive the new shape — and apply! verifies the same thing by default before touching anything.

Scope: gate compilation for the full launch inventory (NOT NULL added/tightened; UNIQUE constraint or unique index created; PK added/changed; CHECK added/changed with the opaque expression embedded verbatim; FK added/retargeted; STRICT conversion; WITHOUT ROWID conversion; NOT-NULL-no-default column added requiring an empty table); each Gate one plan-compiled sampling SELECT with a baked LIMIT — zero rows pass, N rows report as N-or-more with samples; gate SQL under the plan determinism contract. The public `check` fn runs every Gate read-only via execute-query and returns the Check result (pass/fail per gate, counts, sample rows). apply! gate-checks by default, up-front once the transaction is open (TOCTOU-free), rolling back with the Check result on failure; :check-gates? is the opt-out. No plan-time gamble knob; affinity coercion on non-STRICT retype deliberately ungated.

The gate bidirectionality property lands here: Check pass implies no data-dependent apply failure; Gate fail implies apply would abort. ADRs 0008, 0010, 0011.
