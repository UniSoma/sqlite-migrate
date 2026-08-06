# Research: SQLite access from babashka and GraalVM native images

Ticket: sqm-01kzbppmtvnp. Researched 2026-08-06 against primary sources (linked per claim).

## Question

What are the viable ways to talk to SQLite from (a) babashka and (b) a GraalVM
native-image compiled Clojure app, and what constraints do they impose on the
effectful edge of the library, given a runtime-agnostic pure core that needs to:
introspect via SQL/pragmas, count constraint violators, and execute DDL in one
transaction (with connection-scoped pragmas such as `foreign_keys=off`,
`legacy_alter_table`, `defer_foreign_keys` around table rebuilds)?

## (a) Babashka

### Option A1: `pod-babashka-go-sqlite3` — the de-facto default

Source: [pod-babashka-go-sqlite3](https://github.com/babashka/pod-babashka-go-sqlite3)
(README, CHANGELOG, `main.go`, [releases](https://github.com/babashka/pod-babashka-go-sqlite3/releases)).

- **API surface:** four vars in `pod.babashka.go-sqlite3`: `execute!`, `query`,
  `get-connection`, `close-connection`. `execute!`/`query` take either a db path
  string or a connection handle, plus a query vector
  (`["SELECT * FROM foo WHERE id = ?" 1]`) or plain string. `execute!` returns
  `{:rows-affected N, :last-inserted-id N}`; `query` returns a seq of maps.
  HoneySQL composes on top (shown in the README).
- **Parameter binding:** yes, positional `?` via the query vector; blobs work.
- **Connection semantics:** hybrid. Given a path, each call does a Go `sql.Open`
  and `defer conn.Close()` — open/close per call. Since v0.3.12 (2024-10-02),
  `get-connection` opens a persistent connection stored in a UUID-keyed map and
  returns a handle; `close-connection` landed in v0.3.13 (2024-10-14). The
  motivation was per-call open/close thrashing SQLite's page cache
  ([issue #38](https://github.com/babashka/pod-babashka-go-sqlite3/issues/38)).
- **Transactions: none.** No `with-transaction`, no begin/rollback/commit in
  `main.go`. In principle one could issue `BEGIN`/`COMMIT` via `execute!` on a
  cached connection, but (inference from `main.go`, flagged as such) the cached
  "connection" is a Go `database/sql` `*sql.DB` — a connection *pool* — and the
  pod never calls `SetMaxOpenConns(1)` or uses `sql.Conn`. Consecutive calls are
  not guaranteed to hit the same underlying SQLite connection, so `BEGIN` on one
  pooled conn and `COMMIT` on another is undefined behavior. **Treat
  multi-statement transactions as unsupported.**
- **Pragmas:** read-only pragmas (`pragma table_info(t)` etc.) work as ordinary
  statements via `query` (mattn/go-sqlite3 executes them normally). But
  *connection-scoped* pragmas (`foreign_keys=off`, `defer_foreign_keys`,
  `legacy_alter_table`) suffer the pool problem: with path-per-call they
  evaporate between calls; with a cached handle they may land on a different
  pooled connection than the subsequent DDL. This is the killer for
  rebuild-inside-one-transaction.
- **In-memory DBs:** no special `:memory:` handling; the path goes straight to
  `sql.Open("sqlite3", path)`. Per-call semantics destroy an in-memory DB
  between calls; a cached handle keeps it alive for the handle's lifetime
  (modulo pooling — `file::memory:?cache=shared` is the safe spelling).
- **Driver and build:** `github.com/mattn/go-sqlite3 v1.14.22`
  ([go.mod](https://raw.githubusercontent.com/babashka/pod-babashka-go-sqlite3/master/go.mod)),
  compiled with JSON1 and FTS5; Linux builds use musl since v0.3.9.
- **Maturity:** maintained by the babashka org (borkdude); latest release
  v0.3.13 (2024-10-14) — slow but alive, small surface. Listed in the
  [pod registry](https://github.com/babashka/pod-registry).

### Option A2: next.jdbc + sqlite-jdbc under bb — not viable for library users

Sources: [babashka `features.clj`](https://github.com/babashka/babashka/blob/master/src/babashka/impl/features.clj),
[babashka `build.md`](https://github.com/babashka/babashka/blob/master/doc/build.md),
[pod-babashka-sql](https://github.com/babashka/pod-babashka-sql).

- Babashka has feature flags `BABASHKA_FEATURE_JDBC` and `BABASHKA_FEATURE_SQLITE`
  (the latter bakes org.xerial sqlite-jdbc into a custom bb build). Both are
  **false by default**; the stock distributed bb binary has neither next.jdbc
  nor any JDBC driver. Requiring users to compile their own babashka is not a
  viable ask for a library.
- `pod-babashka-sql` covers HSQLDB, DB2, MSSQL, MySQL, Oracle, PostgreSQL with a
  next.jdbc-compatible API (`execute!`, `get-connection`, `with-transaction`,
  `begin`/`commit`/`rollback`) — but **no SQLite variant exists**. Ironically,
  that is the pod family whose transactional API we would want.

### Option A3: shell out to the `sqlite3` CLI via `babashka.process`

Source: [sqlite3 CLI docs](https://sqlite.org/cli.html).

- `sqlite3 db ".mode json" "pragma table_info(t);"` gives parseable output
  (`.mode json` since SQLite 3.33), and a heredoc script gives a true
  single-connection, multi-statement transaction with connection-scoped pragmas.
- Downsides: external runtime dependency on the CLI being installed; everything
  is text/JSON (no blobs, no typed values, no last-insert-id without an extra
  SELECT); quoting fragility.

### Option A4: everything else — nothing SQLite-shaped

The [pod registry](https://github.com/babashka/pod-registry) lists `go_sqlite3`,
`hsqldb`, `mysql`, `postgresql`, `datahike`, `datalevin` as its database pods —
the last two are Datalog stores, not SQLite. The HSQLDB feature flag is a
different database and off by default anyway.

## (b) GraalVM native-image

### Option B1: org.xerial/sqlite-jdbc — works out of the box (recommended)

Sources: [sqlite-jdbc README, GraalVM section](https://github.com/xerial/sqlite-jdbc),
[releases](https://github.com/xerial/sqlite-jdbc/releases),
[CI workflows](https://github.com/xerial/sqlite-jdbc/tree/master/.github/workflows).

- README: "Sqlite JDBC supports GraalVM native-image out of the box **starting
  from version 3.40.1.0**. There has been rudimentary support for some versions
  before that, but this was not actively tested by the CI."
- Mechanism: the JAR ships GraalVM reachability metadata; at image build time
  the sqlitejdbc native library for the compilation target is included in the
  image with the required JNI configuration. At runtime the `.so`/`.dylib`/`.dll`
  is extracted to a temp folder and loaded (same as on the JVM). Optional:
  set `org.sqlite.lib.exportPath` at build time to place the lib beside the
  binary instead of bundling and extracting.
- No manual JNI/resource config needed; native-image is CI-tested
  (`.github/workflows/build-native.yml`).
- Actively maintained: 3.53.2.1 released 2026-07-27; releases track upstream
  SQLite roughly monthly (3.53.2.0 on 2026-06-04, 3.53.1.0 on 2026-05-06,
  3.53.0.0 on 2026-04-14).
- Full feature coverage for our needs: all pragmas, prepared statements, real
  connection-scoped transactions, `:memory:` databases.
- Caveat: it is JNI plus a bundled shared library, not statically linked into
  the image. Fully static musl images would need the `exportPath` route or
  custom work.

### Option B2: alternatives — none worth it

- **SQLJet (pure Java):** dead — last release v1.1.15, compatible with the
  SQLite 3.6-era format, docs from ~2009-2010 ([sqljet.com](https://sqljet.com/)).
  There is no mature pure-Java SQLite.
- **FFI via `java.lang.foreign` (FFM/Panama):** supported in native-image
  (downcalls on x64/aarch64 Linux, Windows, macOS; enabled by default since
  GraalVM for JDK 25; descriptors registered at build time —
  [GraalVM FFM docs](https://www.graalvm.org/jdk25/reference-manual/native-image/native-code-interoperability/ffm-api/)).
  Viable in principle, but it means writing our own sqlite3 binding and
  shipping/locating libsqlite3 — a project, not a dependency. Only worth it for
  static linking or dropping JNI.
- **Static linking of sqlite:** no off-the-shelf Clojure/Java path; would go
  through FFM or a custom sqlite-jdbc build. Not recommended given B1.

### next.jdbc under native-image

Source: [next-jdbc issue #156](https://github.com/seancorfield/next-jdbc/issues/156)
(closed), [tips-and-tricks](https://github.com/seancorfield/next-jdbc/blob/develop/doc/tips-and-tricks.md).

- next.jdbc is plain Clojure with type-hinted interop; babashka itself compiles
  it into custom builds behind `BABASHKA_FEATURE_JDBC`, a strong existence proof
  that it AOTs cleanly under GraalVM. Issue #156 (an early `execute-one!`
  failure under native-image, 2021) is closed.
- Practical recipe: AOT with clean `*warn-on-reflection*`, ship sqlite-jdbc
  >= 3.40.1.0 (its metadata is bundled — no hand-written reflection config for
  the driver), and register the driver class explicitly rather than relying on
  `ServiceLoader` if driver lookup ever fails (a generic native-image JDBC
  concern, not a next.jdbc one).
- SQLite-specific note from the next.jdbc docs: booleans come back as 0/1 in
  result sets — worth normalizing in the introspection layer.

## Constraints on the effectful edge

- **JVM and native-image share one code path.** next.jdbc + sqlite-jdbc
  (>= 3.40.1.0) covers everything the edge needs — connection-scoped pragmas,
  single-transaction DDL rebuilds, in-memory test DBs — and native-image support
  is first-class and CI-tested. The Graal binary is genuinely bonus-tier cheap.
- **Babashka is the odd one out.** Stock bb has no JDBC; the only real option is
  the go-sqlite3 pod, whose API is statement-level with no transactions and
  unreliable connection affinity (unbounded Go `database/sql` pool behind the
  handle). Therefore the edge protocol should sit at the level of
  *"run introspection query, get rows"* and *"apply migration script
  atomically"* — never *"hand me a JDBC connection"*. Introspection ports to
  the pod cleanly; atomic multi-statement rebuild does not.
- Under bb the atomic-apply operation would have to either (1) document reduced
  guarantees (best-effort BEGIN/COMMIT on a cached handle), (2) drive the
  sqlite3 CLI with a heredoc script, or (3) — cleanest long-term — contribute
  begin/commit plus `SetMaxOpenConns(1)` upstream to the pod.

## Recommendation

Build the effectful edge as a small protocol with two operations — introspective
query (rows out) and atomic script apply — implemented over next.jdbc +
org.xerial/sqlite-jdbc (currently 3.53.2.1) for both the JVM and the GraalVM
native binary. Defer babashka support: keep the pure core bb-compatible (no
interop leaks) and the protocol pod-implementable for introspection, but do not
promise transactional migration application under bb until either the pod grows
a transaction API or a CLI-driver implementation is written and tested.
