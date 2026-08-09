---
id: sqm-01kzcv5h87gk
title: Packaging, docs, release
status: in_progress
type: task
priority: 2
mode: afk
created: '2026-08-07T00:49:59.554568924Z'
updated: '2026-08-09T20:47:56.732943981Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: bb release tasks work end to end; version sourced from build.clj; MIT license in place
  done: true
- title: README, recipes, and cljdoc articles published; protocol docstrings render as adapter reference
  done: false
- title: 0.1.0-SNAPSHOT on Clojars, consumed successfully from a scratch project
  done: false
deps:
- sqm-01kzcv5h1c5p
- sqm-01kzcv5h4yna
- sqm-01kzcv5gf22s
tags:
- phase-5
---

## Description

Make it a published library. As a JVM consumer I add io.github.unisoma/sqlite-migrate to deps and go; as a reader I find the pitch, the quickstart, and the recipes.

Scope: build.clj as the version source of truth with real POM dependencies (next.jdbc, sqlite-jdbc) and SCM metadata (commit SHA for SNAPSHOTs, immutable v-tags for fixed releases); bb tasks jar / install (consumer verification via local repo) / deploy (deps-deploy to Clojars, token in env) / cljdoc trigger — mirroring the org's mantine-ui-wrapper workflow, minus its source-only POM surgery; MIT license. Docs: README (pitch, quickstart, CI drift-check recipe), stage-then-swap and converge-on-startup recipes, cljdoc articles reusing the design spec; the protocol docstrings render as the adapter-author reference; a native-image safety page. Ship 0.1.0-SNAPSHOT to Clojars as the test channel and verify consumption from a scratch project. ADR 0014.

## Notes

**2026-08-09T20:47:56.732943981Z**

Packaging landed in 1e7c584. build.clj is the version source of truth (0.1.0-SNAPSHOT); bb jar/install/deploy/cljdoc tasks in place; POM verified to carry real deps (next.jdbc, sqlite-jdbc), MIT license, and commit-SHA SCM tag; LICENSE (MIT, UniSoma) at root and in the jar's META-INF. Docs: doc/cljdoc.edn article tree (design, recipes with CI drift check / converge-on-startup / stage-then-swap, native-image page), README gained installation, quickstart, documentation, releasing, and license sections. Verified locally: bb test 144/765 green, clj-kondo clean, bb install into ~/.m2, and a scratch consumer project ran the full pipeline against the installed artifact ("consumer ok").

Remaining two ACs are blocked on things this environment doesn't have: no CLOJARS_USERNAME/CLOJARS_PASSWORD deploy token in env (bb deploy guards and refuses cleanly), and no GitHub remote exists yet (hard rule: local git only), which cljdoc needs to render the articles. Once a token is exported and the repo is pushed: bb deploy, verify the scratch consumer against Clojars (mv ~/.m2/repository/io/github/unisoma aside first), and bb cljdoc after the first fixed release (cljdoc skips SNAPSHOTs).
