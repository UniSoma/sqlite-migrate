---
id: sqm-01kzpbqq34a3
title: Reconcile check's drift refusal and the Gate :limit key with the ADRs
status: open
type: task
priority: 2
mode: hitl
created: '2026-08-10T17:32:42.467967509Z'
updated: '2026-08-10T17:32:42.467967509Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: 'A decision is recorded for check''s fingerprint refusal: either an ADR amendment blessing it, or code narrowing check back to advisory'
  done: false
- title: 'A decision is recorded for the Gate''s :limit key: either an ADR 0008 amendment widening the locked shape, or code that derives :more? without the extra key'
  done: false
- title: No ADR still contradicts the shipped behaviour in either area
  done: false
---

## Description

Two places where the shipped surface is wider than a locked ADR shape. Both are defensible as written — the deliverable is that the ADRs and the code stop disagreeing, not that either side necessarily moves.

**1. `check` refuses on drift.** `check` verifies the `schema_version` fingerprint before running Gates and throws `:drift-refused`. ADR 0016 calls Check "advisory, outside any transaction"; ADR 0008 and user story 19 describe it as read-only pre-flight that returns a Check result. `:drift-refused` is spec'd as *Apply's* refusal (ADR 0011). Failing fast on a stale Plan is sensible, but it makes `check` throw where the spec has it return.

**2. Gates carry a fifth key.** ADR 0008 fixes the Gate as `{code, path, explanation, :sql}`. The compiled gates also carry `:limit`, which the Check-result renderer uses to decide `:more?`. Small, arguably necessary, but it widens a locked shape without a record.

Marked `hitl`: both are design calls, not mechanical work.

Found by a two-axis code review of the epic (Spec axis, scope creep).
