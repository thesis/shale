(ns shale.riemann
  (:require [riemann.client :as riemann]
            [shale.configurer :refer [config]]
            [taoensso.timbre :as timblre :refer [debug]]))

(def client (when-let [client-config (config :riemann)]
              (debug "Configuring Riemann client..." client-config)
              (let [client-fn (condp = (:protocol client-config)
                                 :tcp riemann/tcp-client
                                 :udp riemann/udp-client
                                 nil)]
                (client-fn (dissoc client-config :protocol)))))

(if client
  (debug "Riemann client configured." client))
