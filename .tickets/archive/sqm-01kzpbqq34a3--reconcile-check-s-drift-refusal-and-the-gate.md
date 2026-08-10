---
id: sqm-01kzpbqq34a3
title: Reconcile check's drift refusal and the Gate :limit key with the ADRs
status: closed
type: task
priority: 2
mode: hitl
created: '2026-08-10T17:32:42.467967509Z'
updated: '2026-08-10T18:22:43.896988749Z'
closed: '2026-08-10T18:22:43.896988749Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: 'A decision is recorded for check''s fingerprint refusal: either an ADR amendment blessing it, or code narrowing check back to advisory'
  done: true
- title: 'A decision is recorded for the Gate''s :limit key: either an ADR 0008 amendment widening the locked shape, or code that derives :more? without the extra key'
  done: true
- title: No ADR still contradicts the shipped behaviour in either area
  done: true
---

## Description

Two places where the shipped surface is wider than a locked ADR shape. Both are defensible as written — the deliverable is that the ADRs and the code stop disagreeing, not that either side necessarily moves.

**1. `check` refuses on drift.** `check` verifies the `schema_version` fingerprint before running Gates and throws `:drift-refused`. ADR 0016 calls Check "advisory, outside any transaction"; ADR 0008 and user story 19 describe it as read-only pre-flight that returns a Check result. `:drift-refused` is spec'd as *Apply's* refusal (ADR 0011). Failing fast on a stale Plan is sensible, but it makes `check` throw where the spec has it return.

**2. Gates carry a fifth key.** ADR 0008 fixes the Gate as `{code, path, explanation, :sql}`. The compiled gates also carry `:limit`, which the Check-result renderer uses to decide `:more?`. Small, arguably necessary, but it widens a locked shape without a record.

Marked `hitl`: both are design calls, not mechanical work.

Found by a two-axis code review of the epic (Spec axis, scope creep).

## Notes

**2026-08-10T18:22:43.896988749Z**

Both surfaces blessed rather than narrowed. ADR 0018 (cd721e3) records check's drift refusal: a drifted Plan's Gate SQL was compiled against spellings that may be gone, so returning risks a false green; 'advisory' in ADR 0016 is sharpened to read-only, not never-throws; drift beats Gates at both edges; check's TOCTOU window stays open on the record because Apply re-verifies in-frame. ADR 0019 (04846e1) grants the Gate's fifth key and pins the Check result's six keys: the limit rides the Gate because a Plan is serializable, and its value is explicitly outside the stability promise. Sweep in ff0705e -- pointer lines on 0008/0012/0013/0016, :drift-refused now Apply's or Check's, a throw-channel sentence under 0013's inventory table (it hid the channel for apply! too), glossary Check and Gate updated, gate suite reads gate-sample-limit instead of literal 10, plus a test for the cross-version :more? path. No source behaviour changed. 152 tests / 791 assertions green, lint clean. Also found and folded in: :op-index on the Check result had no ADR grant either.
