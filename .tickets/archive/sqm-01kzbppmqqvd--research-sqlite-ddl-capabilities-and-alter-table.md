---
id: sqm-01kzbppmqqvd
title: 'Research: SQLite DDL capabilities and ALTER TABLE limits'
status: closed
type: task
priority: 2
mode: afk
created: '2026-08-06T14:12:42.871740618Z'
updated: '2026-08-06T14:21:40.027084655Z'
closed: '2026-08-06T14:21:40.027084655Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:research
assignee: claude
---

## Description

## Question

On the latest SQLite: exactly what can ALTER TABLE do (rename table/column, add/drop column with their documented restrictions), what changes force the 12-step rebuild, and what are the introspection surfaces (sqlite_schema, table_info/table_xinfo, index_list/index_xinfo, foreign_key_list, etc.)? Also the oddities that will shape the design: WITHOUT ROWID, STRICT tables, generated columns, FTS/virtual tables, CHECK constraints, expression/partial indexes. Deliverable: a findings doc other tickets can cite.

## Notes

**2026-08-06T14:21:39.927757917Z**

Latest SQLite is 3.53.4; 3.53.0 (2026-04) newly added ALTER TABLE ... SET/DROP NOT NULL and ADD/DROP CHECK constraint forms, so a planner should feature-gate those on >=3.53.0. In-place ALTER otherwise covers only rename table/column (with trigger/view/FK propagation and error-on-ambiguity), append-only ADD COLUMN (no PK/UNIQUE, no CURRENT_* or parenthesized non-constant defaults, no STORED generated), and DROP COLUMN (fails if the column is PK, UNIQUE, indexed, in an FK, CHECK, generated-column expression, trigger, or view). Everything else — type/collation changes, PK/UNIQUE/FK edits, column reorder, WITHOUT ROWID/STRICT conversion — requires the documented 12-step rebuild (foreign_keys must be toggled OFF outside any transaction; create-new-then-rename order is mandatory). Introspection via sqlite_schema plus pragmas (table_info/xinfo, table_list incl. wr/strict flags, index_list/xinfo, foreign_key_list) recovers structure but NOT CHECK expressions, constraint names, per-column COLLATE, generated/expression-index/partial-index expression text, AUTOINCREMENT, or exact DEFAULT spelling — those live only in the CREATE-statement text, so a diff tool needs a SQL parser or a restricted schema model. FTS5/virtual tables support only RENAME and expose shadow tables; STRICT restricts the type vocabulary to INT/INTEGER/REAL/TEXT/BLOB/ANY. Findings doc: docs/research/sqlite-ddl-capabilities.md on branch research/sqlite-ddl-capabilities.

**2026-08-06T14:21:40.027084655Z**

ALTER TABLE covers rename/add/drop (+3.53 NOT NULL/CHECK forms); all else needs 12-step rebuild; CHECK/DEFAULT/COLLATE/expression text only recoverable by parsing sqlite_schema.sql
