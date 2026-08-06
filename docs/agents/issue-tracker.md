# Issue tracker: knot

Issues and specs for this repo live as markdown files under `.tickets/`, managed by the **`knot`** CLI. The project prefix is `sqm`, so ids look like `sqm-01h8x…`.

**Never `cat`, `grep`, or hand-edit files under `.tickets/`.** `knot` resolves partial ids across live and archive and keeps frontmatter valid; direct file access silently breaks both. Every read and write goes through the CLI.

Ids resolve by prefix — `knot show 01h8x` is enough as long as it's unambiguous.

## Conventions

- **Create a ticket**: `knot create "<title>" --type <bug|feature|task|epic|chore> --mode <afk|hitl> --description "..."`. Use a heredoc via `-d "$(cat <<'EOF' … EOF)"` for multi-line bodies. Other useful flags: `--priority 0..4` (0 = highest, default 2), `--tags a,b`, `--parent <id>`, `--acceptance "<criterion>"` (repeatable), `--dep <id>`, `--link <id>`.
- **Read a ticket**: `knot show <id>` — renders body, notes, acceptance criteria, and graph edges. Add `--json` for a machine-readable envelope.
- **List tickets**: `knot list` (live only). Filters are repeatable: `--status`, `--type`, `--tag`, `--mode`, `--assignee`, `--priority`, `--parent`, `--limit`. `knot closed` lists terminal tickets, newest first.
- **What's actionable**: `knot ready` (all deps closed) — `knot ready --mode afk` for agent-runnable work. `knot blocked` is the inverse.
- **Comment on a ticket**: `knot add-note <id> "..."` — appends a timestamped note. This is the conversation history; it's the equivalent of an issue comment.
- **Apply / remove labels**: labels are knot **tags**. `knot update <id> --add-tag <tag>` / `--remove-tag <tag>` (both repeatable and idempotent). `--tags a,b` replaces the whole set; `--tags ""` clears it. See `triage-labels.md` — two of the five triage roles are *not* tags.
- **Edit frontmatter**: `knot update <id> --title|--type|--priority|--mode|--assignee|--parent`. Pass `""` to clear an optional field.
- **Edit the body**: see *Editing a ticket body* below — `--description` is **not** a whole-body replace.
- **Acceptance criteria**: `--add-ac "<title>"`, `--remove-ac "<title>"`, and `--ac "<title>" --done|--undone` to flip one. Closing is gated on all criteria being done unless `--force` is passed.
- **Start work**: `knot start <id>` — transitions to `in_progress`.
- **Close**: `knot close <id> --summary "..."`. Closed tickets auto-move to `.tickets/archive/` and stay resolvable by id.
- **Reopen**: `knot reopen <id>`.

Statuses are `open` → `in_progress` → `closed`; `closed` is terminal.

## Editing a ticket body

A ticket body is made of sections. `knot update` has three body flags, and they are **not** interchangeable:

| Flag | Scope | Notes |
| --- | --- | --- |
| `--description` / `-d` | the `## Description` section only | Replaced in place; created if missing. Everything else in the body is untouched. |
| `--design` | the `## Design` section only | Same semantics. |
| `--body` | the **entire** body | Destructive full replace. Mutually exclusive with the two above. |

**The common mistake is reaching for `--description` to rewrite a whole ticket.** It won't — it swaps one section and silently leaves the rest, so custom headings and any prose outside `## Description` survive and the ticket ends up self-contradictory. Use `--description` only when you genuinely mean that one section.

**To patch the body as a whole, use `--body`, and read before you write** — `--body` replaces everything, so re-emit the parts you want to keep:

```sh
knot show <id> --json | jq -r '.data.body'          # read current body
knot update <id> --body "$(cat <<'EOF'
<full new body, including any sections you're preserving>
EOF
)"
```

Two things to know about `--body`:

- There is **no `--force`** and no confirmation. Git is the documented undo path — check the ticket file is committed before a large rewrite.
- A `## Acceptance Criteria` section in the body is **display-only on write**. `knot show` synthesizes it from frontmatter, and `--body` does not sync it back. Never author or edit acceptance criteria through `--body`; use `--add-ac` / `--remove-ac` / `--ac "<title>" --done|--undone`.

For additive updates — an observation, a decision, a result — prefer `knot add-note <id> "..."` over rewriting the body at all. Notes are timestamped, append-only, and can't clobber anything.

`knot edit <id>` opens the file in `$EDITOR`; it's for interactive sessions only and will fail in an autonomous run with no terminal.

## When a skill says "publish to the issue tracker"

Run `knot create`. One ticket per unit of work — never a single combined tickets file. Group a feature's tickets under an epic with `--parent <epic-id>`, and wire implementation order with `--dep`.

## When a skill says "fetch the relevant ticket"

Run `knot show <id>`. The user will normally pass the id (possibly partial) directly.

## Wayfinding operations

Used by `/wayfinder`. The **map** is an epic ticket; each **child** is a ticket parented to it.

- **Map**: `knot create "<effort>" --type epic --tags wayfinder:map --description "<Notes / Decisions-so-far / Fog body>"`. The map body is wayfinder-owned in full, so revise it later with `--body`, not `--description` — read `knot show <map> --json | jq -r '.data.body'` first and re-emit the whole thing.
- **Child ticket**: `knot create "<question>" --parent <map-id> --tags wayfinder:<type>`, where `<type>` is `research`/`prototype`/`grilling`/`task`.
- **Blocking**: `knot dep <child> <blocker>` — cycle-checked. Inspect with `knot dep tree <map-id>`. A ticket is unblocked when every dep is closed.
- **Frontier query**: `knot ready --parent <map-id>` lists open, unblocked children. Drop any with an assignee (that's a claim); first row wins.
- **Claim**: `knot start <id> && knot update <id> --assignee <handle>` — the session's first write.
- **Resolve**: `knot add-note <id> "<answer>"`, then `knot close <id> --summary "<gist>"`, then append a context pointer (gist + id) to the map's Decisions-so-far. Appending means a read-modify-write: pull the map body with `knot show <map> --json | jq -r '.data.body'`, add the pointer, and write it back with `knot update <map> --body "$(cat <<'EOF' … EOF)"`.

## Less-common operations

For `knot info` / `check` / `link` / `--json` envelope shapes / the partial-id contract, invoke the `knot` skill rather than guessing flags. `knot help <command>` documents any command in full.
