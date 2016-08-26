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
                 :private-host-and-port "localhost:101010"
                 :type :socks5
                 :public-ip nil})

(deftest test-require->spec
  (testing "type alone fails"
    (is (thrown? clojure.lang.ExceptionInfo
                 (proxies/require->spec [:type (:type base-proxy)]))))

  (testing "type and host"
    (let [req [:and
               [[:type (:type base-proxy)]
                [:private-host-and-port (:private-host-and-port base-proxy)]]]]
      (is (= (select-keys base-proxy
                          [:shared :active :type :private-host-and-port])
             (proxies/require->spec req))))))

(deftest ^:integration test-creation-and-deletion
  (testing "proxy creation"
    (let [pool (:proxy-pool @system)
          spec (select-keys base-proxy [:type :private-host-and-port])]
      (is 0 (count (proxies/view-models pool)))
      (proxies/create-proxy! pool spec)
      (is 1 (count (proxies/view-models pool)))))

  (testing "proxy deletion"
    (let [pool (:proxy-pool @system)
          spec (select-keys base-proxy [:type :private-host-and-port])
          prox (proxies/create-proxy! pool spec)]
      (is 1 (count (proxies/view-models pool)))
      (proxies/delete-proxy! pool (:id prox))
      (is 0 (count (proxies/view-models pool))))))

(deftest ^:integration test-get-or-create!
  (testing "get-or-create! creates a new proxy"
    (let [pool (:proxy-pool @system)
          req [:and
               [[:type (:type base-proxy)]
                [:private-host-and-port (:private-host-and-port base-proxy)]]]]
      (is 0 (count (proxies/view-models pool)))
      (let [prox (proxies/get-or-create-proxy! pool req)]
        (is 1 (count (proxies/view-models pool)))
        (is (select-keys base-proxy [:private-host-and-port :type])
            (select-keys prox [:private-host-and-port :type]))))))
