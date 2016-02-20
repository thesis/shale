(ns shale.core
  (:require [ring.adapter.jetty :as jetty]
            [shale.configurer :refer [config]]
            [shale.periodic :as periodic]
            [shale.nodes :as nodes]
            [shale.handler :refer app]
            [clojure.tools.nrepl.server :as nrepl])
  (:gen-class))

(defonce nrepl-server (atom nil))

(defn init []
  (let [nrepl-port (or (config :nrepl-port) 5001)]
    (reset! nrepl-server
            (nrepl/start-server :port nrepl-port)))
  (nodes/refresh-nodes)
  ;; schedule periodic tasks
  (periodic/schedule!))

(defn destroy []
  (periodic/stop!)
  (nrepl/stop-server @nrepl-server)
  (reset! nrepl-server nil))

(defn -main [& args]
  (init)
  (try
    (jetty/run-jetty app {:port 5000})
    (finally
      (destroy))))
