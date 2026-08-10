# Gates are plan-compiled sampling SELECTs; rebuilds copy by name only

> Amended by ADR 0016: Apply's in-transaction gate check reaches the executor as `gate-sqls` data, not a callback.

A **Gate** is a data precondition carried on an Op: a plain-EDN map with a
code keyword, the path of the object it guards, a human-readable explanation,
and a `:sql` SELECT compiled at plan time — the same anatomy as a Refusal
plus the check statement. Gates live in the op's `:gates` vector. Gate codes
are an **open set** like refusal codes: added in minor versions, never
removed or renamed; consumers must tolerate unknown codes.

The rule that generates the inventory: *a gate exists iff SQLite would
reject or destroy existing rows when the op runs and conformance cannot be
decided from the Snapshot.* Launch inventory:

| Change | Row precondition |
|---|---|
| NOT NULL added/tightened (in-place or rebuild) | no NULLs in the column |
| UNIQUE constraint or unique index created | no duplicate key groups |
| PK added/changed (rebuild) | no duplicates, no NULLs |
| CHECK added or changed | no rows violating the new expression (opaque expression embedded verbatim) |
| FK added/retargeted | no orphan child rows |
| Conversion to STRICT | every value matches its column's declared type |
| Conversion to WITHOUT ROWID | PK columns non-NULL |
| Column added NOT NULL with no non-NULL default (rebuild) | table is empty |

Deliberate exclusions: **affinity coercion** on declared-type change in
non-STRICT tables is SQLite's normal insert semantics — nothing fails, so
no gate (documentation note only). **FK gates are carried even though the
executor frame's `foreign_key_check` would catch orphans at COMMIT** — the
frame check fires after all copy work with a terse pragma result; the gate
reports orphans before any work runs. The frame remains the backstop.

**Gate SQL shape**: one SELECT per gate returning violating rows, with a
plan-time-baked `LIMIT` (a small fixed constant, e.g. 10). Zero rows =
pass; k < N rows = fail with exact count and samples; N rows = fail
reported as "N or more". Proving a pass scans the whole table under any
shape, so the LIMIT controls result-set size, not scan cost — never
materializes a huge result on a huge table. Gate SQL falls under the same
byte-identical determinism contract as op `:sql`; sample row *order* is
whatever SQLite yields (checker output is not under the determinism
contract — the Plan value is).

**Checking**: two consumers, neither replacing SQLite's own enforcement,
which remains the true backstop.

- A public **Check** surface at the effectful edge: Plan + connection,
  read-only, runs every gate verbatim, returns a structured report
  (pass/fail per gate, counts, sample rows). Usable as a pre-flight in CI
  or before a maintenance window.
- **Apply gate-checks by default**: all gates up-front once the
  transaction frame is open (TOCTOU-free under the txn), failing with the
  structured gate report and rolling back — a predictable, attributable
  failure instead of a mid-rebuild `SQLITE_CONSTRAINT`.

**No plan-time policy knob**: no "refuse rather than gamble" capability
making gated ops refuse. With Apply gate-checking by default, running a
gated plan is not a gamble; the knob would demand plan-time certainty
about something plan-time cannot decide — the confusion ADR 0007 already
rejected. The open refusal-code set leaves the door ajar if real demand
appears.

**Rebuild data movement**: the copy is **column mapping by name** — for
each column of the new table that also exists in the old (name-match after
the directives layer has resolved renames; never positional, never
heuristic), copy verbatim; new columns take their declared default;
dropped columns are not copied (their loss was authorized by the
`:destructive-drop` directive that let the drop plan). The copy SELECT is
compiled into the rebuild op's `:sql` at plan time like every other
statement.

**Row transformation beyond column mapping is out of scope** for this
library: no USING-style per-column expressions, no directive-supplied
transforms. "Fix your data first, then converge the schema" is the
contract; gates tell the user precisely what to fix. The seam (plan-time-
compiled copy SELECT) is already right if demand ever justifies adding it.

## Considered Options

- **Informational-only gates (SQLite enforcement is the check)** —
  rejected: wastes the mechanism; the point is an attributable pre-flight
  failure, not a raw constraint error mid-copy.
- **Checker-generated check SQL** — rejected: a checker that writes its
  own SQL is the same auditability hole as an executor that writes SQL
  (ADR 0006).
- **EXISTS probe + sample SELECT pair** — rejected: buys nothing on the
  pass path (a pass scans everything either way) and the baked-LIMIT
  sample already stops early on failure; two artifacts to review for one.
- **Exact COUNT(*) alongside the sample** — rejected: exact violation
  counts aren't worth a second full scan of a huge table; "N or more,
  here are N" is actionable.
- **Refuse-rather-than-gamble capability switch** — rejected: see above.
- **Positional column matching in the rebuild copy** — rejected: name
  identity post-directives is the only non-heuristic rule.
- **USING-style transform expressions via directives** — rejected:
  arbitrary user SQL inside the schema tool's one data-touching statement,
  untestable against the declared schema (opaque), and prior-art research
  flags lossy rebuilds as a top failure mode.

## Consequences

- The execution-policies ticket may name an opt-out for Apply's default
  gate-check; the default is fixed here.
- The public-API ticket includes the Check surface as a shipped effectful
  edge alongside Apply.
- The directives layer carries no data-transform mechanism; directives
  lift `:needs-intent` refusals only (ADR 0007) and never touch gates.
- The testing ticket inherits gate SQL under the byte-identical plan
  determinism property.
- Glossary: Gate sharpened; Check added; Rebuild notes the by-name copy.
