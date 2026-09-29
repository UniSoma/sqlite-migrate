---
id: sqm-01m3pt47gd99
title: Set the SQLite, JDK, Clojure and babashka floors and the CI matrix
status: open
type: feature
priority: 2
mode: hitl
created: '2026-09-29T14:46:59.853073569Z'
updated: '2026-09-29T14:47:03.550885285Z'
parent: sqm-01m3pt2fgxp9
tags:
- wayfinder:grilling
deps:
- sqm-01m3pt46xqvr
- sqm-01m3pt47a3zs
---

## Question

What minimum versions does 1.0 support, and what CI matrix proves them?

Found while charting (.github/workflows/ci.yml, deps.edn, bb.edn):

- The sqlite-jdbc floor leg (3.40.1.0) runs only the property suite; unit tests never run at the floor (deliberate — they pin 3.53 plan shapes).
- The native-image smoke job runs only against the latest sqlite-jdbc, though 3.40.1.0 is cited as where native-image testing begins.
- Only JDK 21 is tested; no JDK, Clojure, or babashka floor is declared anywhere.
- A sqlite3-CLI babashka route would run whatever SQLite the host has (distro versions vary widely).

Decide each floor, whether it is enforced at runtime or documented only, and the CI legs (including the babashka adapter's).