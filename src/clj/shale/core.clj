(ns shale.core
  (:require [ring.adapter.jetty :as jetty]
            [shale.configurer :refer [get-config]]
            [shale.periodic :as periodic]
            [shale.nodes :as nodes]
            [shale.sessions :as sessions]
            [shale.handler :as handler]
            [taoensso.timbre :as timbre :refer [info]]
            [io.aviso.exception :as pretty]
            [cheshire.generate :refer [add-encoder encode-str]]
            [com.stuartsierra.component :as component]
            [system.components.repl-server :refer [new-repl-server]])
  (:import clojure.lang.IPersistentMap
           clojure.lang.IRecord
           org.openqa.selenium.Platform)
  (:gen-class))

; workaround so timbre won't crash when logging an exception involving
; a schema from plumatic/schema. kudos to the onyx project for the idea
; (https://github.com/onyx-platform/onyx, onyx.static.logging-configuration)
(prefer-method pretty/exception-dispatch IPersistentMap IRecord)

(defrecord Jetty [config app actors server]
  component/Lifecycle
  (start [cmp]
    (info "Starting Jetty...")
    (let [port (or (:port config) 5000)
          server (jetty/run-jetty (:ring-app app) {:port port :join? false})]
      (assoc cmp :server server)))
  (stop [cmp]
    (when (:server cmp)
      (info "Stopping Jetty...")
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

(defn start []
  (alter-var-root #'shale-system component/start))

(defn stop []
  (alter-var-root #'shale-system component/stop))

(defn init-cheshire []
  ; unfortunately, cheshire has a global encoders list
  (add-encoder org.openqa.selenium.Platform encode-str))

(defn init []
  (alter-var-root #'shale-system (fn [s] (get-shale-system (get-config))))
  (.addShutdownHook (Runtime/getRuntime) (Thread. stop))
  (init-cheshire))

(defn -main [& args]
  (try
    (init)
    (start)
    (catch Exception e
      (stop))))
