(ns fiddle
  (:require
    [sqlite-migrate.core :as migrate]
    [sqlite-migrate.jdbc :as jdbc]))

(def live-ddl
  ["CREATE TABLE IF NOT EXISTS users (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      name TEXT NOT NULL,
      email TEXT NOT NULL
    );"])

(def target-ddl
  ["CREATE TABLE IF NOT EXISTS users (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      name TEXT NOT NULL DEFAULT 'Anonymous',
      email TEXT NOT NULL UNIQUE,
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP
    );"

   "CREATE TABLE IF NOT EXISTS posts (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER NOT NULL,
      title TEXT NOT NULL,
      content TEXT NOT NULL,
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY (user_id) REFERENCES users(id)
    );"])

(defn ddl->snapshot [ddl]
  (with-open [conn (jdbc/in-memory)]
    (migrate/declared-snapshot conn ddl)))

(comment

  (do
    (def live (ddl->snapshot live-ddl))
    (def target (ddl->snapshot target-ddl))

    (def diff (migrate/diff live target))

    (def plan
      (migrate/plan diff
        {:live-snapshot live
         :declared-snapshot target})))

  (migrate/drift? diff)

  (migrate/plan-report plan)

  :-)
