# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-08-10

First fixed release. The earlier `0.1.0-SNAPSHOT` coordinate was a mutable
pre-release channel used to prove packaging and consumption; it is not a
release, and the notes below do not describe changes relative to it.

### Added

- The declarative pipeline: `snapshot` introspects a live SQLite file,
  `declared-snapshot` realizes a Declaration into a pristine in-memory
  database and introspects that, `diff` compares the two, `plan` compiles the
  difference into an executable Plan, `check` probes it read-only, and
  `apply!` runs it.
- One canonical Snapshot shape for both sides of a comparison, produced only
  by introspection, so a declared schema and a live file are never compared
  across different representations.
- SQL text as the canonical Declaration — a string or a sequence of
  statements. No schema DSL is required, and no SQL is parsed: expression
  text (CHECK bodies, generated expressions, index expressions, partial
  WHERE clauses, DEFAULT spellings) is carried and re-emitted verbatim.
- The Diff as a first-class public surface: flat, plain-EDN entries in a
  locked deterministic order that survive `pr-str` / `read-string`, with
  `drift?`, the presentation-only `drift-report` renderer, and the
  `by-object` regrouping view over them. Ordinary seq functions are the
  filtering API.
- Plan compilation covering tables, columns, CHECK and UNIQUE constraints,
  foreign keys, indexes, triggers, views, and virtual tables — in place when
  the target SQLite version and the table's dependents allow it, otherwise as
  one generalized table Rebuild that preserves rows, `rowid`, and the
  `AUTOINCREMENT` counter.
- Refusals with explicit Directives as the override: the planner never infers
  a rename, and it will not drop a table or a column holding data without
  per-object permission.
- Gates — data preconditions surfaced as data for constraints that tighten
  (`NOT NULL`, CHECK, UNIQUE, primary key, foreign key, `STRICT`,
  `WITHOUT ROWID`) — probed read-only through `check` and `check-report`.
- Atomic Apply: one transaction, all-or-nothing, with a schema-fingerprint
  refusal (`:drift-refused`) raised both by `check` and again from inside the
  transaction, leaving no drift window.
- The `sqlite-migrate.protocols/SQLiteExecutor` seam, whose docstrings are the
  normative contract for adapter authors, plus the bundled JDBC adapter
  (`sqlite-migrate.jdbc/connect` and `in-memory`) built on `next.jdbc`.
- `sqlite-migrate.schema/->sql`, compiling an EDN Schema value into the same
  Declaration statement vector the rest of the pipeline accepts.
- GraalVM native-image support: the library compiles into a consumer's native
  image, proven by a native-image smoke job in CI. No binary is published.
- Documentation: the design write-up, recipes (CI drift check, converge on
  startup, stage then swap), and a native-image page, published as cljdoc
  articles alongside the API reference.

[Unreleased]: https://github.com/unisoma/sqlite-migrate/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/unisoma/sqlite-migrate/releases/tag/v0.1.0
