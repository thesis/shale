(ns shale.webdriver
  (:require clj-webdriver.driver
            [clj-webdriver.core :as core]
            wall.hack)
  (:import [org.openqa.selenium.remote
            CommandExecutor
            HttpCommandExecutor
            DesiredCapabilities
            RemoteWebDriver]
           org.openqa.selenium.Capabilities
           clj_webdriver.ext.remote.RemoteWebDriverExt
           java.net.URL)
  (:use [clj-webdriver.remote.driver :only [session-id]]
        clj-webdriver.taxi))

(defn new-webdriver [node capabilities]
  (clj-webdriver.driver/init-driver
    (RemoteWebDriverExt. (URL. node)
                         (DesiredCapabilities. capabilities))))

(defn resume-webdriver [session-id node capabilities]
  ;; this is pretty dirty- refactor with a protocol?
  (doall
    (map #(.setAccessible % true)
         (.getDeclaredConstructors RemoteWebDriverExt)))
  (let [wd (RemoteWebDriverExt.)
        capabilities (DesiredCapabilities. capabilities)
        cap-field (.getDeclaredField RemoteWebDriver "capabilities")]
    (wall.hack/method RemoteWebDriverExt
                      :setCommandExecutor
                      [CommandExecutor]
                      wd
                      (HttpCommandExecutor. (URL. node)))
    (wall.hack/method RemoteWebDriver
                      :init
                      [Capabilities Capabilities]
                      wd
                      capabilities
                      nil)
    (wall.hack/method (.getClass wd) :setSessionId [String] wd session-id)
    (.setAccessible cap-field true)
    (.set cap-field wd capabilities)
    (clj-webdriver.driver/init-driver wd)))

(defn to-async [wd url]
  (def js "setTimeout(function(){window.location=\"%s\";}, 10);")
  (execute-script wd (format js url)))
