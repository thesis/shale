(ns shale.test.handler
  (:require [clojure.test :refer :all]
            [shale.handler :refer :all]
            [ring.mock.request :as mock]))

(deftest test-app
  (testing "main route"
    (let [response (app (mock/request :get "/"))]
      (is (= (:status response) 200))))

  (testing "sessions route"
    (let [response (app (mock/request :get "/sessions"))]
      (is (= (:status response) 200))
      (is (= (:body response) "[]")))))
