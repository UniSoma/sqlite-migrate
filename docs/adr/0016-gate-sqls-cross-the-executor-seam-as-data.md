# Gate SQL crosses the Executor seam as data; the Frame gains a gate step

> Amended by ADR 0018: "advisory" below means read-only and outside any transaction — `check` does refuse on drift.

Amends ADR 0013 (the `execute-batch!` shape and Frame enumeration) and
ADR 0008 (how Apply's in-transaction gate check reaches the database).

Code review found `SQLiteExecutor` shipping a third `execute-batch!`
arity no ADR granted: `[conn statements pre-check!]`, a zero-arg
callback the adapter invokes after `BEGIN`. It existed to honor ADR
0008's promise — "all gates up-front once the transaction frame is
open (TOCTOU-free under the txn)" — which ADR 0013's "exactly two
ops, two functions plus constructors" never accommodated. The two
ADRs were in latent conflict; this ADR resolves it.

**The arity is granted, but its third parameter is data, not a
closure**: `execute-batch! [conn statements] [conn statements
gate-sqls]`, where `gate-sqls` is a vector of plan-compiled read-only
SELECT strings. The two-argument arity is the three-argument one with
no gate SQL. A callback is the one thing that cannot ride the pod
seam ADR 0013's coarse ops were designed for (a babashka pod passes
data, not functions — and the callback additionally assumed
re-entrant `execute-query` on a conn holding an open transaction,
which a coarse-op runtime cannot honor). SQL strings are exactly what
already rides that seam. The Executor remains a two-op contract.

**Frame step 4 — the gate step**: after `BEGIN` and before the
statements, the executor runs each of `gate-sqls` on its query path
(keyword-keyed row maps, as `execute-query`). If every result is
empty, proceed. If any returns rows, roll back and throw with ex-data
`{:sqlite-migrate/error :gates-violated, :gate-results [...]}` —
index-aligned with `gate-sqls`, one vector of row maps per entry
(empty = that gate passed), no `:statement-index`. The step runs
**all** gates before deciding: Apply's `:gate-failed` must carry the
same per-gate Check result a manual `check` returns, and fail-fast
cannot produce it. The gate step always exists; the list may be
empty — the Frame stays one unconditional shape.

**Core owns the translation.** `apply!` compiles nothing new at this
seam: it passes each Gate's `:sql`, catches `:gates-violated`, zips
indexes back to Gate maps, rebuilds the Check result, and throws the
public `:gate-failed` as before. On success it synthesizes the
passing Check result for the Apply report (all-pass carries no
information the rows would add). "Pre-check" survives as the name of
Apply's default behavior (ADRs 0011/0012 unchanged); only the
callback mechanism is retired.

**The drift check joins the gate step.** `apply!`'s fingerprint
verification ran outside the Frame — the exact TOCTOU window ADR 0008
refused for gates, but against the schema the whole plan was compiled
for. Core now prepends `SELECT * FROM pragma_schema_version WHERE
schema_version <> <plan-fingerprint>` as `gate-sqls` index 0 —
**always, including under `:check-gates? false`**: that opt-out skips
redundant table scans (ADR 0011), and `:drift-refused` is
override-free; the fingerprint probe is O(1). So `apply!` always uses
the three-argument arity. Index-0 rows translate to `:drift-refused`
and take precedence over any gate rows — gate SQL compiled against a
dead schema yields answers about a database that no longer exists.
The early, outside-frame `verify-fingerprint!` stays as the friendly
fast-fail; `check` is unchanged (advisory, outside any transaction — which
means read-only, not never-throws: ADR 0018).

## Considered Options

- **Grant the callback arity as shipped** — rejected: optimizes for
  the JDBC adapter at the expense of the runtime-agnostic bet that
  motivated coarse ops; the TOCTOU promise would silently weaken to
  best-effort on any pod-shaped adapter, and the re-entrant-query
  obligation was never even written down.
- **Return to two 2-arg ops, Check before the Frame** — rejected:
  retracts ADR 0008's TOCTOU-free promise rather than keeping it.
- **Gates as ordinary batch statements** — rejected: a SELECT
  returning violating rows is not an error; forcing failure needs
  RAISE-trigger hacks and loses the structured Check result.
- **Fail-fast on the first violating gate** — rejected: breaks
  `:gate-failed`/`check` Check-result parity (asserted by the error
  envelope suite).
- **A single mandatory 3-arity** — rejected: churns every call site
  to pass `[]` for no contract gain; the convenience arity is defined
  rigorously in terms of the full one.
- **Leave the fingerprint check outside the Frame** — rejected: it
  would be incoherent to argue TOCTOU-freedom matters for data
  preconditions while shrugging at the schema precondition, at zero
  marginal protocol cost.

## Consequences

- Protocol docstrings (the normative adapter-author spec) rewrite the
  three-argument arity and the Frame enumeration; adapter authors now
  implement "any gate rows ⇒ rollback and throw", mirroring the
  `foreign_key_check` step they already own.
- `:gates-violated` joins the executor-level error vocabulary next to
  `:statement-index`; `:gate-failed` and `:drift-refused` stay the
  public classes, built by core.
- `apply!` loses its 2-arity/3-arity branch; the drift window between
  fingerprint read and `BEGIN` closes.
- ADRs 0013 and 0008 carry a one-line pointer here; glossary Frame
  entry gains the gate step.
- The Frame-level gate step needs direct adapter tests — the shipped
  callback arity had none.
