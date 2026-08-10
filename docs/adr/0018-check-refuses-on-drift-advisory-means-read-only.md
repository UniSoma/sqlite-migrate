# Check refuses on drift; advisory means read-only, not never-throws

Amends ADR 0008 (Check's contract), ADR 0012 (`:drift-refused` belongs to
both edges), ADR 0013 (the inventory table's throw channel), and ADR 0016
(what its "advisory" parenthetical meant).

`check` verifies the live `schema_version` fingerprint against the Plan's
source Snapshot provenance and throws `:drift-refused` before running a
single Gate. That behaviour shipped with the Check surface itself and is
asserted by the gate and error-envelope suites, but no ADR granted it:
ADR 0008 describes Check as read-only and returning a structured report,
ADR 0012 lists `:drift-refused` as *Apply's* fingerprint mismatch, and
ADR 0016 says in passing that `check` is "unchanged (advisory, outside any
transaction)". Read literally, that parenthetical denies the refusal. This
ADR blesses the shipped behaviour and fixes the vocabulary that made it
look like a deviation.

**`:drift-refused` belongs to both effectful edges.** A drifted Plan's Gate
SQL was compiled against live table and column spellings that may no longer
exist. Running it yields one of two bad outcomes: a `:sqlite-error` about a
missing object, which is a worse diagnosis than the accurate one; or —
strictly worse — a clean pass computed against a table that has since
changed. A pre-flight whose whole job is to say "your data is ready" must
never produce a false green. Refusing is the only answer that stays true.
The payload is Apply's verbatim (both fingerprints, both Snapshot-metadata
blocks); one `drift-refused!` builds it for both edges. The refusal has no
override at either edge, for ADR 0011's reason: the remedy is cheap and
always available — re-diff, re-plan.

**Advisory means read-only and outside any transaction — not
never-throws.** ADR 0016's parenthetical was describing what the Frame's
new gate step did *not* change about `check`; it was never a claim about
Check's outcome channel. The channel is ADR 0011's, uniformly: throw on
non-success. Nothing about being advisory implies returning a value that
cannot be computed.

**Drift beats Gates, at both edges.** `check` verifies the fingerprint
before running any Gate; `apply!` reads the drift probe at `gate-sqls`
index 0 and refuses on drift even when Gates also returned rows. That
parity was implemented but unstated, one reordering away from being lost.
It is a contract: Gate results computed against a dead schema are not
merely stale, they are unanswerable, and reporting "3 Gates failed" about a
table that no longer has that shape is worse than reporting nothing.

**Check's TOCTOU window stays open, deliberately.** `check` reads the
fingerprint and then runs the Gates, all outside any transaction; the
schema can drift in between. This is the window ADR 0016 called it
"incoherent to shrug at" for Apply, and the asymmetry is the point: Apply
*acts* on its answer, so a stale answer corrupts a database; Check
*reports* its answer to an operator who will then run Apply, and Apply
re-verifies inside the Frame. A drifted Check result costs a wasted read.
Closing the window would need a read-transaction op the Executor does not
have — a third op, against ADR 0013's two-op contract — to buy protection
Apply already provides.

## Considered Options

- **Narrow `check` back to advisory (run the Gates, return regardless)** —
  rejected: this is the false-green case. Gate SQL against a dead schema
  answers about a database that no longer exists, and the caller cannot
  tell that from a genuine pass.
- **Return a Check result carrying a `:drift?` flag instead of throwing** —
  rejected: same defect wearing a warning label, and it splits the outcome
  channel ADR 0011 deliberately made uniform. A caller who ignores the flag
  is the dangerous case exceptions exist to prevent.
- **A `:force?` override on Check's refusal** — rejected: an override
  exists to let a caller proceed, and there is nothing here to proceed to —
  the Gate SQL is the thing that has gone stale.
- **Close Check's TOCTOU window with a read-transaction Executor op** —
  rejected: a third op for every adapter author, to protect a report whose
  consumer re-verifies in the Frame moments later.
- **Amend ADRs 0008/0012/0016 in place rather than record this** —
  rejected: in-place edits erase when a decision was made, which is the
  failure that produced this ADR.

## Consequences

- ADR 0012's `:drift-refused` bullet reads "Apply's and Check's"; the
  payload is unchanged.
- ADR 0013's inventory table gains one sentence: every effectful fn
  signals non-success by throwing. The Returns column hid that channel for
  `apply!` too, so the note covers all four rows rather than footnoting
  `check`.
- ADR 0016's "advisory" parenthetical is superseded by the definition
  above.
- No code changes: the shipped behaviour, docstrings, and tests already
  match. Glossary: Check gains the refusal, and its `_Avoid_` line
  distinguishes naming the surface "pre-flight" from describing it as one.
