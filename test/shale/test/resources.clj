(ns shale.test.resources
  (:require [clojure.test :refer :all]
            [shale.resources :refer [->sessions-request]]))

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
