(ns shale.test.client
  (:require [clojure.test :refer :all]
            [shale.client :refer :all]
            [shale.sessions :refer :all]))

(defn logged-in-sessions-fixture [f]
  (let [reqs {:browser-name "phantomjs" :tags []}]
    (doseq [tag-config [["logged-in"] ["logged-out"]]]
      (shale.sessions/get-or-create-session (assoc reqs :tags tag-config))))
  (f))

(defn reserved-session-fixture [f]
  (shale.sessions/get-or-create-session {:browser-name "phantomjs"
                                         :reserve-after-create true})
  (f))

(defn delete-sessions-fixture [f]
  (let [test-value (f)]
    (doseq [session-id (map #(% :id) (shale.sessions/view-models nil))]
      (shale.sessions/destroy-session session-id))
    test-value))

(use-fixtures :each delete-sessions-fixture)

(defn session-count []
  (count (shale.client/sessions)))

(deftest test-client
  (testing "sessions"
    (is (count (shale.client/sessions)) 0))

  (testing "force create a new session"
    (let [session (shale.client/get-or-create-session! {:browser-name "phantomjs"
                                                        :force-create true})]
      (is (session "browser_name") "phantomjs"))
    (is (session-count) 1))

  (testing "reserving after creating a new session"
    (let [session (shale.client/get-or-create-session!
                    {:reserve-after-create true})]
      (is (session "reserved") true)))

  (testing "releasing a session"
    (reserved-session-fixture (fn []
                                (shale.client/release-session!
                                  ((first (shale.client/sessions)) "id"))
                                (is true (not-any? #(% "reserved")
                                                   (shale.client/sessions))))))

  (testing "get-or-create"
    (testing "creating one session"
      (shale.client/get-or-create-session! {:browser-name "phantomjs"})
      (is (session-count)) 1)

    (testing "that another session isn't created based on browser"
      (shale.client/get-or-create-session! {:browser-name "phantomjs"})
      (is (session-count) 1))

    (testing "that another session is created based on a tag"
      (logged-in-sessions-fixture (fn []
                                    (let [c (session-count)]
                                      (shale.client/get-or-create-session!
                                        {:tags ["some-unknown-tag"]})
                                      (is (+ c 1) (session-count)))))))

  (testing "that the with-webdriver* macro properly releases its session"
    (shale.client/with-webdriver* {:browser-name "phantomjs"}
      (is ((first (shale.client/sessions)) "reserved") true)
      (is (session-count) 1))
    (is (session-count) 1)
    (is ((first (shale.client/sessions)) "reserved") false)))
