(ns shale.client
  (:require [clj-http.client :as client]
            (clj-json  [core :as json])
            [clj-webdriver.taxi :as taxi]
            [shale.webdriver :as webdriver])
  (:use [clj-webdriver.remote.driver :only[session-id]]))

(defn sessions-url []
  (str "http://localhost:5000/" "sessions/"))

(defn session-url [id]
  (str "http://localhost:5000/" "sessions/" id))

(defn sessions []
   (json/parse-string
     (get (client/get (sessions-url)) :body)))

(defn session [id]
  (json/parse-string
    (get (client/get (session-url id)))))

(defn get-or-create-session [{:keys [browser-name
                                     node
                                     reserved
                                     tags
                                     reserve-after-create
                                     force-create]
                              :or {browser-name "phantomjs"
                                   node nil
                                   reserved nil
                                   reserve-after-create nil
                                   force-create nil
                                   tags []}}]
  (let [body (into {} [{:browser_name browser-name}
                        (if (nil? reserved) {} {:reserved reserved})
                        (if (nil? node) {} {:node node})
                        (if (nil? tags) {} {:tags tags})])
        params (into {} [(if reserve-after-create {:reserve reserve-after-create} {})
                         (if force-create {:force_create force-create} {})])]
    (json/parse-string (get (client/post (sessions-url)
                                         {:body (json/generate-string body)
                                          :content-type :json
                                          :accept :json
                                          :query-params params})
                            :body))))

(defn modify-session [id {:keys [reserved tags]
                          :or {reserved nil
                               tags nil}}]
  (def body (into {} [(if (nil? reserved) {} {:reserved reserved})
                      (if (nil? tags) {} {:tags tags})]))
  (def response
    (client/put (session-url id)
                {:body (json/generate-string body)
                 :content-type :json
                 :accept :json}))
  (json/parse-string (get response :body)))

(defn reserve-session [id]
  (modify-session id {:reserved true}))

(defn release-session [id]
  (modify-session id {:reserved false}))

(defn destroy-session [id]
  (client/delete (session-url id))
  nil)

(defn refresh-sessions []
  (json/parse-string
    (get (client/post (str (sessions-url) "refresh") {}) :body)))

(defn get-or-create-webdriver [{:keys [browser-name
                                       node
                                       reserved
                                       tags
                                       reserve-after-create
                                       force-create]
                                :or {browser-name "phantomjs"
                                     node nil
                                     reserved nil
                                     reserve-after-create nil
                                     force-create nil
                                     tags []}}]
  (let [session (get-or-create-session {:browser-name browser-name
                            :node node
                            :reserved reserved
                            :tags tags
                            :reserve-after-create reserve-after-create
                            :force-create force-create})]
    (webdriver/resume-webdriver
      (get session "id")
      (get session "node")
      {"platform" "ANY"
       "browserName" (get session "browser_name")})))

(defn release-webdriver [driver]
  (release-session (session-id driver)))

(defmacro with-webdriver
    "Given tags and a browser name to get or create a new webdriver session,
     execute the forms in `body`, then unreserve the webdriver session.

       Example:
       ========

       ;;
       ;; Log into Github
       ;;
       (use 'clj-webdriver.taxi)
       (with-driver {:browser-name :firefox :tags [\"github\"]}
         (to \"https://github.com\")
         (click \"a[href*='login']\")
         (input-text \"#login_field\" \"your_username\")
         (-> \"#password\"
           (input-text \"your_password\")
           submit))"
  [options & body]
   (def options-with-defaults (merge {:reserved false
                                      :reserve-after-create true} options))
     `(binding [~'clj-webdriver.taxi/*driver*
                (get-or-create-webdriver options-with-defaults)]
        (try
          ~@body
          (finally
            (release-webdriver ~'clj-webdriver.taxi/*driver*)))))
