# SQLite Migrate

A data-driven Clojure library that introspects a live SQLite file, diffs it against a
declared target schema, and produces an executable migration plan — with the diff as a
first-class public surface.

## Language

**Snapshot**:
The canonical, normalized data value describing one SQLite schema. Produced only by
introspection — whether of a live file or of a pristine database — so both sides of a
diff are the same shape.
_Avoid_: catalog, reflection, schema object

**Introspection**:
Reading a schema from a SQLite connection (pragmas plus stored CREATE sql) into a
Snapshot. The single function that produces Snapshots; it reads the `main` schema only.
_Avoid_: reflection, discovery

**Pristine database**:
A throwaway in-memory SQLite into which the declared target schema is executed so it can
be introspected like any live file. The normalizer that makes declared and live schemas
comparable without parsing SQL.
_Avoid_: scratch DB, shadow DB, dev DB

**Live file**:
The user's actual SQLite database — the current-state side of a diff.
_Avoid_: production DB, source DB

**Opaque expression**:
Expression text (CHECK bodies, generated-column expressions, index expressions, partial
WHERE clauses, DEFAULT spellings) carried in a Snapshot as extracted text, never parsed
into an AST. Compared, carried, and re-emitted — never understood.

**Declaration**:
The user-supplied target schema: canonically SQL text (a single string or a seq of
statements), pure state carrying no migration intent. Meaningful only once executed into
a pristine database and introspected into a Snapshot.
_Avoid_: target schema file, schema DSL

**Schema value**:
The EDN sugar form of a Declaration: one data value describing target state, compiled to
SQL statement text. Never consumed directly by the core.
_Avoid_: schema DSL, schema map

**Equivalence**:
The single fixed relation over Snapshots that defines "the same schema" — no
configuration knobs. An empty diff means equivalent, and vice versa. Normalizes only
at comparison time; Snapshots themselves stay verbatim.
_Avoid_: equality, schema match, sameness

**Noise**:
A difference the Equivalence relation erases: identifier case and quoting,
whitespace/comments/keyword case inside opaque expressions, ordering among named
siblings (indexes, triggers, views), engine-internal objects.

**Semantic difference**:
A difference the Equivalence relation keeps — it appears in the diff. Includes
physical column order, declared type text, constraint names, and any expression
difference beyond token identity.

**Token comparison**:
How opaque expressions are compared: both sides tokenized by SQLite's lexical rules
and matched token-for-token — keywords and bare/quoted identifiers case-folded,
string and blob literals byte-exact. Lexical only; never a parser.

**Diff**:
The first-class data value produced by comparing two Snapshots (live, declared): a thin
wrapper map holding a flat sequence of Diff entries plus both sides' Snapshot provenance.
Empty entries ⇔ the Snapshots are Equivalent. A pure state delta: no migration intent,
no cost labels, no derived dependency data.
_Avoid_: changeset, delta report, drift report (a drift report is a rendering of a Diff)

**Diff entry**:
One self-contained semantic difference inside a Diff: target-relative change kind
(`added` = declared-only, `removed` = live-only, `changed` = both, not equivalent), a
path addressing the object, both sides' verbatim sub-values, and for `changed` the set
of differing facts. Carries no rename concept — a rename is a removed/added pair until
the directives layer says otherwise.
_Avoid_: hunk, change record

**Snapshot provenance**:
Where a Snapshot came from, attached to it without affecting its equality: the SQLite
version that read it and the source database's `schema_version` fingerprint. Travels
with the Diff and the Plan as both sides' identity. The fingerprint is a mutation
counter, not a content hash — unequal proves staleness, equal does not prove identity.
Two Snapshots of identical schemas compare equal regardless of provenance.
_Avoid_: Snapshot metadata

**Stored CREATE sql**:
The verbatim CREATE statement SQLite has stored for one object, attached to that
object's Snapshot value without affecting its equality, and carried inside a Diff
entry's both-sides sub-values.

**Drift**:
A non-empty Diff between a live file and a Declaration — the live schema is not
Equivalent to the declared one.
_Avoid_: schema mismatch, divergence

**Plan**:
The ordered, executable data value produced by planning a Diff against the two
Snapshots it was computed from, under given capabilities and directives: a thin
wrapper holding an ordered sequence of Ops,
both sides' Snapshot provenance, the capabilities and directives planned under
(with unused directives called out), and the unhandled Diff entries.
List position is execution order. Pure EDN; nothing connection-bound.
_Avoid_: migration, changeset, script

**Op**:
One logical schema change inside a Plan: a change kind, the path of the object
it touches, the Diff entries it serves, and the exact SQL statements it
executes — compiled at plan time. "Operation" is acceptable in prose.
_Avoid_: step, statement, action

**Rebuild**:
The composite Op kind implementing SQLite's 12-step generalized ALTER TABLE
procedure for one table — create-new, copy, drop-old, rename-new, recreate
dependents — subsuming all of that table's changes at once. The copy maps
columns by name only; it never transforms values.
_Avoid_: table recreation, copy migration

**Apply**:
The effectful edge that executes a Plan on a connection: a dumb fold over the
Ops in plan order, inside the executor-owned transaction/FK frame, always
all-or-nothing — no partial or per-op modes. Refuses to run against a database
whose schema has drifted from the Plan's source Snapshot, with no override.
Returns an Apply report on success; throws on every non-success.
_Avoid_: run, execute, migrate

**Apply report**:
The plain-EDN value a successful Apply returns: the Plan's identity (both
sides' Snapshot provenance), the Check result from the pre-check (absent when
skipped), the Ops executed, and the post-apply `schema_version` fingerprint.
Carries no timestamps or durations.
_Avoid_: result, receipt, log

**Refusal**:
One reason a Diff entry went unhandled in a Plan: a refusal class, a code, and a
human-readable explanation. An unhandled entry carries every Refusal that applies,
not just the first. Codes are an open set — added, never removed or renamed.
_Avoid_: error, rejection, skip

**Refusal class**:
The top-level split every Refusal belongs to: `:incapable` (no route exists under
the given Capabilities — nothing the user says can lift it) or `:needs-intent` (a
route exists but planning it without explicit intent would risk data — lifted by a
directive).

**Capabilities**:
The planner input describing what the plan may assume and do: the target SQLite
version plus the `:rebuild?` policy switch. A flat map; there are no named tiers.
_Avoid_: feature flags, tier, profile

**Gate**:
A data precondition carried on an Op whose success depends on the rows it will
touch (a new NOT NULL, UNIQUE, or STRICT shape): a code, the guarded object's
path, an explanation, and a plan-compiled SELECT that samples violating rows up
to a limit the Gate carries, so a saturated sample reports "N or more".
Gates are op metadata, never Refusals — data conformance is undecidable at plan
time. Codes are an open set — added, never removed or renamed.
_Avoid_: precondition check, guard, validation

**Check**:
The read-only effectful edge that runs a Plan's Gates verbatim against a
connection and returns a Check result. Apply runs the same check by default
before its Ops. Refuses, with no override, to run against a database whose
schema has drifted from the Plan's source Snapshot: Gates compiled against a
schema that no longer exists cannot answer about the one that does.
_Avoid_: dry run, validate; pre-flight as a name for this surface (describing
Check as a pre-flight is fine)

**Check result**:
The plain-EDN value Check returns: pass/fail per Gate, violation counts,
sample rows. Also embedded in the Apply report and in the `:gate-failed`
error payload.
_Avoid_: check report, gate report (a Check report is a rendering of a Check result)

**Directive**:
One datum of explicit migration intent supplied to the planner alongside the Diff
and Capabilities: a plain-EDN map naming an intended action (rename or drop) on one
named object. Conditional — it acts only where live and declared state match its
terms; unmatched directives are inert and reported in the Plan as unused. Lifts
`:needs-intent` Refusals only. Kind keywords are an open set — added, never removed
or renamed.
_Avoid_: hint, annotation, migration option, override

**Claim**:
A Directive resolved against the live side it names. The planner indexes the supplied
Directives by folded live table name — table drops, column renames, column drops — and
reads one table's claims as it plans that table. One table's rename claims resolve as a
set, the greatest that satisfies simultaneously, so swaps and chains resolve together
while a half-match drops out inert. The verb sense is literal: a rename claims the Diff
entries on its live `from` side and its declared `to` side, and those entries fuse into
one synthetic `changed` entry.
_Avoid_: hint, annotation, resolved directive, directive index

**Pairing**:
One live table paired with its declared counterpart, plus the rename map linking their
columns. The planner's central value for a table change: routing, the rebuild family,
the gate family, and the shared planning core all read it. It is created without the
rename map and completed by column fusion, which resolves the map from the active rename
claims — declared folded name to live column name — so the rebuild's copy follows the
rename.
_Avoid_: table pair, context, ctx

**Change set**:
One table's whole pending change, planned as one thing under ADR 0006's selection rule:
the table's Diff entries together with the Pairing they plan against and the routing they
resolve to. The rule is all-or-nothing over a change set: every change achievable in
place plans in place; otherwise the whole set collapses into one `:rebuild-table`. Never
a mix. A change set that must collapse but cannot — the `:rebuild?` capability is off, or
a blocker rides it — leaves every entry in it unhandled.
_Avoid_: entry group, batch; unit for the set (a change set's entries fuse into units,
and a unit is one fused entry)

**Drift report**:
The human-readable plain-text rendering of a Diff: per-fact both-sides lines for changed
objects, whole verbatim CREATE sql for one-sided ones. Presentation only — never a parse
contract; the Diff itself is the machine surface. `X report` always names the
human rendering of value X.
_Avoid_: diff output, diff summary

**Plan report**:
The human-readable plain-text rendering of a Plan — the pre-apply review
artifact: Ops in execution order with their full SQL and Gates, unhandled
entries with their Refusals, unused Directives. Presentation only.
_Avoid_: plan output, plan summary

**Check report**:
The human-readable plain-text rendering of a Check result: failing Gates with
violation counts and sample rows. Presentation only.
_Avoid_: gate report

**Executor**:
The two-op effectful contract every runtime adapter implements: a read-only
query op and an atomic batch-apply op that owns the Frame. Everything
effectful — Introspection, Check, Apply — speaks only to an Executor;
database creation is deliberately outside the contract.
_Avoid_: connection, driver, backend

**Adapter**:
A runtime-specific implementation of the Executor together with the
constructors that open databases (a file, an in-memory database). Each
adapter exposes whatever constructors are natural for its runtime.
_Avoid_: driver, provider

**Frame**:
The executor-owned atomic envelope around a Plan's statements: foreign-key
enforcement off, one transaction, the plan-compiled gate SELECTs first (any
violating row ⇒ rollback), statements in order, a foreign-key check before
commit, all-or-nothing, enforcement always restored. Always the same shape —
the gate list may be empty, but the step always exists.
_Avoid_: transaction wrapper, harness

**Non-success class**:
The keyword classifying every exception the library throws, carried under one
namespaced key in the exception's data. An open set — added, never removed or
renamed. Classes and codes are the machine surface; all message and report
strings are presentation-only.
_Avoid_: error type, error code (for the class itself)
