# Clojure REPL & Editing

## Driving the REPL

**Use the `clojure-mcp` MCP tools — not Bash.** They speak nREPL natively (session, bencode, reload semantics); rolling your own socket script wastes turns and produces brittle output. The tools are deferred — load their schemas once at the start of a Clojure-heavy session via `ToolSearch query:"select:clojure_eval,..."`.

**The REPL is already running on port 7888.** The health check is a `clojure_eval` of `(+ 1 1)` — start every REPL session with that probe and trust its answer. Ignore `.nrepl-port` files — they may be stale from a prior session. Only when the probe fails, run `bb pre_start` (idempotent; starts `clojure -M:test:nrepl`, so test paths are on the classpath), then probe again.

**Always pass `:reload` when requiring during a session.**

**Reload order: `sqlite-migrate.protocols` first, then its dependents, then the test ns.** Re-evaluating a `defprotocol` mints a fresh protocol — objects built against the old one (existing `reify`/`extend` instances, open connections) stop satisfying it until they are recreated. After touching `protocols.clj`, reload the namespaces that implement it and rebuild any live connection values.

**A dependency added to `deps.edn` after the REPL started is off its classpath.** Extend it without a restart:

```clojure
(clojure.repl.deps/add-libs '{org.clojure/test.check {:mvn/version "..."}})
```

## The editing hook

Hooks in `.claude/settings.json` run `clj-paren-repair-claude-hook --cljfmt` before **and** after every Write/Edit of a `.clj*` or `.edn` file:

- **Balanced file** — the hook only runs cljfmt (whitespace/indent normalization).
- **Unbalanced file** — the hook re-infers ALL parens from indentation (parinfer-style) across the file. It can silently revert the intended fix *and* corrupt unrelated forms whose indentation is nonstandard.

So:

- **Re-read the file before the next edit.** cljfmt may have shifted lines; stale line numbers cause bad edits.
- **Keep each edit balanced on its own.** When moving parens, do it as **one** Edit whose `old_string` and `new_string` are each fully balanced, adjusting indentation in the same edit. Two sequential edits that balance only in combination leave the file unbalanced in between — the hook fires after each one.
- **Verify with tools, not by eye.** After a hook-modified result, run `clj-kondo` plus a `rewrite-clj` structural check rather than hand-counting parens.

## Repairing parens

Let the tool do it — it auto-formats with cljfmt:

```bash
clj-paren-repair <files>
```
