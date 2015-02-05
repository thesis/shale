(ns shale.test.sessions
  (:require [clojure.test :refer :all]
            [shale.sessions :refer [matches-requirement]]))

(deftest test-matches-requirement
  (testing "matches-requirement"

    (testing "session-tag matches"
      (let [requirement [:session-tag "a"]
            session     {:tags ["a" "b"]}]
        (is (matches-requirement requirement session))))

    (testing "session-tag doesn't match"
      (let [requirement [:session-tag "a"]
            session     {:tags ["b" "c"]}]
        (is (not (matches-requirement requirement session)))))

    (testing "not-session-tag matches"
      (let [requirement [:not [:session-tag "a"]]
            session     {:tags ["b" "c"]}]
        (is (matches-requirement requirement session))))

    (testing "not-session-tag doesn't match"
      (let [requirement [:not [:session-tag "a"]]
            session     {:tags ["a" "b"]}]
        (is (not (matches-requirement requirement session)))))

    (testing "and of session-tag matches"
      (let [requirement [:and [[:session-tag "a"] [:session-tag "b"]]]
            session     {:tags ["a" "b"]}]
        (is (matches-requirement requirement session))))

    (testing "and of session-tag doesn't match"
      (let [requirement [:and [[:session-tag "a"] [:session-tag "c"]]]
            session     {:tags ["a" "b"]}]
        (is (not (matches-requirement requirement session)))))

    (testing "node-tag matches"
      (let [requirement [:node-tag "a"]
            session     {:node {:tags ["a" "b"]}}]
        (is (matches-requirement requirement session))))

    (testing "node-tag doesn't match"
      (let [requirement [:node-tag "a"]
            session     {:node {:tags ["b" "c"]}}]
        (is (not (matches-requirement requirement session)))))

    (testing "browser matches"
      (let [requirement [:browser-name "a"]
            session     {:browser-name "a"}]
        (is (matches-requirement requirement session))))

    (testing "browser doesn't match"
      (let [requirement [:browser-name "a"]
            session     {:browser-name "b"}]
        (is (not (matches-requirement requirement session)))))

    (testing "current-url matches"
      (let [requirement [:current-url "a"]
            session     {:current-url "a"}]
        (is (matches-requirement requirement session))))

    (testing "current-url doesn't match"
      (let [requirement [:current-url "a"]
            session     {:current-url "b"}]
        (is (not (matches-requirement requirement session)))))
))
