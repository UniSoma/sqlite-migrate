---
id: sqm-01kzbppmxwry
title: 'Research: prior art in declarative schema migration tools'
status: closed
type: task
priority: 2
mode: afk
created: '2026-08-06T14:12:43.068478346Z'
updated: '2026-08-06T14:19:31.208263860Z'
closed: '2026-08-06T14:19:31.208263860Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:research
assignee: claude
---

## Description

## Question

Survey declarative/diff-based schema migration tools — Atlas, sqldef, Alembic autogenerate, Prisma migrate diff, skeema, and any SQLite-specific efforts. For each: how do they represent the target schema, the diff, and the plan? How do they handle renames/intent, destructive changes, and data-dependent legality? What do they get wrong that a first-principles design should avoid? Deliverable: a steal-honestly findings doc.

## Notes

**2026-08-06T14:19:31.095477264Z**

Surveyed Atlas, Skeema, sqldef, Alembic autogenerate, Prisma Migrate, and SQLite-native prior art (12-step procedure, sqldiff, sqlite-utils transform, Rothlisberger pristine-DB pattern) against primary docs and issue trackers. Two diff strategies dominate: object-model diff (Alembic ops tree) and pristine-database diff (Atlas dev DB, Skeema workspace) — the latter is uniquely cheap on SQLite and avoids hand-rolled SQL parsing, sqldef's biggest failure source. Only Alembic and Atlas's Go API expose the diff as first-class data, validating this project's core bet. Rename intent is universally unsolved by heuristics; explicit rename data in the diff (sqldef @renamed, sqlite-utils rename=) is the deterministic answer. Data-dependent legality is almost nowhere: Prisma simulates and refuses with row counts, Atlas lints (MF103/MF104/LT101), everyone else fails at apply time. Findings doc with adopt/avoid takeaways: docs/research/migration-prior-art.md on branch research/migration-prior-art.

**2026-08-06T14:19:31.208263860Z**

Prior-art survey done; adopt pristine-DB diffing + first-class diff data, avoid parser/heuristic-rename/silent-skip traps (doc on research/migration-prior-art)
