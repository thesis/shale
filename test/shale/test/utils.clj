(ns shale.test.utils
  (:require [clojure.java.io :refer [writer]]
            [carica.core :refer [overrider]]
            [shale.configurer :as configurer])
  (:import [org.openqa.selenium.server
            SeleniumServer
            RemoteControlConfiguration]
           [java.io
            IOException
            PrintStream]
           java.net.Socket))

(defn local-port-available? [port]
  (try
    (Socket. "127.0.0.1" port)
    false
    (catch IOException e
      true)))

(defn start-selenium-server [port & [output-stream]]
  (let [config (RemoteControlConfiguration.)
        _ (.setPort config port)]
    ;; if we've been given an output stream, redirect *out* and System.out
    (binding [*out* (if output-stream
                      (writer output-stream)
                      *out*)]
      (if output-stream
        (System/setOut output-stream))
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
        (throw (ex-info "Selenium port is unavailable." {:port port}))))))

(defn stop-selenium-server [server]
  (.stop server))

(defn make-output-stream [port]
  (PrintStream. (str "selenium-server-" port ".txt")))

(defn with-selenium-servers
  "Returns a fixture that starts and cleans up Selenium nodes based on a list
  of ports."
  [ports & {:keys [redirect-output]
            :or {redirect-output true}}]
  (fn [test-fn]
    (let [output-stream (if redirect-output
                          (PrintStream. "selenium-servers.log"))
          servers (doall
                    (pmap #(start-selenium-server % output-stream) ports))]
      (try
        (test-fn)
        (finally (doall (pmap stop-selenium-server servers)))))))

(defn with-custom-config
  "Returns a fixture that runs tests with an overridden config.

  Eg, using the fixture `(with-custom-config {:node-max-sessions 5})` you can
  make all calls `(config :node-max-sessions)` calls in a test return `5`."
  [new-config]
  (fn [test-fn]
    (let [override-config (overrider configurer/config)]
      (with-redefs [configurer/config (override-config new-config)]
        (test-fn)))))
