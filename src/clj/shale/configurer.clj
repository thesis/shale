(ns shale.configurer
    (:use shale.utils)
    (:require [carica.core :refer [resources]]
              [environ.core :refer [env]]
              clojure.walk
              [clojure.java.io :as io]
              clojure.edn
              [shale.logging :as logging]
              [shale.utils :refer [pretty]]))


(defn strip-namespace [kw]
  (keyword (name kw)))

(defn resolve-env-keyword!
  "If a keyword has the namespace :env, look it up in the environment and return the env var value"
  [kw]
  (if (and (keyword? kw) (namespace kw) (= "env" (namespace kw)))
    (get env (strip-namespace kw))
    kw))

(defn get-config
  "Get the config file. Path can either be specified by a CONFIG_FILE
  environment variable, or can be found on the classpath as config.clj."
  []
  (let [config-paths (->> [(some->> (env :config-file)
                                    (str "file://"))
                           (resources "config.clj")]
                          flatten
                          (filter identity)
                          (map io/as-url)
                          concat)
        raw-config (clojure.edn/read-string (slurp (first config-paths)))]
    (logging/info "Loading config...\n")
    (logging/info (pretty raw-config))
    (logging/info "Resolving environment variables...\n")
    (clojure.walk/postwalk resolve-env-keyword! raw-config)))
