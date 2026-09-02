# The Directive set: an eager, order-sensitive, Diff-bound builder of explicit Directives

Amends ADR 0009 (derived drops are compatible with the no-bulk-approval
rule) and ADR 0013 (a fifth public namespace).

ADR 0009 rules out any global "allow all drops" intent: twenty intentional
drops are twenty Directive maps, and the planner never sees a wildcard. The
cost showed up immediately in practice — to authorise the drops a Diff
implies, an author had to plan once, read every `:destructive-drop` refusal
out of `:unhandled`, and hand-write the maps. We keep the rule and remove the
typing: a **Directive set** binds one Diff to the Directives assembled so
far, and *derived* steps (`drop-tables`, `drop-columns`) read the Diff and
emit one explicit `:drop-table` / `:drop-column` Directive per removed
object, skipping objects already claimed by a rename earlier in the set.
Literal steps (`rename-tables`, `rename-columns`) append the maps they are
given verbatim. The output is a plain vector of Directives, passed to `plan`
as before; the planner is untouched and still refuses wildcards it never
receives.

The set lives in a fifth namespace, `sqlite-migrate.directives`, and is a
plain map `{:diff <Diff> :directives [...]}` opened by `against` and closed
by `build`, so it threads through `->`:

```clojure
(-> (d/against diff)
    (d/rename-tables  {"users" "people"})               ; live name → declared name
    (d/rename-columns {"users" {"name" "full_name"}})   ; live table → live col → declared col
    (d/drop-tables)                                     ; every removed table not renamed above
    (d/drop-columns ["orders"])                         ; removed columns of the listed tables
    (d/build))                                          ; → vector of Directives, as `plan` takes them
```

**Eager and order-sensitive.** Each step resolves against the set as it
stands, so renames must precede the drops they should exclude; a drop
derived first plus a rename later is the rename-and-drop conflict ADR 0009
makes the planner throw on. A derived drop is "claimed" by a rename when the
rename's `:from` (table) or its table and `:from` (column) fold to the same
live name — live-side only, never the both-sides match the planner performs.
A half-matched rename therefore still suppresses the drop, and the Plan
reports the rename unused and the entry unhandled: loud either way.

**Derived steps select; literal steps assert.** An optional collection of
live names narrows a derived step to those objects among the Diff's removed
ones (folded like directive identifiers), and a name that is not removed
yields nothing. A literal rename naming an object the Diff never removed is
appended anyway and surfaces as unused in the Plan, per ADR 0009. The set
is never stricter than the planner.

**Order of the output**: literals in call order, derived Directives in Diff
entry order, steps concatenated in thread order — so the Plan's echoed
`:directives` and `:unused-directives` read like the thread.

## Considered Options

- **A plan transformer, `Plan → Plan`** — rejected: a Plan carries neither
  its Snapshots nor its Diff, so it cannot be re-planned from; and the Diff
  already lists every drop candidate, so the Plan step adds nothing.
- **A wildcard directive kind in the planner** — rejected: ADR 0009's
  reason stands; the drift-reset failure mode is the wildcard reaching the
  planner, not the author typing less.
- **Deferred resolution at a finishing call** (order-free) — rejected: a
  second value shape and a mandatory finish for an ordering freedom nobody
  asked for; the thread reading top-down as a narrative is the point.
- **Threading a bare directives vector with the Diff as every derived
  step's second argument** — rejected: breaks the uniform first argument
  that makes `->` work.
- **Singular `rename-table` / `rename-column`** — rejected: a one-entry map
  is no longer than two positional strings.
- **Validating literal renames against the Diff at build time** — rejected:
  inert-but-reported is what keeps directives durable across a fleet.
- **`plan` accepting the set directly** — rejected: `plan` should not learn
  builder shapes. `build` is the one exit, and it returns exactly what `plan`
  takes under `:directives`.
- **Ending the thread on a `:directives` keyword lookup** — rejected: it
  reads as an afterthought, and an explicit `build` gives the namespace one
  entry and one exit.
- **`from` as the opening function** — rejected: `:from` already names the
  live side of a rename in this namespace; `against` is the glossary's own
  word for how a Directive set relates to its Diff.

## Consequences

- Public surface grows by one namespace with `against`, `rename-tables`,
  `rename-columns`, `drop-tables`, `drop-columns`, `build`. Core is
  unchanged.
- The rename-before-drop rule is a documented contract of the namespace, not
  a planner rule; the planner keeps throwing on the conflict it produces.
- Glossary gains Directive set.
