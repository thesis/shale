(ns shale.client
  (:require [clj-http.client :as client]
            (clj-json [core :as json])
            [clj-webdriver.taxi :as taxi]
            [shale.webdriver :as webdriver]
            [slingshot.slingshot :refer [try+ throw+]]
            [taoensso.timbre :as timbre :refer [error]])
  (:use [clj-webdriver.remote.driver :only [session-id]]
        [clojure.set :only [rename-keys]]
        shale.utils))

(defn sessions-url []
  (str "http://localhost:5000/" "sessions/"))

(defn session-url [id]
  (str "http://localhost:5000/" "sessions/" id))

(defn sessions []
  (let [response (try+ (client/get (sessions-url))
                       (catch [:status 400] {:keys [body]} (error body) (throw+)))]
    (json/parse-string (response :body))))

(defn session [id]
  (let [response (client/get (session-url id))]
    (json/parse-string (response :body))))

(defn get-or-create-session! [{:keys [browser-name
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
                                   tags []}
                              :as requirements}]
  (let [body (rename-keys (select-keys requirements
                                       [:browser-name :reserved :node :tags])
                          {:browser-name :browser_name})
        params (rename-keys (select-keys requirements
                                         [:reserve-after-create :force-create])
                            {:reserve-after-create :reserve_after_create
                             :force-create :force_create})
        response (try+
                   (client/post (sessions-url)
                     {:body (json/generate-string body)
                      :content-type :json
                      :accept :json
                      :query-params params})
                   (catch [:status 400] {:keys [body]} (error body) (throw+)))]
    (json/parse-string (response :body))))

(defn modify-session! [id {:keys [reserved tags]
                          :or {reserved nil
                               tags nil}}]
  (let [body (into {} [(if (nil? reserved) {} {:reserved reserved})
                      (if (nil? tags) {} {:tags tags})])
        response (try+ (client/put (session-url id)
                         {:body (json/generate-string body)
                          :content-type :json
                          :accept :json})
                   (catch [:status 400] {:keys [body]} (error body) (throw+)))]
    (json/parse-string (response :body))))

(defn reserve-session! [id]
  (modify-session! id {:reserved true}))

(defn release-session! [id]
  (modify-session! id {:reserved false}))

(defn destroy-session! [id]
  (client/delete (session-url id))
  nil)

(defn destroy-sessions! []
  (client/delete (sessions-url))
  nil)

(defn refresh-sessions! []
  (let [response (try+
                   (client/post (str (sessions-url) "refresh") {})
                   (catch [:status 400] {:keys [body]} (error body) (throw+)))]
    (json/parse-string (response :body))))

(defn get-or-create-webdriver! [{:keys [browser-name
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
                                      tags []}
                                 :as requirements}]
  (let [session (get-or-create-session! requirements)]
    (webdriver/resume-webdriver
      (get session "id")
      (get-in session ["node" "url"])
      {"platform" "ANY"
       "browserName" (get session "browser_name")})))

(defn release-webdriver! [driver]
  (release-session! (session-id driver)))

(defmacro with-webdriver*
    "Given tags and a browser name to get or create a new webdriver session,
     execute the forms in `body`, then unreserve the webdriver session.

       Example :
       ========

       ;;
       ;; Log into Github
       ;;
       (use 'clj-webdriver.taxi)
       (with-webdriver* {:browser-name :firefox :tags [\"github\"]}
         (to \"https://github.com\")
         (click \"a[href*='login']\")
         (input-text \"#login_field\" \"your_username\")
         (-> \"#password\"
           (input-text \"your_password\")
           submit))"
  [options & body]
   (let [options-with-defaults (merge {:reserved false
                                       :reserve-after-create true} options)]
     `(binding [~'clj-webdriver.taxi/*driver*
                (get-or-create-webdriver! ~options-with-defaults)]
        (try
          ~@body
          (finally
            (release-webdriver! ~'clj-webdriver.taxi/*driver*))))))
