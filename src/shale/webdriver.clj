(ns shale.webdriver
  (:require clj-webdriver.driver
            [clj-webdriver.core :as core]
            wall.hack)
  (:import [org.openqa.selenium.remote
            DesiredCapabilities]
           clj_webdriver.ext.remote.RemoteWebDriverExt
           shale.ext.ResumableRemoteWebDriver
           java.net.URL)
  (:use [clj-webdriver.remote.driver :only [session-id]]
        clj-webdriver.taxi))

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
