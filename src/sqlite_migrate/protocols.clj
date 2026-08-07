(ns sqlite-migrate.protocols
  "The effectful-edge contract every runtime adapter implements.

  Everything effectful in sqlite-migrate — introspection, Check, Apply —
  speaks only to a `SQLiteExecutor`. Database creation is deliberately
  outside the contract: each adapter exposes whatever constructors are
  natural for its runtime (see `sqlite-migrate.jdbc`). The docstrings in
  this namespace are the normative adapter-author spec.")

(defprotocol SQLiteExecutor
  "The two-op effectful contract over one open SQLite database.

  Implementations must target the `main` schema of a single database and
  keep one logical connection open for the value's lifetime — introspection
  of an in-memory database only makes sense against the same connection
  that realized it. Conn values should also be `java.io.Closeable`; their
  lifecycle belongs to the caller."
  (execute-query [conn sql params]
    "Execute one read-only SQL statement with positional `params` (a
    sequence, possibly empty) and return the full result set as a vector
    of keyword-keyed row maps (unqualified, lower-case keys). Must not
    mutate the database. Failures throw; the driver exception must ride
    as the cause.")
  (execute-batch! [conn statements] [conn statements pre-check!]
    "Execute the ordered SQL `statements` (a sequence of single-statement
    strings) inside the executor-owned atomic Frame. The Frame is always
    the same shape, unconditionally — never dependent on the statements:

      1. read the current `PRAGMA foreign_keys` setting
      2. `PRAGMA foreign_keys=OFF` — outside any transaction
      3. `BEGIN`
      4. call `pre-check!` when one was supplied — a zero-argument fn
         (its return value is ignored); if it throws, roll back and
         rethrow. Runs inside the open transaction, so anything it
         reads cannot change before the statements run (the TOCTOU-free
         gate-check seam — ADR 0008)
      5. the statements, in order
      6. `PRAGMA foreign_key_check` — if it returns any row, roll back
         and throw
      7. `COMMIT`
      8. restore the prior `foreign_keys` setting, in a `finally`

    The two-argument arity is the three-argument one with no
    `pre-check!`. All-or-nothing: any failure rolls the transaction
    back and rethrows; no statement's effect may survive a failure.
    When step 5 fails, the thrown exception's ex-data must carry the
    failing statement's zero-based index in `statements` under
    `:statement-index`, with the driver exception as the cause —
    callers attribute the failure back to the plan Op that contributed
    the statement. A step-4 or step-6 failure carries no
    `:statement-index`. Returns nil — success is silence, failure
    throws."))
