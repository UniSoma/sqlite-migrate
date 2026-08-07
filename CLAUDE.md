# SQLite Migrate

## Agent skills

### Issue tracker

Issues are tracked with the `knot` CLI in `.tickets/` (project prefix `sqm`) — never read or edit those files directly. To patch a ticket body use `knot update <id> --body`; `--description` replaces only the `## Description` section. See `docs/agents/issue-tracker.md`.

### Triage labels

Triage roles map to knot tags, except agent/human readiness, which maps to knot's `mode` field (`afk`/`hitl`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

## REPL-driven development

- **Drive the REPL instead of write-run-debug cycles.** Use the `clojure-mcp` tools, not Bash; port 7888 is canonical (start it with `clojure -M:nrepl`).
- **Re-read a `.clj*` file after every Edit/Write.** A PostToolUse hook reformats it underneath you, and stale line numbers cause bad edits.
