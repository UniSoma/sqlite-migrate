# Apply is strictly atomic in place, throws on non-success, and takes two flags

Apply executes a Plan on a connection inside the executor-owned frame (ADR
0006) and ships **no atomicity variants**: one transaction, all-or-nothing,
always. There is no per-op transaction mode, no continue-on-error, no
checkpointing between ops. A half-applied schema migration is the worst
outcome the library can produce, and SQLite gives full-plan atomicity for
free — there is no large-batch pain (à la Postgres lock queues) that would
justify chunking. The opts map leaves the door ajar if demand ever appears.

**Stage-then-swap is out of scope as an Apply mode.** Apply's contract is
*Plan + connection*, and a connection cannot swap files out from under other
connections — WAL sidecar files, open file handles, and rename semantics make
that a footgun the library cannot control. SQLite's transaction already gives
atomic-or-nothing in place. Copy-file → apply → swap is a consumer *workflow*
worth a documentation recipe (like CI drift-checking, ADR 0005), never an
Apply mode.

**No run-time destructive guard.** Directives authorize destructive drops at
plan time (ADRs 0007/0009) and the Plan is the reviewable "exactly these
statements will run" artifact. Apply adds nothing on top: no second
confirmation flag, no interactive hook. Double confirmation trains users to
pass it reflexively, and a library has no business being interactive.

**Outcome channel: throw on non-success.** On success Apply returns an
**Apply report** — a plain-EDN value carrying the Plan's identity (both
Snapshot provenance blocks echoed), the gate report from the default pre-check
(absent when gate-checking was skipped), the ops executed, and the live
file's post-apply `schema_version` fingerprint. No timestamps or durations —
the wrapper-level nondeterminism ADR 0006 evicted. Every non-success —
drift refusal, unhandled-entries refusal, gate failure, SQL error — throws
`ex-info` carrying the same structured data. A refused Apply changed
nothing, and a caller that ignores that and carries on is the dangerous
case; exceptions make ignoring impossible while `ex-info` data keeps
handling programmatic. The error-and-reporting ticket refines the ex-info
data shapes; the channel is fixed here.

**Opts: exactly two flags at launch**, in a flat map:

- `:allow-unhandled?` (default `false`) — the partial-convergence opt-in
  delegated by ADR 0007.
- `:check-gates?` (default `true`) — the gate-check opt-out delegated by
  ADR 0008, for the operator who just ran Check and wants to skip a second
  full scan of a huge table.

**No drift override.** The `schema_version` fingerprint refusal (ADR 0006)
has no `:force?` escape hatch: a mismatch means the Plan's SQL was compiled
against a schema that no longer exists, and forcing it executes stale SQL
against unknown state — exactly the corruption class the fingerprint
prevents. The remedy is cheap and always available: re-diff, re-plan,
re-apply.

## Considered Options

- **Non-atomic / per-op / continue-on-error variants** — rejected: chunking
  buys nothing on SQLite and a partial schema is the worst failure mode.
- **Stage-then-swap as an Apply mode** — rejected: unimplementable safely
  from a connection; belongs to the consumer's ops workflow (recipe).
- **Result-map outcome channel (never throw)** — rejected: every caller
  must remember to branch on the outcome; forgetting is silent and
  dangerous. Throwing makes ignoring a non-success impossible.
- **Second destructive-confirmation flag on Apply** — rejected: the
  directive is the confirmation; a second gate is reflex-training noise.
- **`:force?` drift override** — rejected: executes stale SQL against
  unknown state; re-planning is always available.
- **Timestamps/durations in the Apply report** — rejected: callers can time
  their own calls; keeps the value deterministic-shaped like Plan and Diff.

## Consequences

- The error-and-reporting ticket refines the ex-info data shapes for each
  non-success class; the channel (throw vs return) is fixed here.
- The public-API ticket ships `apply` with the two-flag opts map and the
  Apply report as its success value.
- Documentation gains a stage-then-swap recipe alongside the CI drift
  recipe.
- Glossary: Apply sharpened (throws on non-success, no variants); Apply
  report added.
