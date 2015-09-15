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

(deftest ^:integration test-app-startup
  (testing "main route"
    (let [response (app (mock/request :get "/"))]
      (is (= (:status response) 200))))

  (testing "sessions route"
    (let [response (app (mock/request :get "/sessions"))]
      (is (= (:status response) 200))
      (is (= (:body response) "[]"))))

  (testing "nodes route"
    (let [response (app (mock/request :get "/nodes"))]
      (is (= (:status response) 200))
      (is (= (:body response) "[]")))))
