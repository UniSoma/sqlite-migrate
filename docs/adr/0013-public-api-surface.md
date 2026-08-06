# Public API surface: four namespaces, a two-op executor protocol, conn-symmetric edges

The public surface is **four namespaces** (concrete names deferred to
packaging): a **core** namespace holding the entire pipeline, a **protocol**
namespace holding the effectful-edge contract, a **JDBC adapter** namespace,
and a **schema** namespace for the EDN sugar. The seam between core and
adapter is load-bearing for the runtime-agnostic bet (Graal now, babashka
later): a future adapter slots in by requiring the protocol namespace only,
and core never depends on any driver.

**The effectful edge is one protocol, `SQLiteExecutor`, with exactly two
ops** — the shape the babashka research asked for as the lowest common
denominator across runtimes:

- `execute-query [conn sql params]` — one read-only statement; returns a
  vector of keyword-keyed row maps. Introspection and Check ride on this op
  alone. (Identifiers *inside* Snapshot values remain strings per ADR 0001 —
  keyword keys apply to row maps only.)
- `execute-batch! [conn statements]` — runs the ordered SQL statements
  inside the executor-owned atomic frame; returns nil (success is silence,
  failure throws). The frame is always the same shape, unconditionally:
  `PRAGMA foreign_keys=OFF` (outside the transaction) → `BEGIN` →
  statements in order → `PRAGMA foreign_key_check` (any row ⇒ rollback and
  throw) → `COMMIT` → restore `foreign_keys` in a `finally`. The FK check
  runs even for plans with no Rebuild — a uniform frame is a simpler
  adapter-author contract, and it catches non-rebuild FK-affecting ops too.
  The protocol docstrings are the adapter-author spec.

**Database creation is deliberately not part of the protocol.** Each adapter
exposes whatever constructors are natural for its runtime; the JDBC adapter
ships `connect` (file path, or an existing `java.sql.Connection`/`DataSource`
for interop) and `in-memory`, both returning `SQLiteExecutor`-satisfying,
`Closeable` conns whose lifecycle belongs to the caller. This keeps every
core edge function **conn-symmetric**: `snapshot`, `declared-snapshot`,
`check`, and `apply!` all take a conn first (value args after, opts map
last); none takes an "adapter" or factory. `declared-snapshot` realizes the
Pristine database on whatever conn it is given — and guards it: if the
database already contains objects it throws `:malformed-input` rather than
produce a silently polluted declared Snapshot.

**Core inventory** (complete — nothing else is public):

| Fn | Args | Returns |
|---|---|---|
| `snapshot` | `[conn]` | Snapshot of the live `main` schema |
| `declared-snapshot` | `[conn declaration]` | Snapshot (empty-database guard) |
| `diff` | `[live declared]` | Diff |
| `drift?` | `[diff]` | boolean |
| `by-object` | `[diff]` | nested per-object view |
| `plan` | `[diff]` `[diff opts]` | Plan |
| `check` | `[conn plan]` | Check result |
| `apply!` | `[conn plan]` `[conn plan opts]` | Apply report |
| `drift-report` | `[diff]` | string |
| `plan-report` | `[plan]` | string |
| `check-report` | `[check-result]` | string |

`plan` opts are `{:capabilities ... :directives [...]}`, both optional.
**Omitted capabilities default to the live side's Snapshot-metadata SQLite
version** plus `:rebuild? true` — the zero-config path is version-honest by
construction (the Plan targets the engine that actually read the file),
where a baked-in "latest known" constant could silently exceed the deployed
engine. `apply!` opts are exactly `{:allow-unhandled? :check-gates?}`
(ADR 0011).

The schema namespace exports one function, `->sql` (Schema value → vector of
SQL statement strings, i.e. a Declaration) — core never sees Schema values
(ADR 0002).

## Considered Options

- **A composed `migrate!` one-shot** — rejected at launch: recipes over
  bundled compositions (consistent with ADR 0005's no-bundled-check stance);
  trivially addable later, breaking to remove. The "converge on startup"
  recipe is documented instead.
- **An `equivalent?` predicate** — rejected: `drift?` is the locked single
  predicate (ADR 0005); equivalence *is* the empty Diff.
- **`declared-snapshot` taking an adapter/factory** — rejected: it forked
  the edge into two abstractions (conn *and* adapter). Moving creation to
  adapter constructors restored conn symmetry and kept the protocol at two
  ops.
- **Finer protocol ops (begin/exec/commit driven by core)** — rejected: the
  frame is executor-owned per ADR 0006, and coarse ops are the honest floor
  for a babashka pod lacking fine-grained connection affinity.
- **`diff` accepting a Declaration directly** — rejected: hides an effect
  inside an ostensibly pure fn; plan determinism leans on value-in/value-out.
- **Naming Apply's fn `execute!`/`migrate!`** — rejected: the glossary term
  is Apply; the fn spells it `apply!`, bang for the effect and to dodge the
  `clojure.core/apply` collision.

## Consequences

- Adapter authors implement two ops plus constructors; the frame contract
  lives in — and is normative from — the protocol namespace docstrings.
- `plan` must read the live Snapshot metadata embedded in the Diff for its
  capabilities default.
- Concrete namespace names and artifact coordinates are settled by the
  packaging decision, not here.
