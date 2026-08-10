# Releasing

Maintainer-facing. The release is fully manual (ADR 0014) — there is no
CI-triggered deploy. `build.clj`'s `version` is the single source of truth for
the artifact version; every `bb` task reads it from there.

Between releases `version` carries a `-SNAPSHOT` suffix. SNAPSHOTs are the
mutable test channel on Clojars; fixed releases are immutable, are tagged
`v<version>`, and are the only versions cljdoc will build.

## What earns a changelog entry

A change belongs in `CHANGELOG.md` if either is true:

- It is observable through the four public namespaces of ADR 0013 —
  `sqlite-migrate.core`, `.protocols`, `.jdbc`, `.schema` — including new
  members of the add-only open sets (error classes, refusal codes, gates,
  directive kinds).
- It changes the statements `plan` emits for an input that already planned
  successfully.

The second clause is the load-bearing one: this library's contract is the plan
it produces, not its arity list. A planner that now rebuilds where it
previously altered in place is a user-visible change even though no signature
moved. Refactors confined to `sqlite-migrate.impl.*` are invisible.

Write entries under `## [Unreleased]` as the work lands, in the
[Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/) categories —
Added, Changed, Deprecated, Removed, Fixed, Security. Do not paste commit
subjects.

## Cutting a release

Let `X.Y.Z` be the version being released.

Local, before anything leaves the machine:

1. Gate the tree. `bb test` must be green and `clj-kondo --lint src test ci`
   clean. Redirect both to a file and check the exit code — piping to `tail`
   hides a mid-run failure.
2. `CHANGELOG.md`: retitle `## [Unreleased]` to `## [X.Y.Z] - <ISO date>`, open
   a fresh empty `## [Unreleased]` above it, and update the footnote links at
   the bottom — `[Unreleased]` compares `vX.Y.Z...HEAD`, and the new version
   links to its release tag.
3. `build.clj`: drop the `-SNAPSHOT` suffix from `version`.
4. `README.md`: update the install coordinate under **Installation** to
   `X.Y.Z`. This is deliberate duplication — README documents what a consumer
   should depend on, which is the last published release, not the version
   under development. It is the step most easily forgotten.
5. Verify as a consumer. `bb install` puts the jar in `~/.m2`; point a scratch
   project at `io.github.unisoma/sqlite-migrate {:mvn/version "X.Y.Z"}` and run
   the README quickstart against it.
6. Commit as `chore(release): X.Y.Z`. This commit must be exactly the tree that
   built the jar — no ticket bookkeeping, no unrelated fixes.
7. Tag it annotated: `git tag -a vX.Y.Z`. `build.clj`'s `scm` uses the tag as
   the release's SCM metadata, so it carries a message rather than being
   lightweight.
8. Commit `chore(release): Begin <next>-SNAPSHOT`, touching `build.clj` alone.
   Accretion means the next version is normally a minor bump.

Then the network steps, in this order:

9. `git push`.
10. Wait for CI to go green **on the tagged commit** — the version matrix plus
    the native-image smoke job.
11. `git push origin vX.Y.Z`.
12. `bb deploy`. Needs `CLOJARS_USERNAME` / `CLOJARS_PASSWORD` set to a Clojars
    deploy token. Clojars fixed releases are immutable: this is the point of no
    return.
13. `bb cljdoc` to trigger the doc build, then confirm the article tree and the
    `sqlite-migrate.protocols` docstrings render at
    <https://cljdoc.org/d/io.github.unisoma/sqlite-migrate>.
14. Create a GitHub Release on the tag, with that version's changelog section
    as the body.

## Version policy

SemVer-shaped accretion (ADR 0014). Iterate on 0.x while the first consumers
shake the spec out; `1.0.0` once the promises have held under real use. After
that the add-only open sets mean minor and patch releases only — a breaking
change would be a new artifact name, not a major bump.
