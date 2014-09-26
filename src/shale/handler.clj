(ns shale.handler
  (:require [ring.adapter.jetty :as jetty])
  (:use
    [shale.resources :only  [assemble-routes]]
    [ring.middleware.multipart-params :only  [wrap-multipart-params]]
    [compojure.handler :only  [api]]
    [ring.middleware.json :only  [wrap-json-response]]))

(def app
  (-> (assemble-routes)
      api
      wrap-multipart-params
      wrap-json-response))

(defn start  [options]
  (jetty/run-jetty #'app  (assoc options :join? false)))

(defn -main
  ([port] (start  {:port  (Integer/parseInt port)}))
  ([] (-main "5000")))
