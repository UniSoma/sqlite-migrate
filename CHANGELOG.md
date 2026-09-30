# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `sqlite-migrate.directives`, a fifth public namespace holding the Directive
  set: `against` binds a Diff, `rename-tables` and `rename-columns` append
  literal rename Directives, `drop-tables` and `drop-columns` derive one
  explicit `:drop-table` / `:drop-column` Directive per removed object not
  claimed by an earlier rename, and `build` returns the vector `plan` takes
  under `:directives`. The planner is unchanged: it still receives only
  per-object Directives and no wildcard.

### Changed

- `SQLiteExecutor` gains a third op, `first-statement`: the prefix of a SQL
  text that SQLite's prepare consumes as its first statement, or nil when the
  text holds none. This breaks adapters outside this library: each must
  implement it before `declared-snapshot` works on it (ADR 0025).

### Fixed

- When SQLite rejects a Declaration statement, `declared-snapshot` throws
  `:sqlite-error` with the statement's `:statement-index`, counted across the
  whole Declaration, and its text under `:statement`. SQLite's exception is the
  cause. It used to report "statement 0 of the batch failed" and
  `:statement-index 0` wherever the statement sat, without its text.
- A Plan that changes a view drops each surviving view and trigger that reads
  it, directly or through a chain of views, together with the view. It
  re-creates them from their stored SQL after the tables change. They used to
  stay in place while the view was missing. SQLite reads the whole schema again
  during a Rebuild, a table rename, a column rename, and a column drop, so any
  of these failed with `no such table` on the first reader, and `apply!` rolled
  back. For the same reason, a Rebuild drops a trigger of its table that reads
  such a view before the tables change, and creates it after they change. A
  Plan that changes only a view now also drops and re-creates the view's
  readers.
- A Rebuild drops and re-creates each view that reads the rebuilt table through
  a chain of views, and each trigger that refers to one of those views. It used to
  drop only the views and triggers that named the table itself. The rename at
  the end of the Rebuild then failed on a view or trigger that read a view the
  Rebuild had dropped, and `apply!` rolled back. The Rebuild re-creates each
  view after the views it reads.
- A Rebuild no longer drops and re-creates the triggers of a table that a
  `:drop-table` Directive removes in the same Plan. Those triggers leave with
  their table. When a trigger's body named a rebuilt table, the Rebuild used to
  emit `DROP TRIGGER` for a trigger that had already been dropped, and `apply!`
  failed and rolled back. Such a trigger also no longer blocks an in-place
  `DROP COLUMN` on a column its body mentions: the column now drops in place
  where it used to force a Rebuild.
- `declared-snapshot` realizes every statement of a multi-statement Declaration
  string. It used to realize only the first and ignore the rest without a
  word, so a Declaration read from a `schema.sql` file produced a Plan that
  dropped every object after it. SQLite decides where each statement ends, so
  a semicolon inside a string literal, a comment or a trigger body stays in
  its statement. Each statement is guarded on its own: DML, ATTACH, PRAGMA and
  temp objects later in a string are refused with `:malformed-input`, and
  `:statement-index` counts statements across the whole Declaration.
- Parentheses wrapping the whole of a column DEFAULT are Noise: `DEFAULT (0.01)`
  and `DEFAULT 0.01` no longer diff as a `:default` change, and a new key column
  defaulting to `(1)` gates as a constant instead of an opaque expression
  (ADR 0021).
- Adding a column to a table that has rows no longer plans an `ADD COLUMN` that
  SQLite rejects at Apply. A column with a `CURRENT_*` DEFAULT, parenthesized or
  not, any other non-literal DEFAULT, or a `STORED` generated column now goes
  through a Rebuild at every version, and is unhandled with `:rebuild-disabled`
  when `:rebuild?` is off. A `NOT NULL` column with no default, or with
  `DEFAULT NULL`, goes in place from SQLite 3.32 and through a Rebuild below it,
  behind the same `:empty-table` Gate either way (ADR 0022).

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
