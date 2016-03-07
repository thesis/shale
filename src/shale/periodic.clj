(ns shale.periodic
  (:require [overtone.at-at :refer :all]
            [shale.sessions :as sessions]
            [shale.nodes :as nodes]
            [taoensso.timbre :as timbre :refer [info]]
            [com.stuartsierra.component :as component]))

(defn schedule! [thread-pool node-refresh-delay session-refresh-delay]
  (every node-refresh-delay #(nodes/refresh-nodes) thread-pool :fixed-delay true)
  (every session-refresh-delay #(sessions/refresh-sessions nil) thread-pool :fixed-delay true))

(defn stop! [thread-pool]
  (stop-and-reset-pool! thread-pool))

(defrecord Scheduler [config thread-pool]
  component/Lifecycle
  (start [cmp]
    (info "Starting scheduler...")
    (try
      (info "Starting thread pool...")
      (let [thread-pool (mk-pool)
            node-refresh-delay (or (:node-refresh-delay config) 1000)
            session-refresh-delay (or (:session-refresh-delay config) 200)]
        (schedule! thread-pool node-refresh-delay session-refresh-delay)
        (assoc cmp :thread-pool thread-pool))
      (catch Exception e
        (stop! thread-pool)
        (throw e))))
  (stop [cmp]
    (info "Stopping scheduler...")
    (if-not (nil? thread-pool)
      (stop! thread-pool))
    (assoc cmp :thread-pool nil)))

(defn new-scheduler [conf]
  (map->Scheduler {:config conf}))
