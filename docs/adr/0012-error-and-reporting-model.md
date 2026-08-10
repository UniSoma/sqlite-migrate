# One namespaced error envelope; reports are the only human surface

> Amended by ADR 0018: `:drift-refused` is Check's refusal as well as Apply's.
>
> Amended by ADR 0019: the Check result the `:gate-failed` payload carries has a fixed six-key anatomy.

Every `ex-info` the library throws carries **one uniform envelope**: a single
namespaced discriminator key, `:sqlite-migrate/error`, holding a class
keyword, plus per-class payload keys. A consumer writes one
`(-> e ex-data :sqlite-migrate/error)` dispatch regardless of which function
threw. The launch class set — **open** under the same add-only promise as
refusal and gate codes (added in minor versions, never removed or renamed;
consumers must tolerate unknown classes):

- `:malformed-input` — bad arguments anywhere (conflicting directives,
  unreadable declaration, invalid opts). Payload: the offending input under a
  descriptive key; nothing further standardized.
- `:drift-refused` — Apply's or Check's `schema_version` fingerprint mismatch
  (ADR 0018). Payload:
  the Plan's source fingerprint, the live file's actual fingerprint, and both
  Snapshot-provenance blocks.
- `:unhandled-refused` — Apply given a Plan with unhandled entries without
  `:allow-unhandled?`. Payload: the unhandled entries verbatim, each already
  carrying its refusal vector.
- `:gate-failed` — the default pre-check found violating rows. Payload: the
  full Check result, exactly what a manual Check would have returned.
- `:sqlite-error` — an underlying SQLite/JDBC failure during
  Apply/Check/introspection. One class, never sub-classified; the driver
  exception rides as `ex-cause`. A mid-Apply failure carries the failing Op
  verbatim, its plan index, and the specific SQL statement that failed —
  a Rebuild is a dozen statements and the statement pointer is the
  difference between a diagnosis and a dead end.

**Payloads reuse existing values verbatim** — the Check result, the unhandled
entries, the Op — never bespoke summary shapes. One vocabulary of values
everywhere.

**`ex-message` is one line**: a class-specific summary with a key count where
it helps ("gate check failed — 2 of 3 gates violated"), never a multi-line
report. Logs and test runners mangle multi-line messages; the detail lives in
ex-data and the renderers.

**Renderer inventory: two new functions, and a naming convention.**
`*-report` always means "the human-readable string rendering of X" —
presentation only, deterministic, single-arity, no knobs, mirroring
`drift-report` (ADR 0005):

- **`plan-report`** (Plan → string) — the pre-apply review artifact: header
  with both sides' identity, Ops in execution order with kind, object path,
  gates (code + explanation), and **full SQL always** (eliding Rebuild bodies
  would undercut the "exactly this will run" contract that justified
  plan-time `:sql`, ADR 0006); then unhandled entries with each refusal's
  class, code, and explanation; then unused directives.
- **`check-report`** (Check result → string) — failing gates with counts and
  sample rows are unreadable as raw EDN.

To keep the convention consistent, the data value Check returns is renamed
the **Check result** (glossary sharpened); "gate report" inside the Apply
report is likewise the Check result. No Apply-report renderer at launch — a
success report is glanced at, not studied.

**No message catalog.** Explanation strings are baked into values where they
are produced (Refusals and Gates at plan time, per ADRs 0007/0008); renderers
arrange them. The contract: **codes and classes are the machine surface with
the stability promise; explanation strings, report strings, and ex-messages
are presentation-only — never parseable, changeable in any release.**
Localization is out of scope; strings are English, and a consumer wanting
localized output renders from codes themselves.

**No severity field.** The channel is the severity: thrown = fatal,
in-a-report = advisory. The refusal class split and gate pass/fail already
express everything a `:severity` key would, and an explicit field would
invite filtering on it instead of on codes.

## Considered Options

- **Per-function ad-hoc ex-data shapes** — rejected: the uniform envelope is
  what makes "other surfaces render it too" real; CI wrappers dispatch on
  one key.
- **Bare `:error`/`:type` discriminator** — rejected: collision-prone when
  consumers merge the envelope into their own error data.
- **Sub-classifying `:sqlite-error`** — rejected: SQLite's error zoo is a
  losing taxonomy; the cause chain preserves everything.
- **A generic shape-dispatching `report` function** — rejected:
  shape-dispatch magic contradicts the explicit-surfaces posture of ADR 0005.
- **An Apply-report renderer at launch** — rejected: a success value is
  glanced at; add it if demand appears.
- **A message catalog keyed by code** — rejected: indirection buys nothing
  when values already carry their context strings.
- **A `:severity` field** — rejected: duplicates what the structure says.
- **Full rendered report in `ex-message`** — rejected: multi-line messages
  mangle in logs; the data has a better home.

## Consequences

- The public-API ticket ships `plan-report` and `check-report` alongside
  `drift-report`, and documents the envelope key and class set.
- Glossary: Check sharpened (returns a Check result); Check result, Plan
  report, Check report, Non-success class added; Apply report reworded.
- The spec documents the class inventory with payloads, as it does refusal
  and gate codes.
