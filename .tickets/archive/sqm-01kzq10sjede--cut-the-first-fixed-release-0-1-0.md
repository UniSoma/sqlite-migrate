---
id: sqm-01kzq10sjede
title: 'Cut the first fixed release: 0.1.0'
status: closed
type: task
priority: 1
mode: hitl
created: '2026-08-10T23:44:40.010407739Z'
updated: '2026-08-11T00:58:53.937214280Z'
closed: '2026-08-11T00:58:44.980809493Z'
acceptance:
- title: bb test green and clj-kondo --lint src test ci clean on the tagged tree
  done: true
- title: CHANGELOG.md follows Keep a Changelog 1.1.0 and renders as a cljdoc article
  done: true
- title: docs/releasing.md records the release procedure, including the README-coordinate step
  done: true
- title: 0.1.0 consumed from ~/.m2 by the scratch consumer after bb install
  done: true
- title: 0.1.0 deployed to Clojars and cljdoc builds the article tree and the protocols docstrings (closes AC 2 of sqm-01kzcv5h87gk)
  done: true
links:
- sqm-01kzcv5h87gk
---

## Description

Cut sqlite-migrate 0.1.0 — the first fixed (non-SNAPSHOT) release per ADR 0014, and adopt Keep a Changelog 1.1.0 as the changelog format.

0.1.0-SNAPSHOT is already on Clojars and verified consumed, but cljdoc does not build SNAPSHOTs, so the article tree and the normative sqlite-migrate.protocols docstring rendering have never been verified. That is AC 2 of sqm-01kzcv5h87gk, left blocked by design; this release is what unblocks it.

Local work (agent): CHANGELOG.md (Keep a Changelog 1.1.0, empty Unreleased + 0.1.0 entry, cljdoc tree entry), docs/releasing.md runbook, build.clj 0.1.0-SNAPSHOT -> 0.1.0, README install coordinate -> 0.1.0, commit 'Release 0.1.0', annotated tag v0.1.0, then commit 'Begin 0.2.0-SNAPSHOT'.

Network work (Jonas): git push; wait for CI green on the tagged commit; git push origin v0.1.0; bb deploy; bb cljdoc; GitHub Release with the 0.1.0 changelog section as the body.

## Notes

**2026-08-10T23:47:44.794246466Z**

Local half done. Gate green on the tagged tree: bb test 156 tests / 806 assertions / 0 failures, clj-kondo 0 errors 0 warnings. bb install put 0.1.0 in ~/.m2 and a scratch consumer resolved it and ran the full pipeline (drift -> plan -> apply! -> converged, plus schema/->sql): 'consumer ok'. The original scratch consumer project from sqm-01kzcv5h87gk no longer exists on disk, so it was rebuilt minimally rather than reconstructed.

Commits: 753137a chore(tickets), ec1fbe3 chore(release): 0.1.0 (annotated tag v0.1.0 on it), bf71e4a chore(release): Begin 0.2.0-SNAPSHOT. Nothing pushed — the network steps are Jonas's.

Decisions from the grilling session: straight 0.1.0, no rc; Keep a Changelog 1.1.0 at the root and last in the cljdoc article tree; the 0.1.0 entry is one prose sentence disclaiming the mutable 0.1.0-SNAPSHOT channel plus a flat Added of 12 curated bullets; [0.1.0] links to releases/tag/v0.1.0 rather than a 63-commit compare; no ADR (fails all three tests) and no CONTEXT.md change (release process is not migration-domain language); the runbook lives in docs/releasing.md, out of the cljdoc tree, routed from the CLAUDE.md table.

**2026-08-11T00:58:44.980809493Z**

sqlite-migrate 0.1.0 is released. Clojars maven-metadata carries <release>0.1.0</release>; cljdoc built the version with the full article tree — Readme, Changelog, Design, Recipes, Native image — and the four ADR 0013 public namespaces, impl.* hidden. That closes AC 2 of sqm-01kzcv5h87gk, blocked since the snapshot because cljdoc does not build SNAPSHOTs. Tag v0.1.0 is pushed and the GitHub Release carries the 0.1.0 changelog section as its body.

Keep a Changelog 1.1.0 adopted at the root and last in the cljdoc tree; the 0.1.0 entry disclaims the mutable 0.1.0-SNAPSHOT channel in one sentence and lists 12 curated Added bullets. docs/releasing.md holds the runbook and the rule for what earns an entry: observable through core/protocols/jdbc/schema including the add-only open sets, or it changes the statements plan emits for an input that already planned. No ADR and no CONTEXT.md change — release process is not migration-domain language.

Incident worth remembering: the first deploy published 0.2.0-SNAPSHOT instead of 0.1.0. bb deploy and bb cljdoc read version from the working tree's build.clj and never consult the tag, and the runbook ordered the post-release bump before the deploy, so the tree was one commit past the release by deploy time. Recovered by deploying from a checkout of v0.1.0 — no immutable coordinate was burned. Two fixes: fa5477c moves the bump to the last step, and 0e1de4f makes bb deploy print the coordinate and require it typed back (SQM_DEPLOY_YES=1 to skip). The stray 0.2.0-SNAPSHOT was left on Clojars; it is the mutable test channel and will be overwritten by real 0.2.0 work.

**2026-08-11T00:58:53.937214280Z**

Correction to the close summary: the bb deploy confirmation guard is commit 7031ad2, not 0e1de4f.
