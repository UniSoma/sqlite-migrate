---
id: sqm-01kzbppmtvnp
title: 'Research: SQLite access from babashka and GraalVM native images'
status: closed
type: task
priority: 2
mode: afk
created: '2026-08-06T14:12:42.971145828Z'
updated: '2026-08-06T14:18:06.227669244Z'
closed: '2026-08-06T14:18:06.227669244Z'
parent: sqm-01kzbpngs10b
tags:
- wayfinder:research
assignee: claude
---

## Description

## Question

What are the viable ways to talk to SQLite from (a) babashka (pods, built-ins, HTTP sidecars?) and (b) a GraalVM native-image compiled Clojure app (sqlite-jdbc native-image support, alternatives)? What constraints do they impose on the effectful edge of the library, given a runtime-agnostic pure core? Deliverable: options with trade-offs.

## Notes

**2026-08-06T14:18:06.133751539Z**

JVM and Graal native-image share one path: next.jdbc + org.xerial/sqlite-jdbc, which ships GraalVM reachability metadata and CI-tested native-image support since 3.40.1.0 (currently 3.53.2.1) — full pragma/transaction/in-memory coverage, no extra config. Babashka is the odd one out: stock bb has no JDBC (BABASHKA_FEATURE_JDBC/SQLITE are off by default), and the only real option, pod-babashka-go-sqlite3, is statement-level with no transaction API and unreliable connection affinity (unbounded Go database/sql pool), so connection-scoped pragmas and atomic multi-statement DDL rebuilds cannot be guaranteed; read-only introspection works fine. Recommendation: design the effectful edge as a two-operation protocol — introspective query and atomic script apply — over next.jdbc for JVM+native, and defer bb support (pod lacks transactions; fallback options are sqlite3 CLI heredoc or upstreaming begin/commit + SetMaxOpenConns(1) to the pod). Findings doc: docs/research/babashka-graal-sqlite.md on branch research/babashka-graal-sqlite.

**2026-08-06T14:18:06.227669244Z**

next.jdbc+sqlite-jdbc (native-image OK since 3.40.1.0) for JVM/Graal; bb only via go-sqlite3 pod, no transactions — defer bb support
