---
id: sqm-01m3pt4663q3
title: Decide what the Equivalence relation does with unmodeled table clauses
status: open
type: feature
priority: 2
mode: hitl
created: '2026-09-29T14:46:58.499396538Z'
updated: '2026-09-29T14:46:58.595847597Z'
parent: sqm-01m3pt2fgxp9
tags:
- wayfinder:grilling
---

## Question

Some facts the Snapshot never models can change with *no* Diff entry (README, "Unsupported transformations and limits" → "Unmodeled table clauses"):

- the CREATE TABLE conflict policy (`ON CONFLICT`),
- `ASC` / `DESC` / `COLLATE` modifiers inside a PRIMARY KEY or UNIQUE definition,
- names of NOT NULL, DEFAULT, COLLATE, and generated column constraints.

For each at 1.0: model it (Snapshot fact + Diff fact + plan route — additive later, but a new Diff entry for existing inputs is a Plan change), refuse it (detect and surface), or declare it outside the relation and document it? Weigh against ADR 0001 (narrow extractor), ADR 0003 (No-op: empty diff iff equivalent), and whether any silently-missed change can lose data or break a later Apply.