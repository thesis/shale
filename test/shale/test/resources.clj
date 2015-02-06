(ns shale.test.resources
  (:require [clojure.test :refer :all]
            [shale.resources :refer [parse-request-data ->sessions-request]]
            [schema.core :as s]))

(deftest test-parse-request-data

  (testing "parse-request-data"

    (testing "ok"
      (let [context {:request {:request-method :post
                               :body "{\"a\": 1}"
                               :params {"b" "c"}}}]
        (is (=
          (parse-request-data :context context :key :xyz
                              :schema {(s/required-key "a") s/Num})
          [false {:xyz {"a" 1}}]))))

    (testing "with boolean params"
      (let [context {:request {:request-method :post
                               :body "{\"a\": 1}"
                               :params {"b" "True" "c" "False"}}}]
        (is (=
          (parse-request-data :context context :key :xyz
                              :include-boolean-params true)
          [false {:xyz {"a" 1 "b" true "c" false}}]))))

    (testing "malformed json"
      (let [context {:request {:request-method :post :body "q"}}]
        (is (=
          (parse-request-data :context context :key :xyz)
          {:message "Malformed JSON."}))))

    (testing "empty body"
      (let [context {:request {:request-method :post :body nil}}]
        (is (=
          (parse-request-data :context context :key :xyz)
          {:message "Empty body."}))))

    (testing "schema error"
      (let [context {:request {:request-method :post :body "{}"}}]
        (is (=
          (parse-request-data :context context
                              :schema {(s/required-key "a") s/Num})
          {:message "{\"a\" missing-required-key}"}))))

    (testing "default key"
      (let [context {:request {:request-method :post :body "{\"a\": 1}"}}]
        (is (=
          (parse-request-data :context context)
          [false {:shale.resources/data {"a" 1}}]))))))

(deftest test-->sessions-resource

  (testing "->sessions-request"

    (testing "1"
      (let [context {:shale.resources/data {"browser_name" "phantomjs"}}
            sessions-request {:browser-name "phantomjs"}]
        (is (->sessions-request context) sessions-request)))

    (testing "2"
      (let [context {:shale.resources/data {"browser_name" "phantomjs"
                                            "tags" ["walmart"]}}
            sessions-request {:browser-name "phantomjs"
                              :tags ["walmart"]}]
        (is (->sessions-request context) sessions-request)))

    (testing "3"
      (let [context {:shale.resources/data {"tags" ["walmart" "logged-in"]
                                            "reserved" false}}
            sessions-request {:tags ["walmart" "logged-in"]
                              :reserved false}]
        (is (->sessions-request context) sessions-request)))
))
