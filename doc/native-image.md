# Native image

sqlite-migrate is safe to compile into your own GraalVM native image. The
library ships no CLI and publishes no binary — "native-image support" means
the whole pipeline (Snapshot, Declaration, Diff, Plan, Apply) works inside a
binary you build.

## What makes it safe

- **The core is plain data and pure functions.** No runtime reflection, no
  dynamic class loading, no `eval` — nothing that needs native-image
  configuration.
- **The JDBC adapter leans on natively-tested dependencies.**
  [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) tests native-image
  compatibility upstream since 3.40.1.0 and bundles the GraalVM reachability
  metadata it needs; next.jdbc is AOT-friendly.

## Proven in CI

Every build runs a native-image smoke job (ADR 0014): it AOT-compiles a
program that exercises snapshot → declare → diff → plan → apply against a
real in-memory SQLite database, compiles it with `native-image
--no-fallback --initialize-at-build-time`, and requires the binary to print
`ok`. The program is `ci/smoke/smoke.clj` in the repository — it doubles as a
minimal example of compiling the library into a binary:

```
clojure -M:smoke -e "(compile 'smoke)"
native-image -cp "$(clojure -Spath -A:smoke)" \
  --no-fallback --initialize-at-build-time \
  -o smoke-bin smoke
./smoke-bin   # prints: ok
```

## Version floor

Use sqlite-jdbc **3.40.1.0 or newer** in a native image — that is where
upstream native-image testing begins, and it is the floor leg of this
library's own CI version matrix. The bundled SQLite there is 3.40.1; plans
targeting an older engine version simply refuse routes the engine lacks, so a
floor consumer loses some in-place ALTER routes to rebuilds, never
correctness.

## Babashka

Babashka is not supported at launch: bb cannot load next.jdbc/sqlite-jdbc,
and the go-sqlite3 pod cannot guarantee the single-connection transaction
Frame that Apply requires. The effectful edge is a two-function protocol
(`sqlite-migrate.protocols/SQLiteExecutor`) precisely so a future bb adapter
is additive.
