(ns shale.configurer
    (:use shale.utils)
    (:require [carica.core :refer [configurer resources]]
              [environ.core :refer [env]]
              [clojure.java.io :as io]))

(def config
  (->> [(some->> (env :config-file)
                 (str "file://"))
        (resources "config.clj")]
       flatten
       (filter identity)
       (map io/as-url)
       concat
       configurer))
