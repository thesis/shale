(ns shale.handler
  (:require [ring.adapter.jetty :as jetty]
            [clj-json [core :as json]]
            [shale.periodic :as periodic])
  (:use
    [shale.resources :only  [assemble-routes]]
    [ring.middleware.multipart-params :only  [wrap-multipart-params]]
    [compojure.handler :only  [api]])
  (:gen-class))

(defn ignore-trailing-slash
  "From https://gist.github.com/dannypurcell/8215411"
  [handler]
  (fn [request]
    (let [uri (:uri request)]
      (handler (assoc request
                      :uri
                      (if (and
                            (not (= "/" uri))
                            (.endsWith uri "/"))
                          (subs uri 0 (dec (count uri)))
                           uri))))))

(defn user-visible-json-exceptions [handler]
  (fn [request]
    (try
      (handler request)
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (if (data :user-visible)
            {:body (json/generate-string {"error" (.getMessage e)})
             :status (or (data :status) 500)}
            (if (= "production" (System/getenv "RING_ENV"))
              {:body (json/generate-string {"error" "Unknown error."})
               :status 500}
              (throw e))))))))

(def app
  (-> (assemble-routes)
      ignore-trailing-slash
      user-visible-json-exceptions
      api
      wrap-multipart-params))

(defn init []
  ;; schedule periodic tasks
  (periodic/schedule!))

(defn destroy []
  (periodic/stop!))

(defn -main [& args]
  (jetty/run-jetty app {:port 5000}))
