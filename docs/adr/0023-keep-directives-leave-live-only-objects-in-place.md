# Keep Directives leave live-only objects in place

Amends ADR 0006 (Plan completeness gains a kept bucket), ADR 0009 (a
Directive can keep an object, not only lift a Refusal), ADR 0010 (residual
convergence counts Kept entries) and ADR 0020 (the Directive set gains
keep steps).

A consumer's live file holds objects that its own components create at
runtime and that no Declaration names. The first consumer's file has 75
consistency-cache tables, and its "freeze" guard triggers make a table
read-only. Each such table is a `:destructive-drop` Refusal, and each such
trigger is dropped without a Directive, which silently unfreezes the table.
The only ways past were a drop Directive, which destroys the data the
consumer means to keep; `:allow-unhandled?`, which also waives every
unrelated Refusal in the Plan; or appending each object's stored CREATE sql
to a per-file Declaration, which works but turns the Declaration into
"the intent plus this one file's runtime objects".

"Leave this live object alone" is migration intent a state Diff cannot
infer, so it travels as Directives. Four kinds join the add-only set, with
the flat anatomy of the existing kinds, every key naming a **live** object:

```clojure
{:directive :keep-table   :table "x_checks_cache"}
{:directive :keep-view    :view  "v_report"}
{:directive :keep-index   :table "t" :index "t_by_owner"}
{:directive :keep-trigger :table "t" :trigger "freeze_t_disallow_insert"}
{:directive :keep-trigger :view  "v" :trigger "v_insert"}   ; ADR 0024
```

**A keep matches exactly one whole `:removed` entry.** `:keep-table` covers
virtual tables and carries the table's nested indexes and triggers with it;
`:keep-view` carries the view's triggers. `:keep-index` and `:keep-trigger`
match a live-only index or trigger on a table or view that both sides have.
A keep that names a `:changed` or `:added` object, or nothing at all, is
inert and listed in `:unused-directives`, as every unmatched Directive is.
A keep never means "don't converge this declared object". Columns and
constraints cannot be kept: a Rebuild creates the declared shape, so a kept
column could only be dropped or leak into the Declaration.

**The Diff is unchanged and stays honest.** Kept objects still appear as
`:removed` entries, `drift?` stays true for a file that has them, and the
Equivalence relation keeps no knobs (ADR 0003). The documented CI check for
"anything left to do under my intent" plans with the keeps and asserts that
`:ops` and `:unhandled` are both empty.

**The Plan gains a `:kept` slot** holding the Kept entries, in Diff-entry
order. ADR 0006's completeness invariant becomes served ∪ kept ∪ unhandled
= all entries, the three disjoint and mechanically checkable. "Nothing to
do" is empty `:ops` and empty `:unhandled`; `:kept` may be non-empty. `:ops`
stays "things that execute": no no-SQL Op serves a Kept entry. The Plan
report lists Kept entries in their own section. Apply's refusal default
keys on `:unhandled` only, so Kept entries never block it.

**Kept objects survive a Rebuild.** A kept index or trigger on the rebuilt
table is re-created from its **live** stored CREATE sql after the rename,
beside the declared ones. A kept view that mentions the table joins the
surviving dependents the Rebuild already drops before the swap and
re-creates from live text. The planner does not parse a kept object's body,
so a re-create that the new shape makes invalid (a kept trigger reading a
column the Rebuild removes, a kept index over a column dropped in place)
fails Apply at that statement and rolls back, the same failure the object
would cause in the Declaration.

**Kept objects leave with the tables they depend on.** An index or trigger
**on** a dropped table is nested in that table's entry, so a keep naming it
matches nothing and is reported unused; the object goes with `DROP TABLE`.
A kept view, or a kept trigger whose body **reads** a dropped table, is
selected by the lexical mention test the Rebuild uses for dependents and
dropped in phase 1; the keep counts as used, and the Plan report shows the
drop. SQLite's `DROP TABLE` would otherwise leave it dangling, silently or
until a later Rebuild's rename fails the schema check. The lexical test
covers only objects that read a dropped table. The objects **on** it leave
by structure: the indexes and triggers nested in a dropped table's
`:removed` entry are never surviving Rebuild dependents, a set-membership
fact, not a text match. The planner broke that before this ADR: a Rebuild
re-created a dropped table's trigger that mentioned the rebuilt table.

**Conflicts follow ADR 0009.** A keep and a drop or rename over the same
live object claim one live path twice and throw `:malformed-input`.

**ADR 0007's boundary stands.** Dropping a live-only index, trigger or view
still plans without a Refusal: the line is "does executing it lose
values". A trigger missing from a Declaration is usually one its author
deleted; a keep protects the undeclared ones on purpose.

**The Directive set gains keep steps** that mirror the drops:
`keep-tables` and `keep-views` take an optional collection of live names,
and `keep-indexes` and `keep-triggers` an optional collection of parent
table or view names, each emitting one explicit keep per removed candidate.
A keep claims its object as a rename does, so a later derived drop skips it,
and a literal drop after it is the conflict above. Selecting by a name rule
is the caller's filter over the Diff; the steps take no predicate.

**ADR 0010's residual convergence** becomes: after Apply,
`diff(introspect(live), target)` equals exactly the Plan's unhandled
entries plus its Kept entries, with each kept object's stored CREATE sql
unchanged. Data preservation covers every column of a kept table.

## Considered Options

- **A documented recipe: append live CREATE text to the Declaration** —
  rejected: every consumer with runtime objects writes the same step, the
  Declaration becomes per-file state, and a guard on a removed table breaks
  `declared-snapshot`.
- **A library helper that builds the extended Declaration** — rejected: it
  makes the Declaration depend on the live file, contrary to ADR 0002's
  "pure target state", and the Diff stops reporting the objects.
- **A separate plan opt (`:unmanaged #{[:table "x"] …}`)** — rejected: it
  rebuilds the echo, unused-reporting and conflict machinery Directives
  already have, and reopens `plan`'s opts, which ADR 0017 fixed at
  `:capabilities` and `:directives`.
- **A single `:keep` kind carrying a `:path`** — rejected: a second
  Directive anatomy beside four flat-key kinds.
- **A no-SQL `:keep` Op serving the entry** — rejected: it keeps the
  completeness wording at the cost of "an Op is one schema change", and
  Apply would fold over no-ops.
- **A `:needs-intent` Refusal for dropping triggers (or indexes and
  views)** — rejected: it taxes the common case, a trigger deleted from the
  Declaration, to protect the rare undeclared one that a keep now protects.
- **A kept view that reads a dropped table is a conflict** — rejected: it
  blocks the whole upgrade over a data-free object the drop invalidates
  anyway.
- **Predicate arguments on the keep steps** — rejected: two selection
  shapes in one namespace; the drop steps take names.

## Consequences

- The public surface freeze inherits four Directive kinds, four builder
  steps and the Plan's `:kept` slot. All are additive to the open sets.
- The Rebuild's dependent collection reads kept objects from the live
  Snapshot; the view planner re-creates kept view triggers (ADR 0024).
- The generative suite gains keeps in its Directive generation, pinning the
  amended residual convergence.
- The README's "Selective equivalence" limit stays true, since the
  relation and the Diff are unchanged; it gains a pointer to keeps.
- Glossary gains Kept entry; Directive, Plan and Directive set are updated.
