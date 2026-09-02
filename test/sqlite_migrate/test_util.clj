(ns sqlite-migrate.test-util
  "Shared test helpers.")

(defmacro thrown-info
  "Evaluate `body`; return the `ExceptionInfo` it throws, or nil when
  it completes normally."
  [& body]
  `(try ~@body nil (catch clojure.lang.ExceptionInfo e# e#)))

(def trials
  "Per-property trial count — modest by default so the suite stays
  fast; CI raises it through the SQM_TRIALS environment variable."
  (or (some-> (System/getenv "SQM_TRIALS") Long/parseLong) 40))
