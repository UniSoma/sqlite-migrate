# Directives are conditional per-object intent consumed by the planner

A **Directive** is the intent channel: the datum by which an author supplies
what a pure state diff cannot infer. Directives are a **planner input** —
`plan` takes the Diff, the Capabilities, and a seq of directives — and the
Diff itself stays a pure state delta forever: no resolved-Diff intermediate,
no rename entry kind. The fusion a rename performs is visible in the Plan
instead, through the rename-serving op's `:serves` pointing at both the
removed and the added entry.

Each directive is a plain-EDN map with a `:directive` kind keyword, matching
the map anatomy of Refusal, Gate, and Op. Kind keywords are an **open
add-only set** like refusal and gate codes. Launch inventory — exactly the
resolutions of `:destructive-drop`, nothing else:

```clojure
{:directive :rename-table  :from "users" :to "people"}
{:directive :rename-column :table "users" :from "name" :to "full_name"}
{:directive :drop-table    :table "old_stuff"}
{:directive :drop-column   :table "users" :column "legacy"}
```

NOT-NULL defaults and type-change coercions — floated in early sketches —
are deliberately absent: data conformance is the Gates mechanism ("fix your
data first", ADR 0008), and coercion is ruled-out row transformation.

**Binding is by name, per object, no wildcards.** Every `:table`, `:column`,
and `:from` key names a **live** object; every `:to` names a **declared**
one — even when the containing table is itself being renamed, the column
directive carries the live table name, and the table-rename directive alone
owns that mapping. Directive identifiers normalize exactly as identifiers do
at comparison time (case-folded, dequoted). There is no global
"allow all drops" intent and no pattern form: a bulk approval is the
auto-destructive drift reset prior art flags as a top failure mode, so
twenty intentional drops are twenty lines — the friction is the feature.

**Directives are conditional and durable**: "*if* live has X where the
target declares Y, that is a rename/authorized drop". A directive that
matches nothing is **inert but reported** — listed in the Plan's
`:unused-directives` (input order, under the Plan determinism contract),
never an error, and never consulted by Apply's refusal default, which keys
on unhandled entries only (ADR 0007). This keeps directives repo-checkable
across a fleet of databases converging at different times, while a typo'd
directive still surfaces twice: in `:unused-directives` and as the un-lifted
`:destructive-drop` refusal that blocks Apply by default.

**Rename matching is all-or-nothing**: a rename directive matches only when
the live side has the removed object *and* the declaration has the added
one. A half-match is unused; the surviving one-sided entry plans or refuses
on its own merits. A rename that still lifted the drop refusal on a half
match would be a drop authorized by a directive claiming to preserve data.

**A fused pair is just a changed object whose sides differ in name.** No
rename op taxonomy: the pair feeds the normal per-table all-in-place-or-
rebuild decision (ADR 0006), and the rebuild copy maps old name → new name
(ADR 0008). Rename sets whose sequential in-place `RENAME COLUMN` steps
would collide — swaps, chains — simply force the table onto the rebuild
path, where the new table's column names make them trivial.

**Conflicting directives throw as malformed input** (the one throw ADR 0007
reserves): the same live path claimed twice, the same declared target
claimed twice, or a rename and a drop over one object. A contradiction in
the intent channel has no honest resolution; detection is structural
validation of the directive set alone, before planning proper.

**The Plan echoes the full input directive set** in a `:directives` slot,
alongside `:unused-directives` — symmetric with capabilities, so a stored
Plan answers "what intent was this planned under?" without the call site.
Used directives need no per-op attribution: used = directives minus unused,
and their effect is visible through `:serves`.

## Considered Options

- **A resolved-Diff intermediate as a public surface** — rejected: a fourth
  value shape whose only consumer is the planner, and a rename kind smuggled
  into the Diff that ADR 0004 deliberately excluded.
- **Global `:allow-all-drops` intent** — rejected: the drift-reset failure
  mode reintroduced as a convenience.
- **Erroring on unmatched directives** — rejected: forces deleting
  directives in lockstep with the last database of a fleet converging;
  conditional-inert keeps them durable and the refusal default keeps
  misfires loud.
- **Silently ignoring unmatched directives** — rejected: a typo'd `:from`
  would degrade a rename into an unexplained refusal with no pointer back
  to the directive.
- **Temp-name rename dance for colliding in-place renames** — rejected:
  unreviewable intermediate names and a new failure surface for a case
  rebuild handles for free.
- **`:allow-drop-*` naming** — rejected: every directive is an approval;
  the kind names the intended action, matching `:rename-*`.
- **Vector/positional directive forms** — rejected: maps are
  self-describing in a printed Plan and grow optional keys without
  positional breakage, consistent with Refusal, Gate, and Op.

## Consequences

- The public-API ticket shapes `plan`'s arity around (Diff, Capabilities,
  directives) with directives defaulting to none.
- The execution-policies ticket inherits nothing new: Apply's refusal
  default is untouched by unused directives.
- Rename resolution is the only input to the rebuild copy's name mapping;
  everything else in ADR 0008 stands unchanged.
- Glossary gains Directive.
