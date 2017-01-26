(ns shale.ext.ResumableRemoteWebDriver
  (:import [org.openqa.selenium Capabilities Platform]
           [org.openqa.selenium.remote
            CommandExecutor
            HttpCommandExecutor
            DesiredCapabilities
            CapabilityType
            DriverCommand
            RemoteWebDriver]
           clj_webdriver.ext.remote.RemoteWebDriverExt
           java.net.URL)
  (:gen-class
    :main false
    :init url-init
    :constructors {[java.net.URL String]
                   [org.openqa.selenium.remote.CommandExecutor
                    org.openqa.selenium.Capabilities]}
    :post-init resume
    :extends clj_webdriver.ext.remote.RemoteWebDriverExt
    :prefix "-"))

(defn -url-init
  [^URL node-url ^String session-id]
  [[(HttpCommandExecutor. node-url)
    (DesiredCapabilities. {})]
   nil]
  )

(defn -startSession
  [this ^Capabilities desired ^Capabilities required]
  (let [cap-field (.getDeclaredField RemoteWebDriver "capabilities")]
    (.setAccessible cap-field true)
    (.set cap-field this (DesiredCapabilities. {}))))

(defn -resume
  [this ^URL node-url ^String session-id]
  (.setSessionId this session-id)
  ;; get the status from the server and update the capabilities
  (let [response (.execute this (DriverCommand/STATUS) {})
        raw-caps (into {} (.getValue response))
        platform-string (get raw-caps CapabilityType/PLATFORM)
        platform (try
                   (if platform-string
                     (Platform/valueOf platform-string)
                     Platform/ANY)
                   (catch Exception e
                     (Platform/extractFromSysProperty platform-string)))]
    (-> this
        .getCapabilities
        (.setPlatform platform))))
