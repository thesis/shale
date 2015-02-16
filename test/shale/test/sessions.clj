(ns shale.test.sessions
  (:require [clojure.test :refer :all]
            [shale.sessions :refer [matches-requirement]]))

(deftest test-matches-requirement
  (testing "matches-requirement"

    (testing "session-tag matches"
      (let [requirement [:session-tag "a"]
            s {:session {:tags ["a" "b"]}}]
        (is (matches-requirement requirement s))))

    (testing "session-tag doesn't match"
      (let [requirement [:session-tag "a"]
            s {:session {:tags ["b" "c"]}}]
        (is (not (matches-requirement requirement s)))))

    (testing "not-session-tag matches"
      (let [requirement [:not [:session-tag "a"]]
            s {:session {:tags ["b" "c"]}}]
        (is (matches-requirement requirement s))))

    (testing "not-session-tag doesn't match"
      (let [requirement [:not [:session-tag "a"]]
            s {:session {:tags ["a" "b"]}}]
        (is (not (matches-requirement requirement s)))))

    (testing "and of session-tag matches"
      (let [requirement [:and [[:session-tag "a"] [:session-tag "b"]]]
            s {:session {:tags ["a" "b"]}}]
        (is (matches-requirement requirement s))))

    (testing "and of session-tag doesn't match"
      (let [requirement [:and [[:session-tag "a"] [:session-tag "c"]]]
            s {:session {:tags ["a" "b"]}}]
        (is (not (matches-requirement requirement s)))))

    (testing "node-tag matches"
      (let [requirement [:node-tag "a"]
            s {:node {:tags ["a" "b"]}}]
        (is (matches-requirement requirement s))))

    (testing "node-tag doesn't match"
      (let [requirement [:node-tag "a"]
            s {:node {:tags ["b" "c"]}}]
        (is (not (matches-requirement requirement s)))))

    (testing "reserved matches"
      (let [requirement [:reserved false]
            s {:session {:reserved false}}]
        (is (matches-requirement requirement s))))

    (testing "reserved doesn't match"
      (let [requirement [:reserved false]
            s {:session {:reserved true}}]
        (is (not (matches-requirement requirement s)))))

    (testing "browser matches"
      (let [requirement [:browser-name "a"]
            s {:session {:browser-name "a"}}]
        (is (matches-requirement requirement s))))

    (testing "browser doesn't match"
      (let [requirement [:browser-name "a"]
            s {:session {:browser-name "b"}}]
        (is (not (matches-requirement requirement s)))))

    (testing "current-url matches"
      (let [requirement [:current-url "a"]
            s {:session {:current-url "a"}}]
        (is (matches-requirement requirement s))))

    (testing "current-url doesn't match"
      (let [requirement [:current-url "a"]
            s {:session {:current-url "b"}}]
        (is (not (matches-requirement requirement s)))))

    (testing "session-id matches"
      (let [requirement [:session-id "a"]
            s {:session-id "a"}]
        (is (matches-requirement requirement s))))

    (testing "session-id doesn't match"
      (let [requirement [:session-id "a"]
            s {:session-id "b"}]
        (is (not (matches-requirement requirement s)))))

    (testing "node-id matches"
      (let [requirement [:node-id "a"]
            s {:session {:node-id "a"}}]
        (is (matches-requirement requirement s))))

    (testing "node-id doesn't match"
      (let [requirement [:node-id "a"]
            s {:session {:node-id "b"}}]
        (is (not (matches-requirement requirement s)))))))
