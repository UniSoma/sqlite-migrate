# A view present on both sides carries fine-grained trigger entries

Amends ADR 0004 (fine-grained entries are no longer confined to a changed
table).

ADR 0004 gave fine-grained entries to tables only, so any difference in a
view's trigger set collapsed into one `:changed [:view v]` entry with the
`:triggers` fact. The planner served it by dropping the view and
re-creating it with its declared triggers. Two costs followed. A live-only
`INSTEAD OF` trigger on a declared view was dropped with nothing able to
address it, the silent loss a keep (ADR 0023) exists to prevent. And a
trigger-only difference dropped and re-created a view whose SELECT had not
changed.

A view present on both sides now mirrors a table: at most one view-level
`:changed` entry, whose only fact is `:sql`, plus one fine-grained entry per
differing trigger at `[:view v :trigger x]`, paired by folded name and
compared by token identity like a table's triggers. A view present on one
side stays one whole-value entry with its triggers nested. The `:triggers`
view fact is retired.

A trigger-only difference plans as drop and create of the trigger alone. A
view whose SQL changes is still dropped and re-created, which drops every
trigger on it, so its re-create also brings back the view's surviving
triggers: the unchanged declared ones and any kept ones, serving their
entries.

## Considered Options

- **Keep the collapsed entry and let a keep match part of it** — rejected:
  a keep would then match less than a whole `:removed` entry, and `:kept`
  would hold fragments of a `:changed` entry that is also served.
- **Leave view triggers out of keeps, as a README limit** — rejected: the
  change is about one build ticket, and after 1.0 the same input yielding
  different Diff entries is a breaking change.

## Consequences

- The re-create of a changed view is a new place a surviving dependent can
  be forgotten, the class of bug behind the view-over-view Rebuild failure.
  The schema generator gains an `INSTEAD OF` trigger on its view, which it
  lacks today, so residual convergence pins it.
- The drift report renders view trigger entries as it renders table ones.
- `:keep-trigger` names its parent with `:table` or `:view`.
