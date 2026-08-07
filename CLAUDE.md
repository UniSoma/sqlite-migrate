# SQLite Migrate

## Hard rules (every task)

- **Local git only — no remote, no ssh.** No AI attribution in commit messages or trailers.
- **Test before commit.** `bb test` runs the full suite; a single namespace runs with `clojure -X:test :nses '[sqlite-migrate.foo-test]'`. Redirect output to a file and check the exit code — `| tail` hides mid-run failures and truncated runs.
- **Lint before commit.** `clj-kondo --lint src test`.
- **Drive the REPL instead of write-run-debug cycles.** Use the `clojure-mcp` tools, not Bash. The REPL is already running on port 7888 — probe it by evaluating `(+ 1 1)`; only a failed probe warrants `bb pre_start`.
- **Re-read a `.clj*` file after every Edit/Write.** A PostToolUse hook reformats it underneath you, and stale line numbers cause bad edits.
- **Tickets only through the `knot` CLI** (`.tickets/`, prefix `sqm`) — never read or edit those files directly. `knot update --body` replaces a whole ticket body; `--description` replaces only the `## Description` section.

## Where to look

| Topic | Source |
|-------|--------|
| Clojure style: `:sqlite-migrate/error` ex-info taxonomy, normative docstrings, naming, interop, comments, how tests are shaped — read before writing or reviewing Clojure | [docs/agents/clojure-style.md](docs/agents/clojure-style.md) |
| REPL & nREPL, the paren-repair/cljfmt editing hook, protocol reload order | [docs/agents/clojure-repl-and-editing.md](docs/agents/clojure-repl-and-editing.md) |
| Exploring the codebase (clj-surgeon first), chasing evidence on a bug | [docs/agents/codebase-exploration.md](docs/agents/codebase-exploration.md) |
| Domain vocabulary, glossary, design decisions | `CONTEXT.md`, `docs/adr/`, [docs/agents/domain.md](docs/agents/domain.md) |
| Tickets: knot CLI gotchas | [docs/agents/issue-tracker.md](docs/agents/issue-tracker.md) |
| Triage roles → knot tags and `mode` (`afk`/`hitl`) | [docs/agents/triage-labels.md](docs/agents/triage-labels.md) |

## Working style

- **Answer state questions before proposing changes.** When asked "how many X?" or "what's failing?", answer first; only suggest changes if the user asks or a problem warrants flagging.
- **Give multi-step work a verifiable success criterion**, then loop until it's met — a failing test that must pass, a clean lint run, a reproduced bug that stops reproducing.
- **Name domain concepts as `CONTEXT.md` defines them** — in ticket titles, test names, and proposals alike.
