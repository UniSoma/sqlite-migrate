---
id: sqm-01kzctnhwmjm
title: Build sqlite-migrate v0.1.0 per the design spec
status: closed
type: epic
priority: 2
mode: afk
created: '2026-08-07T00:41:15.924087020Z'
updated: '2026-08-10T20:57:41.459606446Z'
closed: '2026-08-10T20:57:41.459606446Z'
---

## Description

## Problem Statement

Clojure teams shipping SQLite evolve their schemas by hand: numbered migration scripts, ad-hoc `ALTER TABLE` incantations, and tribal knowledge of SQLite's 12-step rebuild procedure. The developer knows what the schema *should* be, but no tool will tell them how the live file differs from that intent, whether the difference is safe to converge, or exactly what SQL would run. Existing migration tools either target other databases, hand-roll fragile SQL parsers, guess at renames heuristically, or silently destroy data on drift resets. There is no library that treats the schema diff itself as reviewable, storable data.

## Solution

`sqlite-migrate`: a data-driven Clojure library that introspects a live SQLite file into a Snapshot, diffs it against a declared target schema (SQL text, executed into a pristine in-memory database and introspected the same way), and produces an executable migration Plan — with the Diff as a first-class public surface.

The whole pipeline is pure values between two thin effectful edges: `snapshot`/`declared-snapshot` read schemas in; `check`/`apply!` run a Plan's Gates and Ops against a connection. Everything in between — `diff`, `plan`, the reports — is a pure function over plain EDN. The user reviews the Plan ("exactly these statements will run"), supplies explicit Directives for renames and drops, and applies atomically: one transaction, all-or-nothing, refusing drifted databases and unauthorized destruction by default.

The design is fully decided and recorded: ADRs 0001–0014 in `docs/adr/` are the normative decisions, and `CONTEXT.md` is the glossary (Snapshot, Diff, Plan, Op, Rebuild, Gate, Directive, Refusal, Executor, Frame, …). This spec synthesizes them; where detail is needed, the ADRs govern.

## User Stories

1. As an app developer, I want to declare my target schema as plain SQL text, so that I don't have to learn a new DSL to describe what I already know how to write.
2. As an app developer, I want an optional EDN Schema value that compiles to SQL, so that I can build and manipulate schemas as data when that is more convenient.
3. As an app developer, I want raw-SQL escape hatches everywhere in the EDN sugar, so that its deliberately-subset coverage never blocks me.
4. As an app developer, I want the library to error loudly when my Declaration does something introspection can't capture (DML, ATTACH, PRAGMA side effects, temp objects), so that declarations stay pure state and nothing is silently skipped.
5. As an app developer, I want to introspect a live SQLite file into a plain-EDN Snapshot, so that I can inspect the actual schema programmatically.
6. As an app developer, I want the diff between live and declared schemas as flat, self-contained plain-EDN entries, so that I can filter, store, and dispatch on it with ordinary seq functions.
7. As an app developer, I want a single `drift?` predicate, so that "are we converged?" is one call.
8. As an ops engineer, I want a human-readable drift report, so that I can see what changed at a glance without reading EDN.
9. As a CI pipeline, I want byte-identical serialized Diffs and Plans for identical inputs, so that artifacts can be archived, golden-file-tested, and compared across runs.
10. As a CI pipeline, I want a documented drift-check recipe (introspect, diff, fail on `drift?`, archive the printed Diff), so that schema drift fails the build without the library bundling a CI framework.
11. As an app developer, I want cosmetic differences (identifier case and quoting, expression whitespace/comments/keyword case, sibling order) erased by the one fixed equivalence relation, so that reformatting never reads as drift.
12. As an app developer, I want real differences (physical column order, declared type text, constraint names, any beyond-token expression change) reported honestly, so that the tool never silently ignores a change it can't erase.
13. As an app developer, I want a Plan listing the exact SQL statements that will run, compiled at plan time, so that I can review before anything touches my database.
14. As an operator, I want a plan report rendering Ops in execution order with full SQL, Gates, unhandled entries with their Refusals, and unused Directives, so that pre-apply review is one readable artifact.
15. As an app developer, I want every destructive drop refused until I supply an explicit Directive, so that a drift reset can never silently destroy data.
16. As an app developer, I want renames declared as explicit Directives (never inferred), so that a rename is a rename and its data survives, instead of a drop-plus-add guessed by a heuristic.
17. As a fleet operator, I want Directives that are conditional and inert when unmatched (reported as unused, never an error), so that one checked-in directive set serves many databases converging at different times.
18. As an app developer, I want a typo'd Directive to surface twice — in the Plan's unused list and as the un-lifted refusal that blocks Apply — so that intent mistakes are loud, not silent.
19. As an operator, I want data preconditions (Gates) checked read-only via a public Check surface, so that I know before a maintenance window whether rows violate the new NOT NULL, UNIQUE, CHECK, FK, or STRICT shape.
20. As an operator, I want failing Gates to report violation counts and sample rows, so that I know exactly what data to fix.
21. As an app developer, I want Apply to be strictly atomic — one transaction, all-or-nothing, no partial modes — so that a failure leaves the database exactly as it was.
22. As an operator, I want Apply to refuse a database whose `schema_version` fingerprint no longer matches the Plan's source Snapshot, with no override, so that stale SQL never runs against unknown state.
23. As an app developer, I want rebuilds to preserve surviving columns' values, rowids (rowid tables both sides), and AUTOINCREMENT counters, so that a schema change never corrupts data identity.
24. As an app developer, I want index, trigger, and view changes to plan freely without Directives, so that value-free objects converge without ceremony.
25. As an app developer, I want changed virtual tables refused honestly as `:incapable`, so that module-owned shadow tables are never mangled.
26. As an app developer, I want to plan against explicit Capabilities (target SQLite version, `:rebuild?`), so that a Plan is honest about the engine it will run on.
27. As an app developer, I want zero-config planning to default to the live Snapshot's own SQLite version, so that the default Plan targets the engine that actually read the file.
28. As an operator, I want partial convergence as an explicit opt-in (`:allow-unhandled?`), so that I can apply what is achievable while unhandled entries stay precisely reported.
29. As an app developer, I want every library exception to carry one namespaced envelope (`:sqlite-migrate/error`) with structured, reused payload values, so that error handling is a single dispatch regardless of which function threw.
30. As a tooling author, I want all machine vocabularies (error classes, refusal codes, gate codes, directive kinds) to be open add-only sets — never removed or renamed — so that my integration survives library upgrades.
31. As an adapter author, I want the effectful edge to be a two-op protocol with normative docstrings, so that bringing a new runtime (babashka later) means implementing two functions plus constructors.
32. As a JVM consumer, I want one artifact with real JDBC dependencies, so that getting started is add-one-coordinate-and-go.
33. As a GraalVM user, I want native-image compatibility proven by a CI smoke job and documented, so that I can compile the library into my own binary.
34. As an operator, I want documented recipes for stage-then-swap and converge-on-startup, so that common workflows are covered without the library shipping risky modes or one-shot compositions.

## Implementation Decisions

**Namespaces and artifact (ADRs 0013, 0014).** One artifact, `io.github.unisoma/sqlite-migrate`, MIT-licensed, with `next.jdbc` and `sqlite-jdbc` as real POM dependencies. Four public namespaces: `sqlite-migrate.core` (the whole pipeline), `sqlite-migrate.protocols` (the effectful contract), `sqlite-migrate.jdbc` (the JDBC Adapter), `sqlite-migrate.schema` (EDN sugar, exports only `->sql`). Core never requires any driver; the adapter requires only the protocols namespace — this seam is the runtime-agnostic bet and is enforced as namespace discipline.

**The effectful edge (ADR 0013).** One protocol, `SQLiteExecutor`, exactly two ops: `execute-query [conn sql params]` (read-only, returns a vector of keyword-keyed row maps) and `execute-batch! [conn statements]` (returns nil; owns the Frame). The Frame is unconditional and always the same shape: `PRAGMA foreign_keys=OFF` outside the transaction → `BEGIN` → statements in order → `PRAGMA foreign_key_check` (any row ⇒ rollback and throw) → `COMMIT` → restore enforcement in a `finally`. Database creation is outside the protocol: the JDBC adapter ships `connect` (file path, or existing `Connection`/`DataSource`) and `in-memory`, returning Closeable, protocol-satisfying conns whose lifecycle belongs to the caller. Protocol docstrings are the normative adapter-author spec.

**Core function inventory (ADR 0013) — complete; nothing else is public:**

| Fn | Args | Returns |
|---|---|---|
| `snapshot` | `[conn]` | Snapshot of the live `main` schema |
| `declared-snapshot` | `[conn declaration]` | Snapshot (throws `:malformed-input` on a non-empty database) |
| `diff` | `[live declared]` | Diff |
| `drift?` | `[diff]` | boolean |
| `by-object` | `[diff]` | nested per-object view |
| `plan` | `[diff]` `[diff opts]` | Plan |
| `check` | `[conn plan]` | Check result |
| `apply!` | `[conn plan]` `[conn plan opts]` | Apply report |
| `drift-report` | `[diff]` | string |
| `plan-report` | `[plan]` | string |
| `check-report` | `[check-result]` | string |

Edge fns are conn-first, value args after, opts map last. `plan` opts are `{:capabilities … :directives […]}`; omitted capabilities default to the live side's Snapshot-metadata SQLite version plus `:rebuild? true`. `apply!` opts are exactly `{:allow-unhandled? false, :check-gates? true}` (defaults shown). No `migrate!` one-shot, no `equivalent?` (equivalence *is* the empty Diff).

**Snapshot and introspection (ADRs 0001, 0005).** One canonical Snapshot shape produced only by introspection — live file, or the Declaration executed into a pristine in-memory database. Pragmas provide structure; a narrow extractor lifts only pragma-invisible facts (CHECK bodies, generated/index/partial expressions, DEFAULT spellings, constraint names, per-column COLLATE, AUTOINCREMENT, FK deferrability) from stored CREATE text as verbatim opaque expression text. Never a SQL parser. Scope: `main` schema tables, indexes, views, triggers, plus opaque virtual tables. Plain EDN, string identifiers, columns ordered, indexes/triggers nested under tables. Snapshot metadata (SQLite version, `schema_version` fingerprint, per-object stored CREATE sql) is equality-neutral provenance.

**Declaration (ADR 0002).** SQL text (string or seq of statements) is canonical, split by SQLite's own prepare loop, validated by SQLite itself with which-statement error context. Pure state — zero migration intent. Effects invisible to introspection error loudly. The EDN Schema value is sugar compiling to SQL: identifiers compiled to quoted verbatim spelling (no munging), keyword types for the STRICT-legal set, any string passed through verbatim, `:raw` vectors and `[:raw "…"]` expression positions as escape hatches; views/triggers raw-only initially.

**Equivalence (ADR 0003).** One fixed knobless relation over Snapshots, normalizing at comparison time only. Identifiers case-folded and dequoted; declared type text compared case/whitespace-insensitively, never by affinity; opaque expressions compared as token sequences via a lexical SQLite tokenizer (no grammar — keywords and bare/quoted identifiers fold, string/blob literals byte-exact). Semantic: physical column order, constraint names, PK/index column order, STRICT/WITHOUT ROWID flags. Noise: sibling order among indexes/triggers/views (match by folded name), engine-internal objects (`sqlite_sequence`, `sqlite_autoindex_*`, `sqlite_stat*`, shadow tables). Beyond-token differences are honest drift.

**Diff (ADRs 0004, 0005).** A thin wrapper map: flat vector of entries plus both sides' Snapshot metadata. Entry = target-relative kind (`added`/`removed`/`changed`), object path, both sides' verbatim sub-values (including stored CREATE sql), and for `changed` the set of differing fact keywords — one keyword per equivalence-compared fact, so nothing differs without a name. One entry per one-sided object; fine-grained entries (columns, constraints, indexes, triggers) only inside a `changed` table, plus at most one table-level entry for table-scoped facts. Unnamed constraints pair by token-equality. No rename kind, no cost labels, no dependency indices. Locked deterministic entry order; `pr-str`/`read-string` round-trip is a promised contract. Public Diff surfaces are exactly `drift?`, `drift-report` (per-fact both-sides lines for changed objects; whole verbatim CREATE for one-sided ones), and `by-object`. Filtering is a documented seq-function pattern, not API.

**Plan and Ops (ADR 0006).** `plan` is pure: Diff entries are the work items, both embedded Snapshots the context. The wrapper holds the ordered `:ops` vector, both Snapshot metadata blocks, the `:capabilities` planned under, the echoed `:directives` and `:unused-directives`, and the unhandled entries. An Op = logical `:kind`, object path, `:serves` set of Diff entry paths, `:gates` vector, and `:sql` — exact statements compiled at plan time. Per table: all changes in-place, or one composite `:rebuild-table` op — never mixed. Rebuild statement order is create-under-temp-name → INSERT…SELECT → drop-old → rename-new (never rename-first). Ordering is list position with locked phases: drop removed indexes/triggers/views → drop removed tables → per-table change ops (name-sorted) → create added tables (name-sorted) → create added indexes/triggers/views. Frame is executor-owned, never Ops. Completeness invariant: every entry served by ≥1 op or unhandled-with-refusals.

**Refusals and Capabilities (ADR 0007).** An unhandled entry carries all applicable Refusals: `{class, code, explanation}`. Two classes: `:incapable` (no route under these Capabilities) and `:needs-intent` (route exists; a Directive lifts it). Launch codes: `:virtual-table-changed`, `:rebuild-disabled`, `:unsupported-by-target-version` (all `:incapable`), `:destructive-drop` (`:needs-intent` — removed tables/columns/virtual tables only; index/trigger/view drops plan freely). Plan never throws for refusals. Capabilities are a flat map: target version + `:rebuild?` (default true). No named tiers.

**Gates and rebuild copy (ADR 0008).** A Gate rides on an Op: `{code, path, explanation, :sql}` — a plan-compiled sampling SELECT with a baked LIMIT (small constant, e.g. 10): zero rows pass; N rows report "N or more". Launch inventory: NOT NULL added/tightened, UNIQUE/unique-index created, PK changed, CHECK added/changed, FK added/retargeted, STRICT conversion, WITHOUT ROWID conversion, NOT-NULL-no-default column added (table must be empty). Data conformance is never a Refusal. `check` runs Gates read-only; `apply!` runs them by default up-front inside the open transaction (TOCTOU-free). Rebuild copies strictly by name (post-directive-resolution), plus explicit `rowid` when both sides are rowid tables and `sqlite_sequence` restoration when AUTOINCREMENT on both sides. New columns take declared defaults; authorized-dropped columns are not copied.

**Directives (ADR 0009).** A Directive is a plain-EDN map with a `:directive` kind, consumed by `plan`. Launch kinds — exactly the `:destructive-drop` resolutions:

```clojure
{:directive :rename-table  :from "users" :to "people"}
{:directive :rename-column :table "users" :from "name" :to "full_name"}
{:directive :drop-table    :table "old_stuff"}
{:directive :drop-column   :table "users" :column "legacy"}
```

Binding is by name, per object, no wildcards, no allow-all-drops; live-side names for `:table`/`:from`, declared-side for `:to`; identifiers normalize as the equivalence relation does. Unmatched directives are inert-but-reported in `:unused-directives`; rename matching is all-or-nothing; conflicting directives throw `:malformed-input` before planning. A fused rename pair is a `changed` object feeding the normal in-place-vs-rebuild decision; colliding rename sets (swaps, chains) force rebuild.

**Execution policies (ADR 0011).** `apply!` is strictly atomic in place — no per-op transactions, no continue-on-error, no checkpoints, no stage-then-swap mode, no run-time destructive guard, no drift `:force` override. Success returns the Apply report (both Snapshot metadata blocks, Check result when gates were checked, ops executed, post-apply `schema_version`); every non-success throws.

**Errors and reports (ADR 0012).** Every throw is `ex-info` with the discriminator key `:sqlite-migrate/error` holding a class keyword. Launch classes: `:malformed-input`, `:drift-refused` (both fingerprints + both metadata blocks), `:unhandled-refused` (unhandled entries verbatim), `:gate-failed` (full Check result), `:sqlite-error` (driver cause as `ex-cause`; mid-Apply carries the failing Op, plan index, and failing statement). Payloads reuse existing values verbatim; `ex-message` is one line. Renderers: `drift-report`, `plan-report` (full SQL always), `check-report` — deterministic, single-arity, no knobs. Codes and classes are the stable machine surface; every string is presentation-only. No message catalog, no severity field.

**Versioning and release (ADR 0014).** SemVer accretion: `0.1.0-SNAPSHOT` test channel → `0.1.0` → 0.x while the first consumer shakes the spec out → `1.0.0`; the add-only open sets mean minor/patch after that. Fully manual bb-task release mirroring the org's mantine-ui-wrapper workflow: version string sourced from `build.clj`; tasks `jar`, `install`, `deploy` (deps-deploy, Clojars token in env), `cljdoc`; commit-SHA SCM for SNAPSHOTs, immutable `v<version>` tags for fixed releases. Docs: README (pitch, quickstart, CI drift recipe), cljdoc as API reference plus curated articles (design spec, stage-then-swap and CI-drift recipes).

## Testing Decisions

**The seam is the `SQLiteExecutor` protocol — the design's single effectful seam, and the tests use the highest one available: the public API itself.** Property tests exercise `sqlite-migrate.core` end to end through the real JDBC adapter against real in-memory SQLite databases — never a mocked engine, never internal functions. Good tests here assert external behavior only: properties over the public values (Snapshot, Diff, Plan, reports) and over real database state after `apply!`. The pure pipeline (diff, plan, renderers) is additionally testable value-in/value-out with no connection at all — that purity is the design's testability story, not a second seam.

Six locked correctness properties (ADR 0010):

1. **No-op**: `diff(a, b)` empty iff equivalent.
2. **Round-trip**: introspect → emit SQL → pristine → introspect ⇒ equivalent to the original.
3. **Residual convergence**: after `apply!`, `diff(live, target)` equals exactly the Plan's unhandled entries — with full equivalence on empty-unhandled Plans and the re-plan fixpoint (zero ops, same unhandled) as corollaries.
4. **Data preservation**: surviving columns' rows survive as multisets; rowid stability when both sides are rowid tables; AUTOINCREMENT counter continuity.
5. **Gate bidirectionality**: Check pass ⇒ no data-dependent Apply failure; Gate fail ⇒ Apply would abort.
6. **Plan determinism**: same (Diff, Capabilities, Directives) ⇒ byte-identical (`pr-str`-equal) Plan, gate SQL included. (Check-result sample order is outside the property.) Diff entry order determinism is likewise contractual.
7. **Version honesty**: a Plan for target version V runs successfully on V — tested by running it there.

Four generators drive them: a schema generator emitting shrinkable EDN Schema values that reach beyond the sugar's subset via raw escape hatches; a mutation generator perturbing schemas into nearby targets with matching rename Directives; a row generator producing conforming and violating rows; and a curated corpus of nasty schemas (quoted/keyword identifiers, generated columns, partial indexes, virtual tables) as deterministic regression seeds. `org.clojure/test.check` is the reference tooling. CI runs a two-point version matrix (a pragmatically-chosen floor sqlite-jdbc and the latest) plus a GraalVM native-image smoke job. This is a greenfield repo — there is no prior test art to follow; these properties are the founding style.

## Out of Scope

- Databases other than SQLite.
- CLI or GUI tooling on top of the library (also why no Graal binary is published — native-image support is docs + CI proof for consumers' own binaries).
- Compatibility mode for classic versioned migrations.
- `writable_schema` as a migration mechanism (ADR 0007).
- Row transformation beyond by-name column mapping — no USING-style expressions, no transform directives; "fix your data first" with Gates naming what to fix (ADR 0008).
- Stage-then-swap as an Apply mode — documented consumer recipe only (ADR 0011).
- Message localization/i18n (ADR 0012).
- A `migrate!` one-shot and an `equivalent?` predicate (ADR 0013).
- A babashka adapter (deferred pending pod maturity; the protocol seam is shaped for it).
- Splitting a separate `-jdbc` artifact (additive later option, ADR 0014).
- Non-atomic/per-op/continue-on-error Apply variants (ADR 0011).

## Further Notes

- ADRs 0001–0014 and the `CONTEXT.md` glossary are in-repo and normative; use the glossary vocabulary in code, docstrings, and docs. The protocol namespace docstrings double as the adapter-author spec and render into cljdoc.
- Assume the latest SQLite feature set (including 3.53's ALTER COLUMN SET/DROP NOT NULL and ADD/DROP CHECK) behind the Capabilities version gate; the bundled sqlite-jdbc makes the introspection surface (`table_list`, `table_xinfo`) unconditionally available.
- The concrete CI floor version is a build-time choice (oldest sqlite-jdbc conveniently pinnable), not a spec commitment.
- Deliverables beyond code: README with quickstart and CI drift recipe, stage-then-swap and converge-on-startup recipes, cljdoc articles, `build.clj` + `bb.edn` release tasks modeled on mantine-ui-wrapper (with real POM deps, no source-only surgery).
- Research background lives on `research/*` branches (`docs/research/` files): SQLite DDL capabilities, babashka/Graal access, migration prior art.

## Notes

**2026-08-10T20:57:41.459606446Z**

sqlite-migrate v0.1.0 is built: all 28 children closed. The pipeline ships as designed — snapshot/declared-snapshot introspect into one canonical Snapshot shape, diff produces the flat plain-EDN Diff with drift?/drift-report/by-object over it, plan compiles Ops with Gates, Refusals, Capabilities and Directives, and check/apply! run them through the SQLiteExecutor seam with the unconditional Frame. ADRs 0001-0017 govern; the six locked properties plus the schema/mutation/row generators and the nasty-schema corpus back it. Later work (release cut, remaining docs polish) opens as its own tickets.
