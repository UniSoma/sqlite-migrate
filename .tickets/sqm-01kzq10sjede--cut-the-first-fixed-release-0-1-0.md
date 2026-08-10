---
id: sqm-01kzq10sjede
title: 'Cut the first fixed release: 0.1.0'
status: in_progress
type: task
priority: 1
mode: hitl
created: '2026-08-10T23:44:40.010407739Z'
updated: '2026-08-10T23:44:46.622710712Z'
acceptance:
- title: bb test green and clj-kondo --lint src test ci clean on the tagged tree
  done: false
- title: CHANGELOG.md follows Keep a Changelog 1.1.0 and renders as a cljdoc article
  done: false
- title: docs/releasing.md records the release procedure, including the README-coordinate step
  done: false
- title: 0.1.0 consumed from ~/.m2 by the scratch consumer after bb install
  done: false
- title: 0.1.0 deployed to Clojars and cljdoc builds the article tree and the protocols docstrings (closes AC 2 of sqm-01kzcv5h87gk)
  done: false
links:
- sqm-01kzcv5h87gk
---

## Description

Cut sqlite-migrate 0.1.0 — the first fixed (non-SNAPSHOT) release per ADR 0014, and adopt Keep a Changelog 1.1.0 as the changelog format.

0.1.0-SNAPSHOT is already on Clojars and verified consumed, but cljdoc does not build SNAPSHOTs, so the article tree and the normative sqlite-migrate.protocols docstring rendering have never been verified. That is AC 2 of sqm-01kzcv5h87gk, left blocked by design; this release is what unblocks it.

Local work (agent): CHANGELOG.md (Keep a Changelog 1.1.0, empty Unreleased + 0.1.0 entry, cljdoc tree entry), docs/releasing.md runbook, build.clj 0.1.0-SNAPSHOT -> 0.1.0, README install coordinate -> 0.1.0, commit 'Release 0.1.0', annotated tag v0.1.0, then commit 'Begin 0.2.0-SNAPSHOT'.

Network work (Jonas): git push; wait for CI green on the tagged commit; git push origin v0.1.0; bb deploy; bb cljdoc; GitHub Release with the 0.1.0 changelog section as the body.
