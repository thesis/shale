(ns shale.test.handler
  (:require [clojure.test :refer :all]
            [clj-json [core :as json]]
            [carica.core :refer [override-config]]
            [shale.configurer :as configurer]
            [ring.mock.request :as mock]
            [shale.handler :refer :all]
            [shale.test.utils :refer [with-selenium-servers]]))

(use-fixtures :once (with-selenium-servers [4444]))

(deftest test-app
  (testing "main route"
    (let [response (app (mock/request :get "/"))]
      (is (= (:status response) 200))))

  (testing "sessions route"
    (let [response (app (mock/request :get "/sessions"))]
      (is (= (:status response) 200))
      (is (= (:body response) "[]"))))

  #_(testing "requesting a session with an empty node list throws a 500"
    (with-redefs [configurer/config (override-config :node-list [])]
      (let [response (app (-> (mock/request :post "/sessions")
                              (mock/body "{}")))]
        (is (= (:status response) 500))
        (is (= (json/parse-string (:body response))
               {"error" "No suitable node found!"}))))))
