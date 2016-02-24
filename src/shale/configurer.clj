(ns shale.configurer
    (:use shale.utils)
    (:require [carica.core :refer [resources]]
              [environ.core :refer [env]]
              [clojure.java.io :as io]
              clojure.edn))

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
    (clojure.edn/read-string (slurp (first config-paths)))))
