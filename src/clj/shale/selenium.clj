(ns shale.selenium
  "Our own client implementation for particularly important / thorny Selenium
  node endpoints."
  (:require [cheshire [core :as json]]
            [clj-http.client :as client]
            [slingshot.slingshot :refer [try+ throw+]]
            [cemerick.url :refer [url]]))

(defn session-ids-from-node [node-url]
  (let [sessions-url (->> "./sessions"
                          (url node-url)
                          str)]
    (map #(get % "id")
         (-> (try+
               (client/get sessions-url)
               (catch Exception e))
             (get :body)
             json/parse-string
             (get "value")))))
