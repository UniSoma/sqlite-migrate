# Gate bidirectionality corners: new-column keys and the exact STRICT text rule

Two corners where the shipped gate inventory broke the bidirectionality
property (ADR 0010, property 3), and the decisions that close them.

**Keys spanning new columns.** The UNIQUE, PRIMARY KEY, and FOREIGN KEY
gates used to skip whenever a key column had no live counterpart —
"conformance is not row-dependent yet". But the rebuild copy (ADR 0008)
omits new columns, so every existing row takes the declared DEFAULT, and
a constraint over such a column can fail at copy time with no Gate
compiled: Check passed, Apply aborted. The fix compiles the same
duplicate-group / orphan-lookup SQL with each new key column's DEFAULT
spelling classified and substituted:

- **Constant default** (number, string, blob, TRUE/FALSE): the
  parenthesized spelling stands in for the column verbatim — the
  CHECK-gate precedent for text the planner never evaluates. Every
  copied row shares the constant, so an all-new key degenerates to
  grouping by a constant, which fails exactly when two or more rows
  exist. The parens keep a bare integer literal from reading as a
  positional GROUP BY reference. The new-column cause is named in the
  gate's `:explanation`; the gate codes stay `:unique`,
  `:primary-key`, `:foreign-key` (open set, ADR 0008).
- **NULL default** (none given, or the NULL keyword): no gate. SQLite
  treats NULL-containing keys as always distinct for UNIQUE and PK
  (including in plain rowid tables, which tolerate NULL PK values), and
  a NULL child key is never a dangling FK. Where SQLite *does* reject
  the NULL — STRICT and WITHOUT ROWID shapes mark declared PK columns
  NOT NULL — the column-level `:not-null` / `:empty-table` gates
  already guard the addition. An explicit `DEFAULT NULL` now counts as
  no default for the `:empty-table` gate too; it used to slip through
  the nil-default test although SQLite rejects the addition once rows
  exist.
- **New INTEGER PRIMARY KEY alias**: no gate — the copy omits the
  column and SQLite auto-assigns fresh rowids, so the key cannot
  collide. Measured: auto-assignment wins even when the column
  declares a constant DEFAULT.
- **Opaque expression default**: no gate — a **documented exclusion**
  from the bidirectionality property. The planner never understands
  expression text (ADR 0002), so what the copy will store is
  undecidable at plan time. The Frame's all-or-nothing transaction and
  the `:sqlite-error` throw remain the backstop, exactly as for gates
  the inventory never claimed.

**The exact STRICT text rule.** The STRICT-conversion gate's text
branch used to approximate "looks like a number" by cast
round-tripping, flagging spellings SQLite accepts ('0123', '1e2') —
false Gate failures, breaking the property's other direction. The
branch now replicates the measured acceptance rule (SQLite 3.53.2)
exactly, as a grammar decomposition compiled from
trim/substr/instr/GLOB: surrounding ASCII whitespace (space, \t \n \v
\f \r — not NBSP) trims off; the rest must be a well-formed numeric
literal (optional sign, digits with at most one dot and at least one
digit, optional signed all-digit exponent — no hex, no Inf/NaN, no
underscores); an INTEGER column additionally demands a lossless int64 —
pure-digit spellings by textual boundary comparison against ±2^63,
decimal-point or exponent spellings through the double they denote
(which loses '9223372036854775806.0' to rounding). A value-level oracle
test pins the predicate to real STRICT inserts over the nasty corpus.
Measured cost is roughly 5–6× the old predicate per text row, on a
one-shot full scan the gate contract already accepts (ADR 0008); a
canonical-round-trip short-circuit stays in reserve if it ever matters.

## Considered Options

- **Coarse empty-table gate for keys over new columns** — rejected:
  correct but far too strong (a one-row table passes a degenerate
  unique key only when it has fewer than two rows, not zero), and it
  reports the wrong precondition to fix.
- **Refuse (`:needs-intent`) constraints over opaque-defaulted new
  columns, lifted by a directive** — rejected for now: directives never
  touch gates (ADR 0008), and inventing a refusal for a case the Frame
  already handles safely buys attribution at the price of blocking
  plans that mostly succeed. Recorded as the escalation path if the
  exclusion bites in practice.
- **Keep the conservative text branch as a documented deviation** —
  rejected: a false Gate failure on data SQLite accepts makes Check
  useless as a predicate on exactly the databases (padded numeric IDs)
  most likely to be converting to STRICT; the exact rule is
  expressible and its cost bounded.
- **Evaluate opaque default expressions at plan time** — rejected: the
  planner never understands expression text (ADR 0002), and evaluation
  would need a database at plan time — the boundary ADR 0006 exists to
  keep.

## Consequences

- The bidirectionality property's documented scope gains one precise
  exclusion: keys over new columns whose DEFAULT is an opaque
  expression.
- Gate `:explanation` strings now name the new-column cause; gate SQL
  may embed constant DEFAULT spellings verbatim under the determinism
  contract.
- The STRICT gate's text branch is pinned to a measured SQLite
  acceptance rule; a future SQLite changing that rule shows up as an
  oracle-test failure, not a silent drift.
- The generative suite (sqm-01kzcv5h4yna) inherits new-column keys and
  non-canonical numeric text as generator obligations.
