---
id: sqm-01m1hyze535s
title: Add the Directive set builder namespace (sqlite-migrate.directives)
status: closed
type: feature
priority: 2
mode: afk
created: '2026-09-02T21:04:09.891807075Z'
updated: '2026-09-02T21:23:01.927284285Z'
closed: '2026-09-02T21:23:01.927284285Z'
acceptance:
- title: bb test passes; clj-kondo --lint src test ci is clean
  done: true
- title: The ADR 0020 example thread runs verbatim at the REPL against a fixture and its Plan has no unhandled entries
  done: true
- title: README, CHANGELOG, and ci/smoke/smoke.clj cover the new namespace
  done: true
---

## Description

Ship the Directive set decided in ADR 0020: a fifth public namespace `sqlite-migrate.directives` that assembles explicit Directives against one Diff, so authorising every drop a Diff implies no longer means planning once and hand-copying the `:destructive-drop` refusals out of `:unhandled`.

The design is fully settled in `docs/adr/0020-directive-set-builder.md` and the **Directive set** entry in `CONTEXT.md`. Read both before writing code; do not reopen the choices recorded there.

## Design

Namespace `sqlite-migrate.directives`, six public fns, all pure. The thread opens with `against` and closes with `build`:

```clojure
(-> (d/against diff)
    (d/rename-tables  {"users" "people"})
    (d/rename-columns {"users" {"name" "full_name"}})
    (d/drop-tables)
    (d/drop-columns ["orders"])
    (d/build))
```

- `(against diff)` / `(against diff initial)` → `{:diff diff :directives (vec initial)}`. Throws `:malformed-input` if `diff` is not a Diff.
- `(rename-tables set {live declared ...})` → appends `{:directive :rename-table :from live :to declared}` per entry, verbatim, map iteration order made deterministic (sort by folded live name).
- `(rename-columns set {live-table {live-col declared-col ...} ...})` → appends `{:directive :rename-column :table live-table :from live-col :to declared-col}`, same determinism rule.
- `(drop-tables set)` / `(drop-tables set names)` → for each Diff entry `removed` at a table path, in Diff order, whose folded name is not the folded `:from` of a `:rename-table` already in the set (and, when `names` given, is among the folded `names`), append `{:directive :drop-table :table <live name verbatim>}`.
- `(drop-columns set)` / `(drop-columns set tables)` → same for `removed` column entries, claimed when a `:rename-column` already in the set has the same folded table and folded `:from`; `tables` filters by folded live table name.
- `(build set)` → the `:directives` vector, exactly what `plan` takes under `:directives`. No validation, no resolution; the set is eager, so `build` only extracts.

Folding: reuse `sqlite-migrate.impl.extract/fold-name` (the identifier fold directives already use) — never a private re-implementation.

Not in scope: the planner (untouched), singular rename fns, validation of literal renames against the Diff, `plan` accepting the set, an order-free deferred form.

Docs to update in the same change: README section next to the directives section showing the `->` thread above; CHANGELOG entry under the current -SNAPSHOT per docs/releasing.md; `ci/smoke/smoke.clj` exercises the new namespace once (it is public API, so the native-image job must load it); ADR 0013's inventory is already amended.

Tests (shape per docs/agents/clojure-style.md): example tests for each fn on a small live/declared pair; a rename-then-drop thread where the renamed table is excluded and the rest dropped; the reverse order producing a set the planner rejects with `:malformed-input`; a half-matched rename still suppressing the drop, with the Plan showing it unused and the entry unhandled; the select-list case-fold (`"Users"` selecting `users`); a property: for any generated Diff, `(plan live declared diff {:directives (-> (against diff) drop-tables drop-columns build)})` has no `:needs-intent` refusal of code `:destructive-drop` left in `:unhandled`.

## Notes

**2026-09-02T21:23:01.927284285Z**

Shipped sqlite-migrate.directives (against, rename-tables, rename-columns, drop-tables, drop-columns, build) in commit 1538c69 with example tests, the ordering and half-match cases, and a property that derived drops lift every :destructive-drop refusal. README, CHANGELOG, design and releasing docs, and the smoke program cover the namespace. bb test: 166 tests green; clj-kondo clean; the ADR 0020 thread ran verbatim at the REPL with no unhandled entries.
