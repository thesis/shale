(ns shale.periodic
  (:require [shale.sessions :as sessions])
  (:require [shale.nodes :as nodes])
  (:use overtone.at-at))

(def thread-pool (mk-pool))

(defn schedule! []
  (every 10000 #(nodes/refresh-nodes) thread-pool :fixed-delay true)
  (every 200 #(sessions/refresh-sessions nil) thread-pool :fixed-delay true))


(defn stop! []
  (stop-and-reset-pool! thread-pool))
