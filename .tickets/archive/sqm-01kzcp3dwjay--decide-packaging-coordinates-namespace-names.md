---
id: sqm-01kzcp3dwjay
title: 'Decide packaging: coordinates, namespace names, release story'
status: closed
type: feature
priority: 2
mode: hitl
created: '2026-08-06T23:21:27.698389725Z'
updated: '2026-08-07T00:31:19.479902945Z'
closed: '2026-08-07T00:31:19.479902945Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:grilling
assignee: jonas
---

## Description

## Question

Graduated from the map's fog now that ADR 0013 has fixed the namespace *shape* (four public namespaces: core, protocol, JDBC adapter, schema): pick the artifact coordinates (group/artifact id), the concrete namespace names those four map to, and the release story (versioning scheme given the add-only open-set promises, cljdoc/README posture, how the Graal-native bonus target is published or documented).

## Notes

**2026-08-07T00:31:19.479902945Z**

One artifact io.github.unisoma/sqlite-migrate with real JDBC deps (next.jdbc + sqlite-jdbc; two-artifact split stays an additive later option); namespace root sqlite-migrate.* matching the artifact id and the ADR 0012 error key — sqlite-migrate.core / sqlite-migrate.protocols / sqlite-migrate.jdbc / sqlite-migrate.schema; SemVer accretion: 0.1.0-SNAPSHOT as the mutable test channel, first fixed 0.1.0, 0.x until the motivating consumer proves the promises, then 1.0.0 and minor/patch only (breaking change = new artifact name); release fully manual via bb tasks mirroring mantine-ui-wrapper (version string canonical in build.clj; jar / install-to-~/.m2 / deploy via deps-deploy with Clojars token env vars / cljdoc trigger; SCM = commit SHA for SNAPSHOTs, immutable v<version> tag for releases; real deps in the POM, none of the wrapper's source-only surgery); docs = README (pitch, quickstart, drift-check recipe) + cljdoc canonical API reference + curated articles from the map's outputs; Graal native = documentation + CI native-image smoke job only, no published binary; license MIT. ADR 0014.
