# Triage Labels

The skills speak in terms of five canonical triage roles. This file maps those roles onto what this repo's tracker (`knot`) actually stores.

Three roles are **tags**. Two are **not** — agent-vs-human readiness is a first-class `mode` field in knot, so it's expressed there instead of being duplicated as a tag.

| Label in mattpocock/skills | In our tracker      | Mechanism | Meaning                                  |
| -------------------------- | ------------------- | --------- | ---------------------------------------- |
| `needs-triage`             | `needs-triage`      | tag       | Maintainer needs to evaluate this issue  |
| `needs-info`               | `needs-info`        | tag       | Waiting on reporter for more information |
| `ready-for-agent`          | `mode: afk`         | mode      | Fully specified, ready for an AFK agent  |
| `ready-for-human`          | `mode: hitl`        | mode      | Requires human implementation            |
| `wontfix`                  | `wontfix`           | tag       | Will not be actioned                     |

When a skill mentions a role, use the corresponding mechanism from this table.

## Applying them

Tags (`needs-triage`, `needs-info`, `wontfix`):

```
knot update <id> --add-tag needs-triage
knot update <id> --remove-tag needs-triage
```

Readiness (`ready-for-agent` / `ready-for-human`):

```
knot update <id> --mode afk     # ready-for-agent
knot update <id> --mode hitl    # ready-for-human
```

Because `mode` is a single field, the two readiness roles are mutually exclusive by construction — setting one clears the other. New tickets default to `hitl`.

## Reading them

```
knot list --tag needs-triage      # the triage queue
knot ready --mode afk             # agent-runnable and unblocked
knot list --mode hitl             # needs a human
```

## `wontfix`

knot has no dedicated "closed as not-planned" status. Tag it and close it:

```
knot update <id> --add-tag wontfix
knot close <id> --force --summary "wontfix: <reason>"
```

`--force` is needed only when acceptance criteria are intentionally left undone.

Edit this table if your vocabulary changes — the skills read it rather than assuming the defaults.
