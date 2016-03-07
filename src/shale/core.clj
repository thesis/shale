(ns shale.core
  (:require [ring.adapter.jetty :as jetty]
            [shale.configurer :refer [get-config]]
            [shale.periodic :as periodic]
            [shale.nodes :as nodes]
            [shale.sessions :as sessions]
            [shale.handler :as handler]
            [com.stuartsierra.component :as component]
            [system.components.repl-server :refer [new-repl-server]])
  (:gen-class))

(defrecord Jetty [config app actors server]
  component/Lifecycle
  (start [cmp]
    (prn "Starting Jetty...")
    (let [port (or (:port config) 5000)
          server (jetty/run-jetty (:ring-app app) {:port port :join? false})]
      (assoc cmp :server server)))
  (stop [cmp]
    (when (:server cmp)
      (prn "Stopping Jetty...")
      (.stop (:server cmp)))
    (assoc cmp :server nil)))

(defn new-jetty [conf]
  (map->Jetty {:config conf}))

(defn get-redis-config [conf]
  {:pool {} :spec (:redis conf)})

(defn keyvals->system [kv]
  (apply component/system-map kv))

(defn get-app-system-keyvals [conf]
  [:config conf
   :redis (get-redis-config conf)
   :node-pool (component/using (nodes/new-node-pool conf)
                               {:redis-conn :redis})
   :session-pool (component/using (sessions/new-session-pool conf)
                                  {:redis-conn :redis
                                   :node-pool :node-pool})
   :app (component/using (handler/new-app)
                         [:session-pool :node-pool])])

(defn get-app-system [conf]
  (keyvals->system (get-app-system-keyvals conf)))

(defn get-http-system-keyvals [conf]
  [:http (component/using (new-jetty conf)
                          [:app])])

(defn get-http-system [conf]
  (keyvals->system
    (concat (get-app-system-keyvals conf)
            (get-http-system-keyvals conf))))

(defn get-shale-system [conf]
  (keyvals->system
    (concat (get-app-system-keyvals conf)
            (get-http-system-keyvals conf)
            [:scheduler (periodic/new-scheduler conf)
             :nrepl (if-let [nrepl-port (or (conf :nrepl-port) 5001)]
                      (new-repl-server nrepl-port))])))

(def shale-system nil)

(defn run []
  (alter-var-root #'shale-system component/start))

(defn destroy []
  (alter-var-root #'shale-system component/stop))

(defn init []
  (alter-var-root #'shale-system (fn [s] (get-shale-system (get-config))))
  (.addShutdownHook (Runtime/getRuntime) (Thread. destroy)))

(defn -main [& args]
  (try
    (init)
    (run)
    (catch Exception e
      (destroy))))
