# Refusals are two-class data; capability is version plus one rebuild switch

An unhandled Diff entry in a Plan carries a **vector of Refusals** — every
refusal that applies, never just the first. Each Refusal is a plain-EDN map:
the refusal class, a code keyword, and a human-readable explanation. Classes
are a hard top-level split:

- **`:incapable`** — no route to the target shape exists under the given
  capabilities. No directive can lift it.
- **`:needs-intent`** — a route exists, but planning it without explicit
  intent would risk data. The directives layer consumes exactly this class:
  an entry plans once its refusal vector is empty.

Launch code set (open: codes may be added in minor versions, never removed or
renamed; consumers must tolerate unknown codes):

- `:virtual-table-changed` (`:incapable`) — a changed virtual table; content
  lives in module-owned shadow tables, no general alter or rebuild exists.
  Added virtual tables plan (verbatim declared CREATE); removed ones fall
  under `:destructive-drop`.
- `:rebuild-disabled` (`:incapable`) — the entry's only route is a rebuild
  and the `:rebuild?` capability is false. Absorbs version gaps: when the
  target version lacks an in-place form, the route is rebuild, so with
  rebuilds allowed an older target just rebuilds more — no refusal.
- `:unsupported-by-target-version` (`:incapable`) — the declared object
  itself cannot exist on the target version (e.g. STRICT before 3.37).
  Detected from Snapshot flags, never by parsing SQL.
- `:destructive-drop` (`:needs-intent`) — a removed table, column, or
  virtual table. The pure planner cannot see rows, so every drop is
  destructive-in-kind. A directive lifts it as either "drop" or "rename";
  rename is a resolution of this refusal, not a refusal kind. Removed
  indexes, triggers, and views carry no data and plan without refusal — the
  boundary is "does executing it lose values", not "is it a DROP".

**Plan never fails-fast and never throws for refusals**: every entry is
served or unhandled-with-refusals (ADR 0006's completeness invariant);
throwing is reserved for malformed input.

**Data-dependence is not a refusal.** Whether rows conform to a new shape
(NOT NULL, UNIQUE, STRICT coercion) is undecidable for a pure planner, and
refusing would refuse valid migrations on conforming data. Such ops plan and
carry their preconditions in a `:gates` slot on the Op — mechanism owned by
the data-dependent-gates ticket. STRICT changes are plannable, gated, never
refused.

**Capabilities stay a flat map**: the target SQLite version (environmental)
plus exactly one policy switch, `:rebuild?` (default true). No named tiers;
"tier" is prose, not data.

**writable_schema is out of scope entirely** — not a tier, not even a
refusal code. With Rebuild available, no shape needs it; it buys only
performance and risk.

**The half-applied-rename trap closes at Apply, not in the planner.** A
rename arrives as a removed/added pair; the removed side is refused but the
added side is independently plannable, and applying it would occupy the
rename's target name. Entries stay fully independent in the Plan; instead,
**Apply by default refuses a Plan whose unhandled collection is non-empty**
— partial convergence is an explicit opt-in (flag shape owned by the
execution-policies ticket, default fixed here).

## Considered Options

- **One refusal per entry with class precedence** — rejected: either order
  hides a blocker and forces a second planning round; same reasoning as
  collect-all over fail-fast, one level down.
- **A third `:data-dependent` refusal class** — rejected: refusal is a
  plan-time verdict and data conformance is undecidable at plan time; gates
  as op metadata keep the verdict honest.
- **writable_schema as an opt-in capability tier** — rejected: no
  reachability gain over Rebuild, high corruption risk, and a
  "would-need-writable_schema" code could never legitimately fire.
- **Planner-side quarantine of sibling adds when a drop is refused** —
  rejected: reintroduces rename-pairing heuristics through the back door and
  complicates the planner for one scenario the Apply default already closes.
- **Closed code enum** — rejected: adding a code would be a breaking change;
  the open set with never-remove/never-rename keeps stored Plans readable.

## Consequences

- The directives ticket inherits a crisp contract: directives lift
  `:needs-intent` refusals only; all-`:incapable` unhandled means "this
  library cannot get you there".
- The gates ticket owns the `:gates` slot: expression, checking, and
  reporting of data preconditions.
- The execution-policies ticket names the opt-in flag for applying plans
  with unhandled entries; the refusing default is fixed here.
- Column and table drops never auto-plan; drift resets can't silently
  destroy data — every destructive path passes through an explicit
  directive.
- Glossary gains Refusal, Refusal class, Capabilities, Gate.
