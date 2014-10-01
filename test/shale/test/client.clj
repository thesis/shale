(ns shale.test.client
  (:require [clojure.test :refer :all]
            [shale.client :refer :all]
            [shale.sessions :refer :all]))

(defn logged-in-sessions-fixture [f]
  (let [reqs {:browser-name "phantomjs" :tags []}]
    (doseq [tag-config [["logged-in"] ["logged-out"]]]
      (shale.sessions/get-or-create-session
        (assoc reqs :tags tag-config :force-create true))))
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

(defn session-diff [f]
  (let [before (session-count)]
    (f)
    (- (session-count) before)))

(deftest test-sessions
  (testing "sessions"
    (is (= 0 (session-count)))))

(deftest test-get-or-create
  (testing "get-or-create"
    (testing "creating one session"
      (is (= 1 (session-diff #(shale.client/get-or-create-session!
                             {:browser-name "phantomjs"})))))

    (testing "that another session isn't created based on browser"
      (is (= 0 (session-diff #(shale.client/get-or-create-session!
                             {:browser-name "phantomjs"})))))

    (testing "that another session is created based on a tag"
      (let [test-fn (fn []
                        (shale.client/get-or-create-session!
                          {:browser-name "phantomjs"
                           :tags ["some-unknown-tag"]}))]
        (is (= 3 (session-diff
                   #(logged-in-sessions-fixture test-fn))))))))

(deftest test-force-create
  (testing "force create a new session"
    (let [test-fn
          (fn []
            (let [session (shale.client/get-or-create-session!
                            {:browser-name "phantomjs"
                             :force-create true})]
              (is (get session "browser_name") "phantomjs")))]
      (is (= 1 (session-diff test-fn))))))

(deftest test-reservations
  (testing "releasing a session"
    (let [test-fn (fn []
                    (shale.client/release-session!
                      (get (first (shale.client/sessions)) "id"))
                    (is (not-any? #(get % "reserved")
                                  (shale.client/sessions))))]
      (reserved-session-fixture test-fn)))

  (testing "reserving after creating a new session"
    (let [session (shale.client/get-or-create-session!
                    {:reserve-after-create true
                     :browser-name "phantomjs"})]
      (is (get session "reserved")))))

(deftest test-webdriver-macro
  (testing "that the with-webdriver* macro properly releases its session"
    (shale.client/with-webdriver* {:browser-name "phantomjs"}
      (is (get (first (shale.client/sessions)) "reserved"))
      (is (= 1 (session-count))))
    (is (= 1 (session-count)))
    (is (not (get (first (shale.client/sessions)) "reserved")))))
