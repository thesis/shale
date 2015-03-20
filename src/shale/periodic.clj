(ns shale.periodic
  (:require [overtone.at-at :refer :all]
            [shale.sessions :as sessions]
            [shale.nodes :as nodes]
            [shale.configurer :refer [config]]))

(def node-refresh-delay (or (config :node-refresh-delay ) 1000))
(def session-refresh-delay (or (config :session-refresh-delay) 200))

(def thread-pool (mk-pool))

(defn schedule! []
  (every node-refresh-delay #(nodes/refresh-nodes) thread-pool :fixed-delay true)
  (every session-refresh-delay #(sessions/refresh-sessions nil) thread-pool :fixed-delay true))

(defn stop! []
  (stop-and-reset-pool! thread-pool))
