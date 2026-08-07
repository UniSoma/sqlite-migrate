# Codebase Exploration

## clj-surgeon first

For Clojure, **map the code with `clj-surgeon` before spawning Explore agents or reading `.clj` files in bulk** — it returns form-level outlines in milliseconds for a fraction of the tokens of reading whole files.

1. `clj-surgeon :op ls-tree :dir <path>` — namespaces across a directory.
2. `clj-surgeon :op ls :file <path>` — form boundaries, line ranges, arglists (~50 tokens per file).
3. `Read` only the line ranges you need.
4. Spawn an `Explore` agent only for follow-up questions where you can hand it specific file paths.

`clj-surgeon --help` lists the rest (`ls-deps`, `declares`, `topo`, the CLJC ops, the `!` write variants); the `clj-surgeon` skill has the full guide.

## Debugging: evidence before theory

Match fixes to observed behavior, and **diff the working side against the broken one before theorizing about either.** When two paths through the code exercise the same layer and one works (one corpus statement snapshots correctly, another doesn't), the difference between the inputs is the answer more often than any mechanism you can reason out. For introspection bugs, ask SQLite itself first — run the pragma or `sqlite_master` query in the REPL against a scratch in-memory db before theorizing about what it returns.

When a fix doesn't work, that is evidence against the theory — re-examine rather than stacking a second fix on the first.
