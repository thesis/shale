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
          {:message {"a" 'missing-required-key}}
          (parse-request-data :context context
                              :schema {(s/required-key "a") s/Num})))))

    (testing "default key"
      (let [context {:request {:request-method :post :body "{\"a\": 1}"}}]
        (is (=
          (parse-request-data :context context)
          [false {:shale.resources/data {"a" 1}}]))))

    (testing "request schema keyword coercion"
      (let [context {:request
                     {:request-method :patch
                      :body "{\"a\": 1, \"b\": 2, \"c\": 3, \"d\":[\"z\"]}"}}]
        (is (=
          [false {:shale.resources/data {:a 1 :b 2 "c" 3 :d [:z]}}]
          (parse-request-data :context context
                              :schema {:a s/Int
                                       (s/optional-key :b) s/Int
                                       (s/required-key "c") s/Int
                                       :d [s/Keyword]
                                       })))))

    (testing "request schema keyword case coercion"
      (let [context {:request
                     {:request-method :patch
                      :body "{\"a_one\": 1, \"b_two\": 2, \"c_three\": 3}"}}]
        (is (=
          [false {:shale.resources/data {:a-one 1 :b-two 2 :c-three 3}}]
          (parse-request-data :context context
                              :schema {:a-one s/Int
                                       :b-two s/Int
                                       :c-three s/Int})))))))
