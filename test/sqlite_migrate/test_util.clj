(ns sqlite-migrate.test-util
  "Shared test helpers.")

(defmacro thrown-info
  "Evaluate `body`; return the `ExceptionInfo` it throws, or nil when
  it completes normally."
  [& body]
  `(try ~@body nil (catch clojure.lang.ExceptionInfo e# e#)))
