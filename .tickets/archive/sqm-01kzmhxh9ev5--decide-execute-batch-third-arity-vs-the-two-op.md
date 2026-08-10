---
id: sqm-01kzmhxh9ev5
title: 'Implement ADR 0016: gate-sqls arity replaces the pre-check! callback'
status: closed
type: task
priority: 2
mode: afk
created: '2026-08-10T00:42:15.726585917Z'
updated: '2026-08-10T11:48:02.759236597Z'
closed: '2026-08-10T11:48:02.759236597Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: 'A decision is recorded (ADR 0013 amendment or new ADR): either the pre-check! arity is granted or the protocol returns to exactly two ops'
  done: true
- title: Protocol docstrings (the normative adapter-author spec) match the decision
  done: true
- title: Code and tests conform to the decision; bb test passes
  done: true
---

## Description

**Decided (grilling session, 2026-08-10): Option C — gate SQL crosses the Executor seam as data, not a callback.** The decision is recorded in ADR 0016 (`docs/adr/0016-gate-sqls-cross-the-executor-seam-as-data.md`); pointer lines added to ADRs 0013 and 0008; CONTEXT.md's Frame entry already updated. This ticket now covers the implementation only.

Implement, per ADR 0016:

1. **Protocol** (`src/sqlite_migrate/protocols.clj`): `execute-batch!` arities become `[conn statements]` and `[conn statements gate-sqls]` — `gate-sqls` is a vector of plan-compiled read-only SELECT strings; the 2-arity is the 3-arity with no gate SQL. Rewrite the normative docstring's Frame enumeration: step 4 runs **all** of `gate-sqls` on the query path (keyword-keyed row maps, as `execute-query`); all empty ⇒ proceed; any rows ⇒ rollback and throw ex-data `{:sqlite-migrate/error :gates-violated, :gate-results [...]}` — index-aligned with `gate-sqls`, one vector of row maps per entry (empty = passed), no `:statement-index`. No callback, no `pre-check!`.
2. **JDBC adapter** (`src/sqlite_migrate/jdbc.clj`): `run-frame!` takes `gate-sqls` instead of a `pre-check!` fn and implements step 4 as above.
3. **Core** (`src/sqlite_migrate/core.clj`, `apply!`): drop the `pre-check!` closure and the 2-/3-arity branch — `apply!` always calls the 3-arity. Build `gate-sqls` as: index 0 = `SELECT * FROM pragma_schema_version WHERE schema_version <> <plan-fingerprint>` (**always present, including under `:check-gates? false`** — the opt-out skips table scans only; `:drift-refused` is override-free), then each Gate's `:sql` in op order when gates are checked. Catch `:gates-violated`: index-0 rows ⇒ throw `:drift-refused` with today's payload (plan fingerprint from the plan, live from the returned row), taking precedence over any gate rows; otherwise zip the remaining indexes back to Gate maps, rebuild the Check result, throw `:gate-failed` carrying it under `:check` as today. On success synthesize the passing Check result for the Apply report. Keep the early outside-frame `verify-fingerprint!` fast-fail; `check` unchanged.
4. **Tests**: add direct Frame-level coverage in `test/sqlite_migrate/jdbc_frame_test.clj` — violating gate rows ⇒ rollback with the `:gates-violated` payload shape (index-aligned, no `:statement-index`); all-pass ⇒ statements run; empty `gate-sqls` ≡ 2-arity. Existing `gates_test` / `error_envelope_test` parity suites must stay green (Check-result parity between `apply!`'s `:gate-failed` and manual `check` is load-bearing — it's why step 4 runs all gates, never fail-fast).
5. **Docs**: `doc/design.md` protocol section gains the `gate-sqls` arity (protocol stays "two-op"); ADRs 0011/0012 and CONTEXT.md's Apply-report entry keep the term "pre-check" — it names Apply's default behavior, only the callback mechanism died.

Verify with `bb test` (redirect output to a file, check exit code) and `clj-kondo --lint src test`.

## Notes

**2026-08-10T01:44:41.012345742Z**

Grilling session settled all branches: Option C (gate SQL as data), new ADR 0016 amending 0013+0008, run-all gate semantics with :gates-violated executor payload, fingerprint probe prepended at index 0 (always, even with :check-gates? false, index-0 failure => :drift-refused with precedence), two arities kept, 'pre-check' vocabulary survives. Decision recorded; ticket rewritten as an afk implementation spec.

**2026-08-10T11:48:02.759236597Z**

Shipped in 70e2736. execute-batch! arities are now [conn statements] / [conn statements gate-sqls]; Frame step 4 runs every gate SQL inside the open transaction and throws :gates-violated with index-aligned results. apply! always calls the 3-arity with the O(1) fingerprint probe at index 0 (present under :check-gates? false too, and outranking gate rows). One check-result assembler backs check, :gate-failed, and the Apply report. New Frame-level tests in jdbc_frame_test plus a drifting-executor test pinning the closed drift window. bb test 149/781 green, clj-kondo clean.
