# The Executor finds where a statement ends

Amends ADR 0013 (the Executor gains a third op) and carries out ADR 0002's
"multi-statement text is split by SQLite's own prepare loop".

`declared-snapshot` has to realize and guard each statement of a Declaration
on its own: the guard that refuses DML, ATTACH, PRAGMA and temp objects runs
between statements, and a statement can only be prepared once the ones before
it exist. So core needs the boundaries one at a time, from SQLite, against the
database as it stands. Neither op could supply them: `execute-query` returns
rows, and `execute-batch!` runs whole strings. Before this, each string went
to `execute-batch!` whole and sqlite-jdbc ran only its first statement, so a
Declaration read from a `schema.sql` file lost every object after the first,
silently.

The Executor gains `first-statement [conn sql]`: the prefix of `sql` that
SQLite's prepare consumes as its first statement, or nil when `sql` holds no
statement. It prepares and never executes. An adapter that can reach
`sqlite3_prepare_v2`'s tail returns the text up to it.

sqlite-jdbc does not expose the tail, so the JDBC adapter asks SQLite by
probing. For each `;` in turn, it prepares the text up to that `;` twice,
followed by a comment closer and then by one of two illegal tokens. If both
outcomes match, SQLite stopped before reading what followed, so that `;` ends
the statement. A `;` inside a literal, a quoted identifier, a comment or a
trigger body lets the parse run on into the illegal token, and the two
outcomes differ. The adapter only locates `;` characters; SQLite decides which
of them count. When SQLite rejects a statement partway, the probe stops at the
first `;` after the point of rejection, so executing that prefix raises
SQLite's own error.

## Considered Options

- **Probe in core over `execute-query`, keeping two ops** — rejected: the
  probe relies on the driver preparing only the first statement of a string
  and on comparing driver error messages. Neither is promised by
  `execute-query`'s contract, and an adapter that runs every statement of a
  string, or puts the SQL text in its errors, would break the probe without
  breaking any contract.
- **Split with the lexical tokenizer** — rejected by ADR 0002: string
  manipulation that reimplements SQLite's `sqlite3_complete`, including its
  trigger-body rule.
- **Call `sqlite3_complete` through the FFM API** — rejected: it depends on
  finding the symbol in sqlite-jdbc's extracted native library, and native
  image (ADR 0014) would have to support it.

## Consequences

- Adapter authors implement three ops. The babashka adapter has to supply
  `first-statement` from its runtime, whether it can reach a real prepare tail
  or has to probe as the JDBC adapter does.
- `:statement-index` in `declared-snapshot`'s errors counts statements across
  the whole Declaration, not seq elements. A seq element that holds no
  statement (a comment, say) is skipped. Before, sqlite-jdbc failed on it with
  a spurious `:sqlite-error`.
