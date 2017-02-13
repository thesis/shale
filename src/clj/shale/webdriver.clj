(ns shale.webdriver
  (:require [schema.core :as s]
            clj-webdriver.driver
            [clj-webdriver.core :as core]
            [clj-webdriver.remote.driver :refer [session-id]]
            [clj-webdriver.taxi :refer [execute-script]]
            wall.hack
            shale.ext.ResumableRemoteWebDriver)
  (:import org.openqa.selenium.remote.DesiredCapabilities
           clj_webdriver.ext.remote.RemoteWebDriverExt
           java.net.URL
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
