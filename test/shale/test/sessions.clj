(ns shale.test.sessions
  (:require [clojure.test :refer :all]
            [shale.utils :refer [gen-uuid]]
            [shale.sessions :refer [matches-requirement
                                    require->create]]))

(def base-session {:tags #{"a" "b"}
                   :webdriver-id (gen-uuid)
                   :id (gen-uuid)
                   :node {:id (gen-uuid)
                          :url ""
                          :tags #{"a" "b"}
                          :max-sessions 6}
                   :reserved false
                   :capabilities {:browserName "firefox"}
                   :browser-name "firefox"
                   :current-url "http://google.com"})

(deftest test-matches-requirement
  (testing "matches-requirement"

    (testing "session-tag matches"
      (let [requirement [:tag "a"]
            s base-session]
        (is (matches-requirement s requirement))))

    (testing "session-tag doesn't match"
      (let [requirement [:tag "a"]
            s (assoc base-session :tags #{"b" "c"})]
        (is (not (matches-requirement s requirement)))))

    (testing "not-session-tag matches"
      (let [requirement [:not [:tag "a"]]
            s (assoc base-session :tags #{"b" "c"})]
        (is (matches-requirement s requirement))))

    (testing "not-session-tag doesn't match"
      (let [requirement [:not [:tag "a"]]
            s base-session]
        (is (not (matches-requirement s requirement)))))

    (testing "and of session-tag matches"
      (let [requirement [:and [[:tag "a"] [:tag "b"]]]
            s base-session]
        (is (matches-requirement s requirement))))

    (testing "and of session-tag doesn't match"
      (let [requirement [:and [[:tag "a"] [:tag "c"]]]
            s base-session]
        (is (not (matches-requirement s requirement)))))

    (testing "node-tag matches"
      (let [requirement [:node-tag "a"]
            s base-session]
        (is (matches-requirement s requirement))))

    (testing "node-tag doesn't match"
      (let [requirement [:node-tag "a"]
            s (assoc-in base-session [:node :tags] #{"b" "c"})]
        (is (not (matches-requirement s requirement)))))

    (testing "reserved matches"
      (let [requirement [:reserved false]
            s base-session]
        (is (matches-requirement s requirement))))

    (testing "reserved doesn't match"
      (let [requirement [:reserved false]
            s (assoc base-session :reserved true)]
        (is (not (matches-requirement s requirement)))))

    (testing "browser matches"
      (let [requirement [:browser-name "firefox"]
            s base-session]
        (is (matches-requirement s requirement))))

    (testing "browser doesn't match"
      (let [requirement [:browser-name "firefox"]
            s (assoc base-session :browser-name "b")]
        (is (not (matches-requirement s requirement)))))

    (testing "current-url matches"
      (let [requirement [:current-url "http://google.com"]
            s base-session]
        (is (matches-requirement s requirement))))

    (testing "current-url doesn't match"
      (let [requirement [:current-url "http://google.com"]
            s (assoc base-session :current-url "http://example.com")]
        (is (not (matches-requirement s requirement)))))

    (testing "session-id matches"
      (let [id (gen-uuid)
            requirement [:id id]
            s (assoc base-session :id id)]
        (is (matches-requirement s requirement))))

    (testing "session-id doesn't match"
      (let [id (gen-uuid)
            requirement [:id id]
            s base-session]
        (is (not (matches-requirement s requirement)))))

    (testing "node-id matches"
      (let [id (gen-uuid)
            requirement [:node-id id]
            s (assoc-in base-session [:node :id] id)]
        (is (matches-requirement s requirement))))

    (testing "node-id doesn't match"
      (let [requirement [:node-id (gen-uuid)]
            s (assoc-in base-session [:node :id] (gen-uuid))]
        (is (not (matches-requirement s requirement)))))

    (testing "webdriver-id matches"
      (let [id (gen-uuid)
            requirement [:webdriver-id id]
            s (assoc base-session :webdriver-id id)]
        (is (matches-requirement s requirement))))

    (testing "webdriver-id doesn't match"
      (let [id (gen-uuid)
            requirement [:webdriver-id id]
            s base-session]
        (is (not (matches-requirement s requirement)))))

    (testing "webdriver-id is nil"
      (let [requirement [:nil? :webdriver-id]
            s (assoc base-session :webdriver-id nil)]
        (is (matches-requirement s requirement))))))

(deftest test-require->create
  (testing "inferring create args from requirements"

    (testing "empty requirements"
      (let [requirement nil]
        (is {} (require->create requirement))))

    (testing "browser name"
      (let [requirement [:browser-name "firefox"]]
        (is {:browser-name "firefox"} (require->create requirement))))

    (testing "browser name and tag"
      (let [requirement [:and [:browser-name "firefox"] [:tag "lol"]]]
        (is {:browser-name "firefox"
             :tags #{"lol"}}
            (require->create requirement))))

    (testing "nested browser name and tag"
      (let [requirement [:and [:browser-name "firefox"] [:tag "lol"]]]
        (is {:browser-name "firefox"
             :tags #{"lol"}}
            (require->create requirement))))

    (testing "excluded tags"
      (let [requirement [:and [:webdriver-id (gen-uuid)] [:tag "lol"]]]
        (is {:tags #{"lol"}}
            (require->create requirement))))

    (testing "node requirements"
      (let [id (gen-uuid)
            requirement [:and [:node-id id] [:node-tag "lol"]]]
        (is {:node-require [:and [:id id] [:tag "lol"]]}
            (require->create requirement))))))
