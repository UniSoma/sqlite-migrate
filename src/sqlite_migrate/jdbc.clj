(ns sqlite-migrate.jdbc
  "JDBC adapter: `SQLiteExecutor` over sqlite-jdbc, plus the constructors
  that open databases. Depends on `sqlite-migrate.protocols` only — never
  on the core."
  (:require [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]
    [sqlite-migrate.protocols :as p])
  (:import (java.sql Connection DriverManager)))

(set! *warn-on-reflection* true)

(defn- query
  [^Connection connection sql params]
  (jdbc/execute! connection (into [sql] params)
    {:builder-fn rs/as-unqualified-lower-maps}))

(defn- raw-exec!
  [^Connection connection ^String sql]
  (with-open [st (.createStatement connection)]
    (.execute st sql)))

(defn- foreign-keys-on?
  [^Connection connection]
  (= 1 (-> (query connection "PRAGMA foreign_keys" []) first :foreign_keys)))

(defn- run-frame!
  "The unconditional Frame of `execute-batch!` (see the protocol docstring):
  FK enforcement off outside the transaction, BEGIN, `pre-check!` (when
  supplied) inside the open transaction, statements in order,
  foreign_key_check, COMMIT, prior FK setting restored in a finally."
  [^Connection connection statements pre-check!]
  (let [fk-was-on? (foreign-keys-on? connection)]
    (raw-exec! connection "PRAGMA foreign_keys=OFF")
    (try
      (raw-exec! connection "BEGIN")
      (try
        (when pre-check! (pre-check!))
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
  (execute-batch! [_ statements]
    (run-frame! connection statements nil))
  (execute-batch! [_ statements pre-check!]
    (run-frame! connection statements pre-check!))
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
