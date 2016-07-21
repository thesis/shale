(ns shale.periodic
  (:require [clojure.set]
            [overtone.at-at :as at-at]
            [taoensso.timbre :as timbre :refer [info]]
            [com.stuartsierra.component :as component]
            [schema.core :as s]
            [taoensso.timbre :as timbre :refer [info error debug]]
            [shale.sessions :as sessions]
            [shale.nodes :as nodes])
  (:import shale.sessions.SessionPool
           shale.nodes.NodePool))

(declare schedule! stop! reschedule-session-refreshes!
         start-session-refresh-job! kill-session-refresh-job!)

(s/defrecord Scheduler
  [config
   thread-pool
   session-pool :- (s/maybe SessionPool)
   node-pool :- (s/maybe NodePool)
   session-job-state
   node-refresh-delay :- (s/maybe s/Int)
   session-refresh-delay :- (s/maybe s/Int)]
  component/Lifecycle
  (start [cmp]
    (info "Starting scheduler...")
    (try
      (info "Starting thread pool...")
      (let [thread-pool (at-at/mk-pool)
            new-cmp (-> cmp
                        (assoc :thread-pool thread-pool)
                        (assoc :session-job-state (atom {}))
                        (assoc :session-refresh-delay
                               (or (:session-refresh-delay config) 1000))
                        (assoc :node-refresh-delay
                               (or (:node-refresh-delay config) 5000)))]
        (schedule! new-cmp)
        new-cmp)
      (catch Exception e
        (stop! thread-pool)
        (throw e))))
  (stop [cmp]
    (when-not (nil? thread-pool)
      (info "Stopping scheduler...")
      (stop! thread-pool))
    (-> cmp
        (assoc :thread-pool nil)
        (assoc :session-job-state nil))))

(defn new-scheduler [conf]
  (map->Scheduler {:config conf}))

(s/defn schedule!
  [scheduler :- Scheduler]
  (let [{:keys [session-refresh-delay
                node-refresh-delay
                thread-pool
                session-pool
                node-pool]} scheduler]
    (at-at/interspaced node-refresh-delay
                       #(try
                          (nodes/refresh-nodes node-pool)
                          (catch Exception e
                            (debug "Error refreshing nodes.")
                            (debug e)
                            (throw e)))
                       thread-pool
                       :desc "Refreshing nodes")
    (at-at/interspaced session-refresh-delay
                       #(reschedule-session-refreshes! scheduler)
                       thread-pool
                       :desc "Rescheduling session refreshes")))

(defn stop! [thread-pool]
  (at-at/stop-and-reset-pool! thread-pool))

(s/defn start-session-refresh-job!
  "Schedule an intermittent refresh for a session. Include a random initial
  delay (< 5 seconds) to distribute refreshes."
  [scheduler :- Scheduler
   session-id :- s/Str]
  (info (format "Starting refresh job for session %s..." session-id))
  (let [{:keys [session-pool]} scheduler
        job (at-at/interspaced (:session-refresh-delay scheduler)
                               #(try
                                  (sessions/refresh-session session-pool
                                                            session-id)
                                  (catch Exception e
                                    (debug (format "Error refreshing sessions %s"
                                                   session-id))
                                    (debug e)
                                    (throw e)))
                               (:thread-pool scheduler)
                               :initial-delay (rand-int 5000)
                               :desc (format "Refreshing session %s"
                                             session-id))]
    (swap! (:session-job-state scheduler) #(assoc % session-id job))))

(s/defn kill-session-refresh-job!
  "Unschedule a session's refresh job."
  [scheduler :- Scheduler
   session-id :- s/Str]
  (info (format "Killing refresh job for session %s..." session-id))
  (let [session-jobs @(:session-job-state scheduler)
        job (get session-jobs session-id)]
    (when-not (nil? job)
      (at-at/kill job)
      (swap! (:session-job-state scheduler) #(dissoc % session-id)))))

(s/defn reschedule-session-refreshes!
  "Make sure every session has a periodically scheduled refresh."
  [scheduler :- Scheduler]
  (let [{:keys [session-pool thread-pool]} scheduler
        ids (->> session-pool
                 sessions/view-model-ids
                 (into #{}))
        scheduled-ids (->> @(:session-job-state scheduler)
                           keys
                           (into #{}))
        new-ids (clojure.set/difference ids scheduled-ids)
        old-ids (clojure.set/difference scheduled-ids ids)]
    (doall (map #(kill-session-refresh-job! scheduler %) old-ids))
    (doall (map #(start-session-refresh-job! scheduler %) new-ids))))
