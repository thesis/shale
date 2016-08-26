(ns shale.test.webdriver
  (:require [clojure.test :refer :all]
            [schema.core :as s]
            [shale.webdriver :refer [add-prox-to-capabilities]]))

(s/defn rand-ip :- s/Str []
  (clojure.string/join "." (for [_ (range 4)]
                             (+ 1 (rand-int 254)))))

(s/defn rand-port :- s/Int []
  (rand-int 65535))

(deftest test-prox-capabilities
  (testing "add-prox-to-capabilities"
    (testing "chrome socks proxies"
      (let [capabilities {"browserName" "chrome"}
            host (rand-ip)
            port (rand-port)
            new-caps (add-prox-to-capabilities
                       capabilities :socks5 host port)]
        (is (= new-caps {"browserName" "chrome"
                         "chromeOptions"
                         {"args" [(format "--proxy-server=socks5://%s:%s"
                                          host
                                          port)]}}))))

    (testing "chrome socks proxies with other args"
      (let [capabilities {"browserName" "chrome"
                          "chromeOptions" {"args" ["--disable-plugins"]}}
            host (rand-ip)
            port (rand-port)
            new-caps (add-prox-to-capabilities
                       capabilities :socks5 host port)]
        (is (not (nil? (some #{"--disable-plugins"}
                             (get-in new-caps ["chromeOptions" "args"])))))))

    (testing "firefox socs proxies aren't supported"
      (let [capabilities {"browserName" "firefox"}
            host (rand-ip)
            port (rand-port)]
        (is (thrown? Exception
                     (add-prox-to-capabilities
                       capabilities :socks5 host port)))))))
