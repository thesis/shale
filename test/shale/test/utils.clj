(ns shale.test.utils
  (:import [org.openqa.selenium.server
            SeleniumServer
            RemoteControlConfiguration]
           java.net.Socket
           java.io.IOException))

(defn local-port-available? [port]
  (try
    (Socket. "127.0.0.1" port)
    false
    (catch IOException e
      true)))

(defn start-selenium-server [port]
  (let [config (RemoteControlConfiguration.)
        _ (.setPort config port)]
    (if (local-port-available? port)
      (let [server (SeleniumServer. config)]
          (.start server)
          (.boot server)
          (loop [tries 0]
            (if (local-port-available? port)
              (if (> tries 20)
                (throw "Timed out waiting for Selenium to start." {:port port})
                (do
                  (Thread/sleep 100)
                  (recur (inc tries))))))
          server)
      (throw (ex-info "Selenium port is unavailable." {:port port})))))

(defn stop-selenium-server [server]
  (.stop server))

(defn with-selenium-servers
  "Returns a fixture that starts and cleans up Selenium nodes based on a list
  of ports."
  [ports]
  (fn [test-fn]
    (let [servers (doall (pmap start-selenium-server ports))]
      (test-fn)
      (doall (pmap stop-selenium-server servers)))))
