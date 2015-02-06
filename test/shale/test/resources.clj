(ns shale.test.resources
  (:require [clojure.test :refer :all]
            [shale.resources :refer [parse-json ->sessions-request]]))

(deftest test-parse-json

  (testing "parse-json"

    (testing "ok"
      (let [context {:request {:request-method :post
                               :body "{\"a\": 1}"}}]
        (is (=
          (parse-json :context context :key :xyz)
          [false {:xyz {"a" 1}}]))))

    (testing "malformed json"
      (let [context {:request {:request-method :post
                               :body "q"}}]
        (is (=
          (parse-json :context context :key :xyz)
          {:message "Malformed JSON."}))))

    (testing "empty body"
      (let [context {:request {:request-method :post
                               :body nil}}]
        (is (=
          (parse-json :context context :key :xyz)
          {:message "Empty body."}))))

    (testing "default key"
      (let [context {:request {:request-method :post
                               :body "{\"a\": 1}"}}]
        (is (=
          (parse-json :context context)
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

    (testing "4"
      (let [context {:shale.resources/data {"tags" ["a"]}
                     :request {:params {"reserve" "True"}}}
            sessions-request {:tags ["a"]
                              :reserve-after-create true}]
        (is (->sessions-request context) sessions-request)))
))
