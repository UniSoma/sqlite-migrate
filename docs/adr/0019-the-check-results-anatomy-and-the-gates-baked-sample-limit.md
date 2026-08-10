# The Check result's anatomy, including the Gate's baked sample limit

Amends ADR 0008 (the Gate is five keys, and the Check result's shape is
fixed here rather than sketched).

ADR 0008 states the Gate as an exact anatomy — "a code keyword, the path of
the object it guards, a human-readable explanation, and a `:sql` SELECT
compiled at plan time" — and describes the Check result only in passing, as
"a structured report (pass/fail per gate, counts, sample rows)". The
shipped Gate carries a fifth key, `:limit`, and the shipped Check result
carries six, `:op-index` among them. This ADR grants both, and pins the
Check result's shape, which was never pinned at all.

**The Gate carries its baked sample limit as data**: `{:code :path
:explanation :sql :limit}`. ADR 0008 was incomplete here rather than
violated — it specifies the reporting behaviour ("zero rows = pass;
k < N rows = fail with exact count and samples; N rows = fail reported as
'N or more'") without giving the Gate a key that makes the third case
decidable. A row count alone cannot distinguish "exactly 10 violations"
from "10 or more".

**The limit rides the Gate because a Plan is a serializable artifact.** The
alternative — comparing the row count against a constant in the reading
code — is correct only while the reader's constant equals the one baked
into the `:sql` the rows came from. Raise the limit from 10 to 25 in a
minor release and every previously serialized Plan silently reports
`:more? false` on a saturated sample: a Gate with hundreds of violations
reported as exactly 10. Carrying the limit is the same discipline that
bakes `:sql` into the Plan instead of recompiling it at Check time — the
artifact answers for itself, in the frame of reference it was compiled in.

**The limit's value is explicitly not part of the stability promise.** It
is 10 today and any release may change it. That freedom is the entire
reason the key exists; a consumer reads it from the Gate rather than
assuming one. Tests assert the constant, never the literal.

**The Check result's anatomy**, one entry per Gate in op order:
`{:gate :op-index :pass? :violations :more? :sample-rows}`, under
`{:pass? :gates}`. `:op-index` is the plan index of the Op the Gate hangs
off — what turns "a UNIQUE Gate on `orders` failed" into a pointer at the
Op in the Plan report. It has no ADR grant today, though ADR 0012 blesses
the same pointer on the `:sqlite-error` payload for the same reason.

The Check result is a **public return value and a public error payload** —
ADR 0012 has `:gate-failed` carry it verbatim, and the Apply report embeds
it under `:check` — so its keys are a compatibility surface whether or not
an ADR ever locked them. Fixing all six here is what makes "payloads reuse
existing values verbatim" (ADR 0012) a checkable claim. Row order within
`:sample-rows` stays outside the determinism contract, per ADR 0008.

## Considered Options

- **Derive `:more?` from a constant shared between plan and core** —
  rejected: correct only for Plans compiled by the running version; see the
  saturated-sample failure above.
- **Drop `:more?` and report the raw sampled count** — rejected: discards
  the distinction ADR 0008 explicitly specifies, and "10 violations" on a
  table with thousands is a misleading number to hand an operator.
- **Recover the limit by parsing it back out of the Gate's `:sql`** —
  rejected: making a value's own data unreadable except by re-parsing the
  string it was baked into.
- **Sample `limit + 1` rows and report the extra as "more"** — rejected:
  changes the Gate SQL under the determinism contract to encode a fact the
  Gate can simply carry.
- **File `:op-index` as a separate ticket** — rejected: it is the same
  species of gap as `:limit`, found by the same review, and recording it
  costs a paragraph here versus a ticket to write that paragraph later.
- **Leave the Check result unpinned, since ADR 0008's prose was never a
  locked list** — rejected: unpinned is how `:op-index` arrived without
  anyone deciding, and the value is public in three places.

## Consequences

- No source changes: the shipped Gate and Check result already have these
  shapes.
- The gate suite's expected Gate maps assert `gate-sample-limit`, not the
  literal `10` — otherwise the tests pin what this ADR declares unpinned.
- A test covers the cross-version path directly: a hand-authored Plan whose
  Gate carries a limit other than the current constant still reports
  `:more?` correctly. Without it nothing exercises the behaviour that
  justifies the key.
- Glossary: Gate gains the sample limit as observable behaviour ("N or
  more"), not as a key name.
