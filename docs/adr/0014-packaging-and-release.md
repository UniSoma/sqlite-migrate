# Packaging: io.github.unisoma/sqlite-migrate, sqlite-migrate.* namespaces, manual bb release

**One artifact**: `io.github.unisoma/sqlite-migrate`. The group is the org's
already-verified Clojars group; the artifact id matches the name ADR 0012
baked into the error key (`:sqlite-migrate/error`) — one name everywhere.
The JDBC adapter's dependencies (`next.jdbc`, `sqlite-jdbc`) are **real POM
dependencies**: every launch consumer is JVM-against-a-live-file, so the
default experience is add-one-coordinate-and-go. The ADR 0013 seam stays
enforceable as namespace discipline (the adapter requires only the protocol
namespace), and extracting a separate `sqlite-migrate-jdbc` artifact later
is additive — no reason to run two release trains before a second adapter
exists.

**Namespace root is `sqlite-migrate.*`** — unprefixed, matching the artifact
id and the locked error-key root. The four ADR 0013 namespaces are:

| ADR 0013 role | Namespace |
|---|---|
| core | `sqlite-migrate.core` |
| protocol | `sqlite-migrate.protocols` |
| JDBC adapter | `sqlite-migrate.jdbc` |
| schema | `sqlite-migrate.schema` |

`protocols` is plural per Clojure convention (`next.jdbc.protocols`), and
doesn't over-commit the namespace name to `SQLiteExecutor` being its only
occupant forever.

**Versioning**: SemVer-shaped accretion. Development publishes run as
`0.1.0-SNAPSHOT` (Clojars fixed releases are immutable, so SNAPSHOTs are the
test channel); first fixed release `0.1.0`; iterate on 0.x while the
motivating first consumer shakes the spec out; `1.0.0` when the promises
have held under that real use. From then on the add-only open sets (error
classes, refusal codes, gates, directive kinds) mean minor/patch only —
a breaking change would be a new artifact name, not a major bump.

**Release story**: fully manual, mirroring the org's existing
mantine-ui-wrapper workflow — no CI-triggered deploys. The version string is
the single source of truth in `build.clj`; `bb` tasks: `jar`, `install`
(verify from a consumer via `~/.m2` before publishing), `deploy`
(deps-deploy to Clojars, `CLOJARS_USERNAME`/`CLOJARS_PASSWORD` deploy token
in env), `cljdoc` (trigger the doc build for the deployed version). SCM
metadata: commit SHA for SNAPSHOTs, immutable `v<version>` git tag for fixed
releases. Unlike the wrapper's source-only jar, this POM carries real
dependencies — none of its `<dependencies/>` surgery.

**Docs posture**: README (pitch, quickstart, CI drift-check recipe) + cljdoc
as the canonical API reference — ADR 0013's normative protocol docstrings
render straight into it — plus curated cljdoc articles reusing what this map
already produced (the design spec, the stage-then-swap and CI-drift
recipes).

**Graal-native target**: documentation + CI proof only — a native-image
smoke job in the version matrix and a doc page stating native-image safety
(sqlite-jdbc is native-image-tested upstream since 3.40.1.0). The library
ships no CLI, so there is no binary to publish; "bonus target" means
consumers can compile it into their own images.

**License**: MIT, matching the org's other libraries.

## Considered Options

- **Two artifacts (pure core + `-jdbc` adapter)** — rejected at launch: the
  seam made physical costs a lockstep release train and helps only the
  not-yet-existing second adapter; splitting later is additive.
- **Optional/provided JDBC deps in a single artifact** — rejected: makes the
  99% consumer declare two extra deps to dodge ~10 MB the 1% doesn't want
  yet.
- **Org-prefixed namespace root (`unisoma.sqlite-migrate.*`)** — rejected:
  collision risk for a name this distinctive is theoretical, ADR 0012
  already locked the unprefixed error-key root, and short requires matter.
- **`sqlite-migrate.protocol` / `.executor`** — rejected for the
  conventional plural.
- **`1.0.0-SNAPSHOT` → `1.0.0` directly** — rejected: 1.0 should mean "the
  promises held under a real consumer", and 0.x is the window for
  discovering the spec was wrong somewhere without breaking it.
- **Tag-triggered GitHub Actions deploy** — rejected by preference: the dev
  deploys manually via the proven bb-task workflow.

## Consequences

- The build effort creates `build.clj` + `bb.edn` modeled on
  mantine-ui-wrapper's, minus the source-only POM surgery, plus real deps.
- The CI version matrix (ADR 0010) gains a native-image smoke job.
- Error keys, requires, and coordinates all agree on the literal string
  `sqlite-migrate`.
