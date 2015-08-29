(ns shale.test.client
  (:require [clojure.test :refer :all]
            [ring.adapter.jetty :as jetty]
            [clj-webdriver.taxi :refer [execute-script]]
            [shale.client :refer :all]
            [shale.handler :as handler]
            [shale.test.utils :refer [with-selenium-servers
                                      local-port-available?]])
  (:import java.net.Socket
           java.io.IOException))

(defn server-fixture [f]
  (let [port 5000
        app (jetty/run-jetty handler/app {:port port :join? false})]
    ; give the app 5 seconds to start, or cry real hard
    (pr "Waiting for the web server...")
    (loop [tries 0]
      (if (local-port-available? port)
        (if (> tries 20)
          (do
            (pr ".")
            (Thread/sleep 100)
            (recur (inc tries)))
          (do
            (.stop app)
            (handler/destroy)
            (throw (ex-info "Timed out waiting for the web server." {}))))))
    (handler/init)
    (f)
    (.stop app)
    (handler/destroy)))

(defn logged-in-sessions-fixture [f]
  (let [reqs {:browser-name "phantomjs" :tags []}]
    (doseq [tag-config [["logged-in"] ["logged-out"]]]
      (shale.client/get-or-create-session!
        (assoc reqs :tags tag-config :force-create true))))
  (f))

(defn reserved-session-fixture [f]
  (shale.client/get-or-create-session! {:browser-name "phantomjs"
                                        :reserve-after-create true})
  (f))

(defn delete-sessions-fixture [f]
  (let [test-value (f)]
    (shale.client/destroy-sessions!)
    test-value))

(use-fixtures :once (with-selenium-servers [4443 4444]) server-fixture)
(use-fixtures :each delete-sessions-fixture)

(defn session-count []
  (count (shale.client/sessions)))

(defn session-diff [f]
  (let [before (session-count)]
    (f)
    (- (session-count) before)))

(deftest ^:integration test-sessions
  (testing "sessions"
    (is (= 0 (session-count)))))

(deftest ^:integration test-get-or-create
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
                   #(logged-in-sessions-fixture test-fn))))))
    (testing "that sessions are created on the specified node"
      (let [node-id (-> (shale.client/nodes)
                        first
                        (get "id"))]

        (dotimes [_ 3]
          (shale.client/get-or-create-session!
            {:browser-name "phantomjs"
             :node {:id node-id}}))
        (is (= 3 (count
                   (filter #(= (:id %) node-id)
                           (shale.client/nodes)))))))))

(deftest ^:integration test-force-create
  (testing "force create a new session"
    (let [test-fn
          (fn []
            (let [session (shale.client/get-or-create-session!
                            {:browser-name "phantomjs"
                             :force-create true})]
              (is (get session "browser_name") "phantomjs")))]
      (is (= 1 (session-diff test-fn))))))

(deftest ^:integration test-tag-modification
  (testing "setting tags"
    (let [test-fn (fn []
                    (let [session (first (shale.client/sessions))
                          id (get session "id")]
                      (shale.client/modify-session! id {:tags ["test-tag"]})
                      id))]
      (is (= "test-tag" (-> (logged-in-sessions-fixture test-fn)
                            shale.client/session
                            (get "tags")
                            first))))))

(deftest ^:integration test-reservations
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

(deftest ^:integration test-webdriver-macro
  (testing "that the with-webdriver* macro properly releases its session"
    (shale.client/with-webdriver* {:browser-name "phantomjs"}
      (is (get (first (shale.client/sessions)) "reserved"))
      (is (= 1 (session-count))))
    (is (= 1 (session-count)))
    (is (not (get (first (shale.client/sessions)) "reserved")))))

(deftest ^:integration test-webdriver-js
  (testing "that the wrapped webdriver can execute javascript"
    (shale.client/with-webdriver* {:browser-name "phantomjs"}
      (is (= 1 (execute-script "return 1;"))))))
