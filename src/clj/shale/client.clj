(ns shale.client
  (:require [clj-http.client :as client]
            (cheshire [core :as json])
            [clj-webdriver.taxi :as taxi]
            [shale.webdriver :as webdriver]
            [slingshot.slingshot :refer [try+ throw+]]
            [taoensso.timbre :as timbre :refer [error]])
  (:use [clj-webdriver.remote.driver :only [session-id]]
        [clojure.set :only [rename-keys]]
        shale.utils))

(def default-url-root "http://localhost:5000")

(def ^:private sessions-url
  (memoize
    (fn [url-root]
      (clojure.string/join "/" [url-root "sessions"]))))

(def ^:private nodes-url
  (memoize
    (fn [url-root]
      (clojure.string/join "/" [url-root "nodes"]) )))

(defn ^:private session-url [url-root id]
  (clojure.string/join "/" [(sessions-url url-root)id]))

(defn ^:private session-by-webdriver-url [url-root webdriver-id]
  (clojure.string/join "/" [(sessions-url url-root) "webdriver" webdriver-id]))

(defn ^:private map->session-url [url-root m-or-id]
  (if (map? m-or-id)
    (if (contains? m-or-id :webdriver-id)
      (session-by-webdriver-url url-root (:webdriver-id m-or-id))
      (session-url url-root (or (:session-id m-or-id) (:id m-or-id))))
    (session-url url-root m-or-id)))

(defn sessions
  ([] (sessions default-url-root))
  ([url-root]
   (let [response (try+
                    (client/get (sessions-url url-root))
                    (catch [:status 400] {:keys [body]} (error body) (throw+)))]
     (json/parse-string (response :body)))))

(defn nodes
  ([] (nodes default-url-root))
  ([url-root]
   (let [response (try+ (client/get (nodes-url url-root))
                        (catch [:status 400] {:keys [body]} (error body) (throw+)))]
     (json/parse-string (response :body)))))

(defn session
  ([id] (session default-url-root id))
  ([url-root id]
   (let [url (map->session-url url-root id)
         response (client/get url)]
     (json/parse-string (response :body)))))

(defn get-or-create-session!
  ([requirements] (get-or-create-session! default-url-root requirements))
  ([url-root {:keys [browser-name
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
                    (client/post (sessions-url url-root)
                                 {:body (json/generate-string body)
                                  :content-type :json
                                  :accept :json
                                   :query-params params})
                    (catch [:status 400] {:keys [body]} (error body) (throw+)))]
     (json/parse-string (response :body)))))

(defn modify-session!
  ([id options] (modify-session! default-url-root id options))
  ([url-root id {:keys [reserved tags]
                 :or {reserved nil
                      tags nil}}]
   (let [url (map->session-url url-root id)
         body (merge (if (not (nil? reserved)) {:reserved reserved})
                     (if tags {:tags tags})
                     {})
         response (try+
                    (client/put url
                                {:body (json/generate-string body)
                                 :content-type :json
                                 :accept :json})
                    (catch [:status 400] {:keys [body]}
                      (error body)
                      (throw+)))]
     (json/parse-string (response :body)))))

(defn reserve-session!
  ([id] (reserve-session! default-url-root id))
  ([url-root id]
   (modify-session! url-root id {:reserved true})))

(defn release-session!
  ([id] (release-session! default-url-root id))
  ([url-root id]
   (modify-session! url-root id {:reserved false})))

(defn destroy-session!
  ([id] (destroy-session! default-url-root id))
  ([url-root id]
   (let [url (map->session-url url-root id)]
     (client/delete url {:query-params {"immediately" "true"}}))
   nil))

(defn destroy-sessions!
  ([] (destroy-sessions! default-url-root))
  ([url-root]
   (client/delete (sessions-url url-root) {:query-params {"immediately" "true"}})
   nil))

(defn refresh-sessions!
  ([] (refresh-sessions! default-url-root))
  ([url-root]
   (let [response (try+
                    (client/post
                      (clojure.string/join "/"
                                           [(sessions-url url-root) "refresh"])
                      {})
                    (catch [:status 400] {:keys [body]} (error body) (throw+)))]
     (json/parse-string (response :body)))))

(defn get-or-create-webdriver!
  ([requirements] (get-or-create-webdriver! default-url-root requirements))
  ([url-root {:keys [browser-name
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
   (let [session (get-or-create-session! url-root requirements)]
     (webdriver/resume-webdriver
       (get session "webdriver_id")
       (get-in session ["node" "url"])
       {"platform" "ANY"
        "browserName" (get session "browser_name")}))))

(defn release-webdriver!
  ([driver] (release-webdriver! default-url-root driver))
  ([url-root driver]
   (if-let [session-id (session-id driver)]
     (release-session! url-root {:webdriver-id session-id}))))

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
