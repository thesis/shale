(ns shale.test.handler
  (:require [clojure.test :refer :all]
            [cheshire [core :as json]]
            [carica.core :refer [override-config]]
            [shale.configurer :as configurer]
            [ring.mock.request :as mock]
            [shale.handler :refer :all]
            [shale.test.utils :refer [with-selenium-servers
                                      with-custom-config
                                      clean-redis]]))

(def custom-config
  {:node-list ["http://localhost:4444/wd/hub"]})

(def once-fixtures
  [(with-selenium-servers [4444])
   (with-custom-config custom-config)])

(def each-fixtures
  [clean-redis])

(use-fixtures :once (join-fixtures once-fixtures))
(use-fixtures :each (join-fixtures each-fixtures))

(defn is-status [resp status]
  (is (= (:status resp) status)))

(defn is-200 [resp]
  (is-status resp 200))

(defn is-body [resp body]
  (is (= (:body resp) body)))

(defn is-json [resp]
  (is (.contains (get-in resp [:headers "Content-Type"]) "json"))
  (let [[parse-error? result]
        (try
          [false (json/parse-string (:body resp))]
          (catch com.fasterxml.jackson.core.JsonParseException e
            [true nil]))]
    (is (false? parse-error?)
        (str "Body should be valid JSON:" (:body resp)))
    result))

(deftest ^:integration basic-routes
  (testing "main route"
    (let [response (app (mock/request :get "/"))]
      (is-200 response)))

  (testing "sessions route"
    (let [response (app (mock/request :get "/sessions"))]
      (is-200 response)
      (is-body response "[]")))

  (testing "nodes route"
    (let [response (app (mock/request :get "/nodes"))]
      (is-200 response)
      (is-body response "[]"))))

(defn refresh-nodes []
  (app (mock/request :post "/nodes/refresh")))

(deftest ^:integration node-properties
  (testing "refreshing nodes populates the node list"
    (let [resp-1 (app (mock/request :get "/nodes"))
          resp-refresh (refresh-nodes)
          resp-2 (app (mock/request :get "/nodes"))]
      (doto resp-1
        (is-200)
        (is-json)
        (is-body "[]"))
      (doto resp-refresh
        (is-status 201)
        (is-body nil))
      (is-200 resp-2)
      (let [parsed (is-json resp-2)
            node-list (configurer/config :node-list)]
        (is (= (count (set (map #(get % "url") parsed)))
               (count (set node-list)))
            (str "There should be the same number of uniquely configured node "
                 "URLs as nodes."))))))
