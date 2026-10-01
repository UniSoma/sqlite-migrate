(ns sqlite-migrate.jdbc
  "JDBC adapter: `SQLiteExecutor` over sqlite-jdbc, plus the constructors
  that open databases. Depends on `sqlite-migrate.protocols` only — never
  on the core."
  (:require [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]
    [sqlite-migrate.protocols :as p])
  (:import (java.sql Connection DriverManager PreparedStatement SQLException)
    (org.sqlite SQLiteErrorCode SQLiteException)))

(set! *warn-on-reflection* true)

(defn- query
  "Run `sql` with `params` and return its rows as unqualified lower-case
  maps. A driver failure throws `:sqlite-error` with the driver
  exception as the cause."
  [^Connection connection sql params]
  (try
    (jdbc/execute! connection (into [sql] params)
      {:builder-fn rs/as-unqualified-lower-maps})
    (catch SQLException e
      (throw (ex-info "the query failed"
               {:sqlite-migrate/error :sqlite-error}
               e)))))

(defn- raw-exec!
  [^Connection connection ^String sql]
  (with-open [st (.createStatement connection)]
    (.execute st sql)))

(defn- probe
  "Prepare the first statement of `sql` without stepping it and return
  `(f prepared-statement)`, or SQLite's message when it rejects the
  statement. Any other driver failure throws `:sqlite-error` with the
  driver exception as the cause."
  [^Connection connection ^String sql f]
  (try
    (with-open [ps (.prepareStatement connection sql)]
      (f ps))
    (catch SQLException e
      (if (and (instance? SQLiteException e)
            (= SQLiteErrorCode/SQLITE_ERROR (.getResultCode ^SQLiteException e)))
        (.getMessage e)
        (throw (ex-info "preparing a statement failed"
                 {:sqlite-migrate/error :sqlite-error}
                 e))))))

;; sqlite-jdbc hides the prepare tail, so the boundary is found by
;; probing: after a prefix, this text closes any open `--` or `/*`
;; comment and adds nothing else, and each marker that follows is a
;; token SQLite rejects with an error message that names it.
(def ^:private comment-closer "-- */\n")
(def ^:private probe-markers ["\u0001" "\u0002"])
;; A statement with 999 parameters, which no Declaration statement has;
;; 999 is the lowest ceiling SQLite has shipped for parameter numbers.
(def ^:private no-statement-probe "SELECT ?999")

(defn- stops-before-marker?
  "True when SQLite's prepare of `text` stops before reading anything
  appended after it: the prepare outcome is the same whichever marker
  follows."
  [connection text]
  (apply = (map #(probe connection (str text comment-closer %) (constantly nil))
             probe-markers)))

(defn- parameter-count
  [^PreparedStatement ps]
  (.getParameterCount (.getParameterMetaData ps)))

(defn- holds-no-statement?
  "True when `sql` is only whitespace, comments and semicolons: SQLite
  skips all of it and prepares the SELECT appended after it. A literal,
  quoted identifier or trigger body left open swallows that SELECT."
  [connection sql]
  (= 999 (probe connection (str sql comment-closer ";" no-statement-probe)
           parameter-count)))

(defn- leading-statement
  "`SQLiteExecutor/first-statement` over sqlite-jdbc. A prefix ending at a
  `;` is the first statement exactly when SQLite stops before reading
  what follows it — a semicolon inside a literal, comment or trigger
  body lets the parse run on into the marker, so the outcome depends
  on which marker it meets. Without such a `;`, the whole text is the
  statement unless it holds none."
  [connection ^String sql]
  (or (some (fn [end]
              (let [prefix (subs sql 0 end)]
                (when (stops-before-marker? connection prefix)
                  prefix)))
        (keep-indexed (fn [i c] (when (= \; c) (inc i))) sql))
    (when-not (holds-no-statement? connection sql)
      sql)))

(defn- foreign-keys-on?
  [^Connection connection]
  (= 1 (-> (query connection "PRAGMA foreign_keys" []) first :foreign_keys)))

(defn- check-gates!
  "Frame step 4: run every one of `gate-sqls` inside the open
  transaction — all of them, never stopping at one that returns rows —
  and throw `:gates-violated` with the index-aligned results when any
  returned rows. A gate query that fails throws `:sqlite-error` naming
  its index in `gate-sqls` under `:gate-index`, the driver exception as
  the cause."
  [^Connection connection gate-sqls]
  (let [results (into []
                  (map-indexed
                    (fn [i sql]
                      (try
                        (vec (query connection sql []))
                        (catch Exception e
                          (throw (ex-info (str "gate query " i " failed")
                                   {:sqlite-migrate/error :sqlite-error
                                    :gate-index i}
                                   (or (ex-cause e) e)))))))
                  gate-sqls)]
    (when (some seq results)
      (throw (ex-info (str (count (filter seq results)) " of " (count results)
                        " gate queries returned rows")
               {:sqlite-migrate/error :gates-violated
                :gate-results results})))))

(defn- run-frame!
  "The unconditional Frame of `execute-batch!` (see the protocol docstring):
  FK enforcement off outside the transaction, BEGIN, the `gate-sqls`
  inside the open transaction, statements in order, foreign_key_check,
  COMMIT, prior FK setting restored in a finally."
  [^Connection connection statements gate-sqls]
  (let [fk-was-on? (foreign-keys-on? connection)]
    (raw-exec! connection "PRAGMA foreign_keys=OFF")
    (try
      (raw-exec! connection "BEGIN")
      (try
        (check-gates! connection gate-sqls)
        (doseq [[i s] (map-indexed vector statements)]
          (try
            (raw-exec! connection s)
            (catch Exception e
              (throw (ex-info (str "statement " i " of the batch failed")
                       {:sqlite-migrate/error :sqlite-error
                        :statement-index i}
                       e)))))
        (let [violations (query connection "PRAGMA foreign_key_check" [])]
          (when (seq violations)
            (throw (ex-info (str "foreign_key_check failed — "
                              (count violations) " violating row(s)")
                     {:sqlite-migrate/error :sqlite-error
                      :violations violations}))))
        (raw-exec! connection "COMMIT")
        (catch Throwable t
          (try (raw-exec! connection "ROLLBACK")
            (catch Throwable _))
          (throw t)))
      (finally
        (raw-exec! connection (str "PRAGMA foreign_keys="
                                (if fk-was-on? "ON" "OFF")))))
    nil))

(deftype JdbcExecutor [^Connection connection]
  p/SQLiteExecutor
  (execute-query [_ sql params]
    (query connection sql params))
  (first-statement [_ sql]
    (leading-statement connection sql))
  (execute-batch! [_ statements]
    (run-frame! connection statements []))
  (execute-batch! [_ statements gate-sqls]
    (run-frame! connection statements gate-sqls))
  java.io.Closeable
  (close [_]
    (.close connection)))

(defn connect
  "Open the SQLite file at `path`, or wrap an existing
  `java.sql.Connection` or `javax.sql.DataSource` for interop. Returns a
  `SQLiteExecutor`-satisfying, `java.io.Closeable` conn whose lifecycle
  belongs to the caller."
  ^java.io.Closeable [source]
  (->JdbcExecutor
    (cond
      (string? source) (DriverManager/getConnection (str "jdbc:sqlite:" source))
      (instance? Connection source) source
      (instance? javax.sql.DataSource source) (.getConnection ^javax.sql.DataSource source)
      :else (throw (ex-info "connect takes a file path, Connection, or DataSource"
                     {:sqlite-migrate/error :malformed-input
                      :source source})))))

(defn in-memory
  "Open a fresh private in-memory SQLite database. Returns a
  `SQLiteExecutor`-satisfying, `java.io.Closeable` conn; the database
  lives exactly as long as the conn is open."
  ^java.io.Closeable []
  (connect ":memory:"))
