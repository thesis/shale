(ns shale.periodic
  (:require [shale.sessions :as sessions])
  (:use overtone.at-at))

(def thread-pool (mk-pool))

(defn schedule! []
  (every 200 #(sessions/refresh-sessions nil) thread-pool :fixed-delay true))

(defn stop! []
  (stop-and-reset-pool! thread-pool))
