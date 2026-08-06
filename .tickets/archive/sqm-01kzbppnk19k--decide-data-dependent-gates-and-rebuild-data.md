---
id: sqm-01kzbppnk19k
title: Decide data-dependent gates and rebuild data movement
status: closed
type: feature
priority: 2
mode: hitl
created: '2026-08-06T14:12:43.745580541Z'
updated: '2026-08-06T20:37:28.068979433Z'
closed: '2026-08-06T20:37:28.068979433Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
deps:
- sqm-01kzbppncxq2
assignee: jonas
---

## Description

## Question

When does migration legality depend on rows, not schema (NOT NULL tighten, UNIQUE create, PK duplicates, FK orphans)? Are gates verbatim SELECTs carried by the plan? And what data movement do rebuilds imply — is any row transformation beyond column mapping in scope?

## Notes

**2026-08-06T20:02:08.329406485Z**

From refusal taxonomy (ADR 0007): data-dependent legality is op metadata, never a refusal — ops carry a :gates slot naming their data preconditions (e.g. no NULLs under new NOT NULL, values coercible under STRICT). This ticket owns the mechanism: how gates are expressed, checked, and reported. If a 'refuse rather than gamble' policy knob is wanted, it fires as :incapable-under-policy like :rebuild-disabled does.

**2026-08-06T20:37:17.935674428Z**

RESOLUTION (ADR 0008): Gate = plain-EDN {code, path, explanation, :sql} in the op's :gates vector; SQL is one plan-compiled SELECT returning violating rows with a baked LIMIT (0 rows = pass, k<N = exact count, N = 'N or more'); gate SQL under the byte-identical determinism contract. Open add-only code set. Launch inventory: NOT NULL tighten, UNIQUE/PK create, CHECK add/change, FK add/retarget, STRICT conversion, WITHOUT ROWID conversion, NOT-NULL-no-default ADD COLUMN (table-empty). Affinity coercion is not a gate; FK gates carried despite the frame's foreign_key_check (earlier, richer reporting — frame stays backstop). Checking: public read-only Check surface (Plan + connection → structured report) AND Apply gate-checks by default, all gates up-front inside the txn frame, failing with the report and rolling back. No refuse-rather-than-gamble plan-time knob. Rebuild copy: column mapping by name (post-directive renames, never positional), new columns take declared defaults, dropped columns not copied; copy SELECT compiled at plan time. Row transformation beyond column mapping ruled OUT OF SCOPE (no USING-style transforms, no transform directives) — fix data first, gates say exactly what to fix.

**2026-08-06T20:37:28.068979433Z**

Gates = plan-compiled sampling SELECTs (code+path+explanation+SQL, baked LIMIT, open code set) checked by a public Check surface and by Apply by default up-front in the frame; no plan-time gamble knob; rebuilds copy by name only; row transformation out of scope. ADR 0008.
