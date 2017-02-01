(ns shale.configurer
    (:use shale.utils)
    (:require [carica.core :refer [resources]]
              [environ.core :refer [env]]
              [clojure.java.io :as io]
              clojure.edn
              [shale.logging :as logging]
              [shale.utils :refer [pretty]]))

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
                          concat)]
    (if-let [config (some-> config-paths first slurp clojure.edn/read-string)]
      (do
        (logging/info "Loaded shale config...\n")
        (logging/info (pretty config))
        config)
      (throw (ex-info "configuration not found. Requires either config.clj on classpath or CONFIG_FILE env var" {})))))
