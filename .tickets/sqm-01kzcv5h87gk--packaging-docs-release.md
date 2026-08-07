---
id: sqm-01kzcv5h87gk
title: Packaging, docs, release
status: open
type: task
priority: 2
mode: afk
created: '2026-08-07T00:49:59.554568924Z'
updated: '2026-08-07T01:07:09.416073327Z'
parent: sqm-01kzctnhwmjm
acceptance:
- title: bb release tasks work end to end; version sourced from build.clj; MIT license in place
  done: false
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
