(ns shale.test.resources
  (:require [clojure.test :refer :all]
            [shale.resources :refer [parse-request-data]]
            [schema.core :as s]))

(deftest test-parse-request-data

  (testing "parse-request-data"

    (testing "ok"
      (let [context {:request {:request-method :post
                               :body "{\"a\": 1}"
                               :params {"b" "c"}}}]
        (is (=
          (parse-request-data :context context :k :xyz
                              :schema {(s/required-key "a") s/Num})
          [false {:xyz {"a" 1}}]))))

    (testing "with boolean params"
      (let [context {:request {:request-method :post
                               :body "{\"a\": 1}"
                               :params {"b" "True" "c" "False"}}}]
        (is (=
          (parse-request-data :context context :k :xyz
                              :include-boolean-params true)
          [false {:xyz {"a" 1 "b" true "c" false}}]))))

    (testing "malformed json"
      (let [context {:request {:request-method :post :body "q"}}]
        (is (=
          (parse-request-data :context context :k :xyz)
          {:message "Malformed JSON."}))))

    (testing "empty body"
      (let [context {:request {:request-method :post :body nil}}]
        (is (=
          (parse-request-data :context context :k :xyz)
          {:message "Empty body."}))))

    (testing "schema error"
      (let [context {:request {:request-method :post :body "{}"}}]
        (is (=
          (parse-request-data :context context
                              :schema {(s/required-key "a") s/Num})
          {:message "{\"a\" missing-required-key}\n"}))))

    (testing "default key"
      (let [context {:request {:request-method :post :body "{\"a\": 1}"}}]
        (is (=
          (parse-request-data :context context)
          [false {:shale.resources/data {"a" 1}}]))))))
