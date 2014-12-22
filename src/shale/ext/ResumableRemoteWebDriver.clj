(ns shale.ext.ResumableRemoteWebDriver
  (:import clj_webdriver.ext.remote.RemoteWebDriverExt
           org.openqa.selenium.Capabilities)
  (:gen-class
    :main false
    :extends clj_webdriver.ext.remote.RemoteWebDriverExt
    :prefix -))

(defn -startSession [this ^Capabilities desired ^Capabilities required])
