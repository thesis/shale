(ns shale.webdriver
  (:require [schema.core :as s]
            [cemerick.url :as cemurl]
            [clj-http.client :as client]
            clj-webdriver.driver
            [clj-webdriver.core :as core]
            [clj-webdriver.remote.driver :refer [session-id]]
            [clj-webdriver.taxi :refer [execute-script]]
            wall.hack
            [slingshot.slingshot :refer [try+]]
            shale.ext.ResumableRemoteWebDriver
            [shale.logging :as logging]
            [shale.utils :refer [any-instance?]])
  (:import clj_webdriver.ext.remote.RemoteWebDriverExt
           java.net.ConnectException
           java.net.SocketTimeoutException
           java.net.URL
           org.apache.http.conn.ConnectTimeoutException
           org.openqa.selenium.remote.UnreachableBrowserException
           org.openqa.selenium.remote.DesiredCapabilities
           shale.ext.ResumableRemoteWebDriver))

(s/defn ^:always-validate add-prox-to-capabilities :- {s/Any s/Any}
  [capabilities :- {s/Any s/Any}
   proxy-type   :- (s/enum :socks5 :http)
   proxy-host   :- s/Str ; IP address
   proxy-port   :- s/Int]
  (let [browser-name (get capabilities "browserName")
        host-and-port (format "%s:%s" proxy-host proxy-port)
        proxy-url (format "%s://%s" (name proxy-type) host-and-port)]
    (case browser-name
      "chrome" (merge-with (partial merge-with into)
                           capabilities
                           {"chromeOptions"
                            {"args" [(format "--proxy-server=%s" proxy-url)]}})
      "phantomjs" (merge-with into
                              capabilities
                              {"phantomjs.cli.args"
                               [(format "--proxy=%s" host-and-port)
                                (format "--proxy-type=%s" proxy-type)]})
      (throw (ex-info "Unknown browser for proxy config."
                      {:browser-name browser-name})))))

(s/defn ^:always-validate maybe-add-no-sandbox :- {s/Any s/Any}
  [capabilities :- {s/Any s/Any}]
  (let [browser-name (get capabilities "browserName")]
    (case browser-name
      "chrome" (update-in capabilities ["chromeOptions" "args"] conj "--no-sandbox")
      capabilities)))

(defn new-webdriver [node capabilities]
  (clj-webdriver.driver/init-driver
    (RemoteWebDriverExt. (URL. node)
                         (DesiredCapabilities. capabilities))))

(defn resume-webdriver [session-id node capabilities]
  (let [wd (ResumableRemoteWebDriver. (URL. node) session-id)]
    (clj-webdriver.driver/init-driver wd)))

(s/defn ^:always-validate destroy-webdriver!
  [webdriver-id :- s/Str
   node-url     :- s/Str
   timeout      :- s/Int]
  (let [session-url (->> webdriver-id
                         (format "./session/%s")
                         (cemurl/url node-url)
                         str)]
    (try+
      (client/delete session-url
                     {:socket-timeout timeout
                      :conn-timeout timeout})
      (logging/info (format "Destroyed webdriver %s on node %s."
                            webdriver-id
                            node-url))
      (catch [:status 404] _
        (logging/error
          (format (str "Got a 404 attempting to delete"
                       " webdriver %s from node %s.")
                  webdriver-id
                  node-url)))
      (catch [:status 500] _
        (logging/error
          (format (str "Got a 500 attempting to delete"
                       " webdriver %s from node %s.")
                  webdriver-id
                  node-url)))

      (catch #(any-instance?
                [ConnectTimeoutException
                 SocketTimeoutException
                 UnreachableBrowserException]) e
        (logging/error
          (format (str "Timeout connecting to node %s"
                       " to delete webdriver %s.")
                  node-url
                  webdriver-id)))
      (catch ConnectException e
        (logging/error
          (format (str "Error connecting to node %s"
                       " to delete session %s.")
                  node-url
                  webdriver-id))))))


(defn to-async [wd url]
  (let [js "setTimeout(function(){window.location=\"%s\";}, 10);"]
    (execute-script wd (format js url))))

(defn webdriver-capabilities
  "Get the actual capabilities map from a webdriver."
  [wd]
  (->> wd
       :webdriver
       .getCapabilities
       .asMap
       (map #(vector (keyword (key %)) (val %)))
       (into {})))
