# Prior art: declarative / diff-based schema migration tools

Research for sqm-01kzbppmxwry. Feeds the target-schema-format decision
(sqm-01kzbppn13g7). Context: a first-principles, data-driven, diff-based
SQLite migration library in Clojure where the diff is a first-class public
value. All claims below are sourced from primary docs, repos, and issue
trackers; URLs inline.

## Survey axes

For each tool: (1) target-schema representation, (2) diff artifact and its
inspectability, (3) plan/DDL production and SQLite table rebuilds,
(4) rename/missing-intent handling, (5) destructive and data-dependent
gating, (6) known failure modes.

---

## Atlas (ariga)

- **Target schema**: multiple interchangeable state sources addressed by URL —
  Atlas HCL, plain SQL DDL files, a live database URL, a migration directory
  (replayed), or external ORM loaders. A required **dev database**
  (`--dev-url`, for SQLite typically `sqlite://file?mode=memory`) is the
  normalization step: desired-state files are executed onto it and the result
  is inspected, so the engine — not a hand-written parser — defines semantics.
  (https://atlasgo.io/declarative/diff,
  https://atlasgo.io/getting-started/sqlite-declarative-sql)
- **Diff artifact**: first-class at two levels. CLI `schema diff` emits SQL
  (templatable via `--format`); the Go API (`ariga.io/atlas/sql/schema`)
  exposes a public typed model — `Change` interface, `Changes` slice with
  search/mutation helpers, concrete `AddTable`/`DropColumn`/`RenameColumn`
  types, a `Differ` interface, and options like `DiffSkipChanges`. The pkg
  docs even show rewriting a drop+add pair into a `RenameColumn`
  programmatically. (https://pkg.go.dev/ariga.io/atlas/sql/schema)
- **Plan / SQLite rebuilds**: load desired state into dev DB → inspect target
  → compute `Changes` → plan SQL → lint → apply. For changes SQLite's ALTER
  can't express, Atlas emits the standard rebuild (create `new_<t>`, copy,
  drop, rename) bracketed by `PRAGMA foreign_keys = OFF/ON`. Ordering
  correctness is effectively validated by replay against the dev database.
- **Renames**: since v0.22 (2024-05) a heuristic detector **interactively
  prompts** ("Did you rename ... column from `first_name` to `name`?") before
  choosing RENAME vs DROP+ADD; release notes admit "it's impossible to
  completely disambiguate". In non-interactive CI the ambiguity resurfaces.
  (https://atlasgo.io/blog/2024/05/01/atlas-v-0-22)
- **Gating**: lint analyzers with stable codes — destructive DS101–DS103;
  data-dependent MF103 (add NOT NULL column to populated table), MF104
  (nullable→NOT NULL); backward-incompatible BC101/BC102 (renames);
  SQLite-specific **LT101** (nullable→NOT NULL without DEFAULT may fail on
  NULL data). Each configurable warn-or-fail; `--auto-approve` skips review
  ("not recommended for production"). (https://atlasgo.io/lint/analyzers)
- **Failure modes**: open bug #3317 — during a SQLite rebuild the
  `PRAGMA foreign_keys = OFF` was ineffective and the temporary DROP of a
  parent table **cascade-deleted child rows**
  (https://github.com/ariga/atlas/issues/3317). Licensing drift: much of the
  lint surface moved behind Atlas Pro in Oct 2025
  (https://github.com/ariga/atlas/discussions/2670). Early HN criticism: HCL
  can't express multi-step lock-avoiding or non-transactional operations.

## Skeema

MySQL-family only — no SQLite — but the cleanest design reference for
honesty about limits.

- **Target schema**: plain SQL `CREATE` statements, one file per object, in a
  repo directory tree. (https://github.com/skeema/skeema)
- **Diff artifact**: `skeema diff` prints DDL to stdout; exit codes
  (0 no-diff / 1 diff / 2+ error) are the machine interface. No public typed
  diff model. (https://www.skeema.io/docs/commands/diff/)
- **Plan**: a temporary **workspace schema** runs the CREATE files, then
  introspection diffs against live — same normalization idea as Atlas's dev
  DB. `verify` (default on) tests every generated ALTER in the temp schema
  before emitting it. Cross-schema FK/view ordering is *not* automatic
  ("manual ordering required"). (https://www.skeema.io/docs/requirements/)
- **Renames**: **explicitly unsupported by design** — "a shortcoming of
  Skeema's declarative approach"; renames are seen as DROP+ADD and trip the
  unsafe gate; documented workflow is rename out-of-band then `skeema pull`.
  No heuristics, no prompts. (https://www.skeema.io/docs/requirements/)
- **Gating**: `allow-unsafe` (destructive ops refused unless set) and
  `safe-below-size` (auto-permit unsafe ops on tables under a byte
  threshold — an elegant data-dependent relaxation). No Atlas-style
  MF-class analysis.
- **Failure modes**: the rename gap is its canonical criticism, acknowledged
  by the author on HN (https://news.ycombinator.com/item?id=21405958);
  operational caveats (never run against replicas, OSC tools rename
  constraints).

## sqldef (sqlite3def)

- **Target schema**: one plain SQL file of CREATE statements; bootstrap via
  `sqlite3def mydb.db --export`. (https://github.com/sqldef/sqldef)
- **Diff artifact**: the DDL script itself. `--dry-run` prints the exact
  `BEGIN…COMMIT` batch; `--check` gives CI exit-code semantics; **offline
  mode** diffs two `.sql` files with no database. No machine-readable diff.
  (https://github.com/sqldef/sqldef/blob/master/cmd-sqlite3def.md)
- **Plan / SQLite rebuilds**: parses desired vs current DDL with its own SQL
  parser. Confined to SQLite's **native ALTER subset** — it does *not*
  implement the 12-step rebuild, so type changes and constraint additions on
  existing tables are outside its plan space. HN testers caught it emitting
  SQLite-invalid `ADD CONSTRAINT ... FOREIGN KEY` and printing unexplained
  `Skipped:` lines. (https://news.ycombinator.com/item?id=46845239)
- **Renames**: explicit intent via comment annotations —
  `-- @renamed from=old_name` on tables/columns/indexes. No annotation, no
  inference: rename becomes drop+add.
- **Gating**: DROPs skipped by default; `--enable-drop` opts in. The
  destructive classification was until recently substring-based
  (https://github.com/sqldef/sqldef/pull/1297). No data-dependent checks.
- **Failure modes**: hand-rolled parser rejects valid syntax (long tail of
  parser-fix PRs); silent skips; formatter once dropped constraints on
  round-trip (https://github.com/sqldef/sqldef/pull/1224); no story for
  backfills or unexecutable reverse diffs.

## Alembic autogenerate

- **Target schema**: SQLAlchemy `MetaData`/`Table` objects (Python object
  model), wired as `target_metadata` in `env.py`.
  (https://alembic.sqlalchemy.org/en/latest/autogenerate.html)
- **Diff artifact**: genuinely first-class — `MigrateOperation` objects in
  `UpgradeOps`/`DowngradeOps` containers with `.ops` lists, `reverse()`, and
  per-op `.info`. Extensible at every layer: custom ops
  (`@Operations.register_operation`), custom detection
  (`@comparators.dispatch_for`), custom rendering
  (`@renderers.dispatch_for`), plus `include_object`/`include_name` filters.
  The docs justify ops-as-objects so the same value can be executed *or*
  rendered to code. This is the closest prior art to a diff-as-public-value
  design.
- **Plan / SQLite rebuilds**: the generated Python script *is* the plan; the
  user owns ordering. `batch_alter_table()` implements "move and copy"
  (create-new-first, correct per SQLite docs) with
  `recreate="auto"|"always"|"never"`; offline `--sql` mode needs a
  hand-built `copy_from` table. Batch mode drops the table, so FK
  enforcement must be off. (https://alembic.sqlalchemy.org/en/latest/batch.html)
- **Renames**: documented as **undetectable** — table renames "come out as an
  add/drop of two different tables, and should be hand-edited into a name
  change"; same for columns; also can't detect anonymously-named constraints
  or some type changes. Remedy is manual script editing.
- **Gating**: none. "It is always necessary to manually review and correct
  the candidate migrations"; a generated `nullable=False` simply fails at
  runtime on violating data.
- **Failure modes**: `compare_server_default` off by default and unreliable;
  unnamed constraints untargetable without a `naming_convention`; **unnamed
  CHECK constraints are silently omitted during batch recreate** (implicit
  Boolean/Enum CHECKs vanish) — the recurring rebuild hazard.

## Prisma Migrate (`migrate diff` / `migrate dev`)

- **Target schema**: Prisma Schema Language models; `migrate diff` also
  accepts `--from-empty/--from-schema/--from-migrations/--from-url`.
  (https://www.prisma.io/docs/orm/reference/prisma-cli-reference)
- **Diff artifact**: human-readable summary by default or `--script` SQL;
  `--exit-code` for CI. **No machine-readable diff** — the structured steps
  live only inside the Rust schema engine.
- **Plan / SQLite rebuilds**: engine computes ordered provider-specific SQL.
  For SQLite it *does* use the rebuild pattern
  (`PRAGMA foreign_keys=OFF; CREATE TABLE "new_X"...; INSERT ... SELECT;
  DROP; RENAME`), with at least one bug producing malformed copy SQL
  (https://github.com/prisma/prisma/issues/9204).
- **Renames**: no intent mechanism in PSL; a rename generates DROP+CREATE and
  the documented remedy is `migrate dev --create-only` then hand-editing the
  SQL to `RENAME COLUMN`.
  (https://www.prisma.io/docs/orm/prisma-migrate/workflows/customizing-migrations)
- **Gating**: the strongest data-dependent story surveyed — the **shadow
  database** replays history to detect drift and evaluate data loss, and the
  engine refuses unexecutable steps with row-count-aware messages ("There are
  13 rows in this table, it is not possible to execute this step").
  (https://www.prisma.io/docs/orm/prisma-migrate/understanding-prisma-migrate/shadow-database)
- **Failure modes**: shadow-DB friction on hosted databases; drift detection
  prompting **full database reset with data loss**, notoriously after mixing
  `db push` with `migrate dev`
  (https://github.com/prisma/prisma/discussions/16141); false-positive drift
  (#27737); interactive-only `migrate dev`; provider-specific SQL locks the
  history to one backend.

## SQLite-native prior art

- **The official 12-step procedure**
  (https://www.sqlite.org/lang_altertable.html): FK pragma off → txn → save
  `sqlite_schema` rows for the table → CREATE new → copy → DROP old → RENAME
  → recreate indexes/triggers → recreate affected views →
  `PRAGMA foreign_key_check` → commit → FK pragma on. The docs explicitly
  warn against renaming the old table first: since 3.25.0 RENAME rewrites
  references in triggers/views/FKs, so rename-first corrupts them.
  Create-new-first is the only correct order. Root cause of all of this:
  SQLite stores schema as literal SQL text, not parsed catalogs.
- **sqldiff** (https://www.sqlite.org/sqldiff.html): diffs two live DBs into
  a SQL script, but "schema migrations unsupported" — it cannot emit table
  rebuilds, skips triggers/views, and mangles virtual tables without
  `--vtab`. A data-diff tool, not a migration planner.
- **sqlite-utils transform**
  (https://sqlite-utils.datasette.io/en/stable/cli.html#transforming-tables):
  imperative, not declarative — the user states the edit (`rename=`,
  `types=`, `drop=`...), so intent is never ambiguous. Implements a subset of
  the 12 steps including `PRAGMA foreign_key_check` before commit, but
  **silently loses indexes, triggers, and views** on the transformed table.
  `--sql` returns the plan as reviewable text.
- **Röthlisberger/Manley, "Simple declarative schema migration for SQLite"**
  (https://david.rothlis.net/declarative-schema-migration-for-sqlite/):
  target schema = one `schema.sql`; diffing = execute it into an in-memory
  **pristine database** and compare `sqlite_schema` text +
  `PRAGMA table_info()` against the real DB. Plans native ALTER where legal,
  else the 12-step rebuild. Deliberately excludes triggers, views, data
  migrations, and column renames. Diff is internal, not a value. Same
  pattern in bubble-up (https://github.com/eval/bubble-up) and lamg/migrate
  (https://github.com/lamg/migrate/).

---

## Cross-cutting findings

1. **Two diffing strategies dominate.** Object-model diff (Alembic: reflected
   DB vs metadata → first-class ops tree) and pristine-database diff (Atlas,
   Skeema, Röthlisberger: execute desired DDL into a scratch database and
   compare catalogs). SQLite's free in-memory databases make the pristine
   approach uniquely cheap, and it sidesteps writing a SQL parser entirely —
   sqldef's hand-rolled parser is its largest sustained source of bugs.
2. **Rename intent is the universal unsolved input.** Every declarative tool
   hits it: Alembic and Prisma say hand-edit the output, Skeema refuses,
   Atlas prompts interactively (and admits ambiguity), sqldef uses comment
   directives. Only imperative tools (sqlite-utils) escape, because the user
   states intent directly.
3. **A first-class diff is rare and valuable.** Only Alembic (ops objects)
   and Atlas's Go API (Changes types) expose the diff as data users can
   inspect, filter, rewrite, and test. Skeema, sqldef, and Prisma emit text.
   Both first-class designs pair the data model with pluggable
   detection/rendering — the extension points fall out of the representation.
4. **The 12-step rebuild is the execution substrate, and fidelity varies.**
   Recreating indexes/triggers/views, running `PRAGMA foreign_key_check`, and
   the create-new-first ordering are each dropped by at least one shipped
   tool (sqlite-utils loses indexes/triggers; Alembic silently drops unnamed
   CHECKs; Atlas has an open cascade-deletion bug from an ineffective FK
   pragma).
5. **Data-dependent legality is almost nowhere.** Only Prisma simulates and
   refuses with row counts; only Atlas *lints* for it (MF103/MF104/LT101);
   Skeema's `safe-below-size` is a blunt but useful proxy. Everyone else
   fails at apply time.

## Adopt / avoid

**Adopt**
- Pristine in-memory database as the normalizer: execute the declared schema
  into `:memory:`, read catalogs back, diff catalog data — never parse SQL
  yourself (Atlas dev-DB, Skeema workspace, Röthlisberger; anti-example
  sqldef).
- Diff as a typed, public, reversible value with per-op metadata, and
  detection/rendering as open dispatch over that value (Alembic
  `MigrateOperation`/`UpgradeOps`, Atlas `Changes`). This is the project's
  core bet and the two best tools validate it.
- Rename intent as explicit data in the diff/declaration, not a heuristic:
  a `:rename` op the user asserts (sqlite-utils `rename=`, sqldef
  `@renamed`, Atlas's programmatic `RenameColumn` rewrite). An optional
  similarity *suggestion* layer may propose renames, but the accepted diff
  must record the decision so CI is deterministic (avoid Atlas's
  interactive-only prompt).
- Full-fidelity 12-step rebuild: create-new-first, recreate indexes/triggers
  /views from saved `sqlite_schema` rows, `PRAGMA foreign_key_check` before
  commit, FK pragma verified actually off (Atlas #3317 shows "emitted the
  pragma" is not "pragma took effect" — e.g. inside a transaction it's a
  no-op).
- Named lint findings as data on the diff (Atlas DS/MF/LT codes), with
  data-dependent checks that query the live DB (`SELECT EXISTS(... IS NULL)`
  before NOT NULL tightening, duplicate-check before UNIQUE — Prisma's
  refusal messages as the UX bar), plus a `safe-below-size`-style threshold
  escape hatch (Skeema).
- Destructive ops present in the diff but gated at plan/apply time by
  explicit policy (sqldef's safe-by-default flag; Atlas's
  `DiffSkipChanges`) — never silently omitted.
- CI-friendly surfaces: exit-code drift check (`--check`, Skeema exit codes)
  and offline schema-file-to-schema-file diffing (sqldef offline mode).

**Avoid**
- Hand-written SQL parsing as the source of truth (sqldef's failure tail).
- Silent skips or lossy round-trips: every change the planner cannot or will
  not express must appear in the diff/plan as an explicit refusal with a
  reason (sqldef `Skipped:`, sqlite-utils index loss, Alembic unnamed-CHECK
  omission).
- Interactive prompts as the *only* channel for intent (Atlas) — CI
  determinism requires intent to live in data.
- Conflating dev-loop convenience with production state management: Prisma's
  drift-detection "reset your database" and `db push`-vs-`migrate dev` mixing
  is the cautionary tale; drift should be a reported value, never an
  automatic destructive action.
- Rename-old-table-first rebuild ordering (corrupts trigger/view/FK
  references since SQLite 3.25.0).
- Text-only diff output as the sole artifact (Skeema, Prisma) — it forecloses
  testing, filtering, and programmatic rewriting.
- Coupling core safety features to a paid tier mid-life (Atlas lint
  paywall) — keep gating primitives in the library itself.
