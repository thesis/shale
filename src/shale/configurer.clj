(ns shale.configurer
    (:use shale.utils)
    (:require [carica.core :refer [configurer resources]]
              [environ.core :refer [env]]
              [clojure.java.io :as io]))

(def config
  (configurer
    (concat
      (map io/as-url
           (filter identity
                   (flatten
                     [(if-let [env-var (env :config-file)]
                        (str "file://" env-var))
                      (resources "config.clj")]))))))
