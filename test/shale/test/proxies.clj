(ns shale.test.proxies
  (:require [clojure.test :refer :all]
            [shale.utils :refer [gen-uuid]]
            [shale.proxies :as proxies]
            [shale.test.utils :refer [with-clean-redis
                                      with-system-from-config]]))

(def system (atom nil))

(def once-fixtures
  [(with-system-from-config
     system
     {:node-list ["http://localhost:4444/wd/hub"]})])

(def each-fixtures
  [(with-clean-redis system)])

(use-fixtures :once (join-fixtures once-fixtures))

(use-fixtures :each (join-fixtures each-fixtures))

(def base-proxy {:id (gen-uuid)
                 :shared true
                 :active true
                 :host "localhost"
                 :port 101010
                 :type :socks5
                 :tags #{}
                 :public-ip nil})

(deftest test-require->spec
  (testing "type alone fails"
    (is (thrown? clojure.lang.ExceptionInfo
                 (proxies/require->spec [:type (:type base-proxy)]))))

  (testing "type and host"
    (let [req [:and
               [[:type (:type base-proxy)]
                [:host (:host base-proxy)]
                [:port (:port base-proxy)]]]]
      (is (= (select-keys base-proxy
                          [:shared :active :type :host :port :tags])
             (proxies/require->spec req))))))

(deftest ^:integration test-creation-and-deletion
  (testing "proxy creation"
    (let [pool (:proxy-pool @system)
          spec (select-keys base-proxy [:type :host :port])]
      (is 0 (count (proxies/view-models pool)))
      (proxies/create-proxy! pool spec)
      (is 1 (count (proxies/view-models pool)))))

  (testing "proxy deletion"
    (let [pool (:proxy-pool @system)
          spec (select-keys base-proxy [:type :host :port])
          prox (proxies/create-proxy! pool spec)]
      (is 1 (count (proxies/view-models pool)))
      (proxies/delete-proxy! pool (:id prox))
      (is 0 (count (proxies/view-models pool))))))

(deftest ^:integration test-get-or-create!
  (testing "get-or-create! creates a new proxy"
    (let [pool (:proxy-pool @system)
          req [:and
               [[:type (:type base-proxy)]
                [:host (:host base-proxy)]
                [:port (:port base-proxy)]]]]
      (is 0 (count (proxies/view-models pool)))
      (let [prox (proxies/get-or-create-proxy! pool req)]
        (is 1 (count (proxies/view-models pool)))
        (is (select-keys base-proxy [:host :port :type])
            (select-keys prox [:host :port :type]))))))
