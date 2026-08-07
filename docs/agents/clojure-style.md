# Clojure Style

House rules for writing Clojure in this repo. `.cljfmt.edn` and the PostToolUse hook own layout — whitespace, indentation, and ns-reference sorting are settled without you. What follows is everything the tooling can't decide.

## Errors ride on ex-data

Every deliberate throw is an `ex-info` carrying a `:sqlite-migrate/error` key whose value names the failure class (`:sqlite-error`, `:malformed-input`, `:drift-refused`). Callers dispatch on that key, so a throw without it is unreachable by the callers that care.

When a lower-level exception caused the failure, pass it as the third `ex-info` argument so it **rides as the cause** — the driver exception stays recoverable via `ex-cause`.

Attach the facts a caller needs to attribute the failure, named for what they are: `:statement-index`, `:op-index`, `:op`, `:violations`. Data rides verbatim — the failing Op goes into ex-data as-is rather than summarized.

```clojure
(throw (ex-info (str "statement " i " of the batch failed")
                {:sqlite-migrate/error :sqlite-error
                 :statement-index i}
                e))
```

## Docstrings are normative

A docstring on a protocol method or a public fn **is** the spec — an adapter author implements against it with nothing else to read. Write the contract: what the caller supplies, what comes back, what must hold, what happens on failure. `sqlite-migrate.protocols` is the reference example.

Namespace docstrings state the namespace's job and its place in the layering (`sqlite-migrate.jdbc`: "Depends on `sqlite-migrate.protocols` only — never on the core").

Success is silence: an effectful fn returns nil and throws on failure. Say so in the docstring.

## Naming

- `!` suffix marks an effectful fn — `apply!`, `execute-batch!`, `raw-exec!`. Pure fns carry no suffix.
- `?` suffix marks a predicate — `drift?`, `foreign-keys-on?`.
- `defn-` for helpers; the public surface stays small and deliberate.
- Domain terms come from `CONTEXT.md` — Snapshot, Declaration, Diff, Plan, Op, Frame, drift. Use the glossary's word, in its capitalized form, in docstrings and test names.

## Interop

Any namespace touching Java sets `(set! *warn-on-reflection* true)` immediately after the `ns` form, and type-hints every interop call site until it runs clean. Reach for `clojure.string` and `clojure.java.io` before Java methods.

## Shape of the code

- `if` for one condition, `cond` once there are several branches, `case` for constant dispatch.
- `let` earns its place when a value is used more than once or a name clarifies a long expression; otherwise inline it or thread it.
- Destructure in the parameter vector when a fn reads several keys off a map.
- Return the value the caller needs rather than a boolean flag; `nil` means not-found.
- Threading chains run 3–7 steps. Longer means a fn wants extracting.
- `some->` in place of nested nil checks; `reduce` in place of an atom accumulating in a loop.

## Comments

Comments carry the reason, not the mechanism — an ADR reference (`;; ADR 0012: a mid-Apply failure carries the failing Op verbatim`), a non-obvious ordering constraint, a SQLite quirk. The code says what it does; the comment says why it had to.

## Tests

Real in-memory SQLite through `sqlite-migrate.jdbc/in-memory`, inside `with-open`. No mocks — the behavior under test is SQLite's.

- `deftest` names read as the claim being made: `apply-refuses-on-fingerprint-mismatch`.
- `testing` strings are sentences a reader can check against the assertions beneath them.
- Give `is` a message third argument when the assertion's intent isn't obvious from the form: `(is (some? ex) "apply! must throw on fingerprint mismatch")`.
- Cover the happy path and the failure the docstring promises — assert the `:sqlite-migrate/error` key, not just that something threw.
- `bb test` runs the suite.

## Working in the REPL

Reload with `:reload` after editing a namespace, then evaluate in that namespace. Confirm a fn exists and takes the arity you think it does — `(doc f)`, `(source f)` — before building on it.
