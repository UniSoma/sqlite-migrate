# SQLite DDL Capabilities and ALTER TABLE Limits

Research findings for the design of a declarative, diff-based SQLite migration library.
All claims sourced from sqlite.org primary documentation, fetched 2026-08-06.
Empirical checks were run against a local SQLite 3.40.1 where noted (pragma output shapes only).

**Latest SQLite release verified: 3.53.4 (2026-07-24)** (changes.html). The most
migration-relevant recent release is **3.53.0 (2026-04-09)**, which added new ALTER TABLE
forms: "Enhance ALTER TABLE to permit adding and removing NOT NULL and CHECK constraints"
(changes.html, 3.53.0 entry). The `lang_altertable.html` page (last updated 2026-06-04)
documents `ALTER TABLE ... ALTER COLUMN ... SET NOT NULL / DROP NOT NULL` in prose (§6) and
shows `ADD [CONSTRAINT name] CHECK (expr)` and `DROP CONSTRAINT name` in the syntax diagram
(§1), though the prose for the CHECK forms is not yet written — see §A.5 below.

---

## A. What ALTER TABLE supports on 3.53.x

SQLite implements ALTER TABLE by editing the CREATE-statement text stored in
`sqlite_schema` and re-parsing the whole schema; the command succeeds only if the schema
still parses afterwards (lang_altertable.html §5.1, §9). This textual mechanism explains
most of the restrictions below.

### A.1 RENAME TO (lang_altertable.html §2)

- Renames a table within the same database; **cannot move a table between attached
  databases**.
- Triggers and indices stay attached to the renamed table.
- Since 3.25.0, references to the table **inside trigger bodies and view definitions are
  also rewritten**. Since 3.26.0, FOREIGN KEY references to the renamed table are always
  rewritten — regardless of `PRAGMA foreign_keys` — **unless `PRAGMA legacy_alter_table=ON`**
  is set, in which case parent-table references are never updated (see the compatibility
  table in §2).
- Error mode: since rename must re-parse the whole schema, ALTER TABLE "will normally fail
  and make no changes if it encounters any entries in the sqlite_schema table that do not
  parse" — e.g. a malformed VIEW or TRIGGER attached to the table makes RENAME fail
  (lang_altertable.html §7). Since 3.38.0, `PRAGMA writable_schema=ON` makes ALTER TABLE
  silently ignore unparsable schema rows instead (§7).

### A.2 RENAME COLUMN (lang_altertable.html §3)

- Renames the column "both within the table definition itself and also within all indexes,
  triggers, and views that reference the column."
- "If the column name change would result in a semantic ambiguity in a trigger or view,
  then the RENAME COLUMN fails with an error and no changes are applied."

### A.3 ADD COLUMN (lang_altertable.html §4)

The new column is **always appended at the end**; there is no way to add a column at a
specific position. The column-def may take any CREATE TABLE form, with these restrictions
(quoted near-verbatim from §4):

- "The column may not have a PRIMARY KEY or UNIQUE constraint."
- "The column may not have a default value of CURRENT_TIME, CURRENT_DATE,
  CURRENT_TIMESTAMP, or an expression in parentheses."
- "If a NOT NULL constraint is specified, then the column must have a default value other
  than NULL."
- "If foreign key constraints are enabled and a column with a REFERENCES clause is added,
  the column must have a default value of NULL."
- "The column may not be GENERATED ALWAYS ... STORED, though VIRTUAL columns are allowed."

Since 3.37.0: "When adding a column with a CHECK constraint, or a NOT NULL constraint on a
generated column, the added constraints are tested against all preexisting rows in the
table and the ADD COLUMN fails if any constraint fails" (§4).

Performance shape (§4): renames and constraint-free column additions edit only schema text
and run in O(1) regardless of table size; adding a column with a CHECK constraint or a
NOT NULL generated column must read all rows; DROP COLUMN must rewrite all rows.

Compatibility note: after ADD COLUMN, the database is unreadable by SQLite ≤ 3.1.3 (§4).

### A.4 DROP COLUMN (lang_altertable.html §5, added in 3.35.0 per changes.html; 3.35.5 fixed corruption bugs in it)

Removes the column and **rewrites the table content** to purge its data. "The DROP COLUMN
command only works if the column is not referenced by any other parts of the schema and is
not a PRIMARY KEY and does not have a UNIQUE constraint." Documented failure reasons
(quoted from §5):

- "The column is a PRIMARY KEY or part of one."
- "The column has a UNIQUE constraint."
- "The column is indexed."
- "The column is named in the WHERE clause of a partial index."
- "The column is named in a table or column CHECK constraint not associated with the
  column being dropped."
- "The column is used in a foreign key constraint."
- "The column is used in the expression of a generated column."
- "The column appears in a trigger or view."

Mechanism (§5.1): the CREATE TABLE text has the column definition removed and the whole
schema is re-parsed; DROP COLUMN fails "if there are any traces of the column in other
parts of the schema that will prevent the schema from parsing."

Practical consequence for a planner: dropping an indexed column requires dropping the
index first (and a planner may choose to recreate a replacement); dropping a column
referenced by a view/trigger/generated column/CHECK requires either editing those objects
first or a full table rebuild.

### A.5 New in 3.53.0: constraint add/drop forms

Changes.html (3.53.0): "Enhance ALTER TABLE to permit adding and removing NOT NULL and
CHECK constraints." What the current docs show:

- **`ALTER TABLE t ALTER COLUMN c SET NOT NULL [conflict-clause]`** and
  **`ALTER TABLE t ALTER COLUMN c DROP NOT NULL`** — documented in prose
  (lang_altertable.html §6). Notes: `SET NOT NULL` is a no-op if the column is already
  NOT NULL (never adds a redundant constraint). If a column was created with two or more
  redundant NOT NULL constraints, `DROP NOT NULL` "is guaranteed to remove one or more of
  them, but not necessarily all of them" (§6).
- **`ALTER TABLE t ADD [CONSTRAINT name] CHECK (expr) [conflict-clause]`** and
  **`ALTER TABLE t DROP CONSTRAINT name`** — present in the alter-table-stmt syntax
  diagram (lang_altertable.html §1) but with no prose section yet as of the 2026-06-04
  page revision. A planner targeting these forms should feature-detect at runtime
  (`sqlite_version() >= 3.53.0`) and note that `DROP CONSTRAINT` works by name, which
  means **only named constraints are droppable this way** — another reason for a
  migration tool to always emit `CONSTRAINT name` prefixes.
- Presumably `SET NOT NULL` and `ADD CHECK` validate existing rows the way 3.37.0's
  ADD COLUMN constraint checking does (the changelog and §8 wording imply data must be
  read); the docs do not state this explicitly yet — verify empirically before relying
  on the error behavior.

Note a small doc inconsistency: §8 still says "The only schema altering commands directly
supported by SQLite are the 'rename table', 'rename column', 'add column', 'drop column'
commands" and lists "adding CHECK or FOREIGN KEY or NOT NULL constraints" among the
12-step-only changes — that sentence predates 3.53.0's ALTER COLUMN / ADD CHECK forms.

---

## B. Everything else: the table-rebuild procedure

### B.1 Changes that force a rebuild

From lang_altertable.html §8: the 12-step procedure "is appropriate for dropping a column
[when DROP COLUMN's restrictions bite], changing the order of columns, adding or removing
a UNIQUE constraint or PRIMARY KEY, adding CHECK or FOREIGN KEY or NOT NULL constraints
[now partly covered by 3.53.0 forms — see §A.5], or changing the datatype for a column,
for example." Beyond that list, a rebuild is also the only route for:

- Changing a column's type or affinity, or its collation.
- Adding/removing/reordering PRIMARY KEY columns; adding UNIQUE to an existing column.
- Converting a table to/from WITHOUT ROWID or to/from STRICT (these are table options in
  the CREATE TABLE text with no ALTER form; lang_createtable.html, stricttables.html).
- Adding, removing, or retargeting FOREIGN KEY constraints.
- Changing a DEFAULT (schema-only; eligible for the simpler writable_schema procedure,
  §B.3, but a rebuild is the safe route).
- Converting a column to/from GENERATED, or changing a generated column's expression or
  VIRTUAL/STORED kind (STORED can never be ADD COLUMNed; gencol.html).
- Repositioning a column (new columns only append).
- Any structural change to an FTS5/virtual table other than rename (fts5.html; §D.4).

### B.2 The 12-step generalized ALTER TABLE procedure (quoted verbatim from lang_altertable.html §8)

> The steps to make arbitrary changes to the schema design of some table X are as follows:
>
> 1. If foreign key constraints are enabled, disable them using PRAGMA foreign_keys=OFF.
> 2. Start a transaction.
> 3. Remember the format of all indexes, triggers, and views associated with table X.
>    This information will be needed in step 8 below. One way to do this is to run a query
>    like the following: SELECT type, sql FROM sqlite_schema WHERE tbl_name='X'.
> 4. Use CREATE TABLE to construct a new table "new_X" that is in the desired revised
>    format of table X. Make sure that the name "new_X" does not collide with any existing
>    table name, of course.
> 5. Transfer content from X into new_X using a statement like:
>    INSERT INTO new_X SELECT ... FROM X.
> 6. Drop the old table X: DROP TABLE X.
> 7. Change the name of new_X to X using: ALTER TABLE new_X RENAME TO X.
> 8. Use CREATE INDEX, CREATE TRIGGER, and CREATE VIEW to reconstruct indexes, triggers,
>    and views associated with table X. Perhaps use the old format of the triggers,
>    indexes, and views saved from step 3 above as a guide, making changes as appropriate
>    for the alteration.
> 9. If any views refer to table X in a way that is affected by the schema change, then
>    drop those views using DROP VIEW and recreate them with whatever changes are
>    necessary to accommodate the schema change using CREATE VIEW.
> 10. If foreign key constraints were originally enabled then run PRAGMA foreign_key_check
>     to verify that the schema change did not break any foreign key constraints.
> 11. Commit the transaction started in step 2.
> 12. If foreign keys constraints were originally enabled, reenable them now.

**Ordering caution (§8, "Caution" box):** create-new → copy → drop-old → rename-new is
**correct**; rename-old → create-new → copy → drop-old is **incorrect** — "the initial
rename of the table to a temporary name might corrupt references to that table in
triggers, views, and foreign key constraints," because of the 3.25.0/3.26.0 rename
propagation. A planner must never generate the rename-first variant.

**Why foreign_keys=OFF and not just deferral:** `PRAGMA foreign_keys` "is a no-op within a
transaction" (pragma.html #pragma_foreign_keys), so step 1 must happen *before* step 2's
BEGIN. `PRAGMA defer_foreign_keys=ON` is an in-transaction alternative that postpones
enforcement to COMMIT (pragma.html #pragma_defer_foreign_keys), but the documented
procedure uses foreign_keys=OFF plus a `foreign_key_check` before COMMIT. Also relevant:
with foreign_keys=ON, `DROP TABLE` performs an implicit DELETE of all rows first, which
"fails if violations occur" — i.e. dropping a parent table in step 6 would fire/violate FK
actions (foreignkeys.html §5) — another reason enforcement must be off during the rebuild.

### B.3 The simpler writable_schema procedure (near-verbatim from lang_altertable.html §8)

For changes "that do not affect the on-disk content in any way" — documented as
appropriate for "removing CHECK or FOREIGN KEY or NOT NULL constraints, or adding,
removing, or changing default values on a column":

> 1. Start a transaction.
> 2. Run PRAGMA schema_version to determine the current schema version number. This number
>    will be needed for step 6 below.
> 3. Activate schema editing using PRAGMA writable_schema=ON.
> 4. Run an UPDATE statement to change the definition of table X in the sqlite_schema
>    table: UPDATE sqlite_schema SET sql=... WHERE type='table' AND name='X';
>    *Caution: Making a change to the sqlite_schema table like this will render the
>    database corrupt and unreadable if the change contains a syntax error.*
> 5. If the change to table X also affects other tables or indexes or triggers or views
>    within the schema, then run UPDATE statements to modify those too.
> 6. Increment the schema version number using PRAGMA schema_version=X where X is one more
>    than the old schema version number found in step 2 above.
> 7. Disable schema editing using PRAGMA writable_schema=OFF.
> 8. (Optional) Run PRAGMA integrity_check to verify that the schema changes did not
>    damage the database.
> 9. Commit the transaction started on step 1 above.

The docs repeat the corruption warning twice and recommend rehearsing the UPDATE on a
blank database first. Additional hazards: writable_schema requires
SQLITE_DBCONFIG_DEFENSIVE to be off, and misuse of `schema_version` "can result in
database corruption" (pragma.html #pragma_writable_schema, #pragma_schema_version). A
conservative migration library should treat this procedure as opt-in-only or avoid it —
the 12-step rebuild covers the same changes safely, just slower.

---

## C. The introspection surface

### C.1 sqlite_schema (schematab.html)

One row per table, index, view, and trigger. Columns:

- **type** — `'table'`, `'index'`, `'view'`, or `'trigger'` (virtual tables are `'table'`).
- **name** — object name. Automatic indexes backing UNIQUE/PRIMARY KEY constraints are
  named `sqlite_autoindex_TABLE_N`; INTEGER PRIMARY KEY (rowid alias) gets **no** index
  row at all.
- **tbl_name** — for tables/views, a copy of `name`; for indexes and triggers, the table
  (or view) they belong to. This is the join key used by step 3 of the 12-step procedure.
- **rootpage** — B-tree root page for tables and indexes; 0 or NULL for views, triggers,
  and virtual tables.
- **sql** — the CREATE statement text, *lightly normalized*: the leading
  CREATE/TABLE/VIEW/TRIGGER/INDEX keywords are uppercased, spaces after the first two
  keywords are collapsed to one, leading whitespace removed, and schema qualifiers
  stripped; the rest — including comments, internal whitespace, quoting style, and
  IF NOT EXISTS handling — is otherwise the author's original text. **NULL for automatic
  indexes** (sqlite_autoindex rows have no SQL).

Aliases: `sqlite_master` (historical, always available); `sqlite_temp_schema` /
`sqlite_temp_master` for the temp database. The schema table has no row for itself, and
ALTER TABLE ADD COLUMN etc. rewrite this text in place — so **the sql column tracks
DDL-applied changes**, but its formatting is whatever ALTER TABLE's text surgery produced,
not a canonical form. A diff tool must therefore never compare `sql` strings textually
between databases; it must parse them.

### C.2 Pragmas (pragma.html; empirically confirmed on 3.40.1)

All of the following are also available as **table-valued functions** in SELECTs —
`SELECT * FROM pragma_table_info('t')`, joinable, with an optional trailing schema
argument (pragma.html §"pragma functions", since 3.16.0). This is the ergonomic query
surface for a Clojure introspector.

- **`table_info(T)`** → cid, name, type (declared type text), notnull (0/1), dflt_value,
  pk (0, or 1-based position in the PK). Omits hidden and generated columns.
- **`table_xinfo(T)`** → same plus **hidden**: `0` normal, `1` hidden column in a virtual
  table, `2`/`3` generated column — the doc says "a dynamic or stored generated column
  (2 or 3)" without pinning which is which; empirically **2 = VIRTUAL, 3 = STORED**
  (verified on 3.40.1).
- **`table_list`** (since 3.37.0) → schema, name, type (`table`/`view`/`shadow`/`virtual`),
  ncol (including hidden+generated), **wr** (1 if WITHOUT ROWID), **strict** (1 if
  STRICT). Verified: `wr` and `strict` are reported correctly, so since 3.37 you do NOT
  need SQL parsing for these two table options. The docs add "Additional columns will
  likely be added in future releases" — read this pragma by column name, not position.
- **`index_list(T)`** → seq, name, unique (0/1), **origin** (`c` = CREATE INDEX,
  `u` = UNIQUE constraint, `pk` = PRIMARY KEY), **partial** (1 if partial index).
- **`index_info(I)` / `index_xinfo(I)`** → per index column: seqno, **cid** (−1 = rowid,
  **−2 = expression**), name (NULL for rowid/expression), and in xinfo additionally
  **desc**, **coll** (collation actually used by that index column), **key** (1 for key
  columns, 0 for trailing auxiliary columns such as the implicit rowid).
- **`foreign_key_list(T)`** → id (constraint ordinal), seq (column position within the
  constraint), table (parent), from (child column), to (parent column; NULL when the FK
  references the parent's implicit PK), on_update, on_delete, match. **No constraint
  names.**
- **`database_list`** → attached databases (seq, name, file).
- **`collation_list`**, **`function_list`** → connection-level collations and functions
  (useful to check that collations/functions referenced by expression indexes and CHECKs
  exist before replaying DDL).
- **`schema_version`** (header offset 40; bumped by every schema change — usable as a
  cheap "schema changed?" fingerprint) and **`user_version`** (offset 60; free for the
  application — the natural home for a migration tool's own version stamp). Both warn
  that setting them wrongly can corrupt the database.
- **`foreign_key_check[(T)]`** → rows for each FK violation: child table, rowid (NULL for
  WITHOUT ROWID children), parent table, FK index. Step 10 of the rebuild.
- **`integrity_check` / `quick_check`** → full vs. faster consistency check (quick_check
  skips UNIQUE/index-content verification).
- Behavior pragmas relevant to migration: **`foreign_keys`** (default OFF; no-op inside a
  transaction), **`defer_foreign_keys`** (auto-resets at COMMIT/ROLLBACK),
  **`legacy_alter_table`** (see §D.6), **`writable_schema`** (see §B.3),
  **`trusted_schema`** (see §D.6).

### C.3 What is NOT recoverable from pragmas — SQL parsing required

These facts exist **only** in the `sqlite_schema.sql` text; a diff-based planner that
wants full fidelity must parse CREATE statements:

- **CHECK constraint expressions** — no pragma exposes them at all (neither text nor
  existence).
- **Constraint names** — `CONSTRAINT name` prefixes on PK/UNIQUE/CHECK/FK are not exposed
  anywhere (foreign_key_list has numeric ids only; index_list shows autoindex names, not
  constraint names).
- **DEFAULT fidelity** — `table_info.dflt_value` returns the SQL text of the default
  (verified: `DEFAULT ('x'||'y')` comes back as the string `'x'||'y'`, outer parentheses
  stripped), so literal-vs-expression and exact original spelling require care; round-trip
  comparison should be done on parsed/normalized values, not raw dflt_value strings.
- **Per-column COLLATE** — no collation column in table_info/table_xinfo (verified: a
  `TEXT COLLATE NOCASE` column is indistinguishable from plain TEXT). index_xinfo's
  `coll` shows what an *index* uses, which only indirectly reveals column collation via
  the autoindex of a UNIQUE constraint.
- **Generated-column expressions** — table_xinfo tells you a column is generated (hidden
  2/3) but not the expression.
- **Expression-index expressions** — index_xinfo reports `cid = -2` with a NULL name; the
  expression text is only in the CREATE INDEX sql.
- **Partial-index WHERE clauses** — index_list's `partial` flag says one exists; the
  clause text is only in the sql.
- **AUTOINCREMENT** — not exposed by any pragma; detectable only by parsing the CREATE
  TABLE text (or heuristically via the table's presence in `sqlite_sequence`, which only
  proves it after first insert; autoinc.html).
- **FK deferrability** (`DEFERRABLE INITIALLY DEFERRED`) — not in foreign_key_list.
- **Column order of the original text vs. generated columns** — table_xinfo gives true
  storage order including generated columns, which is what matters for INSERT...SELECT
  column lists; fine — but note ncol in table_list includes hidden columns.
- **WITHOUT ROWID / STRICT** — *are* recoverable without parsing via `table_list.wr` /
  `.strict` on ≥ 3.37.0 (verified); on older versions WITHOUT ROWID can be detected by
  `pragma index_info(T)` returning PK rows for the table name itself (withoutrowid.html),
  and STRICT only by parsing.

Bottom line: **pragmas give you structure (columns, types, notnull, pk, indexes, FKs,
wr/strict); the sql text is authoritative for expressions, names, collations, and
AUTOINCREMENT.** A migration planner needs a real (if partial) CREATE TABLE / CREATE INDEX
parser, or must constrain its declarative model to pragma-visible features.

---

## D. Design-shaping oddities

### D.1 WITHOUT ROWID tables (withoutrowid.html)

- Must declare a PRIMARY KEY — error otherwise. AUTOINCREMENT is an error.
- NOT NULL **is** enforced on all PK columns — unlike ordinary rowid tables, where a
  legacy bug (kept for compatibility) allows NULLs in non-INTEGER PRIMARY KEY columns
  (withoutrowid.html; lang_createtable.html).
- No rowid: `last_insert_rowid()`, incremental BLOB I/O, and `sqlite3_update_hook` don't
  work; `INTEGER PRIMARY KEY` is *not* a rowid alias there.
- Clustered single-B-tree storage; recommended for non-integer/composite PKs with small
  rows.
- Conversion to/from WITHOUT ROWID is always a rebuild (table option lives in CREATE
  TABLE text). Note for the copy step: an implicit rowid does not survive; if user data
  depends on rowids, converting is lossy.
- foreign_key_check reports NULL rowid for violations in WITHOUT ROWID children.

### D.2 STRICT tables (stricttables.html; since 3.37.0)

- Every column must have a datatype, and it must be one of **INT, INTEGER, REAL, TEXT,
  BLOB, ANY** — any other type name is an error (contrast: ordinary tables accept
  anything and derive affinity). This means a planner diffing "declared type" must model
  STRICT and non-STRICT type vocabularies differently.
- Inserted values are coerced only losslessly; otherwise `SQLITE_CONSTRAINT_DATATYPE`.
- **ANY** in a STRICT table stores values verbatim; in an *ordinary* table a declared type
  of ANY has numeric-affinity-like coercion (`'000123'` → integer 123) — the same declared
  type means different things depending on the table option.
- PRIMARY KEY columns are implicitly NOT NULL in STRICT tables (with the usual INTEGER
  PRIMARY KEY NULL→auto-rowid exception).
- `STRICT` and `WITHOUT ROWID` combine freely, in either order.
- Files are readable by older versions only via writable_schema tricks; the keyword itself
  errors before 3.37.0.
- Converting to/from STRICT = rebuild; and the conversion can fail on existing data that
  doesn't satisfy the stricter typing — the planner's INSERT...SELECT may need CASTs.

### D.3 Generated columns (gencol.html; since 3.31.0)

- `GENERATED ALWAYS AS (expr) [VIRTUAL|STORED]`; VIRTUAL is the default. VIRTUAL is
  computed on read; STORED is computed on write and occupies file space.
- May have a datatype (affinity applies to the computed value), NOT NULL, CHECK, UNIQUE,
  collation, and may be indexed; may appear anywhere in the column list; may reference
  other generated columns but not circularly.
- May **not**: have a DEFAULT, be part of the PRIMARY KEY, use subqueries/aggregates/
  non-deterministic functions, or reference anything but constants and same-row columns.
  Every table needs at least one non-generated column.
- ALTER TABLE interaction: `ADD COLUMN` can add VIRTUAL generated columns but **never
  STORED** ones (lang_altertable.html §4) — so adding a STORED generated column is a
  rebuild. NOT NULL on an added generated column triggers full-table validation (3.37.0+).
- `DROP COLUMN` fails if the column "is used in the expression of a generated column"
  (lang_altertable.html §5); `RENAME COLUMN` rewrites the expressions (they're schema
  text, §3).
- Introspection: table_info hides them; table_xinfo shows them with hidden 2 (VIRTUAL) /
  3 (STORED); expression text only in sql (§C.3).

### D.4 FTS5 and virtual tables (fts5.html)

- Created with `CREATE VIRTUAL TABLE ... USING fts5(...)`; "it is an error to add types,
  constraints or PRIMARY KEY declarations" — the column list is names only, optionally
  tagged `UNINDEXED`. So the declarative model for FTS5 tables is a different shape from
  ordinary tables (names + module options, not typed columns).
- Shadow tables appear automatically (`%_data`, `%_idx`, `%_config`, and — unless
  contentless/external-content — `%_content`, `%_docsize`). `pragma table_list` reports
  them with type `shadow`; a schema differ must ignore them (they are owned by the
  module) and must not try to migrate them directly.
- **No ALTER TABLE except RENAME TO** works on virtual tables; structural change =
  create-new/copy/drop/rename, and index content can be rebuilt with the special
  `INSERT INTO t(t) VALUES('rebuild')` command. Options live in `%_config`.
- Introspection: table_xinfo on the FTS5 table shows columns (plus hidden=1 columns) but
  no types/constraints exist to report; the CREATE VIRTUAL TABLE text in sqlite_schema is
  the only record of tokenizer/options.

### D.5 CHECK constraints, expression indexes, partial indexes

- CHECK: evaluated on INSERT/UPDATE; NULL result **passes** ("If the CHECK expression
  evaluates to NULL, or any other non-zero value, it is not a constraint violation",
  lang_createtable.html). Can be disabled globally with `PRAGMA
  ignore_check_constraints=ON` — meaning existing data can silently violate CHECKs; a
  rebuild's INSERT...SELECT will then fail, so a planner should not assume old data
  satisfies old constraints.
- Expression indexes (expridx.html; since 3.9.0): expressions must reference only columns
  of the indexed table, use only deterministic functions, no subqueries. The planner
  matches indexes to queries by (near-)exact expression text — so a migration tool that
  reformats index expressions changes planner behavior even though semantics are equal.
  3.53.0 adds `REINDEX EXPRESSIONS` "to repair stale expression indexes" (changes.html),
  an acknowledgment that expression indexes can go stale — a migration tool may want to
  emit it after operations that alter function behavior.
- Partial indexes (partialindex.html; since 3.8.0): WHERE clause limited to columns of the
  table, literals, operators, deterministic functions; no subqueries or parameters.
  UNIQUE applies only to rows satisfying the WHERE. DROP COLUMN refuses to drop any
  column "named in the WHERE clause of a partial index" (lang_altertable.html §5).
- Both expression text and partial-WHERE text are invisible to pragmas (§C.3) — the sql
  column is the source of truth, and RENAME COLUMN rewrites them (they're schema text).

### D.6 Pragma interactions that shape the runner

- **foreign_keys** must be toggled **outside** any transaction (no-op inside one), which
  forces the 12-step runner's structure: `PRAGMA foreign_keys=OFF; BEGIN; ...; COMMIT;
  PRAGMA foreign_keys=ON;` — the whole migration cannot itself be wrapped in an outer
  transaction by the host application. `defer_foreign_keys=ON` is the in-transaction
  alternative (auto-resets at COMMIT) but the documented procedure prefers OFF +
  `foreign_key_check`.
- **legacy_alter_table=ON** (pragma.html): reverts RENAME to pre-3.25.0 behavior —
  renames are *not* propagated into triggers, views, or FK references ("only rewrites the
  first occurrence"), which silently breaks dependents. Historically needed by tools
  whose rebuild scripts renamed first; a modern planner should leave it OFF and use the
  correct create-copy-drop-rename order. Note ALTER TABLE also historically had issues
  with unparsable schema entries; since 3.38.0 `writable_schema=ON` is the documented
  way to bypass parse errors during ALTER (lang_altertable.html §7), and since 3.38.0
  ALTER TABLE with writable_schema=ON "silently ignores entries ... that do not parse"
  (changes.html 3.38.0).
- **writable_schema** requires DEFENSIVE off; any syntax error written = corrupt database
  (§B.3).
- **trusted_schema** (pragma.html): controls whether unaudited functions/virtual tables
  may be used *from within the schema* (views, triggers, index expressions, generated
  columns, CHECK/DEFAULT). Default ON, but "all applications are encouraged to switch
  this setting off." Relevance: if user schemas use application-defined functions in
  expression indexes / generated columns / CHECKs, replaying that DDL under
  trusted_schema=OFF fails unless the functions are registered with
  SQLITE_DETERMINISTIC/appropriate innocuous flags. A migration tool should surface this
  rather than fight it.
- **schema_version** auto-increments on every schema change — cheap cache-invalidation
  key for a snapshot of introspected schema. **user_version** is entirely free for the
  library's own bookkeeping.

---

## Implications for a diff-based migration planner

1. **Model two execution strategies per table diff.** In-place ALTERs cover exactly:
   table rename, column rename, append-column (with §A.3 restrictions), drop-column (with
   §A.4 restrictions), and — on ≥ 3.53.0 — set/drop NOT NULL, add named CHECK, drop named
   constraint. Everything else is the 12-step rebuild. The planner should compute the
   minimal set: prefer in-place when *all* diffs for a table are in-place-able, else
   collapse the table's whole diff into one rebuild (a rebuild subsumes any number of
   changes at once).
2. **The rebuild is the workhorse; get its invariants right:** foreign_keys OFF before
   BEGIN; create-new-then-rename (never rename-first); recreate indexes/triggers/views
   from saved sql; foreign_key_check before COMMIT. Because foreign_keys can't change
   inside a transaction, the library must own transaction boundaries.
3. **Introspection alone cannot round-trip a schema.** CHECK expressions, constraint
   names, column collations, generated/index/partial expressions, DEFAULT spelling, and
   AUTOINCREMENT live only in `sqlite_schema.sql`. Either (a) ship a CREATE-statement
   parser for the diffing side, or (b) diff *desired state vs. desired state* (both sides
   from the library's own declarative model, using the sql text only via parsing for
   drift detection) and use pragmas for the structural cross-check. Never compare raw sql
   strings — ALTER TABLE's text surgery and the light normalization make them unstable.
4. **Emit canonical, named DDL.** Always generate `CONSTRAINT name` prefixes (enables
   3.53.0 `DROP CONSTRAINT` and makes diffs addressable), and canonicalize expression
   text once and forever (expression-index matching is textual; churn changes query
   plans).
5. **Feature-gate by `sqlite_version()`**: DROP COLUMN ≥ 3.35.0 (avoid 3.35.0–3.35.4:
   corruption bugs), table_list/STRICT/ADD-COLUMN-validation ≥ 3.37.0, ALTER-ignores-
   unparsable-with-writable_schema ≥ 3.38.0, ALTER COLUMN NOT NULL & ADD/DROP CHECK
   constraint ≥ 3.53.0.
6. **Table options are diff dimensions, not properties to ALTER:** WITHOUT ROWID and
   STRICT changes are rebuilds; STRICT also constrains the type vocabulary, and rowid
   loss on WITHOUT ROWID conversion can be semantically lossy.
7. **Treat virtual tables (FTS5) as opaque modules:** diff their CREATE VIRTUAL TABLE
   text/options, migrate by drop-and-recreate (+ 'rebuild' for external content), ignore
   `shadow`-type tables from table_list entirely.
8. **Data can violate its own declared constraints** (ignore_check_constraints, FKs off
   by default, legacy NULLs in rowid-table PKs) — rebuilds must anticipate INSERT...SELECT
   failures and report them as data errors, not planner bugs.
9. **Avoid the writable_schema shortcut by default.** It is documented, faster for
   constraint-removal/default changes, and one syntax error from an unreadable database.
   If offered at all, gate it behind an explicit unsafe flag and follow §B.3 exactly
   (schema_version bump included).

---

## Sources

All fetched 2026-08-06 from sqlite.org:

- https://sqlite.org/lang_altertable.html (page dated 2026-06-04; §§1–9 read in full from raw HTML)
- https://sqlite.org/changes.html (raw HTML; releases through 3.53.4, 2026-07-24)
- https://sqlite.org/pragma.html (incl. raw-HTML verification of table_xinfo/table_list text)
- https://sqlite.org/schematab.html
- https://sqlite.org/stricttables.html
- https://sqlite.org/gencol.html
- https://sqlite.org/withoutrowid.html
- https://sqlite.org/fts5.html
- https://sqlite.org/lang_createtable.html
- https://sqlite.org/foreignkeys.html
- https://sqlite.org/partialindex.html
- https://sqlite.org/expridx.html
- https://sqlite.org/autoinc.html

Empirical checks (pragma output shapes, hidden-code disambiguation, dflt_value fidelity,
table_list wr/strict, index_xinfo cid=-2) run on local SQLite 3.40.1.
