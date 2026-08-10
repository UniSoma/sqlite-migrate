(ns build
  "Release tasks (ADR 0014). `version` below is the single source of
  truth for the artifact version; every task reads it from here.
  Development publishes run as -SNAPSHOT (the Clojars test channel);
  fixed releases drop the suffix and are tagged `v<version>`.

  Run through the bb tasks — `bb jar`, `bb install`, `bb deploy`,
  `bb cljdoc` — or directly as `clojure -T:build <task>`."
  (:require
    [clojure.string :as str]
    [clojure.tools.build.api :as b]))

(def lib 'io.github.unisoma/sqlite-migrate)
(def version "0.1.0")

(def ^:private repo-url "https://github.com/unisoma/sqlite-migrate")
(def ^:private class-dir "target/classes")

(defn- snapshot? []
  (str/ends-with? version "-SNAPSHOT"))

(defn- jar-file []
  (format "target/%s-%s.jar" (name lib) version))

(defn- scm []
  ;; SNAPSHOTs point at the commit they were cut from; fixed releases
  ;; point at the immutable v<version> tag (ADR 0014).
  {:url repo-url
   :tag (if (snapshot?)
          (b/git-process {:git-args "rev-parse HEAD"})
          (str "v" version))})

(defn print-version
  "Print the version string alone — the machine surface for bb tasks."
  [_]
  (println version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar
  "Build target/sqlite-migrate-<version>.jar with a POM carrying the
  real dependencies from deps.edn, MIT license, and SCM metadata."
  [_]
  (clean nil)
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version version
                  :basis basis
                  :src-dirs ["src"]
                  :scm (scm)
                  :pom-data [[:description
                              (str "Declarative SQLite schema migration: introspect a live"
                                " database into a Snapshot, diff it against a Declaration,"
                                " and turn the Diff into an executable Plan.")]
                             [:url repo-url]
                             [:licenses
                              [:license
                               [:name "MIT License"]
                               [:url "https://opensource.org/license/mit"]]]]})
    (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
    (b/copy-file {:src "LICENSE" :target (str class-dir "/META-INF/LICENSE")})
    (b/jar {:class-dir class-dir :jar-file (jar-file)}))
  (println "Built" (jar-file)))

(defn install
  "Build the jar and install it into the local ~/.m2 repository, for
  consumer verification before publishing."
  [_]
  (jar nil)
  (b/install {:basis (b/create-basis {:project "deps.edn"})
              :lib lib
              :version version
              :jar-file (jar-file)
              :class-dir class-dir})
  (println "Installed" (str lib) version "into the local repository"))

(defn deploy
  "Build the jar and deploy it to Clojars via deps-deploy. Requires a
  deploy token in CLOJARS_USERNAME / CLOJARS_PASSWORD."
  [_]
  (jar nil)
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
    {:installer :remote
     :artifact (b/resolve-path (jar-file))
     :pom-file (b/pom-path {:lib lib :class-dir class-dir})})
  (println "Deployed" (str lib) version "to Clojars"))
